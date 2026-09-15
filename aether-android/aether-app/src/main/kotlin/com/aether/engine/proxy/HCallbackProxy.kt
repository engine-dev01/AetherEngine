package com.aether.engine.proxy

import android.os.Handler
import android.os.Message
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HCallbackProxy ≡ SNAKE `my` (androidx/appcompat/view/menu/my.java; T2
 * NATIVE_CALLSITE_MAP hop18 — ฝังที่ ActivityThread.mH.mCallback ผ่าน
 * ny.b.e(d(),this); hop19 x2() = bound-gate ก่อน放行)
 *
 * Blueprint P4: "ติดตั้ง my.java semantics ที่ :pN ก่อน installProviders —
 * ไม่ bound → เก็บ/re-queue; bound → rewrite แล้ว放行"
 *
 * การปรับตัวให้เข้าสถาปัตยกรรมเรา (ต่าง snake ที่ bind ใน bindApplication/hop20):
 *  - snake: binder thread阻塞ที่ ConditionVariable (x2), main H hold ทุก activity
 *    transaction จน jv0.O2 bind เสร็จ — เพราะ bind ของเขา *วิ่งบน main ก่อน H*
 *  - เรา: bind รันใน ProxyActivity.onCreate (= ตัวมันเองคือ H message ที่กำลัง
 *    dispatch) → hold แบบ snake = deadlock ทันที
 *  → semantics ที่ตรงและปลอดภัย = hold เฉพาะ transaction ที่ *เข้ามาระหว่าง bind*
 *    (armed && !bound): guest SDK ที่ startActivity ซ้อนตอน Application.onCreate
 *    จะถูกเก็บไว้ re-post หลัง bind จบ — ลำดับถูกต้อง ≡ hop18 "ไม่ bound → เก็บ"
 *    transaction แรก (bootstrap ของเรา) ไม่เข้าเงื่อนไข (ยังไม่ armed) = ไม่มี deadlock
 *
 * ทุกการ reflect guarded: fail = degrade เป็น no-op pass-through (never-crash
 * เดียวกับ AetherInstrumentation)
 */
object HCallbackProxy {
    private const val TAG = "AetherHCallback"

    /** ≡ t1.d.c/d (AOSP ActivityThread$H) — EXECUTE_TRANSACTION / LAUNCH_ACTIVITY */
    private const val WHAT_DEFAULT_EXEC = 159
    private const val WHAT_DEFAULT_LAUNCH = 100

    @Volatile private var installed = false
    private val armed = AtomicBoolean(false)
    @Volatile private var bound = false
    private val queued = ConcurrentLinkedQueue<PendingMessage>()

    private var mh: Handler? = null
    private var prevCallback: Handler.Callback? = null
    private var whatExec = WHAT_DEFAULT_EXEC
    private var whatLaunch = WHAT_DEFAULT_LAUNCH

    /** obj ของ ClientTransaction เป็น hidden type — เก็บเป็น opaque, re-post ตรงตัว */
    private data class PendingMessage(val what: Int, val obj: Any?, val arg1: Int, val arg2: Int)

    private val callback = Handler.Callback { msg ->
        val code = msg.what
        if (armed.get() && !bound && (code == whatExec || code == whatLaunch)) {
            // hop18: ไม่ bound → เก็บ (re-post โดย finishBind())
            queued.add(PendingMessage(code, msg.obj, msg.arg1, msg.arg2))
            Log.i(TAG, "HELD ${describe(code)} (bind in progress — ≡ my.h() re-queue)")
            true // consumed — เจ้าของ mH ไม่ต้อง dispatch ซ้ำ
        } else {
            prevCallback?.handleMessage(msg) ?: false
        }
    }

    private fun describe(code: Int) = if (code == whatExec) "EXECUTE_TRANSACTION" else "LAUNCH_ACTIVITY"

    /**
     * ติดตั้งที่ ActivityThread.mH.mCallback (≡ ny.b.e(d(),this) — hop18)
     * เรียกจาก child :pN เท่านั้น (ProxyActivity virtual branch) ก่อน bridge.load
     * = ก่อน installProviders ตาม blueprint P4 ✓
     */
    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null) ?: return false
            val fH = atCls.getDeclaredField("mH").apply { isAccessible = true }
            val h = fH.get(at) as? Handler ?: return false
            // resolve code จริงจาก ActivityThread$H (fallback = ค่า AOSP)
            runCatching {
                val hCls = Class.forName("android.app.ActivityThread\$H")
                whatExec = hCls.getDeclaredField("EXECUTE_TRANSACTION").getInt(null)
            }
            runCatching {
                val hCls = Class.forName("android.app.ActivityThread\$H")
                whatLaunch = hCls.getDeclaredField("LAUNCH_ACTIVITY").getInt(null)
            }
            val fCb = Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
            prevCallback = fCb.get(h) as? Handler.Callback
            if (prevCallback === callback) { installed = true; return true }
            fCb.set(h, callback)
            mh = h
            installed = true
            Log.i(TAG, "installed at mH.mCallback (exec=$whatExec launch=$whatLaunch, prev=${prevCallback?.javaClass?.simpleName})")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "install failed (degrade pass-through): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** เริ่มช่วง hold — เรียก *ก่อน* GuestRuntimeBridge.load (bind เริ่ม) */
    fun arm() { armed.set(true); bound = false }

    /**
     * bind จบ (สำเร็จหรือล้ม) → 放行ทุก transaction ที่ถูกเก็บ
     * ≡ hop19 "bound →放行" — re-post ผ่าน mH ปกติ ลำดับเดิม (FIFO)
     */
    @Synchronized
    fun finishBind() {
        armed.set(false)
        bound = true
        val h = mh
        // drain → postAtFront *ย้อนลำดับ* = ตัวเก่าสุดอยู่หัวแถวสุด (FIFO คงเดิม;
        // atFront ซ้ำ ๆ ผลักของใหม่ลงก่อน — bug ที่ตรวจพบตอน review ตัวเอง)
        val pending = ArrayList<PendingMessage>()
        while (true) {
            val p = queued.poll() ?: break
            pending.add(p)
        }
        pending.reverse() // MutableList.reverse() = Unit (Kotlin) — แก้ round แรกที่ใช้แบบ chain
        pending.forEach { p: PendingMessage ->
            if (h != null) h.sendMessageAtFrontOfQueue(Message.obtain(h, p.what, p.arg1, p.arg2, p.obj))
        }
        if (pending.isNotEmpty()) Log.i(TAG, "RELEASED ${pending.size} held transaction(s) (≡ my bound→放行)")
    }

    /** test/reset symmetry ≡ AetherInstrumentation.reset() */
    @Synchronized
    fun reset() {
        runCatching {
            val h = mh
            val fCb = h?.let {
                Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
            }
            fCb?.set(h, prevCallback)
        }
        mh = null; prevCallback = null; installed = false
        armed.set(false); bound = false; queued.clear()
    }

    /** สถานะสำหรับ chainCheck */
    fun status(): String = "hcb installed=$installed armed=${armed.get()} bound=$bound held=${queued.size}"
}
