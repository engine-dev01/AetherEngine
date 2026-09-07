package com.aether.engine.proxy

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * ProxyService — Multi-process worker service (multi-process :p0-:p3)
 * สำหรับรับ IPC calls จาก process pool
 */
open class ProxyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    class P0 : ProxyService()
    class P1 : ProxyService()
    class P2 : ProxyService()
    class P3 : ProxyService()
}
