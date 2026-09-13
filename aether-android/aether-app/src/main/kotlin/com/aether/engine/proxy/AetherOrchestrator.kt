package com.aether.engine.proxy

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aether.SandboxManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AetherOrchestrator — Engine Lifecycle Manager (engine singleton)
 *
 * จัดการ lifecycle ของ engine ทั้งหมด:
 * 1. Init — สร้าง virtual app container, service proxies, ART hooks
 * 2. Attach — เชื่อมต่อกับ target process (game)
 * 3. Run — virtual container runtime
 * 4. Detach — ปลดล็อก process
 * 5. Shutdown — ปิด engine ทั้งหมด
 *
 * ใช้คู่กับ:
 * - VirtualAppContainer (fake context, path redirect)
 * - ServiceBinderProxy (8 system service proxies)
 * - ArtHookEngine removed (was no-op)
 * - SandboxManager (bind-mount, PGL bypass)
 * - Engine.kt (native JNI bridge)
 *
 * วิธีใช้:
 * 1. AetherOrchestrator.init(context)
 * 2. AetherOrchestrator.attachToProcess(pid, "")
 * 3. AetherOrchestrator.startEngine()
 * 4. // ... engine running ...
 * 5. AetherOrchestrator.shutdown()
 */
object AetherOrchestrator {

    private const val TAG = "AetherOrchestrator"

    // ─── State ───
    private val isInitialized = AtomicBoolean(false)
    private val isAttached = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // ─── Target Process ───
    private val targetPid = AtomicInteger(-1)
    private val targetModule = AtomicInteger(-1) // module base address
    private var targetModuleName: String = ""
    private var targetPackage: String = ""

    // ─── Engine Stats ───
    private var attachTime: Long = 0
    private var readCount: Long = 0
    private var scanCount: Long = 0

    // ─── Context ───
    private var appContext: Context? = null

    // ══════════════════════════════════════════
    //  Initialization
    // ══════════════════════════════════════════

