package com.aether.engine.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.aether.engine.proxy.IntentParser

class AetherStubReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AetherStubReceiver"
        private const val ACTION_STUB_RECEIVER = "com.aether.engine.STUB_RECEIVER"
        private const val SIGNATURE_PERMISSION = "com.aether.engine.permission.IPC_ACCESS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 1. Validate action
        if (ACTION_STUB_RECEIVER != intent.action) {
            Log.w(TAG, "Unexpected action: ${intent.action}")
            return
        }

        // 2. Enforce signature permission (broadcast permission)
        if (!enforceIpcPermission(context)) {
            Log.w(TAG, "Broadcast rejected: missing signature permission")
            return
        }

        // 3. Offload to background thread via goAsync()
        val route = IntentParser.route(intent)
        Log.d(TAG, "IntentParser route=$route action=${intent.action}")
        if (!IntentParser.dispatch(intent)) {
            Log.w(TAG, "Unknown route — dropping")
            return
        }
        val pendingResult = goAsync()
        Thread({
            try {
                val data = intent.extras?.getBundle("data")
                Log.d(TAG, "Stub command [route=$route] processed: ${data?.keySet()}")
            } catch (e: Exception) {
                Log.e(TAG, "Stub processing error", e)
            } finally {
                pendingResult.finish()
            }
        }).start()
    }

    private fun enforceIpcPermission(context: Context): Boolean {
        val granted = context.checkCallingOrSelfPermission(SIGNATURE_PERMISSION)
        return granted == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
