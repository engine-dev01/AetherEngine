package com.aether.engine.proxy

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder

/**
 * AetherInstrumentation — Activity virtualization via Instrumentation hook
 * (Scaffold-4). Standard VirtualApp/DroidPlugin pattern; NO native ART hook
 * (the prototype's Native.update is RWX runtime-generated and cannot be ported
 * byte-exact — see docs/SCAFFOLD4_ACTIVITY_VIRTUALIZATION.md).
 *
 * Two intercepts on the process's ActivityThread.mInstrumentation:
 *
 *   Hook A — (cut audit C9: execStartActivity subclass ไม่เคย dispatch;
 *            binder-layer rewrite วางแผนไว้ที่ ServiceBinderProxy = blueprint P4)
 *     rewrite an Intent targeting a GUEST activity (not in our manifest) into
 *     the registered STUB (ProxyActivity$P0), stashing the real guest intent.
 *     AMS then sees a component IT KNOWS → launches in :p0 instead of routing
 *     out to the real installed app.
 *
 *   Hook B — newActivity (public API override):
 *     when ActivityThread builds the stub, detect the stashed guest intent and
 *     instantiate the REAL guest Activity class (guest classloader) instead.
 *
 * All reflection guarded: if a signature differs on this Android version the
 * hook degrades to pass-through (no crash), logged for device diagnosis.
 */
