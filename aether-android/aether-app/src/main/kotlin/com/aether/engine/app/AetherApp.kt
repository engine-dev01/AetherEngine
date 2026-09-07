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
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize native library (Phase 11: pluggable via EngineLoader)
        try {
            EngineLoader.load(this)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native engine: ${e.message}")
        }

        // Initialize core managers (each wrapped in Throwable — never let a
        // single subsystem crash the whole app at launch)
        // SandboxManager.init(this) called by AetherOrchestrator.init() — no duplicate
        // Phase 3.5: โหลด data เข้าที่เก็บ sandbox (virtual root /root/ ตาม blueprint)
        try {
            // Phase 1+2: real package is "com.aether" (host applicationId).
            // Old "com.aether.aether" was wrong — broadcast/queries/data-dir mismatch
            // caused daemon to silently never reach its own receiver.
            SandboxManager.bootstrapGameData("com.aether")
        } catch (e: Exception) {
            Log.e(TAG, "Sandbox bootstrap error: ${e.message}")
        }
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

        // Phase 3.1: hydrate payload store จาก vision/files/ (DATA_DUMP §4 — 92 SHA-256 named)
        // เดิม provisionPayloadsFromFiles count อย่างเดียว → เปลี่ยนเป็นเรียก native loadDir
        try {
            val payloadDir = java.io.File(dataDir, "vision/files")
            val jklHex = "010100640100000000000000000100001400000000006464000000000100"  // DATA_DUMP §4.3
            val count = com.aether.Engine.nativeHydratePayloads(payloadDir.absolutePath, jklHex)
            Log.i(TAG, "nativeHydratePayloads: $count payloads loaded from ${filesDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "nativeHydratePayloads: ${t.message}")
        }

        // ─── Phase 3: Engine wiring (bundle ใน APK แล้ว) ───
        // CrashHandler: uncaught exception → report (logcat + upload best-effort)
        try { CrashHandler.install(this) } catch (t: Throwable) { Log.e(TAG, "CrashHandler.install: ${t.message}") }
        // AetherOrchestrator: VirtualAppContainer + binder proxies + ART hooks
        // + native engine — ทุกขั้นมี try/catch ภายใน ไม่ทำ app ล่ม
        try { AetherOrchestrator.init(this) } catch (t: Throwable) { Log.e(TAG, "AetherOrchestrator.init: ${t.message}") }
        // Daemon FG (:daemon process) — watchdog + keep-alive ตาม blueprint
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, AetherDaemonService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Daemon start failed: ${e.message}")
        }

        Log.i(TAG, "AetherEngine initialized — Application.onCreate()")
    }
}
