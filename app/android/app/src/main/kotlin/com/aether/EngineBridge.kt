package com.aether

import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * com.aether.engine_bridge — Flutter ↔ Aether shell bridge (Phase 3)
 *
 * Phase 3 methods (in-process, no external Intent unless user opts-in):
 *   (launchGame/getEngineStatus cut 2026-09-14 — audit C1: 0 Dart callers)
 *   - isTargetInstalled : is a package installed? (precheck before virtualization)
 *   - getEngineStats    : full stats (readCount, scanCount, uptime, etc)
 *   - readMemory        : read N bytes from module base (demo: libaether.so .text)
 *   - scanAOB           : scan a hex pattern in module .text (demo)
 *   - nativeCompute     : run Engine.nativeCompute (8-byte hex)
 *   - compressPayload   : native zlib deflate
 *   - launchInSandbox   : virtualize target (in-process — NOT external Intent)
 *   - launchApp         : OPT-IN: launch any installed app by package name
 *
 * CI-safe: every function uses BLOCK BODY (no try-as-expression).
 * Kotlin compiler in CI infers Unit for catch branches when the
 * try is an expression-body — block body with explicit return is
 * the CI-safe pattern.
 */
object EngineBridge : MethodCallHandler {
    private const val TAG = "EngineBridge"
    internal const val CHANNEL = "com.aether/engine_bridge"
    private var channel: MethodChannel? = null
    private var ctx: Context? = null

    fun init(context: Context, messenger: BinaryMessenger) {
        this.ctx = context
        channel = MethodChannel(messenger, CHANNEL)
        channel?.setMethodCallHandler(this)
        // Phase 1+2 critical: self-attach in THIS process.
        try {
            val orchestrator = com.aether.engine.proxy.AetherOrchestrator
            if (!orchestrator.isInitialized()) {
                orchestrator.init(context)
            }
            val ownPid = android.os.Process.myPid()
            if (!orchestrator.isAttachedToProcess()) {
                val attached = orchestrator.attachToProcess(ownPid, "", "com.aether")
                if (attached) {
                    orchestrator.startEngine()
                    Log.i(TAG, "EngineBridge.init: self-attach OK in main PID=$ownPid, engine started")
                } else {
                    Log.e(TAG, "EngineBridge.init: self-attach FAILED in main PID=$ownPid")
                }
            } else {
                Log.i(TAG, "EngineBridge.init: already attached in main PID=$ownPid")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "EngineBridge.init: self-attach exception: ${e.message}", e)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                            "isTargetInstalled" -> result.success(isTargetInstalled(call.argument<String>("packageName")))
                // "getEngineStatus" branch ตัด (audit C1: 0 callers ฝั่ง Dart —
                // UI ใช้ getEngineStats)
                "getEngineStats" -> result.success(getEngineStats())
                "getVirtualAppStatus" -> result.success(getVirtualAppStatus())
                "testVirtualFS" -> result.success(testVirtualFS())
                "readMemory" -> result.success(readMemory(
                    call.argument<Number>("address")?.toLong() ?: 0L,
                    call.argument<Number>("size")?.toInt() ?: 0
                ))
                "scanAOB" -> result.success(scanAOB(
                    call.argument<String>("hex") ?: "",
                    call.argument<String>("mask") ?: ""
                ))
                "nativeCompute" -> result.success(nativeComputeHex(
                    call.argument<Number>("input")?.toInt() ?: 0
                ))
                "compressPayload" -> result.success(compressPayloadHex(
                    call.argument<String>("hex") ?: ""
                ))
                "launchApp" -> result.success(launchApp(
                    call.argument<String>("packageName") ?: ""
                ))
                "launchInSandbox" -> result.success(launchInSandbox(
                    call.argument<String>("packageName") ?: ""
                ))
                "readDiag" -> result.success(readDiag())
                "chainCheck" -> result.success(chainCheck(
                    call.argument<String>("packageName") ?: ""
                ))
                "handshakeStatus" -> result.success(handshakeStatus())
                else -> result.notImplemented()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Method ${call.method} failed: ${e.message}", e)
            result.error("ENGINE_ERROR", e.message, null)
        }
    }

