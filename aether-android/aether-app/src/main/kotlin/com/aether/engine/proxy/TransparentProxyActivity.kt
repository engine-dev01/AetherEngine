package com.aether.engine.proxy

import android.app.Activity
import android.os.Bundle

/**
 * TransparentProxyActivity — Invisible activity proxy (invisible activity)
 *
 * ใช้สำหรับรับ Intent และ forward ไปยัง process pool โดยไม่มี UI ปรากฏ
 * แตกต่างจาก ProxyActivity ตรงที่ transparent theme + ไม่ finish ทันที
 * (รอให้ caller ส่ง result กลับก่อน)
 */
open class TransparentProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent — no content view
        // Intent forwarding handled by caller via startActivityForResult
    }

    class P0 : TransparentProxyActivity()
    class P1 : TransparentProxyActivity()
    class P2 : TransparentProxyActivity()
    class P3 : TransparentProxyActivity()
}
