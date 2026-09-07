package com.aether.engine.proxy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * ProxyContentProvider — Multi-process IPC Gateway (Aether-compatible)
 *
 * Routes encrypted IPC messages through process pool (:p0-:p3) to
 * AetherSystemCallProvider (:daemon). Each instance handles its own
 * process pool's IPC workload.
 *
 * Protocol:
 *   call("route", null, Bundle {
 *     "target_action": "attach" | "read" | "scan" | "heartbeat",
 *     "pid": int,
 *     "address": long,
 *     ...
 *   })
 *
 * Returns: Bundle { "success": bool, "data": byte[], "error": string }
 */
open class ProxyContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "AetherProxyProvider"
        private const val METHOD_ROUTE = "route"
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "ProxyContentProvider created in PID=${Process.myPid()}")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (METHOD_ROUTE != method) {
            return Bundle().apply { putBoolean("success", false); putString("error", "unknown method: $method") }
        }

        val action = extras?.getString("target_action") ?: return Bundle().apply {
            putBoolean("success", false); putString("error", "missing target_action")
        }

        return when (action) {
            "attach" -> handleAttach(extras)
            "read"   -> handleRead(extras)
            "scan"   -> handleScan(extras)
            "detach" -> handleDetach(extras)
            "heartbeat" -> Bundle().apply { putBoolean("success", true); putLong("timestamp", System.currentTimeMillis()) }
            else -> Bundle().apply { putBoolean("success", false); putString("error", "unknown action: $action") }
        }
    }

    private fun handleAttach(extras: Bundle): Bundle {
        val pid = extras.getInt("pid", 0)
        val module = extras.getString("module", "")
        if (pid <= 0) return errorBundle("invalid pid: $pid")

        return try {
            val result = AetherIpcBridge.attach(pid, module)
            Bundle().apply {
                putBoolean("success", result)
                putInt("pid", pid)
                putString("module", module)
            }
        } catch (e: Exception) {
            errorBundle("attach failed: ${e.message}")
        }
    }

    private fun handleRead(extras: Bundle): Bundle {
        val pid = extras.getInt("pid", 0)
        val address = extras.getLong("address", 0)
        val size = extras.getLong("size", 0)
        if (pid <= 0 || address <= 0 || size <= 0) return errorBundle("invalid read params")

        return try {
            val data = AetherIpcBridge.read(pid, address, size)
            Bundle().apply {
                putBoolean("success", data != null)
                if (data != null) putByteArray("data", data)
            }
        } catch (e: Exception) {
            errorBundle("read failed: ${e.message}")
        }
    }

    private fun handleScan(extras: Bundle): Bundle {
        val pid = extras.getInt("pid", 0)
        val base = extras.getLong("base", 0)
        val size = extras.getLong("size", 4194304)
        val pattern = extras.getByteArray("pattern")
        val mask = extras.getString("mask", "")
        if (pid <= 0 || pattern == null) return errorBundle("invalid scan params")

        return try {
            val offset = AetherIpcBridge.scan(pid, base, size, pattern, mask)
            Bundle().apply {
                putBoolean("success", offset > 0)
                putLong("offset", offset)
            }
        } catch (e: Exception) {
            errorBundle("scan failed: ${e.message}")
        }
    }

    private fun handleDetach(extras: Bundle): Bundle {
        return try {
            AetherIpcBridge.detach()
            Bundle().apply { putBoolean("success", true) }
        } catch (e: Exception) {
            errorBundle("detach failed: ${e.message}")
        }
    }

    private fun errorBundle(msg: String): Bundle {
        Log.e(TAG, msg)
        return Bundle().apply { putBoolean("success", false); putString("error", msg) }
    }

    // Unused
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    class P0 : ProxyContentProvider()
    class P1 : ProxyContentProvider()
    class P2 : ProxyContentProvider()
    class P3 : ProxyContentProvider()
}

/**
 * AetherIpcBridge — routes IPC calls to the JNI native layer
 * Acts as the bridge between ContentProvider IPC and libaether.so
 */
object AetherIpcBridge {

    fun attach(pid: Int, module: String): Boolean {
        return try {
            com.aether.Engine.nativeAttach(pid, module)
        } catch (e: Exception) {
            Log.e("AetherIpcBridge", "attach error: ${e.message}")
            false
        }
    }

    fun read(pid: Int, address: Long, size: Long): ByteArray? {
        return try {
            com.aether.Engine.nativeRead(pid, address, size)
        } catch (e: Exception) {
            Log.e("AetherIpcBridge", "read error: ${e.message}")
            null
        }
    }

    fun scan(pid: Int, base: Long, size: Long, pattern: ByteArray, mask: String): Long {
        return try {
            com.aether.Engine.nativeScanAOB(pid, base, size, pattern, mask)
        } catch (e: Exception) {
            Log.e("AetherIpcBridge", "scan error: ${e.message}")
            0
        }
    }

    fun detach() {
        // Clear cached state in native layer
        try {
            com.aether.Engine.nativeSetSeed(0) // reset seed = clear session
        } catch (_: Exception) {}
    }
}
