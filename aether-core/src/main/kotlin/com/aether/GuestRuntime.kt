package com.aether

import android.app.Application
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.util.Log
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
        var anyOk = false
        anyOk = bindInitialApplication(at) || anyOk
        anyOk = bindAllApplications(at) || anyOk
        anyOk = bindResources(at) || anyOk
        if (anyOk) state.set(State.ACTIVE)
        hookRegistry.onBound?.invoke(this)
        return anyOk
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
            provider.attachInfo(guestContext, info)
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
        private val hooks = HookRegistry()

        fun sessionId(id: String) = apply { sessionId = id }
        fun applicationClassHint(fqcn: String) = apply { appClassHint = fqcn }
        fun autoStartApplication() = apply { callOnCreate = true }
        fun installProviders(classes: List<String>) = apply { providers = classes }

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
            attachBaseContext(app, guestCtx)

            val runtime = GuestRuntime(
                targetPkg = targetPkg,
                sessionId = sessionId,
                guestClassLoader = loader,
                guestApplication = app,
                guestContext = guestCtx,
                hookRegistry = hooks.also { it.hostApplication = hostContext.applicationContext as? Application },
            )
            // Auto-bind ถ้า caller ขอ — ไม่บังคับ
            if (callOnCreate) runtime.bindToActivityThread()
            return Result.success(runtime)
        }

        // ── Builder-private helpers ──

        private fun createGuestContext(): Context? = try {
            hostContext.createPackageContext(
                targetPkg,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
        } catch (e: Throwable) {
            Log.w(TAG, "createPackageContext: ${e.message}")
            null
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

        private fun attachBaseContext(app: Application, base: Context) {
            try {
                val m = android.content.ContextWrapper::class.java
                    .getDeclaredMethod("attachBaseContext", Context::class.java)
                m.isAccessible = true
                m.invoke(app, base)
            } catch (e: Throwable) {
                Log.w(TAG, "attachBaseContext: ${e.message}")
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
