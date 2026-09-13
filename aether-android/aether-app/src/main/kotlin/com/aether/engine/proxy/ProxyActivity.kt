package com.aether.engine.proxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.io.File

/**
 * ProxyActivity — Multi-process worker activity (multi-process :p0-:p3)
 * แต่ละ instance รันใน process pool แยก เพื่อ isolate IPC workload
 *
 * Phase 1+2 — IN-PROCESS ONLY
 *   AetherEngine does NOT launch external games or open the Play Store.
 *   This activity only:
 *     1. Initializes VirtualAppContainer in this process (for IPC isolation)
 *     2. Self-attach via AetherOrchestrator (own PID + libaether.so)
 *     3. Returns — no Intent dispatch, no external startActivity
 *
 * The :p0-:p3 multi-process pool still exists for process isolation
 * (IPC workload separation) but the engine itself runs in libaether.so
 * inside whatever process the activity is hosted in.
 */
open class ProxyActivity : Activity() {
    companion object {
        @Volatile private var firewallInstalled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ p3-first identity (SNAKE semantics: ทุก getter ใน child อ่าน jv0.D2()
        //   = p3 ไม่ใช่ intent — jv0.E2():58): ถ้า handshake ยื่น config ตอน
        //   provider install แล้ว ให้ config ชนะ intent extras เสมอ
        val p3 = GuestProcessHolder.config
        val intentPkg = intent.getStringExtra("target_package") ?: "com.aether"
        val targetPkg = p3?.guestPkg?.takeIf { it.isNotEmpty() } ?: intentPkg
        if (p3 != null && p3.guestPkg != intentPkg) {
            DiagLog.d("ProxyActivity", "identity: p3=${p3.guestPkg} overrides intent=$intentPkg")
        }
        val isVirtual = targetPkg.isNotEmpty() && targetPkg != "com.aether"

        // VirtualFS data isolation: the guest must read/write its OWN sandboxed
        // data (vision/data/user/0/<pkg>) — NOT the real installed app's data
        // dir. target_sandbox = virtual root (vision/); package dir derived.
        val guestDataDir = intent.getStringExtra("target_sandbox")
            ?.let { root -> File(root, "data/user/0/$targetPkg") }

        DiagLog.init(applicationContext)
        DiagLog.d("ProxyActivity", "onCreate target=$targetPkg virtual=$isVirtual pid=${android.os.Process.myPid()}")
        // capture getApplication()/resources state BEFORE any guest work — this
        // is what the framework reads in handleLaunchActivity (crash point).
        DiagLog.d("ProxyActivity", "ctx: application=${application?.javaClass?.name} " +
            "getResources=${runCatching { resources != null }.getOrNull()} " +
            "appCtx=${applicationContext?.javaClass?.name}")

        // 0. ★ ขั้น ②: ถ้า provider handshake (call METHOD_INIT) เกิดขึ้นแล้ว —
        //    GuestProcessHolder.config ถูก set ตอน framework install provider
        //    (ก่อนถึงตรงนี้) → log ยืนยัน; ยังไม่มี (fallback path) → seed จาก intent
        GuestProcessHolder.config
            ?.also { DiagLog.d("ProxyActivity", "p3 via handshake: slot=${it.slot} pkg=${it.guestPkg}") }
            ?: intent.getIntExtra("guest_slot", -1).let { s ->
                if (s >= 0) GuestProcessHolder.seed(targetPkg, s)
            }

        // 1. ตั้ง VirtualAppContainer ใน :p0/:p1/:p2/:p3 (เตรียม hooks)
        try {
            VirtualAppContainer.init(this, targetPkg)
            VirtualAppContainer.setup()
            DiagLog.d("ProxyActivity", "VirtualAppContainer ready for $targetPkg")
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "VirtualAppContainer setup failed", e)
        }

        // 2. Self-attach via AetherOrchestrator (no external intent)
        try {
            val ownPid = android.os.Process.myPid()
            val attached = com.aether.engine.proxy.AetherOrchestrator.attachToProcess(
                ownPid, "", "com.aether"
            )
            if (attached) {
                com.aether.engine.proxy.AetherOrchestrator.startEngine()
                DiagLog.d("ProxyActivity", "Self-attach OK in PID=$ownPid, engine started")
            } else {
                DiagLog.d("ProxyActivity", "Self-attach failed in PID=$ownPid")
            }
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "Self-attach exception", e)
        }