class AetherInstrumentation(
    private val base: Instrumentation,
    // audit C8-stale-hook: ต้อง rebind เมื่อ identity เปลี่ยน (เดิม val ทั้งคู่)
    private var stubComponent: String,   // e.g. com.aether.engine.proxy.ProxyActivity$P0
    private var guestClassLoader: ClassLoader,
    private var guestApp: Application?,
) : Instrumentation() {

    /** audit C8-stale-hook: แทนค่า identity บน wrapper ที่ติดตั้งอยู่แล้ว */
    fun rebind(stub: String, loader: ClassLoader, app: Application?) {
        this.stubComponent = stub
        this.guestClassLoader = loader
        this.guestApp = app
    }

    companion object {
        private const val TAG = "AetherInstr"
        // Public so ProxyActivity can stash the guest target on the stub intent
        // (buildStubIntent) and newActivity (hook B) can read it back to swap.
        const val EXTRA_GUEST_INTENT = "aether.guest_intent"
        const val EXTRA_GUEST_CLASS = "aether.guest_class"

        @Volatile private var installed = false

        /**
         * Replace ActivityThread.mInstrumentation with an AetherInstrumentation
         * wrapping the current one. Idempotent + fully guarded.
         * Returns true on success.
         */
        fun install(
            stubComponent: String,
            guestClassLoader: ClassLoader,
            guestApp: Application?,
        ): Boolean {
            return try {
                if (installed) {
                    // audit C8-stale-hook: rebind wrapper เดียวที่ mInstrumentation ถือ
                    val atCls0 = Class.forName("android.app.ActivityThread")
                    val at0 = atCls0.getMethod("currentActivityThread").invoke(null)
                    val cur0 = (atCls0.getDeclaredField("mInstrumentation").also {
                        it.isAccessible = true }.get(at0)) as? AetherInstrumentation
                    if (cur0 != null) {
                        cur0.rebind(stubComponent, guestClassLoader, guestApp)
                        DiagLog.d(TAG, "rebind reused wrapper stub=" + stubComponent)
                        return true
                    }
                    installed = false
                }
                val atCls = Class.forName("android.app.ActivityThread")
                val at = atCls.getMethod("currentActivityThread").invoke(null) ?: return false
                val f = atCls.getDeclaredField("mInstrumentation")
                f.isAccessible = true
                val current = f.get(at) as? Instrumentation ?: return false
                if (current is AetherInstrumentation) {
                    // wrapper จาก session ก่อนยังอยู่ (reset() แค่ลืม flag) —
                    // ต้อง rebind ไม่งั้น identity ใหม่หายเงียบ (seam test จับได้)
                    current.rebind(stubComponent, guestClassLoader, guestApp)
                    installed = true
                    DiagLog.d(TAG, "install found existing wrapper → rebind stub=" + stubComponent)
                    return true
                }
                val wrapper = AetherInstrumentation(current, stubComponent, guestClassLoader, guestApp)
                f.set(at, wrapper)
                installed = true
                DiagLog.d(TAG, "installed (wrapping ${current.javaClass.name})")
                true
            } catch (e: Throwable) {
                DiagLog.err(TAG, "install failed", e)
                false
            }
        }

        /** audit C8: session จบ (ProxyActivity.onDestroy) → อนุญาต install ใหม่ */
        fun reset() {
            installed = false
            DiagLog.d(TAG, "reset - next install() rebinds fresh identity")
        }

        /**
         * Build a STUB intent that AMS knows (targets [stubComponent], which is
         * registered in OUR manifest under :p0) while stashing the real guest
         * activity class + guest package in extras.
         *
         * Why not startActivity(guestIntent) directly: the guest activity is NOT
         * in our manifest, so AMS routes the intent OUT to the real installed app
         * (the 8BP process). The stub IS in our manifest → AMS launches it in :p0;
         * newActivity (hook B, a PUBLIC override) then swaps stub → real guest
         * activity using the guest classloader.
         *
         * This deliberately avoids execStartActivity (a hidden API that cannot be
         * reliably overridden on modern Android — the subclass method is never
         * invoked by ActivityThread). Rewriting the intent BEFORE startActivity is
         * the stable VirtualApp/DroidPlugin equivalent.
         */
        fun buildStubIntent(
            hostPkg: String,
            stubComponent: String,
            guestPkg: String,
            guestClass: String,
        ): Intent = Intent().apply {
            setClassName(hostPkg, stubComponent)
            putExtra(EXTRA_GUEST_CLASS, guestClass)
            putExtra(EXTRA_GUEST_INTENT, guestPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ─── Hook B: swap stub → guest activity at instantiation ───

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        val guestClass = intent?.getStringExtra(EXTRA_GUEST_CLASS)
        if (guestClass != null && className == stubComponent) {
            try {
                // The stub P0 is being instantiated by ActivityThread with the
                // guest target stashed in its intent extras (KOS pattern: the
                // launch AMS initiated IS the swapped one — no second dispatch).
                // Instantiate the real guest Activity through the guest loader.
                val act = base.newActivity(guestClassLoader, guestClass, intent)

                // ══ Pre-attach rewire (PROVEN necessary, round-6 log) ══
                // performLaunchActivity calls newActivity() FIRST, then
                // attach(stubContext) + setTheme(stubTheme) — and the guest's
                // AppCompatActivity.attachBaseContext creates its
                // AppCompatDelegate DURING attach, capturing the theme
                // available at that moment. Rewiring only inside
                // callActivityOnCreate (after attach) was too late: the
                // delegate had already resolved host-material ids
                // (0x7f080080 design_menu_item_action_area_stub is in OUR
                // arsc via material:1.11.0, NOT in the guest's id space —
                // verified in the host resources.arsc).
                //
                // Fix: set mBase by DIRECT FIELD ASSIGNMENT here (before the
                // framework's attach() runs). Note attachBaseContext() is
                // unusable for this — ContextWrapper throws
                // IllegalStateException("Base context already set") when the
                // framework later attaches its stub context, which would
                // crash the launch. Field assignment avoids the guard; the
                // framework's attachBaseContext will then see mBase != null
                // and throw — SO we CANNOT pre-set the field either.
                //
                // The framework attach() is unavoidable; therefore the only
                // correct interception point is BETWEEN attach() and
                // onCreate — which is exactly callActivityOnCreate below.
                // The round-5 crash persisted because caches were nulled on
                // the ACTIVITY only; the AppCompatDelegate held its own
                // host-based Theme obtained via the WINDOW. The delegate's
                // theme comes from window.getContext().getTheme() where
                // PhoneWindow's context is the ACTIVITY itself — after the
                // cache rebuild in callActivityOnCreate the activity theme is
                // guest-based, but AppCompatDelegateImpl re-obtains the
                // theme LAZILY (mThemePeeked in subDecor construction).
                // callActivityOnCreate rewire therefore IS the right point;
                // what was missing in round 5 was that setTheme(guestResId)
                // ran AFTER ContextThemeWrapper caches were nulled — but the
                // delegate also needs getTheme() to be hit lazily. This is
                // now handled there (cache null + setTheme sequence).

                DiagLog.d(TAG, "newActivity swapped stub → $guestClass")
                return act
            } catch (e: Throwable) {
                DiagLog.err(TAG, "newActivity swap $guestClass failed", e)
                // fall through to stub
            }
        }
        DiagLog.d(TAG, "newActivity passthrough className=$className guestClass=$guestClass")
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        // KOS-equivalent 'Installed guest ActivityInfo before onCreate'.
        //
        // Framework flow (verified in AOSP ActivityThread.performLaunchActivity):
        //   attach() sets Activity.mActivityInfo = r.activityInfo (the STUB's
        //   ActivityInfo from OUR manifest) → setTheme(r.activityInfo.
        //   getThemeResource()) → the guest activity then inflates its layout
        //   through the HOST theme/resource table → 'Resources$NotFoundException
        //   design_menu_item_action_area_stub' (host id 0x7f080080 is not a
        //   drawable in the guest's resource space).
        //
        // Fix: before onCreate, install the GUEST's real ActivityInfo (its own
        // theme, softInputMode, uiOptions, flags...) and re-apply the guest
        // theme so getResources()/getTheme() resolve in the guest's space.
        // audit C8: guestApp/stubComponent เป็น var (rebind ได้) → local capture
        // ครั้งเดียวต่อ call เพื่อ smart-cast + กัน race ระหว่าง session swap
        val gapp = guestApp
        val stub = stubComponent
        try {
            if (gapp != null && activity.javaClass.name != stub &&
                activity.packageName != gapp.packageName) {
                // 0) FORCE-GUEST Resources (round-8, SNAKE-proven zg0.k/a5):
                //    Before ANY cache-null/recreate cycle, replace the host-table
                //    Resources (MiuiResourcesImpl) with one built on the guest's
                //    own arsc (addAssetPath guest apk FIRST). All subsequent
                //    lazy re-inits (delegate, theme, inflater) then resolve in
                //    the guest id space.
                buildGuestResources(gapp.packageName!!)?.let { gres ->
                    findFieldUp(activity.javaClass, "mResources")?.let { fl ->
                        fl.isAccessible = true
                        fl.set(activity, gres)
                    }
                    // guest app context: its mResources field drives
                    // getApplicationContext().getResources() too
                    findFieldUp(gapp.javaClass, "mResources")?.let { fl ->
                        fl.isAccessible = true
                        fl.set(gapp, gres)
                    }
                    // The base ContextImpl behind the guest app context caches
                    // its Resources in mResourcesInner — same table swap
                    runCatching {
                        val appCtx = gapp
                        if (appCtx != null) {
                            val mBaseF = findFieldUp(appCtx.javaClass, "mBase")
                            if (mBaseF != null) {
                                mBaseF.isAccessible = true
                                val baseCtx = mBaseF.get(appCtx)
                                if (baseCtx != null) {
                                    findFieldUp(baseCtx.javaClass, "mResources")?.let { fl ->
                                        fl.isAccessible = true
                                        fl.set(baseCtx, gres)
                                    }
                                }
                            }
                        }
                    }
                    DiagLog.d(TAG, "callActivityOnCreate: Resources → guest arsc table (${gres.javaClass.simpleName})")
                }
                // 1) mBase → guest app context (package, resources, classloader)
                val mBase = findFieldUp(activity.javaClass, "mBase")
                if (mBase != null) {
                    mBase.isAccessible = true
                    mBase.set(activity, guestApp)
                    DiagLog.d(TAG, "callActivityOnCreate: mBase → guest app ctx for ${activity.javaClass.name}")
                }
                // 1.5) PROVEN root cause of the round-5/6 Resources$NotFoundException:
                // The guest's AppCompatActivity.attachBaseContext(stubCtx) ran during
                // the framework attach() — BEFORE this hook — and lazily CREATED its
                // AppCompatDelegate with mContext = stub-wrapped context. The delegate
                // resolves AppCompatTheme attrs through ITS OWN mContext (verified in
                // androidx AppCompatDelegateImpl: createSubDecor → mContext.
                // obtainStyledAttributes(R.styleable.AppCompatTheme)), NOT through the
                // Activity — so nulling the Activity's ContextThemeWrapper caches
                // (round-5 fix) had no effect on it: host id 0x7f080080
                // (design_menu_item_action_area_stub — present in OUR arsc via
                // material:1.11.0, absent from the guest id space) resolved through
                // the HOST theme → 'not a Drawable' → crash.
                //
                // Fix: null the guest's mDelegate field (AppCompatActivity.mDelegate)
                // and its cached mResources. getDelegate() is lazily re-invoked inside
                // the guest's onCreate path (setTheme/getMenuInflater/ensureWindow all
                // call getDelegate()), recreating the delegate with the NOW-guest base
                // context (mBase swapped above) → mContext = guest → AppCompatTheme
                // resolves in the guest id space.
                findFieldUp(activity.javaClass, "mDelegate")?.let { fl ->
                    fl.isAccessible = true
                    fl.set(activity, null)
                }
                // NOTE (round-8): mResources is NO LONGER nulled here — step 0
                // above SET it to the guest-arsc Resources. Nulling would make
                // the lazy re-fetch fall back to the host-table Resources again
                // (the round-5..7 crash). mDelegate nulling stays: the delegate
                // recreates with the now-guest context.
                DiagLog.d(TAG, "callActivityOnCreate: AppCompat mDelegate nulled (will lazily recreate with guest ctx)")
                // Null the ContextThemeWrapper caches as well (round-5 fix —
                // mTheme/mInflater must re-resolve; mResources was replaced by
                // the guest table in step 0, NOT nulled).
                for (fieldName in listOf("mTheme", "mInflater")) {
                    findFieldUp(activity.javaClass, fieldName)?.let { fl ->
                        fl.isAccessible = true
                        fl.set(activity, null)
                    }
                }
                findFieldUp(activity.javaClass, "mThemeResource")?.let { fl ->
                    fl.isAccessible = true
                    fl.set(activity, 0)
                }
                DiagLog.d(TAG, "callActivityOnCreate: ContextThemeWrapper caches cleared (mTheme/mResources/mInflater/mThemeResource)")
                // 2) mActivityInfo → guest ActivityInfo (theme, flags, metadata)
                val guestInfo = resolveGuestActivityInfo(activity.javaClass.name)
                if (guestInfo != null) {
                    val f = findFieldUp(activity.javaClass, "mActivityInfo")
                    if (f != null) {
                        f.isAccessible = true
                        f.set(activity, guestInfo)
                        // Re-apply the GUEST theme — attach() already applied the
                        // STUB's; the guest layout depends on its own attributes.
                        val themeRes = guestInfo.theme
                        if (themeRes != 0) {
                            activity.setTheme(themeRes)
                        }
                        DiagLog.d(TAG, "callActivityOnCreate: guest ActivityInfo installed " +
                            "(theme=0x${Integer.toHexString(guestInfo.theme)}) for ${activity.javaClass.name}")
                    }
                }
            }
        } catch (e: Throwable) {
            DiagLog.d(TAG, "pre-onCreate rewire: ${e.message}")
        }
        base.callActivityOnCreate(activity, icicle)
    }

    /**
     * hop23 ≡ SNAKE b8.callActivityOnResume → Native.ac(activity, Method)
     * (T1 F2: Lcom/snake/helper/Native;->ac(Ljava/lang/Object;Ljava/lang/Object;)V
     *  @ dex 0xed930 ใน b8 — T2 NATIVE_CALLSITE_MAP hop 23)
     * ac→nativeProcessPair signature MATCH ตาม T1; snake แพ็ค Method ที่
     * hidden-dex ประกาศ (pjowqpxe — รอ D6) ฝั่งเราจึงส่ง Method ที่หาได้
     * (onResume ของ activity ตัวเอง) หรือ null เมื่อไม่มี — endpoint ของเรา
     * เป็นโค้ดเราทั้งคู่ (B4) จึงเป็นกลางเชิง semantics และ never-crash.
     */
    override fun callActivityOnResume(activity: Activity) {
        runCatching {
            val m = runCatching { activity.javaClass.getDeclaredMethod("onResume") }.getOrNull()
            com.aether.Engine.nativeProcessPair(activity, m)
        }.onFailure { DiagLog.d(TAG, "ac hop (nativeProcessPair): ${it.message}") }
        base.callActivityOnResume(activity)
    }

    /**
 * Re-root the guest's Resources at the GUEST's own arsc table.
 *
 * PROVEN root cause of rounds 5-7 (see 2026-09-11 RCA): the guest context
 * from createPackageContext hands back a Resources whose asset table wraps
 * the HOST's arsc (on MIUI: MiuiResourcesImpl). Every guest resId (0x7f...)
 * then resolves against the host table → 'Resources$NotFoundException:
 * com.aether:id/design_menu_item_action_area_stub' at super.onCreate.
 *
 * SNAKE-proven fix (zg0.k + a5.java, verified in decompile 2026-09-09):
 *   AssetManager am = AssetManager.class.newInstance()   // FRESH instance
 *   am.addAssetPath(guestApkSourceDir)                    // guest arsc FIRST
 *   Resources res = new Resources(am, hostMetrics, hostConfig)
 * → guest ids resolve in the guest's own table.
 */
private fun buildGuestResources(guestPkg: String): android.content.res.Resources? {
    return try {
        val pm = guestApp?.packageManager ?: return null
        val appInfo = pm.getApplicationInfo(guestPkg, 0)
        val apkPath = appInfo.sourceDir ?: return null
        // Fresh AssetManager via reflection — SNAKE a5: newInstance()
        val am = Class.forName("android.content.res.AssetManager")
            .getDeclaredConstructor().newInstance() as android.content.res.AssetManager
        val mAdd = am.javaClass.getMethod("addAssetPath", String::class.java)
        mAdd.isAccessible = true
        mAdd.invoke(am, apkPath)
        // host metrics/config are safe to reuse (they describe the SCREEN,
        // not the resource table)
        val hostRes = guestApp!!.resources ?: return null
        val ctor = android.content.res.Resources::class.java.getConstructor(
            android.content.res.AssetManager::class.java,
            android.util.DisplayMetrics::class.java,
            android.content.res.Configuration::class.java
        )
        val res = ctor.newInstance(am, hostRes.displayMetrics, hostRes.configuration)
        DiagLog.d(TAG, "buildGuestResources: guest arsc loaded from $apkPath")
        res
    } catch (e: Throwable) {
        DiagLog.d(TAG, "buildGuestResources failed: ${e.message}")
        null
    }
}
    private fun resolveGuestActivityInfo(activityClass: String): ActivityInfo? {
        return try {
            val pm = guestApp?.packageManager ?: return null
            val pkg = guestApp?.packageName ?: return null
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            pi.activities?.firstOrNull { it.name == activityClass }
                // fall back to the launcher's info (theme usually app-wide)
                ?: pi.activities?.firstOrNull { it.name.contains("Activity") }
        } catch (e: Throwable) {
            DiagLog.d(TAG, "resolveGuestActivityInfo: ${e.message}")
            null
        }
    }

    private fun findFieldUp(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try { return c.getDeclaredField(name) } catch (e: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }


    /**
     * If [intent] targets a guest activity (a class NOT in our manifest under
     * the guest package), stash the guest class and retarget the intent at the
     * registered stub so AMS launches it in :p0 instead of routing out.
     *
     * audit C9: ผู้เรียกเดิม (execStartActivity subclass) ตายโดยโครงสร้าง → ลบไป
     * แล้ว; logic นี้คือชิ้นที่ blueprint P4 จะประกอบใหม่ *ที่ binder layer*
     * (ServiceBinderProxy.handleActivityCall → startActivity) ไม่ใช่ subclass —
     * ยังไม่ wire = dead code ที่ตั้งใจ (P4), gate นับเป็น KNOWN-PENDING.
     */
    @Suppress("unused")
    private fun rewriteToStub(intent: Intent) {
        try {
            val comp = intent.component ?: return
            val cls = comp.className
            // Guest activities live under the guest package; our stub is com.aether.*
            if (cls.startsWith("com.aether.")) return          // already ours — skip
            if (intent.getStringExtra(EXTRA_GUEST_CLASS) != null) return
            intent.putExtra(EXTRA_GUEST_CLASS, cls)
            intent.putExtra(EXTRA_GUEST_INTENT, comp.packageName)
            intent.setClassName(
                // stub lives in the host package (com.aether)
                guestApp?.let { "com.aether" } ?: "com.aether",
                stubComponent,
            )
            DiagLog.d(TAG, "rewriteToStub: $cls → $stubComponent")
        } catch (e: Throwable) {
            DiagLog.err(TAG, "rewriteToStub", e)
        }
    }
}
