package android.app

/*
 * AOSP-signature test stub (compile + runtime for localTest only).
 *
 * audit C11-degraded-only: เดิมเป็น object ที่ currentActivityThread() คืน null
 * เสมอ → ทุก test ยืนยันแค่ path ที่ degrade; ตอนนี้เพิ่ม seam:
 *   - holder: ให้ test ฉาย fake ActivityThread instance เข้ามา
 *   - mInstrumentation: field จริงที่ install() เขียน (≡ framework)
 * test ต้อง assert ทั้งสอง path (degraded + success)
 */
open class ActivityThread {
    @JvmField var mInstrumentation: Instrumentation? = null

    companion object {
        @JvmStatic var holder: Any? = null
        @JvmStatic fun currentActivityThread(): Any? = holder
    }
}
