package com.aether.engine.proxy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * OAuthFlow — vx (REPORT §3.3 + §7.3) — OAuth login inject
 *
 * Prototype: vx.f(activity,url,code,token,withResult) -> InternalWebBrowser
 *          -> redirect fragment "...&access_token=...&..." -> parse
 *          -> JWT decode payload "sub" -> reflection loginWithResult
 *
 * Aether: standalone object ไม่แตะ InternalWebBrowser เดิม (เพิ่มไฟล์ใหม่)
 */
object OAuthFlow {
    private const val TAG = "AetherOAuth"
    private const val ACTION_RESULT   = "com.aether.engine.INTERNAL_OAUTH_RESULT"
    private const val ACTION_CANCELLED = "com.aether.engine.INTERNAL_OAUTH_CANCELLED"

    /** เปิด InternalWebBrowser พร้อม OAuth creds (เรียกจาก Kotlin/EngineBridge) */
    fun start(activity: Activity, url: String, code: String, token: String, withResult: Boolean) {
        val intent = Intent(activity, InternalWebBrowser::class.java).apply {
            putExtra(InternalWebBrowser.EXTRA_URL, url)
            putExtra(InternalWebBrowser.EXTRA_CODE, code)
            putExtra(InternalWebBrowser.EXTRA_TOKEN, token)
            putExtra(InternalWebBrowser.EXTRA_WITH_RESULT, withResult)
        }
        activity.startActivity(intent)
        Log.i(TAG, "OAuth start code=$code withResult=$withResult")
    }

    /** Parse redirect fragment หา access_token */
    fun parseAccessToken(fragment: String): String? {
        // fragment: "#access_token=XYZ&token_type=Bearer&..."
        for (part in fragment.trimStart('#').split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "access_token") return kv[1]
        }
        return null
    }

    /** JWT decode: Base64 payload -> JSON -> "sub" */
    fun jwtSub(jwt: String): String? {
        return try {
            val payloadB64 = jwt.split(".").getOrNull(1) ?: return null
            val padded = payloadB64.padEnd((payloadB64.length + 3) / 4 * 4, '=')
            val json = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(json).optString("sub", null)
        } catch (e: Exception) {
            Log.w(TAG, "JWT decode fail: ${e.message}")
            null
        }
    }

    /** Reflection เรียก loginWithResult(int,String,String,String) ใน target app */
    fun invokeLoginWithResult(targetClz: String, token: String, code: String, userId: String, withResult: Boolean): Boolean {
        return try {
            val cls = Class.forName(targetClz)
            val m = cls.methods.firstOrNull { it.name == "loginWithResult" } ?: return false
            val inst = cls.getDeclaredConstructor().newInstance()
            m.invoke(inst, code.toIntOrNull() ?: 0, token, userId, if (withResult) "true" else "false")
            true
        } catch (e: Exception) {
            Log.w(TAG, "invokeLoginWithResult fail: ${e.message}")
            false
        }
    }

    /** Broadcast receiver pair (cy$a style) — รับ OAuth result */
    class OAuthResultReceiver(private val onToken: (String)->Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_RESULT -> {
                    val token = intent.getStringExtra("token") ?: return
                    onToken(token)
                }
                ACTION_CANCELLED -> Log.i(TAG, "OAuth cancelled")
            }
        }
        fun register(ctx: Context) {
            val f = IntentFilter().apply { addAction(ACTION_RESULT); addAction(ACTION_CANCELLED) }
            ctx.registerReceiver(this, f)
        }
    }
}