    /**
     * Initialize orchestrator + all subsystems
     * @param context — Application context
     * @param fakePkg — Package name ปลอม (ถ้าต้องการ override)
     */
    fun init(context: Context, fakePkg: String = ""): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "Already initialized")
            return true
        }

        appContext = context.applicationContext

        try {
            // 1. Initialize Virtual App Container
            VirtualAppContainer.init(context, fakePkg)
            Log.d(TAG, "VirtualAppContainer initialized")

            // 2. Initialize VirtualAppContainer setup (proxies + hooks)
            VirtualAppContainer.setup()
            Log.d(TAG, "ServiceBinderProxy: ${ServiceBinderProxy.listProxiedServices().size} proxies active")

            // ART hook + StringObfuscator removed (no-op / orphan — see audit AD-1)

            // 4. Initialize SandboxManager + provision helpers
            SandboxManager.init(context)
            try {
                val payloadDir = java.io.File(context.dataDir, "root/files")
                SandboxManager.provisionPayloadsFromFiles(payloadDir)
                SandboxManager.provisionVdexStubs()
            } catch (_: Exception) {}
            Log.d(TAG, "SandboxManager initialized")

            // 5. Initialize Flagger
            Flagger.init(context)
            Log.d(TAG, "Flagger initialized")

            // ServiceBinderProxy (black.android pattern)
            Log.d(TAG, "ServiceBinderProxy: ${ServiceBinderProxy.listProxiedServices().size} proxies active")

            // JNI Hook — native offsets
            try {
                com.aether.Engine.nativeOffset()
                com.aether.Engine.nativeOffset2()
                Log.d(TAG, "JNI Hook: nativeOffset active")
            } catch (e: Throwable) {
                Log.w(TAG, "JNI Hook init: ${e.message}")
            }

            // IO virtualization + hide detection + DEX loading
            try {
                com.aether.Engine.enableIO()
                // [CUT 2026-09-11] protection — hideXposed/network-probe/empty-dex
                // ทำให้แอพกั๊กตัวเอง งดก่อนทดสอบการทำงานหลัก
                // com.aether.Engine.hideXposed()
                // com.aether.Engine.installNetworkHttpProbe()
                // com.aether.Engine.loadEmptyDex()
                Log.d(TAG, "IO system initialized (protection disabled for test)")
            } catch (e: Throwable) {
                Log.w(TAG, "Phase 5 init: ${e.message}")
            }

            // 6. Native engine already loaded by AetherApp via EngineLoader
            // System.loadLibrary("aether") is NOT called here — avoiding double-load
            Log.d(TAG, "Native engine: loaded by EngineLoader (AetherApp)")

            // 7. Enable IO virtualization + register redirects from VirtualFSWrapper
            try {
                com.aether.Engine.enableIO()
                // Register all existing redirects from VirtualFSWrapper to native
                VirtualFSWrapper.listRedirects().forEach { rule ->
                    val parts = rule.split(" → ")
                    if (parts.size == 2) {
                        com.aether.Engine.addIORule(parts[0], parts[1])
                    }
                }
                Log.d(TAG, "VirtualFS: IO enabled, ${VirtualFSWrapper.size()} redirect rules registered")
            } catch (e: Throwable) {
                Log.w(TAG, "VirtualFS init: ${e.message}")
            }

            isInitialized.set(true)
            Log.i(TAG, "Orchestrator initialized successfully")
            return true

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize orchestrator: ${e.message}")
            return false
        }
    }

    // ══════════════════════════════════════════
    //  Process Management
    // ══════════════════════════════════════════

    /**
     * Attach to target process (game)
     * @param pid — process ID ของ game
     * @param moduleName — module name to attach (e.g., "" (empty = skip attach)
     * @param packageName — game package name
     */
    fun attachToProcess(pid: Int, moduleName: String, packageName: String = ""): Boolean {
        if (!isInitialized.get()) {
            Log.e(TAG, "Not initialized")
            return false
        }

        // Self-attach: no external process needed — AetherEngine works within its own process space.
        // Empty moduleName → attach to own process (libaether.so is already loaded).
        val isSelfAttach = moduleName.isEmpty()
        val ownPid = android.os.Process.myPid()
        val targetPidVal = if (isSelfAttach) ownPid else pid
        val moduleToAttach = if (isSelfAttach) "libaether.so" else moduleName

        if (isAttached.get() && targetPid.get() == targetPidVal) {
            Log.w(TAG, "Already attached to PID ${targetPid.get()}")
            return true
        }

        targetPid.set(targetPidVal)
        targetModule.set(0) // resolved by native
        targetModuleName = moduleToAttach
        targetPackage = if (isSelfAttach) "com.aether" else packageName

        try {
            // 1. Find module base address (self or external)
            val base = com.aether.Engine.nativeFindModuleBase(targetPidVal, moduleToAttach)
            if (base <= 0) {
                Log.e(TAG, "Cannot find module base for $moduleToAttach in PID $targetPidVal")
                return false
            }
            targetModule.set(base.toInt())
            Log.d(TAG, "Module base: 0x${base.toString(16)}")

            // 2. Attach to process (native — self or external)
            val attached = com.aether.Engine.nativeAttach(targetPidVal, moduleToAttach)
            if (!attached) {
                Log.e(TAG, "Failed to attach to PID $targetPidVal")
                return false
            }

            // 3. Setup VirtualFS for target (self = own package; external = game package)
            val dataDir = if (isSelfAttach) {
                "/data/user/0/com.aether"  // real host applicationId (not "com.aether.aether")
            } else {
                "/data/user/0/$packageName"
            }
            val virtualFS = VirtualAppContainer.getVirtualFS()
            virtualFS.setupForApp(
                dataDir = dataDir,
                nativeLibDir = "",
                externalDir = "/sdcard",
                fakePackage = VirtualAppContainer.getFakePackageName()
            )
            Log.d(TAG, "VirtualFS setup for PID $targetPidVal (self=$isSelfAttach)")

            isAttached.set(true)
            attachTime = System.currentTimeMillis()
            Log.i(TAG, "Attached to PID $targetPidVal ($moduleToAttach) [self-attach=$isSelfAttach]")
            return true

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to attach: ${e.message}")
            return false
        }
    }

    /**
     * Detach from target process
     */
    fun detachFromProcess(): Boolean {
        if (!isAttached.get()) return true

        isAttached.set(false)
        targetPid.set(-1)
        targetModule.set(0)

        Log.i(TAG, "Detached from process")
        return true
    }

    // ══════════════════════════════════════════
    //  Engine Operations
    // ══════════════════════════════════════════

    /**
     * Start engine — begin virtual container runtime.
     * Wires native VirtualFS + I/O rules via JNI.
     * Can be called after self-attach or launchInSandbox.
     */
    fun startEngine(): Boolean {
        if (!isAttached.get()) {
            Log.w(TAG, "startEngine: not attached — call attachToProcess() first")
            return false
        }

        if (isRunning.get()) {
            Log.w(TAG, "Engine already running")
            return true
        }

        // Wire VirtualFS I/O rules to native layer
        try {
            com.aether.Engine.enableIO()
            VirtualFSWrapper.listRedirects().forEach { rule ->
                val parts = rule.split(" → ")
                if (parts.size == 2) {
                    com.aether.Engine.addIORule(parts[0], parts[1])
                }
            }
            Log.d(TAG, "VirtualFS: ${VirtualFSWrapper.size()} I/O rules registered to native")
        } catch (e: Throwable) {
            Log.w(TAG, "VirtualFS wire: ${e.message}")
        }

        // Native watchdog check — verify native layer is alive
        try {
            val healthy = com.aether.Engine.nativeWatchdogCheck()
            if (!healthy) {
                Log.w(TAG, "startEngine: nativeWatchdogCheck=false — native may be unstable")
            } else {
                Log.d(TAG, "startEngine: nativeWatchdogCheck=true — native healthy")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startEngine: nativeWatchdogCheck failed: ${e.message}")
        }

        isRunning.set(true)
        Log.i(TAG, "Engine started (PID=${targetPid.get()}, module=$targetModuleName)")
        return true
    }

    /**
     * Stop engine — pause memory reading
     */
    fun stopEngine() {
        isRunning.set(false)
        Log.i(TAG, "Engine stopped")
    }

    /**
     * Read memory from target process
     * @param address — memory address to read
     * @param size — number of bytes to read
     * @return byte array or null on failure
     */
    fun readMemory(address: Long, size: Int): ByteArray? {
        if (!isRunning.get()) return null

        val pid = targetPid.get()
        if (pid <= 0) return null

        return try {
            val data = com.aether.Engine.nativeRead(pid, address, size.toLong())
            if (data != null) {
                readCount++
            }
            data
        } catch (e: Throwable) {
            Log.e(TAG, "Memory read failed: ${e.message}")
            null
        }
    }

    /**
     * Scan AOB pattern in target process
     * @param pattern — byte pattern to scan
     * @param mask — pattern mask (x=match, ?=wildcard)
     * @return address of first match or 0
     */
    fun scanAOB(pattern: ByteArray, mask: String): Long {
        if (!isRunning.get()) return 0

        val pid = targetPid.get()
        val base = targetModule.get().toLong()
        if (pid <= 0 || base <= 0) return 0

        return try {
            // Default scan size: 8MB (from RemoteConfig)
            val scanSize = 8 * 1024 * 1024L
            val result = com.aether.Engine.nativeScanAOB(pid, base, scanSize, pattern, mask)
            if (result > 0) {
                scanCount++
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "AOB scan failed: ${e.message}")
            0
        }
    }

    // ══════════════════════════════════════════
    //  Native payload clusters (slots 3,4,5,9,12)
    //  guarded — safe even before attach
    // ══════════════════════════════════════════

    /** slot 3 — zlib deflate (ZSTD-like payload) */
    fun compressPayload(data: ByteArray): ByteArray? = try {
        com.aether.Engine.nativeCompressPayload(data)
    } catch (e: Throwable) { Log.e(TAG, "compress failed: ${e.message}"); null }

    /** slot 3 — inflate */
    fun decompressPayload(data: ByteArray): ByteArray? = try {
        com.aether.Engine.nativeDecompressPayload(data)
    } catch (e: Throwable) { Log.e(TAG, "decompress failed: ${e.message}"); null }

    /** slot 4 — encrypt keystream (AES-GCM จริงหลัง root) */
    fun encryptPayload(data: ByteArray, key: ByteArray): ByteArray? = try {
        com.aether.Engine.nativeEncryptPayload(data, key)
    } catch (e: Throwable) { Log.e(TAG, "encrypt failed: ${e.message}"); null }

    /** slot 5 — decrypt */
    fun decryptPayload(data: ByteArray, key: ByteArray): ByteArray? = try {
        com.aether.Engine.nativeDecryptPayload(data, key)
    } catch (e: Throwable) { Log.e(TAG, "decrypt failed: ${e.message}"); null }

    /** slot 12 — watchdog health (attach + module base) */
    fun watchdogCheck(): Boolean = try {
        com.aether.Engine.nativeWatchdogCheck()
    } catch (e: Throwable) { Log.e(TAG, "watchdog failed: ${e.message}"); false }

    // ══════════════════════════════════════════
    //  Phase 3: mount sandbox (Boolean return — CI-safe)
    // ══════════════════════════════════════════

    /** mountSandboxForSelf — bind-mount sandbox dir over engine's own data dir.
     *  Best-effort: requires root for mount --bind; non-root returns true
     *  because VirtualFS path-redirect already wired in startEngine. */
    fun mountSandboxForSelf(): Boolean = try {
        SandboxManager.mountSandbox("com.aether")
        Log.i(TAG, "mountSandbox(com.aether) called (non-root → no-op + VirtualFS fallback)")
        true
    } catch (e: Throwable) { Log.w(TAG, "mountSandboxForSelf failed: ${e.message}"); false }

    /** execShell — รันคำสั่ง shell ผ่าน Runtime (no-root shell fallback)
     *  @return stdout+stderr (trim) หรือ null เมื่อไม่มีสิทธิ์/error */
    fun execShell(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream?.bufferedReader()?.readText().orEmpty()
        p.waitFor()
        (out + err).trim().ifEmpty { null }
    } catch (e: Throwable) {
        Log.e(TAG, "execShell failed: ${e.message}")
        null
    }

    // ══════════════════════════════════════════
    //  State Queries
    // ══════════════════════════════════════════

    /** ตรวจสอบว่า engine initialized หรือไม่ */
    fun isInitialized(): Boolean = isInitialized.get()

    /** ตรวจสอบว่า attached to process หรือไม่ */
    fun isAttachedToProcess(): Boolean = isAttached.get()

    /** ตรวจสอบว่า engine กำลังทำงานหรือไม่ (native health check) */
    fun isEngineRunning(): Boolean {
        if (!isRunning.get()) return false
        // Native watchdog: verify native layer is still alive
        return try {
            com.aether.Engine.nativeWatchdogCheck()
        } catch (e: Throwable) {
            Log.w(TAG, "isEngineRunning: nativeWatchdogCheck failed — ${e.message}")
            isRunning.set(false)
            false
        }
    }

    /** คืน target PID */
    fun getTargetPid(): Int = targetPid.get()

    /** คืน target module name */
    fun getTargetModuleName(): String = targetModuleName.toString()

    /** คืน engine stats */
    fun getStats(): EngineStats {
        return EngineStats(
            initialized = isInitialized.get(),
            attached = isAttached.get(),
            running = isRunning.get(),
            targetPid = targetPid.get(),
            targetModule = targetModuleName.toString(),
            uptimeMs = if (isAttached.get()) System.currentTimeMillis() - attachTime else 0,
            readCount = readCount,
            scanCount = scanCount,
            serviceProxyCount = ServiceBinderProxy.listProxiedServices().size
        )
    }

    // ══════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════

    /**
     * Shutdown orchestrator + all subsystems
     */
    // ══════════════════════════════════════════
    //  Sandbox Launch
    //  Flow: bootstrap target → mount sandbox → startActivity ใน process :p0
    // ══════════════════════════════════════════
    fun launchInSandbox(context: Context, targetPkg: String): Boolean {
        if (!isInitialized.get()) {
            Log.e(TAG, "launchInSandbox: not initialized")
            return false
        }
        return try {
            // 0. If VirtualAppContainer already initialized for a different target,
            //    shutdown first so init() can re-apply hooks for the new target.
            //    (init() has an isInitialized guard that early-returns — without
            //    this, fakePackageName would stay at the old value and the guest
            //    would run under the wrong package identity.)
            if (VirtualAppContainer.isReady() &&
                VirtualAppContainer.getFakePackageName() != targetPkg &&
                VirtualAppContainer.getFakePackageName() != context.packageName
            ) {
                Log.i(TAG, "launchInSandbox: re-init for new target " +
                    "(${VirtualAppContainer.getFakePackageName()} → $targetPkg)")
                VirtualAppContainer.shutdown()
            }

            // 1. Setup VirtualAppContainer ให้ใช้ fake package = target
            VirtualAppContainer.init(context, targetPkg)
            VirtualAppContainer.setup()
            Log.d(TAG, "launchInSandbox: VirtualAppContainer ready for $targetPkg")

            // 2. Bootstrap + mount sandbox (bind-mount root/data เกมจริง -> root/)
            SandboxManager.bootstrapGameData(targetPkg)
            SandboxManager.mountSandbox(targetPkg)
            Log.d(TAG, "launchInSandbox: sandbox mounted for $targetPkg")

            // 3. Start engine (JNI hooks + ART)
            startEngine()
            Log.d(TAG, "launchInSandbox: engine started")

            // 4. Start game activity ใน process :p0 (proxy) — ไม่ใช่เรียกตรง
            //    IMPORTANT: the guest Application is loaded/started INSIDE :p0
            //    (ProxyActivity), NOT here in the caller's (main) process. Running
            //    a real app's Application.onCreate in main crashes the UI when the
            //    guest spawns threads that hit an unbound context (prototype jv0.O2
            //    runs in the proxy process for exactly this isolation).
            // 4a. ★ ขั้น ② (a7.w/u:292 + a7.m:171 parity) — จอง slot + provider
            //     handshake: ContentResolver.call(content://com.aether.proxy.content.N,
            //     "_Engine_|_init_process_", cfg) → Android spawn :pN เอง (framework
            //     ติดตั้ง provider ตาม manifest process=) → child ตอบ IBinder กลับ
            //     → linkToDeath คุมชีพ + config ถึง child ก่อน activity dispatch
            val slot = GuestProcessTable.allocate(context, targetPkg)
            var handshook = false
            if (slot >= 0) {
                // p3.r semantics = ANDROID USER id (0..9) — ไม่ใช่ uid (SNAKE p3.p/q
                // ต่างหากที่ถือ uid); A16: userId = uid / 100000
                handshook = GuestProcessTable.spawnAndConfig(
                    context, ClientConfig(targetPkg, slot, android.os.Process.myUid() / 100000),
                )
            } else {
                Log.w(TAG, "launchInSandbox: no free slot (a7:317 semantics) → P0 fallback")
            }

            // 4b. start activity stub บน slot ที่จองไว้ (r1.k/kl0 parity:
            //     stub component ต้องตรงกับ process suffix ของ provider ที่ปลุกขึ้น)
            // ★ device test 00:31:21 (com.aether_1.zip): เดิม "else 0" dispatch เข้า
            //   :p0 ที่ slot table จองให้ guest อื่นแล้ว (chaincheck ปินไว้) →
            //   p3-first (≡ jv0.P2 semantics — ถูกแล้ว) override intent เกม
            //   target กลายเป็น chaincheck, เกมไม่ถูกเปิด → stub ต้องตรงกับ slot
            //   ที่ allocate เสมอ ไม่ handshook ก็ dispatch P<slot> (framework
            //   spawn ผ่าน manifest process= อยู่แล้ว = พฤติกรรมก่อนมี handshake)
            val proxy = Intent()
            val stubSuffix = if (slot >= 0) slot else 0
            proxy.setClassName(
                context,
                "com.aether.engine.proxy.ProxyActivity\$P$stubSuffix",
            )
            proxy.putExtra("target_package", targetPkg)
            proxy.putExtra("target_sandbox", SandboxManager.getSandboxRoot()?.absolutePath)
            // key ≡ GuestProcessTable.EXTRA_SLOT (single source — ห้าม literal ซ้ำ)
            // ส่งเสมอ: child ใช้ seed fallback ตอน handshake=false
            // หมายเหตุ snake-parity: il0 แพ็ค real intent (_S_|_target_ ฯลฯ) = งาน P4
            proxy.putExtra(GuestProcessTable.EXTRA_SLOT, stubSuffix)
            proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(proxy)
            val label = if (handshook) "P$stubSuffix" else "P$stubSuffix(no-handshake)"
            Log.i(TAG, "launchInSandbox: ProxyActivity.$label dispatched for " +
                "$targetPkg (handshake=$handshook)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "launchInSandbox failed: ${e.message}")
            false
        }
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down orchestrator...")

        // 1. Stop engine
        stopEngine()

        // 2. Detach from process
        detachFromProcess()

        // 3. Shutdown subsystems
        VirtualAppContainer.shutdown()
        // ArtHookEngine.unhookAll() — removed (no-op)
        SandboxManager.init(appContext!!) // re-init to clear state

        // Binder override cleanup
        try {
            com.aether.Engine.restoreBinderCallingPidOverride(0)
            com.aether.Engine.restoreBinderCallingUidOverride(0)
            Log.d(TAG, "Binder PID/UID overrides restored")
        } catch (e: Throwable) {
            Log.w(TAG, "Binder cleanup: ${e.message}")
        }

        // 4. Reset counters
        readCount = 0
        scanCount = 0

        isInitialized.set(false)
        Log.i(TAG, "Orchestrator shutdown complete")
    }

    // ══════════════════════════════════════════
    //  Data Classes
    // ══════════════════════════════════════════

    data class EngineStats(
        val initialized: Boolean,
        val attached: Boolean,
        val running: Boolean,
        val targetPid: Int,
        val targetModule: String,
        val uptimeMs: Long,
        val readCount: Long,
        val scanCount: Long,
        val serviceProxyCount: Int
    )
}
