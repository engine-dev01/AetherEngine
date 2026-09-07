package com.aether.engine.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import com.aether.engine.daemon.AetherDaemonInnerService

class AetherSystemCallProvider : ContentProvider() {

    companion object {
        private const val TAG = "AetherSysCallProvider"

        // IPC constants (inlined — single source of truth lives here)
        private const val SIGNATURE_PERMISSION = "com.aether.engine.permission.IPC_ACCESS"
        private const val PROVIDER_METHOD_VM   = "vm"
        private const val EXTRA_SUCCESS        = "success"
        private const val EXTRA_SERVER_NAME    = "server_name"
        private const val EXTRA_SERVER_BINDER  = "server_binder"

        /** Binder instance เดียวของ daemon (Messenger) — lazy init เพื่อ stability */
        @Volatile private var daemonBinder: IBinder? = null

        @Synchronized
        private fun getDaemonBinder(): IBinder {
            daemonBinder?.let { return it }
            val messenger = AetherDaemonInnerService.createDaemonBinder()
            daemonBinder = messenger.binder
            return daemonBinder!!
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 1. Enforce signature permission
        if (!enforceIpcPermission()) {
            Log.w(TAG, "IPC call rejected: missing signature permission")
            return Bundle().apply { putBoolean(EXTRA_SUCCESS, false) }
        }

        // 2. Handle VM method — คืน Binder (Messenger) ของ daemon ให้ process pool
        if (PROVIDER_METHOD_VM == method) {
            val serverName = extras?.getString(EXTRA_SERVER_NAME)
            val binder: IBinder = getDaemonBinder()   // real Binder instance (Messenger-based)
            return Bundle().apply {
                putBoolean(EXTRA_SUCCESS, true)
                putString(EXTRA_SERVER_NAME, serverName)
                putBinder(EXTRA_SERVER_BINDER, binder)  // Bundle API: putBinder (putIBinder ไม่มี)
            }
        }

        return super.call(method, arg, extras)
    }

    /** ตรวจสอบว่า caller มี signature permission หรือไม่ */
    private fun enforceIpcPermission(): Boolean {
        val ctx = context ?: return false   // no context → reject
        val result = ctx.checkCallingOrSelfPermission(SIGNATURE_PERMISSION)
        return result == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Unused ContentProvider methods
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
