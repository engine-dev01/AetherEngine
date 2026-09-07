package com.aether.engine.daemon

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AetherDaemonService : Service() {

    companion object {
        const val CHANNEL_ID = "aether_daemon"
        const val NOTIF_ID   = 10000
        const val RESTART_DELAY_MS = 1500L
        const val TAG = "AetherDaemon"
    }

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafe()
        // Phase 1+2: load libaether.so + init orchestrator in THIS process (:daemon).
        // EngineLoader already loads libaether.so in the main process, but the daemon
        // runs in a SEPARATE process (:daemon) which has its own /proc/self/maps.
        // Without this, nativeFindModuleBase(ownPID, "libaether.so") returns 0 because
        // libaether.so is not mapped in :daemon → attach fails silently.
        try {
            if (!isLibAetherLoaded()) {
                System.loadLibrary("aether")
                Log.i(TAG, "libaether.so loaded in :daemon process (PID=${android.os.Process.myPid()})")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libaether.so in :daemon: ${e.message}")
        }
        try {
            com.aether.engine.proxy.AetherOrchestrator.init(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "AetherOrchestrator.init in :daemon failed: ${t.message}")
        }
        // เริ่ม Inner worker แบบ plain Service (ไม่ใช่ FGS — parent เป็น FGS อยู่แล้ว)
        // ห้ามใช้ startForegroundService เพราะ inner ไม่ call startForeground() → crash
        try {
            val innerIntent = Intent(this, AetherDaemonInnerService::class.java)
            startService(innerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start inner service: ${e.message}")
        }
    }

    /** Check whether libaether.so is already loaded in this process. */
    private fun isLibAetherLoaded(): Boolean {
        return try {
            // Calling any external function with a non-existent symbol would throw,
            // but a cheap probe: try to read the library mapping via dlopen NULL.
            // Simplest reliable check: try to resolve a known JNI symbol.
            Class.forName("com.aether.Engine")
            // If Engine class loaded, the native lib that backs it is loaded.
            // We need a different signal — but a class-load is sufficient because
            // System.loadLibrary throws ExceptionInInitializerError if lib is missing.
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aether Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background data‑sync & health‑check"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** เรียก startForeground พร้อม Foreground Service Type สำหรับ API 34+ */
    private fun startForegroundSafe() {
        if (foregroundStarted) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34 (Android 14)
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether Engine")
            .setContentText("Background service running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Internal engine health watchdog — monitors AetherEngine's own process state.
        // No external process dependency. Engine operates within its own process space.
        Thread {
            val ownPid = android.os.Process.myPid()
            var isEngineHealthy = false
            var healthCheckCount = 0

            while (true) {
                try {
                    // Step 1: Check orchestrator state
                    val isInitialized = com.aether.engine.proxy.AetherOrchestrator.isInitialized()
                    val isAttached = com.aether.engine.proxy.AetherOrchestrator.isAttachedToProcess()
                    val isRunning = com.aether.engine.proxy.AetherOrchestrator.isEngineRunning()

                    // Step 2: Native watchdog check (libaether.so internal health)
                    val nativeHealthy = try {
                        com.aether.Engine.nativeWatchdogCheck()
                    } catch (e: Throwable) {
                        false
                    }

                    // Step 3: Engine state transitions
                    when {
                        // Case 1: Engine initialized but not attached — self-attach
                        (isInitialized && !isAttached) -> {
                            Log.d(TAG, "Watchdog: engine initialized but not attached — self-attach PID=$ownPid")
                            doSelfAttach()
                            healthCheckCount = 0
                        }
                        // Case 2: Attached but not running — start engine
                        (isAttached && !isRunning) -> {
                            Log.d(TAG, "Watchdog: engine attached but not running — start engine")
                            com.aether.engine.proxy.AetherOrchestrator.startEngine()
                            isEngineHealthy = true
                            healthCheckCount = 0
                        }
                        // Case 3: Running but native health check fails
                        (isRunning && !nativeHealthy) -> {
                            healthCheckCount++
                            if (healthCheckCount >= 3) {
                                Log.w(TAG, "Watchdog: native health degraded (${healthCheckCount}x) — re-attaching")
                                doSelfAttach()
                                healthCheckCount = 0
                            }
                        }
                        // Case 4: All healthy
                        (isInitialized && isAttached && isRunning && nativeHealthy) -> {
                            if (healthCheckCount > 0 || !isEngineHealthy) {
                                Log.i(TAG, "Watchdog: engine healthy — PID=$ownPid, initialized=$isInitialized, attached=$isAttached, running=$isRunning")
                            }
                            isEngineHealthy = true
                            healthCheckCount = 0
                        }
                        // Case 5: Engine not initialized — init first
                        (!isInitialized) -> {
                            Log.d(TAG, "Watchdog: engine not initialized — init + self-attach")
                            val ctx = applicationContext
                            com.aether.engine.proxy.AetherOrchestrator.init(ctx)
                            doSelfAttach()
                            healthCheckCount = 0
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Watchdog health check error: ${e.message}", e)
                }
                Thread.sleep(3000) // check every 3s
            }
        }.apply { isDaemon = true }.start()

        return START_STICKY
    }

    /** Self-attach: AetherEngine operates within its own process space.
     *  Empty moduleName triggers self-attach path in AetherOrchestrator.attachToProcess. */
    private fun doSelfAttach() {
        try {
            val ownPid = android.os.Process.myPid()
            // Empty moduleName → self-attach (own PID + libaether.so)
            val attached = com.aether.engine.proxy.AetherOrchestrator.attachToProcess(
                ownPid, "", "com.aether"  // real package (host applicationId)
            )
            Log.i(TAG, "Watchdog: self-attach(ownPID=$ownPid) → $attached")

            if (!attached) {
                Log.w(TAG, "Watchdog: self-attach failed — will retry in 3s")
                return
            }

            // Step 2: Re-prime the IPC bridge
            val bridgeIntent = Intent("com.aether.engine.BRIDGE_RESET").apply {
                `package` = "com.aether"  // real package
                putExtra("pid", ownPid)
                putExtra("module", "libaether.so")
                putExtra("package_name", "com.aether")
                putExtra("self_attach", true)
            }
            sendBroadcast(bridgeIntent)

            // Step 3: Notify Flutter layer
            val statusIntent = Intent("com.aether.engine.WATCHDOG_STATUS").apply {
                `package` = "com.aether"  // real package
                putExtra("status", "attached")
                putExtra("pid", ownPid)
                putExtra("self_attach", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(statusIntent)

            // Step 4: Start engine if not running
            com.aether.engine.proxy.AetherOrchestrator.startEngine()
            Log.i(TAG, "Watchdog: self-attach complete, engine started for own PID=$ownPid")
        } catch (e: Throwable) {
            Log.e(TAG, "Watchdog: self-attach exception: ${e.message}", e)
        }
    }

    /** Reset engine state when engine is down */
    private fun resetEngineState() {
        try {
            // Broadcast reset to proxy pool
            val resetIntent = Intent("com.aether.engine.BRIDGE_RESET").apply {
                `package` = "com.aether"  // real package
                putExtra("status", "detached")
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(resetIntent)
            Log.i(TAG, "Watchdog: engine state reset broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: reset broadcast error: ${e.message}", e)
        }
    }

    /** ตั้ง Alarm เพื่อ restart service หากถูก kill (ใช้ inexact alarm ปลอดภัย API 31+) */
    private fun scheduleRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = System.currentTimeMillis() + RESTART_DELAY_MS
            val pi = PendingIntent.getService(
                this,
                0,
                Intent(this, AetherDaemonService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Log.d(TAG, "Restart alarm scheduled in ${RESTART_DELAY_MS} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduleRestart()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
