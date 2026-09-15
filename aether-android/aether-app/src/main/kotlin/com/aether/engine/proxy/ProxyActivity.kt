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
        // audit C15-diag-order: init ก่อนทุกบรรทัดที่ log (เดิม line แรกของ chain
        // คือ DiagLog.d ตอน dir==null → บรรทัด identity ที่หายไปจาก trace.log)
        DiagLog.init(applicationContext)

        // ★ p3-first identity (SNAKE semantics: ทุก getter ใน child อ่าน jv0.D2()
        //   = p3 ไม่ใช่ intent — jv0.E2():58): config ชนะ intent extras —
        //   ยกเว้นเดียว (audit C14): config ที่ค้างจากปุ่มวินิจฉัย (DIAG_PKG)
        //   ห้ามชนะ session จริง → reset แล้วใช้ intent
        val p3 = GuestProcessHolder.config
        val intentPkg = intent.getStringExtra("target_package") ?: "com.aether"
        if (p3 != null && p3.guestPkg == GuestProcessHolder.DIAG_PKG && intentPkg != GuestProcessHolder.DIAG_PKG) {
            DiagLog.d("ProxyActivity",
                "identity: diag config (${p3.guestPkg}) ล้างทิ้ง — guest จริง=$intentPkg (C14)")
            GuestProcessHolder.reset()
        }
        val p3Eff = GuestProcessHolder.config
        val targetPkg = p3Eff?.guestPkg?.takeIf { it.isNotEmpty() } ?: intentPkg
        if (p3Eff != null && p3Eff.guestPkg != intentPkg) {
            // audit C14: mismatch จริง (ไม่ใช่ diag) = chain พัง — รายงาน ไม่ใช่เงียบ
            DiagLog.d("ProxyActivity", "identity: p3=${p3Eff.guestPkg} overrides intent=$intentPkg")
            DiagLog.writeResult(linkedMapOf(
                "ok" to "false", "stage" to "identity",
                "reason" to "p3=${p3Eff.guestPkg}!=intent=$intentPkg",
                "identity" to p3Eff.guestPkg, "slot" to p3Eff.slot.toString()))
            finish()
            return
        }
        val isVirtual = targetPkg.isNotEmpty() && targetPkg != "com.aether"

        // VirtualFS data isolation: the guest must read/write its OWN sandboxed
        // data (root/data/user/0/<pkg>) — NOT the real installed app's data
        // dir. target_sandbox = virtual root (root/); package dir derived.
        val guestDataDir = intent.getStringExtra("target_sandbox")
            ?.let { root -> File(root, "data/user/0/$targetPkg") }

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
            ?: intent.getIntExtra(GuestProcessTable.EXTRA_SLOT, -1).let { s ->
                if (s >= 0) GuestProcessHolder.seed(targetPkg, s)
            }

        // 1. ตั้ง VirtualAppContainer ใน :p0/:p1/:p2/:p3 (เตรียม hooks)
        try {
            VirtualAppContainer.init(this, targetPkg)
            VirtualAppContainer.setup()
            DiagLog.d("ProxyActivity", "VirtualAppContainer ready for $targetPkg")
        } catch (e: Throwable) {
            DiagLog.err("ProxyActivity", "VirtualAppContainer setup failed", e)
            DiagLog.writeResult(linkedMapOf(
                "ok" to "false", "stage" to "container", "reason" to (e.message ?: e.javaClass.simpleName),
                "identity" to targetPkg))
        }

        // 2. Self-attach via AetherOrchestrator (no external intent)
        //    audit C13-vfs-clobber: เดิม hardcode "com.aether" → setupForApp ชี้
        //    VirtualFS กลับ host ทับ map ของ guest ที่ step 1 ตั้งไว้; virtual mode
        //    ต้อง attach ด้วย identity เดิมของ process (host — เพราะเราคือ host
        //    process ที่ pin ตัวเอง) แต่ห้าม re-point VFS เมื่อ guest map active
        try {
            val ownPid = android.os.Process.myPid()
            // C13-vfs-clobber: เดิม hardcode "com.aether" ที่ call-site; ตอนนี้
            // attachToProcess อ่าน identity จริงของ process เอง (param = null)
            val attached = com.aether.engine.proxy.AetherOrchestrator.attachToProcess(
                ownPid, ""
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
                DiagLog.d("ProxyActivity", "swap-miss: reported via launch_result + finish (C2)")
                DiagLog.writeResult(linkedMapOf(
                    "ok" to "false", "stage" to "swap",
                    "reason" to "newActivity passthrough — hook miss (stub!=installed)",
                    "identity" to targetPkg))
                finish()
                return
            }
            installGuestThreadFirewall()
            var launched = false
            // audit C2: stage tracking → launch_result.json (UI บอก hop ที่ตายจริง)
            var stage = "manifest"
            var loadReason = ""
            try {
                val gm = readGuestManifest(targetPkg)
                DiagLog.d("ProxyActivity", "manifest: app=${gm.appClass} launcher=${gm.launcher} providers=${gm.providers.size}")
                if (gm.launcher.isNullOrEmpty()) {
                    loadReason = "launcher not resolved from manifest/conf"
                } else {
                stage = "guest-load"
                // ── P4 ≡ snake hop18: HCallbackProxy ที่ mH.mCallback ก่อน bind ──
                // (blueprint: "ติดตั้ง my.java semantics ที่ :pN ก่อน installProviders")
                // armed = ระยะเวลา load; transaction อื่น (guest SDK startActivity
                // ซ้อน) ถูก hold แล้ว 放行 หลัง bind — bootstrap message ปัจจุบัน
                // ไม่ถูก hold (dispatch อยู่ = ยังไม่ arm ตอนรับ message)
                val hcbOk = HCallbackProxy.install()
                HCallbackProxy.arm()
                DiagLog.d("ProxyActivity", "HCallbackProxy install=$hcbOk armed (≡my@t1.g)")
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
                // P4/hop19: bind จบ (สำเร็จหรือล้ม) → 放行 transaction ที่ hold;
                // ล้ม = ปล่อยคืนระบบตามเดิม (ห้ามกิน message ของ framework)
                HCallbackProxy.finishBind()
                DiagLog.d("ProxyActivity", HCallbackProxy.status())

                // Scaffold-4: install Instrumentation hook so a guest-targeted
                // startActivity is retargeted to THIS stub (ProxyActivity$P0) and
                // swapped back to the guest Activity inside :p0 — instead of AMS
                // routing out to the real installed app.
                if (res.success && res.guestClassLoader != null && !gm.launcher.isNullOrEmpty()) {
                    // ★ device evidence 01:18:52.942: dispatch ย้ายเป็น P<slot> แล้ว
                    // (8c51880) แต่ install ยัง hardcode P0 → newActivity swap
                    // ตรวจ className==stubComponent fail → passthrough ตัว bootstrap
                    // (ProxyActivity ไม่ใช่เกม) รัน onCreate ของเกม → ตาย → UI เด้ง
                    // = regression ของตัวเอง; ใช้ component จริงของ instance นี้เสมอ
                    // Activity.getComponentName() → synthetic 'componentName'
                    // ('component' ไม่มีบน Activity — CI run 34774695761 fail)
                    val selfStub = componentName?.className
                        ?: "com.aether.engine.proxy.ProxyActivity\$P0"
                    val hooked = AetherInstrumentation.install(
                        stubComponent = selfStub,
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
                } else {
                    stage = "instrumentation"
                    loadReason = res.reason.ifEmpty {
                        if (!res.success) "guest load failed"
                        else if (res.guestClassLoader == null) "no guest classloader"
                        else "launcher unresolved"
                    }
                }
                } // else launcher != null
            } catch (e: Throwable) {
                stage = "guest-load"
                loadReason = "${e.javaClass.simpleName}: ${e.message}"
                // ปล่อย message ที่ hold คืนระบบเสมอ (ห้ามค้างใน queue ของ framework)
                runCatching { HCallbackProxy.finishBind() }
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
            DiagLog.writeResult(linkedMapOf(
                "ok" to launched.toString(),
                "stage" to if (launched) "recreate" else stage,
                "reason" to (if (launched) "relaunch scheduled" else loadReason.ifEmpty { "unknown" }),
                "identity" to targetPkg,
                "slot" to (GuestProcessHolder.config?.slot ?: -1).toString()))
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
                // exit=reported (C2): เหตุผลอยู่ใน launch_result.json แล้ว;
                // guest session จบ → คืน Holder ให้ slot ว่างจาก identity เก่า (C14)
                GuestProcessHolder.reset()
                finish()
            }
            return
        }

        // 4. Non-virtual (self/host) mode: finish — no external Intent dispatched
        DiagLog.writeResult(linkedMapOf(
            "ok" to "true", "stage" to "host", "reason" to "non-virtual (host mode)",
            "identity" to targetPkg))
        finish()
    }

    /**
     * audit C8-never-closed: guest runtime ต้องถูกปิดเมื่อ stub จบ — ปล่อยค้างไว้
     * ทำให้ relaunch รอบถัดไปเจอ classloader/identity ของ session เก่า (C8-stale-hook)
     */
    override fun onDestroy() {
        if (isFinishing) {
            runCatching { GuestRuntimeBridge.close() }
                .onFailure { DiagLog.d("ProxyActivity", "bridge close: ${it.message}") }
            // guest session จบจริง (ไม่ใช่ recreate — recreate ไม่เรียก onDestroy
            // ของ instance เก่า... บน API บางรุ่นเรียก — guard ด้วย isFinishing)
            runCatching { GuestProcessHolder.reset() }
            runCatching { AetherInstrumentation.reset() }
            // P4: ถอด mCallback คืน prev + ล้าง queue (session จบจริงเท่านั้น)
            runCatching { HCallbackProxy.reset() }
        }
        super.onDestroy()
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
        val swallowed = java.util.concurrent.atomic.AtomicInteger(0)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val isMain = t === Looper.getMainLooper().thread
            DiagLog.err("ProxyActivity", "guest-firewall caught on ${t.name} (main=$isMain)", e)
            // audit C15: นับ + รายงานผ่าน launch_result แทนเงียบ (เดิม dumpLogcat
            // ทุกครั้ง = ถล่ม buffer; ตอนนี้ทุก 5 ครั้ง)
            val n = swallowed.incrementAndGet()
            DiagLog.writeResult(linkedMapOf(
                "ok" to "false", "stage" to "firewall",
                "reason" to e.javaClass.simpleName + " on " + t.name + " (swallowed #" + n + ")",
                "identity" to (GuestProcessHolder.config?.guestPkg ?: "?")))
            if (n % 5 == 1) DiagLog.dumpLogcat("firewall main=$isMain thread=${t.name}")
            // audit C15-firewall-swallows (strict): swallow เฉพาะ known-benign =
            // SecurityException บน background thread (GMS dynamite/WorkManager/
            // shortcuts ที่ binder ถือ guest pkg ใต้ host UID); ทุกกรณีอื่น —
            // รวม main thread — ต้อง chain ให้ prev (system default) เพื่อ
            // process ตายจริง + มี tombstone/dropbox ให้ diagnosis (01:18
            // mystery-disappearance ที่ crashed:false จะไม่มองไม่เห็นอีก)
            // device proof 17:07:01.266 (round-3): whitelist ปิด caller-slot SE ครบ
            // (0 rethrow families) → SE ที่เหลือ = GMS broker protocol (m7.*
            // dynamite measurement ส่งชื่อ guest ให้ com.google.android.gms ตรวจ
            // กับ PMS จริง — ไม่ใช่ระบบ service ของเราจึง spoof ที่ proxy ไม่ได้)
            // และมัน dispatch บน MAIN looper ของ guest SDK ≡ เคสนinja ที่ blueprint
            // D5 ระบุให้ไหลเป็น benign — swallow + report; เคสนอก family นี้ chain ตาม C15
            val gmsFamily = e.stackTrace.any { st ->
                val c = st.className
                c.startsWith("m7.") || c.startsWith("com.google.android.gms") ||
                c.startsWith("com.google.firebase") || c.startsWith("bb.")
            }
            val benign = e is SecurityException && (!isMain || gmsFamily)
            if (!benign && prev != null) {
                DiagLog.d("ProxyActivity", "firewall CHAIN→prev: ${e.javaClass.name} main=$isMain")
                try { prev.uncaughtException(t, e) } catch (_: Throwable) { }
            } else {
                DiagLog.d("ProxyActivity", "firewall swallowed benign=${benign} on ${t.name}")
            }
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

    // audit C7 (F3 parity): landscape stubs — guest (8BP) ล็อก orientation;
    // ไม่มี stub ฝั่ง landscape = ไม่มี component ให้ AMS ตอนหมุนจอ/relaunch
    class P0_L : ProxyActivity()
    class P1_L : ProxyActivity()
    class P2_L : ProxyActivity()
    class P3_L : ProxyActivity()
}
