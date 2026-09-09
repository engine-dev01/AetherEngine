package com.aether.engine.proxy

import android.content.Context
import android.content.SharedPreferences

/**
 * Flagger — Runtime feature flag manager (feature flags)
 *
 * ควบคุมเปิด/ปิด feature โดยไม่ต้อง rebuild APK
 * ใช้ SharedPreferences เก็บค่า + รองรับ remote config override
 */
object Flagger {

    private const val PREFS_NAME = "com.aether.flagger"
    private const val KEY_PREFIX = "flag_"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Flag Definitions ───
    // vpnEnabled/autoAttachEnabled/sandboxEnabled/aobScanEnabled/debugLogging —
    // removed 2026-09-09 (WIRING_AUDIT §C orphans — no callers)

    // ─── Core ───

    fun set(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PREFIX + key, value)?.apply()
    }

    fun get(key: String, default: Boolean): Boolean {
        return prefs?.getBoolean(KEY_PREFIX + key, default) ?: default
    }

    fun getString(key: String, default: String): String {
        return prefs?.getString(KEY_PREFIX + key, default) ?: default
    }

    fun getLong(key: String, default: Long): Long {
        return prefs?.getLong(KEY_PREFIX + key, default) ?: default
    }

}
