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

    /** เปิดใช้งาน VPN tunnel */
    fun vpnEnabled(): Boolean = get("vpn_enabled", true)

    /** เปิดใช้งาน auto-attach ghost engine */
    fun autoAttachEnabled(): Boolean = get("auto_attach", true)

    /** เปิดใช้งาน sandbox redirect */
    fun sandboxEnabled(): Boolean = get("sandbox_enabled", false)

    /** เปิดใช้งาน AOB memory scanning */
    fun aobScanEnabled(): Boolean = get("aob_scan", true)

    /** เปิด debug logging */
    fun debugLogging(): Boolean = get("debug_logging", false)

    // ─── Core ───

    fun set(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PREFIX + key, value)?.apply()
    }

    fun get(key: String, default: Boolean): Boolean {
        return prefs?.getBoolean(KEY_PREFIX + key, default) ?: default
    }

    fun setString(key: String, value: String) {
        prefs?.edit()?.putString(KEY_PREFIX + key, value)?.apply()
    }

    fun getString(key: String, default: String): String {
        return prefs?.getString(KEY_PREFIX + key, default) ?: default
    }

    fun setLong(key: String, value: Long) {
        prefs?.edit()?.putLong(KEY_PREFIX + key, value)?.apply()
    }

    fun getLong(key: String, default: Long): Long {
        return prefs?.getLong(KEY_PREFIX + key, default) ?: default
    }

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
