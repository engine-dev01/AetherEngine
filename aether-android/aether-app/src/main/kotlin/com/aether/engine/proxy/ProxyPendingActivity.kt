package com.aether.engine.proxy

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle

/**
 * ProxyPendingActivity — PendingIntent proxy target (PendingIntent proxy)
 *
 * รับ PendingIntent ที่ถูกส่งมาจากระบบ (AlarmManager, Notification, etc.)
 * และ forward ต่อไปยัง process pool ที่เหมาะสม โดยไม่แสดง UI
 */
open class ProxyPendingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forward intent extras to the appropriate proxy process
        val targetIntent = intent.getParcelableExtra<Intent>("forward_intent")
        if (targetIntent != null) {
            startActivity(targetIntent)
        }

        finish()
    }

    class P0 : ProxyPendingActivity()
    class P1 : ProxyPendingActivity()
    class P2 : ProxyPendingActivity()
    class P3 : ProxyPendingActivity()

    companion object {
        /**
         * สร้าง PendingIntent ที่ชี้ไปยัง process pool ที่กำหนด
         * @param ctx    Android Context
         * @param pool   process pool index (0-3)
         * @param intent intent ที่ต้องการ forward
         */
        fun create(ctx: android.content.Context, pool: Int, intent: Intent): PendingIntent {
            val cls: Class<out ProxyPendingActivity> = when (pool) {
                0 -> P0::class.java
                1 -> P1::class.java
                2 -> P2::class.java
                3 -> P3::class.java
                else -> P0::class.java
            }
            val proxyIntent = Intent(ctx, cls).apply {
                putExtra("forward_intent", intent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                ctx, pool, proxyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
