package com.aether.engine.app

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * EngineLoader — Pluggable native engine selector
 *
 * Phase 11: opt-in framework for swapping Aether's libaether.so
 * with Snake's libengine.so (or future custom engines).
 *
 * Currently supports:
 *   - EngineType.AETHER  → libaether.so (Aether's own, 3.6 MB, verified)
 *   - EngineType.SNAKE   → libengine.so (Snake's 8.5 MB, OLLVM, requires manual
 *                          asset deployment — not bundled in APK by default)
 *
 * To enable Snake engine:
 *   1. Place `libengine.so` (arm64-v8a) in app/src/main/jniLibs/arm64-v8a/
 *   2. Call EngineLoader.setEngineType(context, EngineType.SNAKE)
 *      (or set SharedPreferences "engine_type" = "snake")
 *   3. App will try Snake's lib on next launch; fall back to Aether on fail
 *
 * For now, only AETHER is bundled. SNAKE is a future option.
 */
object EngineLoader {
    private const val TAG = "EngineLoader"
    private const val PREFS = "aether_engine"
    private const val KEY_TYPE = "engine_type"

    enum class EngineType(val libraryName: String, val displayName: String) {
        AETHER("aether", "Aether Engine 1.0.0"),
        SNAKE("engine", "Snake Engine 2.2.6 (opt-in)"),
    }

    /**
     * Try to load preferred engine (from SharedPreferences).
     * Fall back to AETHER on failure.
     */
    fun load(context: Context): EngineType {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val typeName = prefs.getString(KEY_TYPE, EngineType.AETHER.name) ?: EngineType.AETHER.name
        val type = runCatching { EngineType.valueOf(typeName) }.getOrDefault(EngineType.AETHER)
        return try {
            System.loadLibrary(type.libraryName)
            Log.i(TAG, "✓ ${type.displayName} loaded (library: lib${type.libraryName}.so)")
            type
        } catch (e: UnsatisfiedLinkError) {
            if (type == EngineType.SNAKE) {
                Log.w(TAG, "libengine.so not found — falling back to Aether (libaether.so)")
                try {
                    System.loadLibrary(EngineType.AETHER.libraryName)
                    Log.i(TAG, "✓ Fallback: ${EngineType.AETHER.displayName} loaded")
                    // Persist fallback so we don't retry Snake on every launch
                    prefs.edit { putString(KEY_TYPE, EngineType.AETHER.name) }
                    EngineType.AETHER
                } catch (e2: UnsatisfiedLinkError) {
                    Log.e(TAG, "CRITICAL: libaether.so not found: ${e2.message}")
                    throw e2
                }
            } else {
                Log.e(TAG, "CRITICAL: libaether.so not found: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Set preferred engine (persisted to SharedPreferences).
     * Used by settings panel (Phase 12) or AetherOrchestrator.
     */
    fun setEngineType(context: Context, type: EngineType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_TYPE, type.name) }
        Log.i(TAG, "Engine type set to ${type.displayName} (restart app to apply)")
    }

    fun getEngineType(context: Context): EngineType {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TYPE, EngineType.AETHER.name) ?: EngineType.AETHER.name
        return runCatching { EngineType.valueOf(name) }.getOrDefault(EngineType.AETHER)
    }
}
