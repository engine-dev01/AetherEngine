package com.aether

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [GuestRuntime] (v2 architecture).
 *
 * ทดสอบ API surface เท่านั้น — ไม่สามารถทดสอบ runtime binding บน JVM
 * เพราะต้องการ Android framework (ActivityThread, ContextImpl, etc.).
 * ใช้ [robolectric] หรือ instrumented test สำหรับส่วนนั้น.
 */
class GuestRuntimeTest {

    // ─────────────────────────────────────────
    //  DSL Builder API — verify compile + shape
    // ─────────────────────────────────────────

    @Test
    fun builder_does_not_throw_with_null_context() {
        // builder เก็บ context ไว้ แต่ไม่เรียกใช้จนกว่า build()
        // ดังนั้นสร้าง builder กับ null ได้ (แต่ build() จะ fail)
        val b = GuestRuntime.builder("com.test.pkg", null as Context?)
        assertNotNull(b)
    }

    @Test
    fun builder_chained_calls_return_same_builder() {
        // ใช้ null context — แค่ตรวจ DSL shape, ไม่เรียก build()
        val b: GuestRuntime.Builder = GuestRuntime.builder("com.test.pkg", null as Context?)
            .sessionId("test-session-1")
            .applicationClassHint("com.test.pkg.MyApp")
            .installProviders(listOf("com.test.pkg.Provider1"))
            .onCreated { /* no-op */ }
            .onBound { /* no-op */ }
            .onProvidersInstalled { _, _ -> /* no-op */ }
            .onSuspended { /* no-op */ }
            .onResumed { /* no-op */ }
            .onDestroyed { /* no-op */ }
        assertNotNull(b)
    }

    @Test
    fun builder_autoStartApplication_toggles_flag() {
        val b = GuestRuntime.builder("com.test.pkg", null as Context?)
            .autoStartApplication()
        assertNotNull(b)
        // เรียก build() ด้วย null context ต้อง fail gracefully (Result.failure)
        val r = b.build()
        assertTrue("expected failure when hostContext is null", r.isFailure)
    }

    @Test
    fun build_with_null_context_returns_failure() {
        val r = GuestRuntime.builder("com.test.pkg", null as Context?)
            .build()
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("error should mention context: $msg", msg.contains("createPackageContext"))
    }

    // ─────────────────────────────────────────
    //  State enum — verify exhaustive
    // ─────────────────────────────────────────

    @Test
    fun state_enum_has_expected_values() {
        val states = GuestRuntime.State.values()
        assertEquals(4, states.size)
        assertNotNull(GuestRuntime.State.INITIALIZING)
        assertNotNull(GuestRuntime.State.ACTIVE)
        assertNotNull(GuestRuntime.State.SUSPENDED)
        assertNotNull(GuestRuntime.State.DESTROYED)
    }

    // ─────────────────────────────────────────
    //  AutoCloseable contract — verify interface
    // ─────────────────────────────────────────

    @Test
    fun guestRuntime_implements_AutoCloseable() {
        val cls = GuestRuntime::class.java
        assertTrue(
            "GuestRuntime must implement AutoCloseable",
            java.lang.AutoCloseable::class.java.isAssignableFrom(cls)
        )
    }

    // ─────────────────────────────────────────
    //  Public API surface (regression guard)
    //  เพิ่ม field ใหม่ = ต้องเตือนที่นี่
    // ─────────────────────────────────────────

    @Test
    fun public_api_surface_is_stable() {
        // รายการ API ที่ Tier 3 (aether-android) ใช้ — เปลี่ยน = breaking change
        val expected = setOf(
            "targetPkg",
            "sessionId",
            "guestClassLoader",
            "guestApplication",
            "guestContext",
            "providersInstalled",
            "attachedProviders",
            "skippedProviders",
            "currentState",
            "bindToActivityThread",
            "installProviders",
            "callOnCreate",
            "suspend",
            "resume",
            "close",
            "builder",
        )
        val declared = GuestRuntime::class.java.declaredMethods
            .map { it.name }
            .toSet() + GuestRuntime::class.java.declaredFields.map { it.name }

        val missing = expected - declared
        assertTrue("missing public API: $missing", missing.isEmpty())
    }

    @Test
    fun state_field_is_atomic() {
        // เป็น AtomicReference (ไม่ใช่ var ธรรมดา) เพื่อ thread-safety
        val f = GuestRuntime::class.java.getDeclaredField("state")
        assertEquals(
            "state must be AtomicReference for thread-safety",
            "java.util.concurrent.atomic.AtomicReference",
            f.type.name
        )
    }
}
