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
 * AetherSystemCallProvider (:engine). Each instance handles its own
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

        // wire_contract_check [W2]: keys ของ route protocol — single source (const)
        // หมายเหตุ wire-scan 2026-09-14: ไม่มี client ฝั่งส่ง "route" ใน repo นี้
        // (AetherIpcBridge เรียก native ตรงใน-process) = IPC surface สำรองสำหรับ
        // cross-process remote-ops (future D9) — server ยังตอบถูก schema เสมอ
        const val KEY_TARGET_ACTION = "target_action"
        const val KEY_PID      = "pid"
        const val KEY_MODULE   = "module"
        const val KEY_ADDRESS  = "address"
        const val KEY_SIZE     = "size"
        const val KEY_BASE     = "base"
        const val KEY_PATTERN  = "pattern"
        const val KEY_MASK     = "mask"
        const val KEY_SUCCESS  = "success"
        const val KEY_ERROR    = "error"
        const val KEY_DATA     = "data"
        const val KEY_OFFSET   = "offset"
        const val KEY_TIMESTAMP = "timestamp"
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "ProxyContentProvider created in PID=${Process.myPid()}")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // ★ ขั้น ② — provider handshake (SNAKE ProxyContentProvider.java:15):
        // framework ติดตั้ง provider ตอน spawn :pN → server เรียก method นี้เพื่อ
        // ยื่น p3 config ก่อน activity ใด ๆ มาถึง → child เก็บ identity ไว้
        if (GuestProcessTable.METHOD_INIT == method) {
            return GuestProcessHolder.handleInit(extras)
        }
        if (METHOD_ROUTE != method) {
            return Bundle().apply { putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "unknown method: $method") }
        }

        val action = extras?.getString(KEY_TARGET_ACTION) ?: return Bundle().apply {
            putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "missing target_action")
        }

        return when (action) {
            "attach" -> handleAttach(extras)
            "read"   -> handleRead(extras)
            "scan"   -> handleScan(extras)
            "detach" -> handleDetach(extras)
            "heartbeat" -> Bundle().apply { putBoolean(KEY_SUCCESS, true); putLong(KEY_TIMESTAMP, System.currentTimeMillis()) }
            else -> Bundle().apply { putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "unknown action: $action") }
        }
    }

    private fun handleAttach(extras: Bundle): Bundle {
        val pid = extras.getInt(KEY_PID, 0)
        val module = extras.getString(KEY_MODULE, "")
        if (pid <= 0) return errorBundle("invalid pid: $pid")

        return try {
            val result = AetherIpcBridge.attach(pid, module)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, result)
                putInt(KEY_PID, pid)
                putString(KEY_MODULE, module)
            }
        } catch (e: Exception) {
            errorBundle("attach failed: ${e.message}")
        }
    }

    private fun handleRead(extras: Bundle): Bundle {
        val pid = extras.getInt(KEY_PID, 0)
        val address = extras.getLong(KEY_ADDRESS, 0)
        val size = extras.getLong(KEY_SIZE, 0)
        if (pid <= 0 || address <= 0 || size <= 0) return errorBundle("invalid read params")

        return try {
            val data = AetherIpcBridge.read(pid, address, size)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, data != null)
                if (data != null) putByteArray(KEY_DATA, data)
            }
        } catch (e: Exception) {
            errorBundle("read failed: ${e.message}")
        }
    }

    private fun handleScan(extras: Bundle): Bundle {
        val pid = extras.getInt(KEY_PID, 0)
        val base = extras.getLong(KEY_BASE, 0)
        val size = extras.getLong(KEY_SIZE, 4194304)
        val pattern = extras.getByteArray(KEY_PATTERN)
        val mask = extras.getString(KEY_MASK, "")
        if (pid <= 0 || pattern == null) return errorBundle("invalid scan params")

        return try {
            val offset = AetherIpcBridge.scan(pid, base, size, pattern, mask)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, offset > 0)
                putLong(KEY_OFFSET, offset)
            }
        } catch (e: Exception) {
            errorBundle("scan failed: ${e.message}")
        }
    }

    private fun handleDetach(extras: Bundle): Bundle {
        return try {
            AetherIpcBridge.detach()
            Bundle().apply { putBoolean(KEY_SUCCESS, true) }
        } catch (e: Exception) {
            errorBundle("detach failed: ${e.message}")
        }
    }

    private fun errorBundle(msg: String): Bundle {
        Log.e(TAG, msg)
        return Bundle().apply { putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, msg) }
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
        // Clear cached state in native layer.
        // หมายเหตุ: ไม่ใช่ hop ของ snake (flagger na/nb = 0 callers ตาม T1 F2) —
        // คงไว้เป็น session hygiene ฝั่ง Aether เท่านั้น; hop ของ Native.i คือ
        // nativeSetSeed(SDK_INT) ใน GuestRuntime.bindToActivityThread (T2 hop21)
        try {
            com.aether.Engine.nativeSetSeed(0) // reset seed = clear session
        } catch (_: Exception) {}
    }
}
