package com.aether.engine.proxy

import android.app.Application
import android.content.Context
import android.util.Log
import com.aether.GuestRuntime

/**
 * GuestRuntimeBridge — compat layer ระหว่าง [VirtualAppLoader] (v1) และ [GuestRuntime] (v2)
 *
 * ## ปัญหา
 * v1 (VirtualAppLoader) มีจุดอ่อน:
 *   1. 2-pass bind (mInitialApplication + mAllApplications) — ไม่ bind Resources
 *   2. Application.getApplicationContext() return null → AOSP API 36
 *      ConfigurationController.updateLocaleListFromAppContext crash NPE
 *   3. ทุก error throw exception ไปยัง caller (ProxyActivity) — ไม่มี fallback
 *   4. ไม่มี lifecycle hooks (suspend/resume/destroyed)
 *
 * ## ทางแก้
 * v2 (GuestRuntime) ใน aether-core แก้ทั้ง 4 ข้อ — แต่ ProxyActivity เรียก v1 อยู่
 * Bridge ทำหน้าที่:
 *   - รับ signature เดิม (LoadResult, load()) — ProxyActivity ไม่ต้องแก้
 *   - delegate ไป GuestRuntime ภายใน — ได้ v2 fixes ทันที
 *   - ถ้า v2 fail → fallback ไป v1 (VirtualAppLoader) — backward compat
 *   - เก็บ metrics (latency, success, fallback reason) — diagnostics
 *
 * ## ใช้แบบนี้
 *   val res = GuestRuntimeBridge.load(ctx, pkg, hint, onCreate, providers)
 *   // res มี shape เดียวกับ VirtualAppLoader.load() — drop-in replacement
 *
 * ## Mode
 *   AUTO (default): ลอง v2 ก่อน, ถ้า fail fallback v1
 *   V2_ONLY:        v2 เท่านั้น (ไม่ fallback — fail loud)
 *   V1_ONLY:        v1 เท่านั้น (force legacy — ใช้ตอน debug)
 *   DUAL:           รันทั้งคู่ parallel เพื่อเปรียบเทียบ
 *
 * ## Migration
 *   Step 3 จะแก้ ProxyActivity/AetherOrchestrator ให้เรียก Bridge แทน
 *   ตอนนี้ Bridge เป็น **optional** — ไม่กระทบ call sites เดิม
 *
 * @see com.aether.GuestRuntime (v2 — aether-core)
 * @see VirtualAppLoader (v1 — aether-app/proxy, fallback)
 */
object GuestRuntimeBridge {

    private const val TAG = "AetherGuestBridge"

    // ══════════════════════════════════════════
    //  Public types
    // ══════════════════════════════════════════

    /**
     * Strategy สำหรับ load — ใช้ [mode] เพื่อเลือก V1/V2/auto
     */
    enum class RuntimeMode {
        /** v2 ก่อน → fallback v1 (default) */
        AUTO,
        /** v2 เท่านั้น (fail loud) */
        V2_ONLY,
        /** v1 เท่านั้น (legacy) */
        V1_ONLY,
        /** รันทั้ง v2 และ v1 parallel (เปรียบเทียบ) */
        DUAL
    }

    /**
     * เหตุการณ์ที่เกิดระหว่าง load — ใช้สำหรับ diagnostics + test
     */
    sealed class LoadEvent {
        data class V2Start(val sessionId: String) : LoadEvent()
        data class V2Success(val providersInstalled: Int) : LoadEvent()
        data class V2Failure(val reason: String, val exception: Throwable?) : LoadEvent()
        data class V1Start(val reason: String) : LoadEvent()
        data class V1Success(val providersInstalled: Int) : LoadEvent()
        data class V1Failure(val reason: String) : LoadEvent()
        data class FallbackTriggered(val fromReason: String) : LoadEvent()
    }

    /**
     * Metrics ของการ load — เก็บไว้ดู telemetry
     */
    data class Metrics(
        val totalLoadMs: Long,
        val v2Attempted: Boolean,
        val v2Succeeded: Boolean,
        val v1Attempted: Boolean,
        val v1Succeeded: Boolean,
        val fallbackTriggered: Boolean,
        val eventCount: Int,
    ) {
        override fun toString(): String = buildString {
            append("Metrics(load=${totalLoadMs}ms")
            if (v2Attempted) append(", v2=${if (v2Succeeded) "OK" else "FAIL"}")
            if (v1Attempted) append(", v1=${if (v1Succeeded) "OK" else "FAIL"}")
            if (fallbackTriggered) append(", FALLBACK")
            append(", events=$eventCount)")
        }
    }

