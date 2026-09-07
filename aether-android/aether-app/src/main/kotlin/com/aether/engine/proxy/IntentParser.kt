package com.aether.engine.proxy

import android.content.Intent
import android.util.Log

/**
 * IntentParser — jl0 (REPORT §3.3 + §7.6)
 *  a(Intent) -> ตรวจ action แล้ว dispatch ไป proxy ที่ถูกต้อง
 */
object IntentParser {
    private const val TAG = "AetherIntentParser"

    enum class Route { UNKNOWN, ACTIVITY, SERVICE, PROVIDER, BROADCAST }

    fun route(intent: Intent?): Route {
        if (intent == null) return Route.UNKNOWN
        val action = intent.action ?: return Route.UNKNOWN
        return when {
            action.startsWith("com.aether.proxy.activity") -> Route.ACTIVITY
            action.startsWith("com.aether.proxy.service")  -> Route.SERVICE
            action.startsWith("com.aether.proxy.content")  -> Route.PROVIDER
            action == "com.aether.engine.STUB_RECEIVER"    -> Route.BROADCAST
            else -> Route.UNKNOWN
        }
    }

    fun parseAction(intent: Intent?): String? = intent?.action

    fun dispatch(intent: Intent?): Boolean {
        val r = route(intent)
        Log.d(TAG, "dispatch action=${intent?.action} -> $r")
        return r != Route.UNKNOWN
    }
}