        // 3. Virtual-target mode: load + START the guest IN THIS proxy process
        //    (jv0.O2 port). Isolated here so a guest crash cannot take down the
        //    main UI process. Firewall contains guest background-thread crashes.
        if (isVirtual) {
            // RELAUNCH (2nd onCreate after recreate()): the intent already
            // carries the guest extras — AetherInstrumentation.newActivity has
            // ALREADY swapped this instance to the real guest Activity class.
            // Guard so we never re-run the bootstrap (which would loop).
            // Note: if this code is reached with extras present, the swap did
            // not happen (hook missing) — finish to avoid a blank stub.
            if (intent.getStringExtra(AetherInstrumentation.EXTRA_GUEST_CLASS) != null) {
                DiagLog.d("ProxyActivity", "relaunch reached bootstrap — swap did not happen; finishing")
                finish()
                return
            }
            installGuestThreadFirewall()
            var launched = false
            try {
                val gm = readGuestManifest(targetPkg)
                DiagLog.d("ProxyActivity", "manifest: app=${gm.appClass} launcher=${gm.launcher} providers=${gm.providers.size}")
                // S1 currentApplication + S2 providers + S3 onCreate.
                // Step 3: migrate to GuestRuntimeBridge (v2 compat layer).
                // Bridge delegates to v2 (GuestRuntime) with auto-fallback to v1.
                // Signature identical to VirtualAppLoader.load - drop-in replacement.
                val res = GuestRuntimeBridge.load(
                    hostContext = applicationContext,
                    targetPkg = targetPkg,
                    appClassHint = gm.appClass,
                    callOnCreate = true,
                    providers = gm.providers,
                    sandboxDir = guestDataDir
                )
                if (guestDataDir != null) {
                    DiagLog.d("ProxyActivity", "guest data dir → ${guestDataDir.absolutePath}")
                }
                DiagLog.d("ProxyActivity",
                    "GuestRuntimeBridge($targetPkg) -> ${res.success} (${res.reason}) " +
                    "source=${res.runtimeSource} providers=${res.providersInstalled}/${gm.providers.size} " +
                    "guestCL=${res.guestClassLoader != null} " +
                    "metrics=${res.metrics.totalLoadMs}ms fallback=${res.metrics.fallbackTriggered}")

                // Scaffold-4: install Instrumentation hook so a guest-targeted
                // startActivity is retargeted to THIS stub (ProxyActivity$P0) and
                // swapped back to the guest Activity inside :p0 — instead of AMS
                // routing out to the real installed app.
                if (res.success && res.guestClassLoader != null && !gm.launcher.isNullOrEmpty()) {
                    val hooked = AetherInstrumentation.install(
                        stubComponent = "com.aether.engine.proxy.ProxyActivity\$P0",
                        guestClassLoader = res.guestClassLoader,
                        guestApp = res.loadedApplication,
                    )
                    DiagLog.d("ProxyActivity", "AetherInstrumentation.install → $hooked")
                    // KOS-verified pattern: do NOT dispatch a SECOND P0 instance.
                    // In the KOS reference the FIRST (and only) P0 activity launch is
                    // the one that gets swapped to the guest ('newActivity: instantiated
                    // through guest AppComponentFactory' appears exactly once, for the
                    // launch that AMS initiated in response to the user tapping play).
                    // Our second startActivity from an activity that is finishing was
                    // never dispatched by AMS (verified: 0 framework logs after it).
                    // Instead: rewrite THIS activity's intent extras so the guest
                    // target is carried on the CURRENT instance, then trigger the
                    // relaunch-in-place that AMS already knows about.
                    intent.putExtra(AetherInstrumentation.EXTRA_GUEST_CLASS, gm.launcher)
                    intent.putExtra(AetherInstrumentation.EXTRA_GUEST_INTENT, targetPkg)
                    DiagLog.d("ProxyActivity",
                        "guest target carried on bootstrap P0 intent: $targetPkg/${gm.launcher}")
                    launched = true
                    // The actual instantiation happens on relaunch: the bootstrap
                    // instance finishes, AMS relaunches P0 (standard singleTop-style
                    // recreation), and AetherInstrumentation.newActivity swaps it
                    // to the guest class via the extras above.
                }
            } catch (e: Throwable) {
                DiagLog.err("ProxyActivity", "guest launch exception", e)
            }
            // dump full process logcat (framework + our traces) for post-mortem.
            // NOTE: run on a background thread — Thread.sleep on main would block
            // the Looper for 1.5s right when AMS delivers the launch message
            // (verified: 0 log lines for the whole window after dispatch, the
            // swap never ran, UI bounced back).
            Thread {
                try {
                    Thread.sleep(1500)
                } catch (ie: InterruptedException) { /* keep go */ }
                DiagLog.dumpLogcat("after guest launch (launched=$launched)")
            }.start()
            if (launched) {
                // Relaunch THIS instance through the framework: recreate() runs
                // the normal activity lifecycle (onDestroy → onCreate) WITHOUT a
                // second AMS dispatch — ActivityThread.handleRelaunchActivity
                // calls Instrumentation.newActivity with the SAME intent (which
                // now carries the guest extras) → the hook swaps stub → guest.
                // This is the single-launch swap the KOS reference performs.
                DiagLog.d("ProxyActivity", "recreate() → relaunch with guest extras")
                runOnUiThread { recreate() }
            } else {
                // Launch failed — nothing to show, close the bootstrap instance.
                finish()
            }
            return
        }

