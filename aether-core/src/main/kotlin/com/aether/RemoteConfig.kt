package com.aether

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * RemoteConfig — Offline config registry (no remote endpoint)
 *
 * Provides AOB signatures, memory offsets, และ game configuration แบบ offline
 * ใช้ offline fallback default — ไม่ fetch จาก remote server
 * (Aether ไม่มี server deployment; ถ้าต้องการ remote update ในอนาคต เพิ่ม setEndpoint() + fetch)
 *
 * Flow:
 * 1. init() — load cache file (ถ้ามี) → set cachedConfig
 * 2. fetchRemoteAsync() — immediate offline fallback (no network) → notify listeners
 * 3. getAobSignature/getMemoryOffset/getFeatureFlag — read from cached config
 */
object RemoteConfig {

    private const val TAG = "AetherRemoteConfig"
    private const val CACHE_FILE = "remote_config.json"
    private const val CACHE_TTL_MS = 3600_000L // 1 ชั่วโมง

    private var appContext: Context? = null
    private var cachedConfig: JSONObject? = null
    private var lastFetchTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listeners = mutableListOf<ConfigLoadListener>()

    // ─── Callback Interface ───

    interface ConfigLoadListener {
        fun onConfigLoaded(config: RemoteConfig)
        fun onConfigFailed(error: String)
    }

    // ─── Data Classes ───

    data class AobSignature(
        val name: String,
        val pattern: ByteArray,
        val mask: String,
        val module: String = "",  // empty = no game attached (8 Ball Pool uses Unity not Aether target)
        val scanSize: Long = 4 * 1024 * 1024
    )

    data class MemoryOffset(
        val name: String,
        val baseOffset: Long,
        val chain: List<Long> = emptyList()
    )

    data class RemoteConfig(
        val version: Int,
        val gamePackage: String,
        val gameVersion: String,
        val aobSignatures: List<AobSignature>,
        val memoryOffsets: List<MemoryOffset>,
        val featureFlags: Map<String, Boolean>
    )

    // ─── Initialization ───

    fun init(context: Context) {
        appContext = context.applicationContext
        loadCache()
    }

    fun addListener(listener: ConfigLoadListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConfigLoadListener) {
        listeners.remove(listener)
    }

    // ─── Cache Management ───

    private fun getCacheFile(): File {
        return File(appContext?.filesDir, CACHE_FILE)
    }

    private fun loadCache(): RemoteConfig? {
        return try {
            val file = getCacheFile()
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS) {
                val json = file.readText()
                cachedConfig = JSONObject(json)
                lastFetchTime = file.lastModified()
                Log.i(TAG, "Cache loaded: version=${cachedConfig?.optInt("version", 0)}")
                parseConfig(cachedConfig!!)
            } else {
                Log.i(TAG, "Cache expired or missing")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache load failed: ${e.message}")
            null
        }
    }

    private fun saveCache(json: JSONObject) {
        try {
            getCacheFile().writeText(json.toString())
            lastFetchTime = System.currentTimeMillis()
            Log.i(TAG, "Cache saved")
        } catch (e: Exception) {
            Log.e(TAG, "Cache save failed: ${e.message}")
        }
    }

    // ─── Offline Fetch (no remote endpoint) ───

    /**
     * ทันทีใช้ offline fallback — ไม่ fetch จาก remote (Aether ไม่มี server)
     * Caller pattern ยังเหมือนเดิม: listeners.onConfigLoaded(fallback) on main thread
     */
    fun fetchRemoteAsync() {
        mainHandler.post {
            val result = loadCache() ?: offlineFallback()
            for (l in listeners) l.onConfigLoaded(result)
        }
    }

    fun fetchRemoteSync(): RemoteConfig? {
        return loadCache() ?: offlineFallback()
    }