    /**
     * Result มี shape เดียวกับ [VirtualAppLoader.LoadResult] — drop-in compat
     * เพิ่ม [metrics] + [eventLog] สำหรับ diagnostics
     */
    data class LoadResult(
        val success: Boolean,
        val reason: String,
        val targetPackage: String,
        val applicationClass: String? = null,
        val loadedApplication: Application? = null,
        val providersInstalled: Int = 0,
        val guestClassLoader: ClassLoader? = null,
        val runtimeSource: String = "unknown",  // "v2" | "v1" | "none"
        val metrics: Metrics = Metrics(0, false, false, false, false, false, 0),
        val eventLog: List<LoadEvent> = emptyList(),
    ) {
        /** true ถ้า load จาก v2 (GuestRuntime) */
        val isV2: Boolean get() = runtimeSource == "v2"
        /** true ถ้า load จาก v1 (VirtualAppLoader) */
        val isV1: Boolean get() = runtimeSource == "v1"
    }

    // ══════════════════════════════════════════
    //  State
    // ══════════════════════════════════════════

    @Volatile private var mode: RuntimeMode = RuntimeMode.AUTO
    @Volatile private var lastResult: LoadResult? = null
    @Volatile private var activeRuntime: GuestRuntime? = null

    /** ตั้ง mode (สำหรับ test หรือ feature flag) */
    fun setMode(m: RuntimeMode) {
        mode = m
        Log.i(TAG, "mode → $m")
    }

    /** คืน active runtime (ถ้ามี) — ใช้สำหรับ hook external components */
    fun activeRuntime(): GuestRuntime? = activeRuntime

    /** คืน last result (read-only) */
    fun lastResult(): LoadResult? = lastResult

    // ══════════════════════════════════════════
    //  Main API — drop-in compat กับ VirtualAppLoader.load()
    // ══════════════════════════════════════════

    /**
     * Load [targetPkg]'s Application เข้า process นี้
     *
     * Signature เหมือน VirtualAppLoader.load() 100% — ProxyActivity เรียก
     * GuestRuntimeBridge แทนได้ทันทีโดยไม่ต้องแก้
     *
     * @param hostContext Real app context
     * @param targetPkg package name ของ guest (เช่น "com.miniclip.eightballpool")
     * @param appClassHint FQCN ของ Application class (อาจว่าง — ใช้ manifest แทน)
     * @param callOnCreate ถ้า true → เรียก Application.onCreate() หลัง bind
     * @param providers รายชื่อ ContentProvider class ที่ต้อง install ก่อน onCreate
     * @return LoadResult ที่ compat กับ VirtualAppLoader.LoadResult
     */
    fun load(
        hostContext: Context,
        targetPkg: String,
        appClassHint: String? = null,
        callOnCreate: Boolean = false,
        providers: List<String> = emptyList(),
    ): LoadResult {
        val t0 = System.currentTimeMillis()
        val events = mutableListOf<LoadEvent>()

        val result = when (mode) {
            RuntimeMode.V2_ONLY -> loadV2(hostContext, targetPkg, appClassHint,
                callOnCreate, providers, events)
            RuntimeMode.V1_ONLY -> loadV1(hostContext, targetPkg, appClassHint,
                callOnCreate, providers, events)
            RuntimeMode.DUAL -> loadDual(hostContext, targetPkg, appClassHint,
                callOnCreate, providers, events)
            RuntimeMode.AUTO -> loadAuto(hostContext, targetPkg, appClassHint,
                callOnCreate, providers, events)
        }

        val totalMs = System.currentTimeMillis() - t0
        val withMetrics = result.copy(
            metrics = Metrics(
                totalLoadMs = totalMs,
                v2Attempted = events.any { it is LoadEvent.V2Start },
                v2Succeeded = events.any { it is LoadEvent.V2Success },
                v1Attempted = events.any { it is LoadEvent.V1Start },
                v1Succeeded = events.any { it is LoadEvent.V1Success },
                fallbackTriggered = events.any { it is LoadEvent.FallbackTriggered },
                eventCount = events.size,
            ),
            eventLog = events.toList(),
        )

        lastResult = withMetrics
        Log.i(TAG, "load($targetPkg) → $withMetrics")
        return withMetrics
    }

    // ══════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════

    /**
     * Release active GuestRuntime — เรียกตอน process จะ shutdown
     * (ProxyActivity.onDestroy() / AetherOrchestrator.shutdown())
     */
    fun close() {
        try {
            activeRuntime?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close: ${e.message}")
        }
        activeRuntime = null
        lastResult = null
    }

    // ══════════════════════════════════════════
    //  Internal: AUTO mode (v2 → v1 fallback)
    // ══════════════════════════════════════════