    // ══════════════════════════════════════════
    //  Engine methods — all block body
    // ══════════════════════════════════════════

    /**
     * isTargetInstalled — checks whether [packageName] is installed on this device.
     * Used by Flutter UI to precheck before virtualization (so we never silently
     * "virtualize" a missing target, and never auto-dispatch an external Intent
     * to a non-existent app).
     */
    private fun isTargetInstalled(packageName: String?): Boolean {
        val c = ctx ?: return false
        if (packageName.isNullOrEmpty()) return false
        return try {
            c.packageManager.getPackageInfo(packageName, 0)
            Log.i(TAG, "isTargetInstalled($packageName) → true")
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            Log.i(TAG, "isTargetInstalled($packageName) → false (not found)")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "isTargetInstalled($packageName) error: ${e.message}")
            false
        }
    }

    private fun getEngineStats(): Map<String, Any> {
        val o = com.aether.engine.proxy.AetherOrchestrator
        val s = o.getStats()
        val out = HashMap<String, Any>()
        out["initialized"] = s.initialized
        out["attached"] = s.attached
        out["running"] = s.running
        out["targetPid"] = s.targetPid
        out["targetModule"] = s.targetModule
        out["uptimeMs"] = s.uptimeMs
        out["readCount"] = s.readCount
        out["scanCount"] = s.scanCount
        out["serviceProxyCount"] = s.serviceProxyCount
        return out
    }

    /**
     * Phase 3.5.C: virtual app status snapshot for Flutter UI.
     * Reports VirtualAppContainer state (read-only).
     */
    private fun getVirtualAppStatus(): Map<String, Any> {
        val vac = com.aether.engine.proxy.VirtualAppContainer
        val out = HashMap<String, Any>()
        out["ready"] = vac.isReady()
        out["realPackage"] = vac.getRealPackageName()
        out["fakePackage"] = vac.getFakePackageName()
        out["serviceProxyCount"] = vac.getServiceProxyCount()
        out["redirectCount"] = vac.getRedirectCount()
        out["fakeDataDir"] = vac.getFakeDataDir()
        out["fakeNativeLibDir"] = vac.getFakeNativeLibDir()
        out["virtualTarget"] = vac.isVirtualTarget()
        out["classRuleCount"] = try { com.aether.Engine.classRuleCount() } catch (e: Throwable) { 0 }
        return out
    }

    /**
     * Phase 3.5.C: exercise VirtualFS path resolution. Returns a
     * human-readable status string ("OK: ..." or "NO REDIRECT: ...").
     */
    private fun testVirtualFS(): String {
        return com.aether.engine.proxy.VirtualAppContainer.testVirtualFSResolve()
    }

    private fun readMemory(address: Long, size: Int): Map<String, Any>? {
        if (size <= 0 || size > 4096) return null
        val o = com.aether.engine.proxy.AetherOrchestrator
        if (!o.isAttachedToProcess()) return null
        val pid = o.getTargetPid()
        val moduleName = o.getTargetModuleName()
        val base: Long = if (address == 0L) {
            com.aether.Engine.nativeFindModuleBase(pid, moduleName)
        } else address
        if (base <= 0) return null
        val data: ByteArray? = o.readMemory(base, size)
        if (data == null) return null
        val hex = StringBuilder()
        for (b in data) hex.append(String.format("%02x", b.toInt() and 0xff))
        val ascii = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c in 32..126) ascii.append(c.toChar()) else ascii.append('.')
        }
        val out = HashMap<String, Any>()
        out["size"] = data.size
        out["address"] = base
        out["hex"] = hex.toString()
        out["ascii"] = ascii.toString()
        return out
    }

    private fun scanAOB(hex: String, maskIn: String): Long {
        if (hex.isEmpty()) return 0
        val o = com.aether.engine.proxy.AetherOrchestrator
        if (!o.isAttachedToProcess()) return 0
        if (hex.length % 2 != 0) return 0
        val pattern = ByteArray(hex.length / 2)
        try {
            for (i in pattern.indices) {
                pattern[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Throwable) {
            return 0
        }
        val mask = if (maskIn.isEmpty()) "x".repeat(pattern.size) else maskIn
        val pid = o.getTargetPid()
        val moduleName = o.getTargetModuleName()
        val base = com.aether.Engine.nativeFindModuleBase(pid, moduleName)
        if (base <= 0) return 0
        return com.aether.Engine.nativeScanAOB(pid, base, 256L * 1024, pattern, mask)
    }

    /**
     * nativeComputeHex — 8-byte result, hex-encoded as String (avoids ByteArray? type inference issues).
     * Returns empty string on failure.
     */
    private fun nativeComputeHex(input: Int): String {
        return try {
            val r: ByteArray? = com.aether.Engine.nativeCompute(input)
            if (r == null) "" else bytesToHex(r)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeCompute failed: ${e.message}")
            ""
        }
    }

    /**
     * compressPayloadHex — zlib deflate. Input/output both hex strings.
     * Returns empty string on failure.
     */
    private fun compressPayloadHex(hex: String): String {
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        return try {
            val data = hexToBytes(hex)
            val r: ByteArray? = com.aether.Engine.nativeCompressPayload(data)
            if (r == null) "" else bytesToHex(r)
        } catch (e: Throwable) {
            Log.e(TAG, "compressPayload failed: ${e.message}")
            ""
        }
    }

    // ══════════════════════════════════════════
    //  External Intent (opt-in)
    // ══════════════════════════════════════════

    private fun launchApp(packageName: String): Boolean {
        val c = ctx ?: return false
        if (packageName.isEmpty()) return false
        return try {
            val intent = c.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(intent)
            Log.i(TAG, "launchApp($packageName) → dispatched")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "launchApp($packageName) failed: ${e.message}")
            false
        }
    }

    /**
     * launchInSandbox — run [packageName] as a virtual app inside this process
     * (jv0.O2 loader + sandbox provisioning + class-map redirect), NOT an
     * external Intent. Returns false if the orchestrator is not ready or the
     * target could not be virtualized.
     */
    private fun launchInSandbox(packageName: String): Any {
        val c = ctx ?: return mapOf("ok" to false, "stage" to "bridge", "reason" to "no context")
        if (packageName.isEmpty())
            return mapOf("ok" to false, "stage" to "bridge", "reason" to "empty package")
        return try {
            val ok = com.aether.engine.proxy.AetherOrchestrator.launchInSandbox(c, packageName)
            Log.i(TAG, "launchInSandbox($packageName) -> $ok")
            if (!ok) mapOf("ok" to false, "stage" to "dispatch",
                "reason" to "orchestrator refused - see chainCheck/trace")
            else launchResultAwait(packageName)
        } catch (e: Throwable) {
            Log.e(TAG, "launchInSandbox($packageName) failed: ${e.message}")
            mapOf("ok" to false, "stage" to "bridge",
                "reason" to (e.javaClass.simpleName + ": " + e.message))
        }
    }

    /**
     * audit C2: :pN เขียน files/diag/launch_result.json ข้าม process —
     * รอแบบ non-blocking บน background thread แล้วคืน Map{ok,stage,reason,identity,slot}
     */
    private fun launchResultAwait(packageName: String): Any {
        val t0 = System.currentTimeMillis()
        val done = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
        Thread {
            var m: Map<String, String> = emptyMap()
            repeat(10) {
                try { Thread.sleep(400) } catch (_: InterruptedException) { }
                m = com.aether.engine.proxy.DiagLog.readResult()
                // ts ต้องใหม่กว่า t0 — ไม่งั้นเป็น result ค้างจาก session ก่อน (stale)
                val fresh = (m["ts"]?.toLongOrNull() ?: 0L) > t0
                if (fresh && m["identity"] == packageName &&
                    (m["ok"] == "false" || m["stage"] == "recreate")) {
                    result.set(m); done.countDown(); return@Thread
                }
            }
            result.set(m.filterKeys { it != "ts" })  // timeout: ถ้าไม่ fresh ก็ทิ้ง
            done.countDown()
        }.start()
        done.await(4200, java.util.concurrent.TimeUnit.MILLISECONDS)
        val m = result.get().filterKeys { it != "ts" }
        val out = LinkedHashMap<String, Any>(m)
        if (m.isEmpty()) {
            out["ok"] = true
            out["stage"] = "pending"
            out["reason"] = "child ยังไม่เขียน result ใน 4.2s - กด Diag ดู trace"
        } else {
            out["ok"] = m["ok"] == "true"   // Dart เทียบ == true (bool)
        }
        return out
    }

    /**
     * readDiag — collect the in-app diagnostic traces (DiagLog trace.log +
     * latest logcat dump + latest crash report) so the Flutter UI can show
     * them without adb. Reads from the :p0 process's shared filesDir.
     */
    private fun readDiag(): String {
        val c = ctx ?: return "(no context)"
        return try {
            val sb = StringBuilder()
            val diagDir = java.io.File(c.filesDir, "diag")
            val trace = java.io.File(diagDir, "trace.log")
            if (trace.exists()) {
                sb.append("═══ trace.log (${trace.length()}B) ═══\n")
                sb.append(trace.readText())
                sb.append("\n")
            } else {
                sb.append("(no trace.log — sandbox not launched yet)\n")
            }
            // newest logcat dump
            diagDir.listFiles { f -> f.name.startsWith("logcat_") }
                ?.maxByOrNull { it.lastModified() }
                ?.let {
                    sb.append("\n═══ ${it.name} (${it.length()}B) ═══\n")
                    // tail — last 400 lines is enough for the launch window
                    val lines = it.readText().lines()
                    sb.append(lines.takeLast(400).joinToString("\n"))
                }
            // audit C15-diag-nocrash: แนบ crash report ล่าสุด (CrashHandler เขียน
            // filesDir/crash_logs/ — readDiag เดิมไม่เคยแสดง)
            java.io.File(c.filesDir, "crash_logs").listFiles { f ->
                f.name.startsWith("crash_") && f.name.endsWith(".log")
            }?.maxByOrNull { it.lastModified() }?.let {
                sb.append("\n\u2550\u2550\u2550 ${it.name} (${it.length()}B) \u2550\u2550\u2550\n")
                sb.append(it.readLines().takeLast(80).joinToString("\n"))
            }
            sb.toString()
        } catch (e: Throwable) {
            "readDiag failed: ${e.message}"
        }
    }

    // ══════════════════════════════════════════
    //  chainCheck — จำลองปุ่ม "1234" (UI test key) พิสูจน์ hop ต่อ hop
    //  ปุ่มกด → EngineBridge → Orchestrator → VirtualAppContainer →
    //  ServiceBinderProxy(sCache) → GuestProcessTable(slot) → provider call
    //  ทุกขั้นรายงานสถานะจริงของตัวเอง กลับเป็นข้อความเดียว
    // ══════════════════════════════════════════

    private fun chainCheck(packageName: String): String {
        val c = ctx ?: return "✗ bridge: no context"
        val sb = StringBuilder()
        // hop1: identity pair (guest upgrade path)
        val vac = com.aether.engine.proxy.VirtualAppContainer
        sb.append("[1] identity real=${vac.getRealPackageName()} fake=${vac.getFakePackageName()}\n")
        // hop2: sCache wrappers ติดตั้งจริงกี่ key (ใน process ที่ bridge รัน = main)
        sb.append("[2] ${com.aether.engine.proxy.ServiceBinderProxy.sCacheVerify()}\n")
        sb.append("[2b] proxies=${com.aether.engine.proxy.ServiceBinderProxy.listProxiedServices().size}\n")
        // hop3: AMS singleton (ของจริงเป็น BinderProxy — ของเราเป็น java Proxy)
        sb.append("[3] ")
        sb.append(try {
            val am = Class.forName("android.app.ActivityManager")
            val sf = am.getDeclaredField("IActivityManagerSingleton")
            sf.isAccessible = true
            val sing = sf.get(null)
            var cc: Class<*>? = sing.javaClass
            var inst: Any? = null
            while (cc != null) {
                try {
                    val mf = cc.getDeclaredField("mInstance")
                    mf.isAccessible = true
                    inst = mf.get(sing)
                    break
                } catch (_: NoSuchFieldException) { cc = cc.superclass }
            }
            val n = inst?.javaClass?.name ?: "null"
            if (java.lang.reflect.Proxy.isProxyClass(inst?.javaClass ?: Object::class.java))
                "AMS mInstance=PROXY ✓ (tz.i parity)"
            else "AMS mInstance=REAL ($n) — singleton replace ยังไม่ landing"
        } catch (e: Throwable) { "AMS singleton read failed: ${e.message}" })
        sb.append("\n")
        // hop4: guest slot table (server side)
        sb.append("[4] ${com.aether.engine.proxy.GuestProcessTable.status()}\n")
        // hop5: child holder config (ถ้าตรวจใน :pN จะเห็น; main = null ปกติ)
        val hc = com.aether.engine.proxy.GuestProcessHolder.config
        sb.append("[5] childConfig=" + (hc?.let { "${it.guestPkg} slot=${it.slot}" } ?: "none(main? OK)"))
        sb.append("\n")
        // hop6: target installed? (ปุ่ม Play precheck เดียวกัน)
        if (packageName.isNotEmpty()) {
            sb.append("[6] installed(")
            sb.append(packageName)
            sb.append(")=")
            sb.append(try {
                c.packageManager.getPackageInfo(packageName, 0); "yes"
            } catch (_: Throwable) { "NO" })
        }
        return sb.toString()
    }

    /**
     * handshakeStatus — จำลอง a7.m() provider call จริง (method "_Engine_|_init_process_")
     * ผ่าน provider ฝั่งเดียวกัน (P0 ทำงานใน :p0 → call ข้าม process จริง)
     * ใช้กดจาก UI เพื่อตรวจ child ตอบ config + IBinder กลับไหม โดยไม่ต้อง launch เกม
     */
    private fun handshakeStatus(): String {
        val c = ctx ?: return "✗ no context"
        return try {
            // endpoint เดียวกับ spawner จริง (GuestProcessTable) — ใช้ helper + key
            // ชุดเดียวกัน ห้าม hardcode ซ้ำ (บทเรียน schema ไม่ตรงกันทั้งระบบ)
            // audit C14: diag ต้องไม่กิน slot 0 ของ guest จริง — ใช้ slot สุดท้าย
            val diagSlot = com.aether.engine.proxy.GuestProcessTable.MAX_SLOTS - 1
            val uri = android.net.Uri.parse(
                com.aether.engine.proxy.GuestProcessTable.providerAuthority(diagSlot))
            val extras = com.aether.engine.proxy.GuestProcessTable.configToBundle(
                com.aether.engine.proxy.ClientConfig(
                    com.aether.engine.proxy.GuestProcessHolder.DIAG_PKG, diagSlot,
                    android.os.Process.myUid() / 100000))
            val reply = c.contentResolver.call(uri,
                com.aether.engine.proxy.GuestProcessTable.METHOD_INIT, null, extras)
            if (reply == null) "✗ reply=null"
            else "success=${reply.getBoolean(com.aether.engine.proxy.GuestProcessTable.EXTRA_SUCCESS)} " +
                "err=${reply.getString(com.aether.engine.proxy.GuestProcessTable.EXTRA_ERROR)} " +
                "clientBinder=${reply.getBinder(com.aether.engine.proxy.GuestProcessTable.BUNDLE_CLIENT) != null}"
        } catch (e: Throwable) {
            "✗ ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ══════════════════════════════════════════
    //  ByteArray helpers (block body)
    // ══════════════════════════════════════════

    private fun bytesToHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
