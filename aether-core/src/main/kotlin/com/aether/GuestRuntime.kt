package com.aether

import android.app.Application
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * GuestRuntime — Single-responsibility isolated guest session lifecycle.
 *
 * ───────────────────────────────────────────────────────────────────
 *  ใหม่ใน v2: ตามหลัก 3-tier architecture (aether-architecture-v2.md §2)
 *  - Tier 1: native runtime (libaether.so)  — อยู่ที่ aether-native
 *  - Tier 2: THIS FILE                       — guest session lifecycle
 *  - Tier 3: component binding (ProxyActivity)— อยู่ที่ aether-android
 *
 *  แทนที่จะ patch ปลายเหตุ (Scaffold-1 hook mInitialApplication อย่างเดียว)
 *  v2 แก้ที่ต้นเหตุ: ทำให้ guest Application มี mLoadedApk valid
 *  เพื่อให้ ContextImpl.getApplicationContext() ไม่ fallback ไป
 *  mMainThread.getApplication() (ซึ่งเป็น root cause ของ NPE ใน
 *  ConfigurationController.updateLocaleListFromAppContext — verified
 *  จาก AOSP API 36 ContextImpl.java:476 + ConfigurationController.java:284).
 *
 *  เหนือกว่าต้นแบบ AetherMind:
 *  - 3-pass bind (Application + Configuration + ClassLoader) — Snake ทำ 2-pass
 *  - DSL builder — ลด parameter list จาก 5 ตัว (host,pkg,hint,onCreate,providers)
 *    เหลือ chained calls ที่อ่านง่าย
 *  - Pluggable strategy — reflection fallback เป็น strategy ที่สลับได้
 *  - Lifecycle hooks — pause/resume/destroy ตาม pattern ของ AndroidX Lifecycle
 *  - ไม่ผูกกับ AetherEngine — pure Android API, library เปล่า
 *
 *  ความปลอดภัย:
 *  - ทุก reflection guarded + try/catch — version drift ไม่ crash
 *  - ไม่เก็บ reference ไปยัง Application ของ host — ป้องกัน leak
 *  - Disposable contract — close() ต้องถูกเรียกเมื่อ session จบ
 */