    /** Phase D — Offline fallback default config (server unreachable ครั้งแรก) */
    private fun offlineFallback(): RemoteConfig {
        return RemoteConfig(
            version = 0,
            // Phase 1+2: in-process only. gamePackage kept for backward
            // compatibility (RemoteConfig schema) but the engine does
            // not dispatch Intent to this package.
            gamePackage = "com.aether",
            gameVersion = "56.23.2",
            aobSignatures = listOf(
                AobSignature(
                    name = "ball_position",
                    pattern = byteArrayOf(0.toByte(), 0.toByte(), (-128).toByte(), 0x3F.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()),
                    mask = "xxxx???x??x?",
                    module = "",  // empty = no game attached
                    scanSize = 4 * 1024 * 1024
                )
            ),
            memoryOffsets = emptyList(),
            featureFlags = mapOf(
                "vpn_enabled" to true,
                "auto_attach" to true,
                "aob_scan" to true
            )
        )
    }

    // ─── Config Parser ───

    private fun parseConfig(json: JSONObject): RemoteConfig? {
        return try {
            val version = json.getInt("version")
            val game = json.getJSONObject("game")
            val gamePackage = game.getString("package")
            val gameVersion = game.getString("version")

            val sigs = mutableListOf<AobSignature>()
            val sigsArray = json.optJSONArray("aob_signatures")
            if (sigsArray != null) {
                for (i in 0 until sigsArray.length()) {
                    val sig = sigsArray.getJSONObject(i)
                    val patternArray = sig.getJSONArray("pattern")
                    val pattern = ByteArray(patternArray.length())
                    for (j in 0 until patternArray.length()) {
                        pattern[j] = patternArray.getInt(j).toByte()
                    }
                    sigs.add(AobSignature(
                        name = sig.getString("name"),
                        pattern = pattern,
                        mask = sig.optString("mask", ""),
                        module = sig.optString("module", ""),
                        scanSize = sig.optLong("scan_size", 4 * 1024 * 1024)
                    ))
                }
            }

            val offsets = mutableListOf<MemoryOffset>()
            val offsetsArray = json.optJSONArray("memory_offsets")
            if (offsetsArray != null) {
                for (i in 0 until offsetsArray.length()) {
                    val off = offsetsArray.getJSONObject(i)
                    val chain = mutableListOf<Long>()
                    val chainArray = off.optJSONArray("chain")
                    if (chainArray != null) {
                        for (j in 0 until chainArray.length()) {
                            chain.add(chainArray.getLong(j))
                        }
                    }
                    offsets.add(MemoryOffset(
                        name = off.getString("name"),
                        baseOffset = off.getLong("base_offset"),
                        chain = chain
                    ))
                }
            }

            val flags = mutableMapOf<String, Boolean>()
            val flagsObj = json.optJSONObject("feature_flags")
            if (flagsObj != null) {
                val keys = flagsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    flags[key] = flagsObj.getBoolean(key)
                }
            }

            RemoteConfig(
                version = version,
                gamePackage = gamePackage,
                gameVersion = gameVersion,
                aobSignatures = sigs,
                memoryOffsets = offsets,
                featureFlags = flags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}")
            null
        }
    }

    // ─── Getters for Native Layer ───

    fun getAobSignature(name: String): AobSignature? {
        val config = parseConfig(cachedConfig ?: return null) ?: return null
        return config.aobSignatures.find { it.name == name }
    }

    fun getMemoryOffset(name: String): MemoryOffset? {
        val config = parseConfig(cachedConfig ?: return null) ?: return null
        return config.memoryOffsets.find { it.name == name }
    }

    fun getFeatureFlag(key: String, default: Boolean): Boolean {
        val config = parseConfig(cachedConfig ?: return default)
        val flag = config?.featureFlags?.get(key)
        return if (flag != null) flag else default
    }

    fun getConfig(): RemoteConfig? {
        return cachedConfig?.let { parseConfig(it) }
    }

    fun isCacheValid(): Boolean {
        return cachedConfig != null &&
               System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS
    }
}