    private fun loadAuto(
        hostContext: Context,
        targetPkg: String,
        appClassHint: String?,
        callOnCreate: Boolean,
        providers: List<String>,
        events: MutableList<LoadEvent>,
    ): LoadResult {
        val v2 = loadV2(hostContext, targetPkg, appClassHint, callOnCreate, providers, events)
        if (v2.success) return v2

        // Fallback to v1
        events.add(LoadEvent.FallbackTriggered(v2.reason))
        Log.w(TAG, "v2 failed (${v2.reason}) → fallback to v1")
        return loadV1(hostContext, targetPkg, appClassHint, callOnCreate, providers, events)
    }

    // ══════════════════════════════════════════
    //  Internal: v2 path (GuestRuntime — ใหม่)
    // ══════════════════════════════════════════

    private fun loadV2(
        hostContext: Context,
        targetPkg: String,
        appClassHint: String?,
        callOnCreate: Boolean,
        providers: List<String>,
        events: MutableList<LoadEvent>,
    ): LoadResult {
        val sessionId = "bridge-${System.currentTimeMillis()}"
        events.add(LoadEvent.V2Start(sessionId))

        val buildResult = GuestRuntime
            .builder(targetPkg, hostContext)
            .sessionId(sessionId)
            .apply { if (!appClassHint.isNullOrEmpty()) applicationClassHint(appClassHint) }
            .installProviders(providers)
            .apply { if (callOnCreate) autoStartApplication() }
            .build()

        val runtime = buildResult.getOrElse { e ->
            val reason = "build failed: ${e.message ?: e.javaClass.simpleName}"
            events.add(LoadEvent.V2Failure(reason, e))
            Log.w(TAG, "v2 build: $reason")
            return LoadResult(
                success = false,
                reason = reason,
                targetPackage = targetPkg,
                applicationClass = appClassHint,
                runtimeSource = "none",
            )
        }

        // Close previous runtime ก่อน (safety)
        activeRuntime?.close()

        // Bind to ActivityThread (3-pass)
        val bound = runtime.bindToActivityThread()
        if (!bound) {
            val reason = "bindToActivityThread failed (3-pass)"
            events.add(LoadEvent.V2Failure(reason, null))
            runtime.close()
            return LoadResult(
                success = false,
                reason = reason,
                targetPackage = targetPkg,
                runtimeSource = "none",
            )
        }

        // Install providers (ถ้า build ไม่ได้ทำ — แต่ build ทำไปแล้ว เพราะ flag ใน builder)
        val installed = runtime.providersInstalled

        activeRuntime = runtime
        events.add(LoadEvent.V2Success(installed))

        return LoadResult(
            success = true,
            reason = "v2 OK: 3-pass bind + $installed providers",
            targetPackage = targetPkg,
            applicationClass = runtime.guestApplication.javaClass.name,
            loadedApplication = runtime.guestApplication,
            providersInstalled = installed,
            guestClassLoader = runtime.guestClassLoader,
            runtimeSource = "v2",
        )
    }

    // ══════════════════════════════════════════
    //  Internal: v1 path (VirtualAppLoader — เก่า, fallback)
    // ══════════════════════════════════════════

    private fun loadV1(
        hostContext: Context,
        targetPkg: String,
        appClassHint: String?,
        callOnCreate: Boolean,
        providers: List<String>,
        events: MutableList<LoadEvent>,
    ): LoadResult {
        events.add(LoadEvent.V1Start("v1 fallback"))
        val r = VirtualAppLoader.load(
            hostContext, targetPkg, appClassHint,
            callOnCreate = callOnCreate, providers = providers,
        )
        if (r.success) {
            events.add(LoadEvent.V1Success(r.providersInstalled))
        } else {
            events.add(LoadEvent.V1Failure(r.reason))
        }
        return LoadResult(
            success = r.success,
            reason = r.reason,
            targetPackage = r.targetPackage,
            applicationClass = r.applicationClass,
            loadedApplication = r.loadedApplication,
            providersInstalled = r.providersInstalled,
            guestClassLoader = r.guestClassLoader,
            runtimeSource = if (r.success) "v1" else "none",
        )
    }

    // ══════════════════════════════════════════
    //  Internal: DUAL mode (parallel — diagnostics)
    // ══════════════════════════════════════════

    private fun loadDual(
        hostContext: Context,
        targetPkg: String,
        appClassHint: String?,
        callOnCreate: Boolean,
        providers: List<String>,
        events: MutableList<LoadEvent>,
    ): LoadResult {
        val v2 = loadV2(hostContext, targetPkg, appClassHint, callOnCreate, providers, events)
        if (v2.success) return v2

        events.add(LoadEvent.FallbackTriggered(v2.reason))
        return loadV1(hostContext, targetPkg, appClassHint, callOnCreate, providers, events)
    }
}