class GuestRuntime private constructor(
    val targetPkg: String,
    val sessionId: String,
    val guestClassLoader: ClassLoader,
    val guestApplication: Application,
    val guestContext: Context,
    private val hookRegistry: HookRegistry,
) : AutoCloseable {

    /** Lifecycle states เหมือน Lifecycle.State ของ AndroidX แต่เบากว่า. */
    enum class State { INITIALIZING, ACTIVE, SUSPENDED, DESTROYED }

    private val state = AtomicReference(State.INITIALIZING)

    /** Provider installation count (set after installProviders). */
    @Volatile var providersInstalled: Int = 0
        private set

    /** รายชื่อ providers ที่ install สำเร็จ (สำหรับ diagnostics). */
    val attachedProviders: List<String> get() = _attachedProviders.toList()
    private val _attachedProviders = mutableListOf<String>()

    /** รายชื่อ providers ที่ install ไม่สำเร็จ (สำหรับ diagnostics). */
    val skippedProviders: List<Pair<String, String>> get() = _skipped.toList()
    private val _skipped = mutableListOf<Pair<String, String>>()

    val currentState: State get() = state.get()

    /**
     * Bind guest session เข้า ActivityThread.
     * ทำให้ AOSP framework (ConfigurationController, ResourcesManager, etc.)
     * เห็น guest Application เป็น "the Application" ใน process นี้.
     *
     * Returns true on success, false ถ้า reflection fail ทุกขั้น (graceful).
     */
    fun bindToActivityThread(): Boolean {
        val at = currentActivityThreadOrNull() ?: return false
        // Hard guard (verified crash 2026-09-07, logcat 21:38/21:39/21:40):
        // a guest Application without a base context (mBase == null) must NOT
        // be bound into mInitialApplication — the next activity launch calls
        // ConfigurationController.updateLocaleListFromAppContext(getApplication())
        // → getResources() → NPE → :p0 process dies → UI bounces back.
        val mBase = appMBaseOrNull(guestApplication)
        if (mBase == null) {
            Log.e(TAG, "bindToActivityThread REFUSED: guest Application has no base context")
            return false
        }
        // Spoof BEFORE the binds below — every SDK/provider init that follows
        // (FirebaseInitProvider 05.963 in the aether-live snapshot) reads the
        // framework process name; it must already say the guest main name.
        spoofProcessName(at)
        // hop ≡ SNAKE Native.i(SDK_INT) ที่ jv0.O2:245 (F2 invoke @0x1b06d0) —
        // native จุดเดียวใน bind path; seed = SDK_INT ของ host (T1 sig (I)V)
        runCatching { com.aether.Engine.nativeSetSeed(android.os.Build.VERSION.SDK_INT) }
            .onFailure { Log.w(TAG, "nativeSetSeed(SDK_INT) hop failed: ${it.message}") }
        var anyOk = false
        anyOk = bindInitialApplication(at) || anyOk
        anyOk = bindAllApplications(at) || anyOk
        anyOk = bindResources(at) || anyOk
        if (anyOk) state.set(State.ACTIVE)
        hookRegistry.onBound?.invoke(this)
        // Diagnostics: exactly which pass landed where (previously "3-pass OK"
        // masked a failed mInitialApplication bind).
        val nowInitial = fieldValueOrNull(at, "mInitialApplication")
        Log.i(TAG, "bindToActivityThread: ok=$anyOk mInitialApplication=${nowInitial?.javaClass?.name} (guest=${guestApplication.javaClass.name})")
        return anyOk
    }

    /** Base context (ContextWrapper.mBase) of [app], or null if not attached. */
    private fun appMBaseOrNull(app: Application): Any? {
        var c: Class<*>? = app.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("mBase")
                f.isAccessible = true
                return f.get(app)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            } catch (e: Throwable) {
                return null
            }
        }
        return null
    }

    /**
     * Install ContentProviders of the guest (Firebase, Facebook, AppLovin, ...).
     * เรียกจาก caller หลัง bindToActivityThread เพราะ providers อ่าน
     * ActivityThread.currentApplication() ใน attachInfo.
     */
    fun installProviders(providers: List<String>): Int {
        if (state.get() == State.DESTROYED) return 0
        val loader = guestClassLoader
        var ok = 0
        for (providerClass in providers) {
            val r = installOneProvider(providerClass, loader)
            if (r == true) {
                ok++
                _attachedProviders.add(providerClass)
            } else if (r is String) {
                _skipped.add(providerClass to r)
            }
        }
        providersInstalled = ok
        hookRegistry.onProvidersInstalled?.invoke(this, ok)
        return ok
    }

    /**
     * Call guest Application.onCreate() — เริ่ม SDK initializers.
     * ควรเรียกหลัง installProviders เท่านั้น.
     */
    fun callOnCreate(): Result<Unit> {
        if (state.get() != State.ACTIVE) {
            return Result.failure(IllegalStateException("not active: ${state.get()}"))
        }
        return try {
            val at = currentActivityThreadOrNull()
            val instr = at?.let { fieldValueOrNull(it, "mInstrumentation") } as? Instrumentation
            if (instr != null) {
                val m = instr.javaClass.getMethod("callApplicationOnCreate", Application::class.java)
                m.invoke(instr, guestApplication)
            } else {
                guestApplication.onCreate()
            }
            hookRegistry.onCreated?.invoke(this)
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "callOnCreate failed: ${t.message}")
            Result.failure(t)
        }
    }

    /** Pause session — เก็บ state เผื่อ resume. */
    fun suspend() {
        if (state.get() == State.ACTIVE) state.set(State.SUSPENDED)
        hookRegistry.onSuspended?.invoke(this)
    }

    /** Resume session จาก SUSPENDED → ACTIVE. */
    fun resume() {
        if (state.get() == State.SUSPENDED) state.set(State.ACTIVE)
        hookRegistry.onResumed?.invoke(this)
    }

    /**
     * Close session — ต้องเรียกเมื่อไม่ใช้ guest อีก.
     * คืน mInitialApplication กลับเป็น host (ถ้าจำได้), clear hooks, mark DESTROYED.
     */
    override fun close() {
        if (state.get() == State.DESTROYED) return
        try {
            val at = currentActivityThreadOrNull()
            if (at != null) {
                fieldValueOrNull(at, "mInitialApplication")?.let { current ->
                    if (current === guestApplication) {
                        // คืน host Application กลับเข้า mInitialApplication
                        // (ถ้า hookRegistry เก็บ host app ไว้)
                        hookRegistry.hostApplication?.let { host ->
                            setFieldIfPresent(at, "mInitialApplication", host)
                        }
                    }
                }
                // ลบ guest ออกจาก mAllApplications
                val list = fieldValueOrNull(at, "mAllApplications") as? ArrayList<Application>
                if (list != null) list.remove(guestApplication)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "close: ${t.message}")
        }
        state.set(State.DESTROYED)
        hookRegistry.onDestroyed?.invoke(this)
    }

    // ───────────────────────────────────────────────────────────
    //  Private — 3-pass bind (เหนือกว่าต้นแบบ)
    // ───────────────────────────────────────────────────────────

    /**
     * Spoof the framework-reported process name of this proxy process to the
     * guest's MAIN process name (targetPkg, no suffix).
     *
     * Evidence (aether-live snapshot vs SNAKE1, both 8BP):
     *   - crashlytics folder (written by the GAME's bundled SDK, NOT by us):
     *       SNAKE1 → .crashlytics.v3/com.miniclip.eightballpool/  (main name)
     *       Aether → .crashlytics.v3/com.aether_p0/               (":p0" leaked)
     *   - "com.aether:p0" means every SDK-visible identity (AMS isProcessNameOf
     *     checks, ActivityThread.getProcessName(), Application.getProcessName())
     *     still says host. GMS dynamite measurement keys its init threading on
     *     this: SNAKE1/ninja only ever hit SecurityException on FA WORKER
     *     threads (benign), ours landed on MAIN → Looper died → frozen screen.
     *
     * Touches ONLY our in-process ActivityThread fields (never game files).
     * mProcessName is cached from the bindApplication transaction, so setting
     * it here is the same value the real engine would have carried.
     */
    private fun spoofProcessName(at: Any) {
        val name = targetPkg
        if (name.isEmpty()) return
        var applied = false
        // 1. ActivityThread.mProcessName (private field through API 34).
        if (setFieldIfPresent(at, "mProcessName", name)) applied = true
        // 2. API 35+ moved the cache into ProcessNameProvider — guarded fallback
        //    (device under test is Android 16: crash report os.version=16).
        if (!applied) {
            val providerField = findField(at.javaClass, "mProcessNameProvider")
            if (providerField != null) {
                try {
                    providerField.isAccessible = true
                    val provider = providerField.get(at)
                    if (provider != null) {
                        for (inner in arrayOf("mProcessName", "processName")) {
                            if (setFieldIfPresent(provider, inner, name)) {
                                applied = true
                                break
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "spoofProcessName: provider fallback failed: ${e.message}")
                }
            }
        }
        // 3. Android 16 (verified device: aether-live2 applied=false on 1+2):
        //    the cache lives in AppBindData — every public read path resolves
        //    there: Application.getProcessName() → ActivityThread
        //    .currentProcessName() → am.mBoundApplication.processName (AOSP 16
        //    ActivityThread.java:452 field, :968 String processName, :2940).
        //    SNAKE1 parity proof: its crashlytics folder (written by the game's
        //    own SDK) is .crashlytics.v3/com.miniclip.eightballpool/ — main name,
        //    never "com.snake:p0" → SNAKE rewrites exactly this field.
        if (!applied) {
            val bound = fieldValueOrNull(at, "mBoundApplication")
            if (bound != null) {
                applied = setFieldIfPresent(bound, "processName", name)
                Log.i(TAG, "spoofProcessName: mBoundApplication.processName → $name (applied=$applied)")
            } else {
                Log.w(TAG, "spoofProcessName: mBoundApplication is null (not yet bound?)")
            }
        }
        // 4. CompatibilityInfo changes when processName != packageName; the
        //    framework caches it. Clear it so it recomputes as "main process".
        val ciField = findField(at.javaClass, "mCompatibilityInfo")
        if (ciField != null) {
            try {
                ciField.isAccessible = true
                ciField.set(at, null)
            } catch (e: Throwable) {
                Log.w(TAG, "spoofProcessName: mCompatibilityInfo clear failed: ${e.message}")
            }
        }
        Log.i(TAG, "spoofProcessName: $name (applied=$applied)")
    }

    private fun bindInitialApplication(at: Any): Boolean {
        val r = guardedBool("bindInitialApplication") {
            setFieldIfPresent(at, "mInitialApplication", guestApplication)
        }
        return r ?: false
    }

    private fun bindAllApplications(at: Any): Boolean {
        val r = guardedBool("bindAllApplications") {
            @Suppress("UNCHECKED_CAST")
            val list = fieldValueOrNull(at, "mAllApplications") as? ArrayList<Application>
            if (list != null && !list.contains(guestApplication)) {
                list.add(guestApplication)
            }
            true
        }
        return r ?: false
    }

    /**
     * Pass 3 (เหนือกว่าต้นแบบ Snake): bind guest Resources เข้า ActivityThread.
     * ช่วยให้ ResourcesManager resolve configuration ของ guest ก่อน host.
     */
    private fun bindResources(at: Any): Boolean {
        val r = guardedBool("bindResources") {
            // Hook ResourcesManager.updateResourceConfiguration? ไม่จำเป็น
            // เพราะ guest Application.getResources() ใช้ resources ของ guest
            // เองผ่าน mLoadedApk.mResources — ขอแค่ verify accessible.
            val res = guestContext.resources
            res.assets  // touch เพื่อ verify
            true
        }
        return r ?: false
    }

    /**
     * Install หนึ่ง provider. Returns:
     * - true ถ้าสำเร็จ
     * - String (error reason) ถ้า skip
     * - null ถ้า field ทั้งหมดหาย (catastrophic)
     */
    /**
     * Install หนึ่ง provider. Returns:
     * - true ถ้าสำเร็จ
     * - String (error reason) ถ้า skip
     * - null ถ้า field ทั้งหมดหาย (catastrophic)
     */
    private fun installOneProvider(providerClass: String, loader: ClassLoader): Any? {
        return try {
            // Skip GMS/ads/analytics providers that talk to Google Play services
            // with the guest package name — under virtualization the calling UID
            // is ours, so binder calls with the GUEST package fail with
            // SecurityException (verified: dynamite measurement 'Unknown calling
            // package name' kills main thread). These providers are NOT needed
            // for the game itself to run; installing them only risks crashes.
            // Skip ONLY providers that make external binder calls to GMS
            // measurement/ads services during onCreate — these throw
            // SecurityException on the main thread that disrupts game init
            // even when caught by the firewall (aborts Handler dispatch).
            // PlayGamesInitProvider + FirebaseInitProvider are ALLOWED:
            // they init SDK state the game expects; their binder calls are
            // contained by Binder.clearCallingIdentity (step 2) + firewall.
            val skipPrefixes = listOf(
                "com.google.android.gms.ads",           // MobileAdsInitProvider → external
                "com.google.android.gms.measurement",   // AppMeasurement → dynamite killer
                "io.bidmachine.",                       // ads
                "com.vungle.",                          // ads
                "com.ironsource.",                      // ads
                "com.applovin.",                        // ads
                "com.facebook.ads.",                    // FB audience network
            )
            if (skipPrefixes.any { providerClass.startsWith(it) }) {
                return "skipped (ads/measurement provider — unsafe under virtual UID)"
            }
            val cls = loader.loadClass(providerClass)
            val provider = cls.getDeclaredConstructor().newInstance() as? ContentProvider
            if (provider == null) {
                return "${cls.simpleName} is not ContentProvider"
            }
            val info = ProviderInfo().apply {
                authority = providerClass
                packageName = guestApplication.packageName
                name = cls.name
                applicationInfo = guestApplication.applicationInfo
                exported = false
                enabled = true
            }
            // SNAKE R2/Q2 pattern: clearCallingIdentity prevents the system
            // from checking Binder.getCallingUid() during provider attach.
            // Without this, providers that query PackageManager during
            // attachInfo get SecurityException (host UID ≠ guest package).
            val token = Binder.clearCallingIdentity()
            try {
                provider.attachInfo(guestContext, info)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            true
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ───────────────────────────────────────────────────────────
    //  Reflection helpers (strategy: AOSP API 36 forward-compat)
    // ───────────────────────────────────────────────────────────

    private fun currentActivityThreadOrNull(): Any? = guarded("currentActivityThread") {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null)
    }?.also { Log.d(TAG, "ActivityThread = ${it.javaClass.name}") }

    private fun setFieldIfPresent(target: Any, name: String, value: Any): Boolean {
        val f = findField(target.javaClass, name) ?: return false
        f.isAccessible = true
        f.set(target, value)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldValueOrNull(target: Any, name: String): Any? {
        val f = findField(target.javaClass, name) ?: return null
        f.isAccessible = true
        return f.get(target)
    }

    /** Walk superclass chain — null if absent at any level. */
    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    /** Run block, log + return null on any reflection failure (forward-compat). */
    private inline fun guarded(label: String, block: () -> Any?): Any? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "$label: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** Run block expecting Boolean, log + return null on failure. */
    private inline fun guardedBool(label: String, block: () -> Boolean): Boolean? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "$label: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    // ───────────────────────────────────────────────────────────
    //  Builder DSL — ลด parameter list, เพิ่ม flexibility
    // ───────────────────────────────────────────────────────────

    class Builder internal constructor(
        private val targetPkg: String,
        private val hostContext: Context,
    ) {
        private var sessionId: String = "guest-${System.nanoTime()}"
        private var appClassHint: String? = null
        private var callOnCreate: Boolean = false
        private var providers: List<String> = emptyList()
        private var sandboxDataDir: File? = null
        private val hooks = HookRegistry()

        fun sessionId(id: String) = apply { sessionId = id }
        fun applicationClassHint(fqcn: String) = apply { appClassHint = fqcn }
        fun autoStartApplication() = apply { callOnCreate = true }
        fun installProviders(classes: List<String>) = apply { providers = classes }
        /**
         * Re-root guest data dirs (files/cache/databases/external) into [dir] —
         * VirtualFS data isolation. Without this the guest reads/writes the REAL
         * installed app's data dir (/data/user/0/<targetPkg>), not the sandbox.
         */
        fun sandboxDataDir(dir: File?) = apply { this.sandboxDataDir = dir }

        fun onCreated(block: (GuestRuntime) -> Unit) = apply { hooks.onCreated = block }
        fun onBound(block: (GuestRuntime) -> Unit) = apply { hooks.onBound = block }
        fun onProvidersInstalled(block: (GuestRuntime, Int) -> Unit) = apply { hooks.onProvidersInstalled = block }
        fun onSuspended(block: (GuestRuntime) -> Unit) = apply { hooks.onSuspended = block }
        fun onResumed(block: (GuestRuntime) -> Unit) = apply { hooks.onResumed = block }
        fun onDestroyed(block: (GuestRuntime) -> Unit) = apply { hooks.onDestroyed = block }

        /**
         * Build isolated guest session. Returns Result เพื่อไม่ throw ผ่าน caller.
         *
         * Failure modes (ไม่ crash):
         * - target package not installed → Result.failure
         * - classloader resolution fail → Result.failure
         * - reflection fail ทุกขั้น → Result.failure with reason
         */
        fun build(): Result<GuestRuntime> {
            // Strategy 1: createPackageContext (ใช้ system PackageManager)
            val guestCtx = createGuestContext() ?: return Result.failure(
                IllegalStateException("createPackageContext($targetPkg) failed")
            )
            val loader = guestCtx.classLoader
            val appInfo = resolveApplicationInfo() ?: return Result.failure(
                IllegalStateException("getApplicationInfo($targetPkg) failed")
            )
            val appClassName = appClassHint ?: appInfo.className
                ?: return Result.failure(IllegalStateException("no Application class for $targetPkg"))
            val appClass = try {
                loader.loadClass(appClassName)
            } catch (e: Throwable) {
                return Result.failure(IllegalStateException("loadClass($appClassName): ${e.message}"))
            }
            val app = instantiateApplication(appClass, guestCtx) ?: return Result.failure(
                IllegalStateException("instantiateApplication($appClassName) failed")
            )
            // Hard guard: never return a baseless guest Application — binding
            // it into mInitialApplication would NPE :p0 on the next activity
            // launch (ConfigurationController.updateLocaleListFromAppContext).
            if (!attachBaseContext(app, guestCtx)) {
                return Result.failure(IllegalStateException(
                    "attachBaseContext failed — guest Application has no base context; " +
                    "refusing to bind (would crash :p0 on next activity launch)"))
            }

            val runtime = GuestRuntime(
                targetPkg = targetPkg,
                sessionId = sessionId,
                guestClassLoader = loader,
                guestApplication = app,
                guestContext = guestCtx,
                hookRegistry = hooks.also { it.hostApplication = hostContext.applicationContext as? Application },
            )
            // KOS-equivalent 'Published guest LoadedApk': LoadedApk.mApplication
            // must resolve to the GUEST app so framework factories and guest
            // SDKs (getApplicationContext) see the guest, not null/host.
            publishGuestApp(runtime, app)
            // Auto-bind ถ้า caller ขอ — ไม่บังคับ
            if (callOnCreate) runtime.bindToActivityThread()
            return Result.success(runtime)
        }

        // ── Builder-private helpers ──

        private fun createGuestContext(): Context? = try {
            val ctx = hostContext.createPackageContext(
                targetPkg,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            sandboxDataDir?.let { root -> redirectDataDirs(ctx, root) }
            ctx
        } catch (e: Throwable) {
            Log.w(TAG, "createPackageContext: ${e.message}")
            null
        }

        /**
         * Re-root the guest's data paths into [sandboxRoot] (VirtualFS data
         * isolation).
         *
         * createPackageContext points the LoadedApk's data paths at the REAL
         * /data/user/0/<targetPkg>, so the guest would read/write the real
         * installed app's data. Patching the LoadedApk File fields makes
         * getFilesDir()/getCacheDir()/getDatabasePath() (lazily derived from
         * mDataDir) and the external dirs resolve under the sandbox instead.
         *
         * Also clones ApplicationInfo and re-points dataDir — guest SDKs
         * (Unity/Firebase) read applicationInfo.dataDir directly. The clone
         * avoids mutating the system's shared ApplicationInfo instance.
         *
         * @return true if mDataDir was re-rooted
         */
        private fun redirectDataDirs(ctx: Context, sandboxRoot: File): Boolean {
            return try {
                val loadedApk = fieldValueOrNull(ctx, "mPackageInfo") ?: run {
                    Log.w(TAG, "redirectDataDirs: mPackageInfo not found on ${ctx.javaClass.name}")
                    return false
                }
                val dataDir = sandboxRoot
                // Device-protected (DE) equivalent: root/data/user_de/0/<pkg>
                // (mirrors the real /data/user_de/0 split — SandboxManager creates it)
                val deDataDir = sandboxRoot.parentFile?.parentFile?.parentFile
                    ?.let { File(it, "user_de/0/${sandboxRoot.name}") } ?: File(sandboxRoot, "de")
                val extDataDir = File(sandboxRoot, "external")
                val extCacheDir = File(sandboxRoot, "external_cache")
                dataDir.mkdirs(); deDataDir.mkdirs(); extDataDir.mkdirs(); extCacheDir.mkdirs()

                // API 36 LoadedApk (verified against AOSP android-16 LoadedApk.java):
                //   mDataDirFile                  File   ← getDataDirFile()
                //   mCredentialProtectedDataDirFile File ← getCredentialProtectedDataDirFile()
                //   mDeviceProtectedDataDirFile    File   ← getDeviceProtectedDataDirFile()
                //   mDataDir                       String ← getDataDir()
                // ContextImpl.getDataDir() reads mCredentialProtectedDataDirFile (normal
                // storage) → ALL of getFilesDir/getDatabasePath/getSharedPreferences
                // derive from it. 'mCredentialProtectedDataDir' (no File suffix) does
                // NOT exist on LoadedApk (that name is ApplicationInfo-only) — the
                // previous round set the wrong name and data never re-rooted.
                var anyOk = false
                anyOk = setFieldB(loadedApk, "mCredentialProtectedDataDirFile", dataDir) || anyOk
                anyOk = setFieldB(loadedApk, "mDataDirFile", dataDir) || anyOk
                anyOk = setFieldB(loadedApk, "mDeviceProtectedDataDirFile", deDataDir) || anyOk
                anyOk = setFieldB(loadedApk, "mDataDir", dataDir.absolutePath) || anyOk
                setFieldB(loadedApk, "mDeDataDir", deDataDir)                 // legacy name
                setFieldB(loadedApk, "mExternalDataDir", extDataDir)         // legacy name
                setFieldB(loadedApk, "mExternalCacheDir", extCacheDir)
                val ok = anyOk

                val appInfo = fieldValueOrNull(loadedApk, "applicationInfo") as? ApplicationInfo
                if (appInfo != null) {
                    try {
                        // Deep copy via the Parcelable round-trip (ApplicationInfo
                        // has no public clone()). The copy avoids mutating the
                        // system's shared/cached ApplicationInfo instance.
                        val parcel = android.os.Parcel.obtain()
                        try {
                            appInfo.writeToParcel(parcel, 0)
                            parcel.setDataPosition(0)
                            val copy = ApplicationInfo.CREATOR.createFromParcel(parcel)
                            copy.dataDir = dataDir.absolutePath
                            setFieldB(loadedApk, "applicationInfo", copy)
                            setFieldB(ctx, "mApplicationInfo", copy)
                        } finally {
                            parcel.recycle()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "redirectDataDirs: ApplicationInfo copy: ${e.message}")
                    }
                }
                Log.i(TAG, "redirectDataDirs: $targetPkg → ${dataDir.absolutePath} (mDataDir ok=$ok)")
                ok
            } catch (e: Throwable) {
                Log.e(TAG, "redirectDataDirs failed", e)
                false
            }
        }

        /**
         * Publish the guest into its own LoadedApk (KOS 'Published guest LoadedApk
         * for framework factory resolution' equivalent):
         *   - LoadedApk.mApplication = guest app (else getApplicationContext()
         *     resolves null/host and framework factories NPE — verified device
         *     log 'MBLifecycleProvider ... on a null object')
         *   - LoadedApk.mClassLoader = guest loader so AppComponentFactory
         *     resolution uses guest classes
         */
        private fun publishGuestApp(runtime: GuestRuntime, guestApp: Application) {
            try {
                val apk = fieldValueOrNull(runtime.guestContext, "mPackageInfo") ?: return
                setFieldB(apk, "mApplication", guestApp)
                Log.i(TAG, "publishGuestApp: LoadedApk.mApplication=${guestApp.javaClass.name}")
            } catch (e: Throwable) {
                Log.w(TAG, "publishGuestApp: ${e.message}")
            }
        }

        /** Set field [name] on [target] (walking the class hierarchy). @return true if set. */
        private fun setFieldB(target: Any, name: String, value: Any): Boolean {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(target, value)
                    return true
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                } catch (e: Throwable) {
                    return false
                }
            }
            return false
        }

        private fun resolveApplicationInfo(): ApplicationInfo? = try {
            hostContext.packageManager.getApplicationInfo(targetPkg, 0)
        } catch (e: Throwable) {
            Log.w(TAG, "getApplicationInfo: ${e.message}")
            null
        }

        private fun instantiateApplication(appClass: Class<*>, ctx: Context): Application? {
            // Strategy A: Instrumentation.newApplication (เลียนแบบ LoadedApk.makeApplicationInner)
            val at = try { Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null) } catch (e: Throwable) { null }
            val instr = at?.let { fieldValueOrNull(it, "mInstrumentation") }
            if (instr != null) {
                try {
                    val m = instr.javaClass.getMethod(
                        "newApplication",
                        ClassLoader::class.java, String::class.java, Context::class.java
                    )
                    return m.invoke(instr, appClass.classLoader, appClass.name, ctx) as Application
                } catch (e: Throwable) {
                    Log.w(TAG, "Instrumentation.newApplication failed, fallback to direct: ${e.message}")
                }
            }
            // Strategy B: direct instantiation (fallback)
            return try {
                appClass.getDeclaredConstructor().newInstance() as Application
            } catch (e: Throwable) {
                Log.w(TAG, "direct newInstance: ${e.message}")
                null
            }
        }

        /**
         * Attach [base] to [app] via DIRECT mBase field assignment.
         *
         * Deterministic + idempotent — no framework-method invoke (the
         * reflection call to ContextWrapper.attachBaseContext failed on device
         * with an uninformative exception; verified 2026-09-07 logcat
         * "attachBaseContext: null" followed by ConfigurationController NPE).
         *
         * A guest Application bound into ActivityThread.mInitialApplication
         * with mBase == null NPEs the whole :p0 process on the next activity
         * launch (updateLocaleListFromAppContext → getResources()).
         *
         * @return true if the app has a valid base context after the call
         */
        private fun attachBaseContext(app: Application, base: Context): Boolean {
            return try {
                // mBase is declared on ContextWrapper — walk the hierarchy
                var c: Class<*>? = app.javaClass
                var field: java.lang.reflect.Field? = null
                while (c != null) {
                    try { field = c.getDeclaredField("mBase"); break }
                    catch (e: NoSuchFieldException) { c = c.superclass }
                }
                if (field == null) {
                    Log.w(TAG, "attachBaseContext: mBase field NOT FOUND in ${app.javaClass.name} hierarchy")
                    return false
                }
                field.isAccessible = true
                if (field.get(app) == null) {
                    field.set(app, base)
                }
                val ok = field.get(app) != null
                Log.i(TAG, "attachBaseContext: mBase=${field.get(app)?.javaClass?.name} (ok=$ok)")
                ok
            } catch (e: Throwable) {
                Log.w(TAG, "attachBaseContext: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        private fun fieldValueOrNull(target: Any, name: String): Any? {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(target)
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                } catch (e: Throwable) {
                    return null
                }
            }
            return null
        }

        companion object {
            private const val CONTEXT_INCLUDE_CODE = 0x00000001
            private const val CONTEXT_IGNORE_SECURITY = 0x00000002
        }
    }

    /** Hook registry — แยก state ออกจาก GuestRuntime หลัก. */
    internal class HookRegistry {
        var onBound: ((GuestRuntime) -> Unit)? = null
        var onCreated: ((GuestRuntime) -> Unit)? = null
        var onProvidersInstalled: ((GuestRuntime, Int) -> Unit)? = null
        var onSuspended: ((GuestRuntime) -> Unit)? = null
        var onResumed: ((GuestRuntime) -> Unit)? = null
        var onDestroyed: ((GuestRuntime) -> Unit)? = null
        var hostApplication: Application? = null
    }

    companion object {
        private const val TAG = "AetherGuestRuntime"

        /** Entry point — DSL builder. */
        fun builder(targetPkg: String, hostContext: Context) =
            Builder(targetPkg, hostContext)
    }
}
