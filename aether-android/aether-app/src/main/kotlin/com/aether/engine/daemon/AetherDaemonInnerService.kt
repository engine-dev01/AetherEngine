package com.aether.engine.daemon

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

class AetherDaemonInnerService : Service() {

    companion object {
        const val TAG = "AetherDaemonInner"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 วินาที

        /** ส่งค่า default Binder ของ inner daemon — ใช้โดย SystemCallProvider */
        fun createDaemonBinder(): Messenger = Messenger(DaemonHandler)
    }

    // ─── Binder target กลาง (share handler) ───
    private object DaemonHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                1 -> handlePing(msg)
                2 -> handleHeartbeat(msg)
                else -> super.handleMessage(msg)
            }
        }

        private fun handlePing(msg: Message) {
            // reply: เดิม msg.replyTo เป็น sender
            val reply = Message.obtain(null, 1, 0, 0)
            try {
                msg.replyTo?.send(reply)
            } catch (e: RemoteException) {
                Log.e(TAG, "ping reply failed: ${e.message}")
            }
        }

        private fun handleHeartbeat(msg: Message) {
            Log.d(TAG, "Binder heartbeat received from proc=${msg.arg1}")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable { heartbeat() }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Inner service created (Binder: ${DaemonHandler.javaClass.name})")
        scheduleHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun scheduleHeartbeat() {
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun heartbeat() {
        Log.d(TAG, "Heartbeat – inner service alive")
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "Inner service destroyed")
        super.onDestroy()
    }

    /** ส่ง Binder (Messenger) ให้ process pool ติดต่อได้ */
    override fun onBind(intent: Intent?): IBinder = createDaemonBinder().binder
}
