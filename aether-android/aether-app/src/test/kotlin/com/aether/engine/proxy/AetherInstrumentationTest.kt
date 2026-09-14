/*
 * AetherEngine — AetherInstrumentation unit test (API surface only)
 *
 * Scope: 3 tests on pure Kotlin helpers that don't need Android framework
 * at runtime. Reflection-based rewire (newActivity, callActivityOnCreate,
 * resolveGuestActivityInfo, rewriteToStub) is NOT testable on plain JVM —
 * it depends on Activity, Context, PackageManager runtime types. Those
 * belong to instrumented test or Robolectric (out of scope here).
 *
 * Same rationale as aether-core/GuestRuntimeTest.kt and EngineRuntimeTest.kt
 * which assert the DSL/builder surface only.
 */
package com.aether.engine.proxy

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherInstrumentationTest {

    @Test
    fun `buildStubIntent sets className extras and flag`() {
        val intent = AetherInstrumentation.buildStubIntent(
            hostPkg = "com.aether",
            stubComponent = "com.aether.engine.proxy.ProxyActivity\$P0",
            guestPkg = "com.miniclip.eightballpool",
            guestClass = "com.miniclip.eightballpool.EightBallPoolActivity"
        )
        assertNotNull("intent must not be null", intent)
        assertEquals("className must point at stub component",
            "com.aether.engine.proxy.ProxyActivity\$P0", intent.component?.className)
        assertEquals("EXTRA_GUEST_CLASS must hold guest class FQN",
            "com.miniclip.eightballpool.EightBallPoolActivity",
            intent.getStringExtra(AetherInstrumentation.EXTRA_GUEST_CLASS))
        assertEquals("EXTRA_GUEST_INTENT must hold guest pkg",
            "com.miniclip.eightballpool",
            intent.getStringExtra(AetherInstrumentation.EXTRA_GUEST_INTENT))
        assertTrue("FLAG_ACTIVITY_NEW_TASK must be set",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `buildStubIntent with same host and guest pkg still produces a valid intent`() {
        // No assertion about equality; the function must not throw for any
        // (host, guest) pair — only FLAG_ACTIVITY_NEW_TASK and structure
        // are guaranteed.
        val intent = AetherInstrumentation.buildStubIntent(
            hostPkg = "com.aether",
            stubComponent = "com.aether.engine.proxy.ProxyActivity\$P0",
            guestPkg = "com.aether",
            guestClass = "com.aether.LocalActivity"
        )
        assertNotNull("intent must not be null even when host==guest", intent)
        assertTrue("NEW_TASK flag must be set even when host==guest",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `install returns false on plain JVM (no ActivityThread)`() {
        // install() needs ActivityThread.currentActivityThread() to wire the
        // hook. On plain unit-test classpath that throws NoClassDefFoundError
        // or returns null — the catch returns false. The contract is binary:
        // install() never crashes the caller; it returns true on success
        // and false on any failure.
        val stubComponent = "com.aether.engine.proxy.ProxyActivity\$P0"
        val guestLoader = ClassLoader.getSystemClassLoader()
        val result = AetherInstrumentation.install(stubComponent, guestLoader, null)
        assertEquals("install must return false on plain JVM (no ActivityThread)",
            false, result)
    }

    @Test
    fun `install rebinds on second guest session (audit C8-stale-hook + C11 seam)`() {
        // C11 seam: stub ActivityThread.holder = instance ที่มี mInstrumentation
        // (≡ success path บนเครื่องจริง — framework วาง Instrumentation ไว้ก่อนเสมอ)
        val at = android.app.ActivityThread()
        at.mInstrumentation = android.app.Instrumentation()
        android.app.ActivityThread.holder = at
        try {
            val loader1 = ClassLoader.getSystemClassLoader()
            val ok1 = AetherInstrumentation.install(
                "com.aether.engine.proxy.ProxyActivity\$P1", loader1, null)
            assertTrue("install success path ต้อง true เมื่อ seam ให้ AT", ok1)
            val wrapper = at.mInstrumentation as? AetherInstrumentation
            assertNotNull("mInstrumentation ต้องถูกแทนด้วย wrapper", wrapper)

            // session 2 (installed แล้ว, identity ใหม่): ต้อง rebind wrapper เดิม
            val loader2 = java.net.URLClassLoader(emptyArray())
            val ok2 = AetherInstrumentation.install(
                "com.aether.engine.proxy.ProxyActivity\$P2", loader2, null)
            assertTrue(ok2)
            org.junit.Assert.assertSame("wrapper เดียวต้องถูก reuse (rebind)",
                wrapper, at.mInstrumentation)
            val fStub = AetherInstrumentation::class.java.getDeclaredField("stubComponent")
            fStub.isAccessible = true
            assertEquals("rebind แล้ว stub ต้องเปลี่ยน",
                "com.aether.engine.proxy.ProxyActivity\$P2", fStub.get(wrapper))

            // reset (≡ ProxyActivity.onDestroy) → install รอบใหม่สร้าง wrapper ใหม่
            AetherInstrumentation.reset()
            val ok3 = AetherInstrumentation.install(
                "com.aether.engine.proxy.ProxyActivity\$P3", loader1, null)
            assertTrue(ok3)
            // wrapper เดิมถูก rebind (ไม่ใช่ instance ใหม่) — identity ต้องใหม่จริง
            assertNotNull(at.mInstrumentation)
            assertEquals("rebind หลัง reset ได้ stub ใหม่",
                "com.aether.engine.proxy.ProxyActivity\$P3", fStub.get(at.mInstrumentation))
        } finally {
            android.app.ActivityThread.holder = null
            AetherInstrumentation.reset()  // คืน state ให้ test อื่น (degraded path)
        }
    }
}
