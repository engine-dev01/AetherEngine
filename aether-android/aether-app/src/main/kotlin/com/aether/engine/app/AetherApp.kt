package com.aether.engine.app

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.aether.RemoteConfig
import com.aether.SandboxManager
import com.aether.engine.daemon.AetherDaemonService
import com.aether.engine.proxy.AetherOrchestrator
import com.aether.engine.proxy.CrashHandler
import com.aether.engine.proxy.Flagger


class AetherApp : Application() {
    companion object {
        private const val TAG = "AetherApp"

        /**
         * ชื่อ process ปัจจุบัน (≡ role ตาม snake yu0.f() — T2 hop17):
         *   com.aether        → Main   → เรียก Native.ic
         *   com.aether:pN     → Child  → เรียก Native.ic
         *   com.aether:engine → Server → ไม่เรียก (snake: server ไม่มี ic call)
         * 3 ทางอ่าน เพราะ hidden-API visibility ต่างกันตามเวอร์ชัน
         */
        fun currentProcessName(): String {
            runCatching {
                val m = Application::class.java.getDeclaredMethod("getProcessName")
                (m.invoke(null) as? String)?.let { return it }
            }
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val m = at.getDeclaredMethod("currentProcessName")
                m.isAccessible = true
                (m.invoke(null) as? String)?.let { return it }
            }
            return runCatching {
                java.io.File("/proc/self/cmdline").readText().split('\u0000').first()
            }.getOrDefault("")
        }

        /** role dispatch ตาม yu0.f: Main/Child เรียก ic, Server ไม่เรียก */
        fun processRole(name: String): String = when {
            name.endsWith(":engine") -> "server"
            Regex(":[pP]\\d+$").containsMatchIn(name) -> "child"
            else -> "main"
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize native library (Phase 11: pluggable via EngineLoader)
        try {
            EngineLoader.load(this)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native engine: ${e.message}")
        }

        // audit C6: hidden-API exemption ครั้งเดียวต่อ process — ก่อนทุก hook/reflection
        // (chain ยิง restricted members ~20 จุด; เดิมไม่มี exemption ใดใน repo →
        //  NoSuchField/Method เงียบ ๆ ใน runCatching = boot chain พังแบบมองไม่เห็น)
        try {
            val ok = com.aether.Engine.nativeExemptHiddenApi()
            Log.i(TAG, "hidden-API exemption: $ok")
        } catch (t: Throwable) {
            Log.w(TAG, "hidden-API exemption failed: ${t.message}")
        }

        // audit C2: DiagLog ต้องพร้อมบน main ด้วย (readResult ใน EngineBridge อ่าน
        // ไฟล์เดียวกัน; เดิม init มีแต่ใน ProxyActivity/:pN)
        try { com.aether.engine.proxy.DiagLog.init(this) } catch (_: Throwable) {}

        // hop17 ≡ SNAKE Native.ic ที่ yu0.f() หลัง loadLibrary — Main+Child เท่านั้น
        // (T1: F2 sig (Landroid/content/Context;)V · T2: server ไม่เรียก ic)
        run {
            val pname = currentProcessName()
            val role = processRole(pname)
            if (role == "main" || role == "child") {
                try {
                    com.aether.Engine.nativeInitContext(this)
                    Log.i(TAG, "nativeInitContext (≡Native.ic) done role=$role proc=$pname")
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeInitContext role=$role: ${t.message}")
                }
            } else {
                Log.i(TAG, "nativeInitContext skipped — role=$role (snake parity: server ไม่เรียก ic)")
            }
        }

        // Initialize core managers (each wrapped in Throwable — never let a
        // single subsystem crash the whole app at launch)
        // SandboxManager.init(this) called by AetherOrchestrator.init() — no duplicate
        //
        // T1 (com.snake.zip = ดัมป์ต้นแบบตอน *เปิดแอพ* 13:17): sandbox ว่างเปล่า
        // (root/{cache,data,data/app,system} = dir เปล่า, ไม่มี package.conf/token/conf
        //  ใด ๆ) — engine ไม่ fabricate อะไรตอนเปิด ทุกอย่างเกิดตอน *กดเริ่มเกม*
        // (bootstrapGameData(targetPkg) ใน launchInSandbox) และ guest เขียนเอง
        // เดิมเรียก bootstrapGameData("com.aether") ที่นี่ = สร้าง package.conf ของ
        // host + token ปลอมตอนเปิด (ผิดต้นแบบ + ไฟล์ผีที่ไม่มี consumer) → ตัด
        try { Flagger.init(this) } catch (t: Throwable) { Log.e(TAG, "Flagger.init: ${t.message}") }
        try { RemoteConfig.init(this) } catch (t: Throwable) { Log.e(TAG, "RemoteConfig.init: ${t.message}") }

        // Phase 3.3: RemoteConfig — fetch async (server endpoint ไม่มีจริง → fallback เป็น offline)
        // call site ตามแผน — ไม่มี caller ก่อนหน้านี้
        try {
            com.aether.RemoteConfig.fetchRemoteAsync()
            Log.i(TAG, "RemoteConfig.fetchRemoteAsync triggered")
        } catch (t: Throwable) {
            Log.w(TAG, "RemoteConfig.fetchRemoteAsync: ${t.message}")
        }

        // Phase 3.1: hydrate payload store จาก root/files/ (DATA_DUMP(UNVERIFIED) §4 — 92 SHA-256 named)
        // เดิม provisionPayloadsFromFiles count อย่างเดียว → เปลี่ยนเป็นเรียก native loadDir
        try {
            val payloadDir = java.io.File(dataDir, "root/files")
            val jklHex = "010100640100000000000000000100001400000000006464000000000100"  // DATA_DUMP(UNVERIFIED) §4.3
            val count = com.aether.Engine.nativeHydratePayloads(payloadDir.absolutePath, jklHex)
            // audit C13-hydrate-log: log must name the dir actually scanned
            Log.i(TAG, "nativeHydratePayloads: $count payloads loaded from ${payloadDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "nativeHydratePayloads: ${t.message}")
        }

        // ─── Phase 3: Engine wiring (bundle ใน APK แล้ว) ───
        // CrashHandler: uncaught exception → report (logcat + upload best-effort)
        try { CrashHandler.install(this) } catch (t: Throwable) { Log.e(TAG, "CrashHandler.install: ${t.message}") }
        // AetherOrchestrator: VirtualAppContainer + binder proxies + ART hooks
        // + native engine — ทุกขั้นมี try/catch ภายใน ไม่ทำ app ล่ม
        try { AetherOrchestrator.init(this) } catch (t: Throwable) { Log.e(TAG, "AetherOrchestrator.init: ${t.message}") }
        // Daemon FG (:engine process) — watchdog + keep-alive ตาม blueprint
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, AetherDaemonService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Daemon start failed: ${e.message}")
        }

        Log.i(TAG, "AetherEngine initialized — Application.onCreate()")
    }
}