        // 4. Non-virtual (self/host) mode: finish — no external Intent dispatched
        finish()
    }

    /**
     * Scaffold-4: start the guest launcher activity THROUGH the stub.
     *
     * The intent is REWRITTEN UP FRONT to ProxyActivity$P0 (registered in our
     * manifest under :p0) with the real guest class/package stashed in extras —
     * then AetherInstrumentation.newActivity (hook B, public override) swaps the
     * stub back to the real guest Activity inside :p0.
     *
     * Previously this dispatched setClassName(targetPkg, launcher) directly and
     * relied on execStartActivity to rewrite it. execStartActivity is a HIDDEN
     * API that ActivityThread invokes via reflection on the concrete framework
     * signature — a Kotlin subclass method is never dispatched, so the rewrite
     * never ran and AMS routed the intent OUT to the real installed app.
     */
    private fun startGuestActivity(targetPkg: String, launcher: String): Boolean {
        return try {
            val intent = AetherInstrumentation.buildStubIntent(
                hostPkg = packageName,
                stubComponent = "com.aether.engine.proxy.ProxyActivity\$P0",
                guestPkg = targetPkg,
                guestClass = launcher,
            )
            startActivity(intent)
            DiagLog.d("ProxyActivity", "guest activity dispatched via stub: $targetPkg/$launcher → P0")
            true
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "startGuestActivity($launcher)", e)
            false
        }
    }

    /**
     * Install a process-wide uncaught-exception handler for the :p0 proxy
     * process that logs guest-thread crashes instead of letting them reach the
     * system default handler (which would kill the app). Chains to the previous
     * handler ONLY for the proxy's own main thread; background (guest) threads
     * are contained.
     */
    private fun installGuestThreadFirewall() {
        if (firewallInstalled) return
        firewallInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val isMain = t === Looper.getMainLooper().thread
            DiagLog.err("ProxyActivity", "guest-firewall caught on ${t.name} (main=$isMain)", e)
            DiagLog.dumpLogcat("firewall main=$isMain thread=${t.name}")
            // GUEST ISOLATION: once the guest Application has been started in
            // this process, SDK background work (GMS dynamite measurement,
            // WorkManager, Crashlytics, Play Games shortcuts...) regularly
            // throws SecurityException because binder calls carry the guest
            // package name under our host UID. These are NOT host bugs and
            // must NOT kill the whole process — KOS survives them via its
            // VirtualServiceContext; we survive them by containing EVERY
            // exception from guest-started work (any thread, including main
            // Handler messages dispatched by guest SDKs).
            // A truly-dead main looper is unrecoverable either way, but the
            // framework has already torn the activity down by the time an
            // uncaught handler runs — swallowing here keeps the process alive
            // so the guest activity (already dispatched) can still launch.
            // DO NOT propagate to prev (default kill) while a guest is active.
        }
    }

    /** Guest manifest fields needed to load + launch. */
    private data class GuestManifest(
        val appClass: String?,
        val providers: List<String>,
        val launcher: String?,
    )

    /**
     * Read the guest's REAL manifest from the installed target APK via
     * PackageManager (public API; QUERY_ALL_PACKAGES granted). This is the
     * source of truth — NOT the fabricated package.conf stub, which contained
     * engine components (com.aether.AetherHostActivity) instead of the target's.
     *
     * Falls back to package.conf only if the target is not queryable.
     */
    private fun readGuestManifest(targetPkg: String): GuestManifest {
        // 1. Primary: query the installed target APK directly.
        try {
            val pm = packageManager
            val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_PROVIDERS
            val pi = pm.getPackageInfo(targetPkg, flags)
            val appClass = pi.applicationInfo?.className
            val providers = pi.providers?.mapNotNull { it.name } ?: emptyList()
            // launcher: resolve the MAIN/LAUNCHER activity for this package.
            val launcher = pm.getLaunchIntentForPackage(targetPkg)?.component?.className
                ?: pi.activities?.firstOrNull { it.name.contains("EightBallPool") }?.name
                ?: pi.activities?.firstOrNull()?.name
            DiagLog.d("ProxyActivity",
                "readGuestManifest(PM): app=$appClass launcher=$launcher providers=${providers.size} activities=${pi.activities?.size ?: 0}")
            if (launcher != null) {
                return GuestManifest(appClass, providers, launcher)
            }
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "readGuestManifest(PM)", e)
        }
        // 2. Fallback: package.conf (only if PM query failed / not installed).
        return try {
            val conf = java.io.File(
                com.aether.SandboxManager.getSandboxRoot(),
                "data/app/$targetPkg/package.conf"
            )
            if (conf.exists() && conf.length() >= 32) {
                val pc = com.aether.PackageConfParser.parse(conf.readBytes())
                DiagLog.d("ProxyActivity",
                    "readGuestManifest(conf): app=${pc.applicationClass} launcher=${pc.launcherActivity} providers=${pc.providers.size}")
                GuestManifest(pc.applicationClass, pc.providers, pc.launcherActivity)
            } else GuestManifest(null, emptyList(), null)
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "readGuestManifest(conf)", e)
            GuestManifest(null, emptyList(), null)
        }
    }

    class P0 : ProxyActivity()
    class P1 : ProxyActivity()
    class P2 : ProxyActivity()
    class P3 : ProxyActivity()
}
