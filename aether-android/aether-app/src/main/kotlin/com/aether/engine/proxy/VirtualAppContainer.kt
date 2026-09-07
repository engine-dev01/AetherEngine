package com.aether.engine.proxy

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.aether.SandboxManager
import java.io.File

/**
 * VirtualAppContainer — Virtual App Container (virtual container)
 *
 * สร้าง fake Application context สำหรับ virtual app:
 * 1. Fake package name (override getPackageName())
 * 2. Fake ApplicationInfo (override sourceDir, nativeLibraryDir)
 * 3. Override PackageManager queries (getApplicationInfo, getPackageInfo)
 * 4. Override ServiceManager lookups (ผ่าน ServiceBinderProxy)
 *
 * ใช้คู่กับ:
 * - VirtualFS (path redirect)
 * - ServiceBinderProxy (service interception)
 * - ArtHookEngine removed (was no-op)
 *
 * วิธีใช้:
 * 1. VirtualAppContainer.init(realContext, fakePackage)
 * 2. VirtualAppContainer.setup() — hook everything
 * 3. ใช้ fakeContext แทน realContext ทุกที่
 */
object VirtualAppContainer {

    private const val TAG = "AetherVirtualApp"

    // ─── State ───
    private var isInitialized = false
    private var realContext: Context? = null
    private var fakePackageName: String = ""
    private var realPackageName: String = ""

    // ─── Fake Application ───
    private var fakeApplication: Application? = null
    private var fakeApplicationInfo: ApplicationInfo? = null

    // ─── Virtual FS ───
    // VirtualFS — use Kotlin wrapper (C++ VirtualFS is native layer, not directly accessible from Kotlin)
    private val virtualFS = VirtualFSWrapper

    // ══════════════════════════════════════════
    //  Initialization
    // ══════════════════════════════════════════

    /**
     * Initialize virtual app container
     * @param context — Real application context
     * @param fakePkg — Package name ปลอม (ถ้าต้องการ override)
     */
    fun init(context: Context, fakePkg: String = "") {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        realContext = context.applicationContext
        realPackageName = context.packageName
        fakePackageName = if (fakePkg.isNotEmpty()) fakePkg else realPackageName

        // 1. Setup VirtualFS paths
        setupVirtualFS()

        // 2. Create fake ApplicationInfo
        createFakeApplicationInfo()

        // 3. Create fake Application context
        createFakeApplication()

        isInitialized = true
        Log.i(TAG, "Initialized: real=$realPackageName → fake=$fakePackageName")
    }

    /**
     * Setup everything — hook methods, register proxies
     */
    fun setup() {
        if (!isInitialized) {
            Log.e(TAG, "Not initialized")
            return
        }

        // 1. Setup Service Binder Proxies
        val context = realContext ?: return
        ServiceBinderProxy.init(context, fakePackageName)

        // 2. Setup ART Hook Engine (ถ้าต้องการ hook methods)
        // ArtHookEngine removed (was no-op)

        // 3. Register redirect rules into native (only in virtual-target mode).
        //    Path rules: forward VirtualFS map → Engine.addIORule so native IO
        //    layer resolves guest paths into the sandbox.
        //    Class rules: seed the loadClass registry (NATIVE_LOGIC.md §B) so
        //    JniHook custom_loadClass can redirect guest class lookups.
        if (isVirtualTarget()) {
            registerNativeRules()
        }

        Log.i(TAG, "Setup complete: ${ServiceBinderProxy.listProxiedServices().size} proxies active")
    }

    /**
     * Push VirtualFS path map + class map into the native engine.
     * Idempotent: clears the class registry first so re-setup does not
     * accumulate stale entries.
     */
    private fun registerNativeRules() {
        try {
            com.aether.Engine.enableIO()
            virtualFS.listRedirects().forEach { rule ->
                val parts = rule.split(" → ")
                if (parts.size == 2) {
                    com.aether.Engine.addIORule(parts[0], parts[1])
                }
            }
            Log.d(TAG, "registerNativeRules: ${virtualFS.size()} path rules registered")
        } catch (e: Throwable) {
            Log.w(TAG, "registerNativeRules (IO): ${e.message}")
        }
        try {
            com.aether.Engine.clearClassRules()
            // Guest components resolve against the target's own package; the
            // engine maps them 1:1 (slashed target class) so loadClass returns
            // the guest class from the sandbox classloader rather than the host.
            classRules().forEach { (requested, target) ->
                com.aether.Engine.addClassRule(requested, target)
            }
            Log.d(TAG, "registerNativeRules: ${com.aether.Engine.classRuleCount()} class rules active")
        } catch (e: Throwable) {
            Log.w(TAG, "registerNativeRules (class): ${e.message}")
        }
    }

    /**
     * Class redirect entries (dotted requested → slashed target), discovered
     * from the target's package.conf manifest snapshot. Each guest component
     * (Application + activities/services/providers/receivers) maps 1:1 so
     * loadClass resolves the guest class from the sandbox rather than the host.
     * Falls back to the launcher/Application identity if package.conf is
     * unavailable.
     */
    private fun classRules(): List<Pair<String, String>> {
        if (!isVirtualTarget()) return emptyList()
        val rules = LinkedHashSet<String>()

        // 1. discover from package.conf (source of truth per PROVISIONING doc)
        try {
            val confFile = File(
                SandboxManager.getSandboxRoot(),
                "data/app/$fakePackageName/package.conf"
            )
            if (confFile.exists() && confFile.length() >= 32) {
                val conf = com.aether.PackageConfParser.parse(confFile.readBytes())
                rules.addAll(conf.allComponents())
                Log.d(TAG, "classRules: ${rules.size} components from package.conf")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "classRules: package.conf parse failed: ${e.message}")
        }

        // 2. fallback identity if package.conf yielded nothing
        if (rules.isEmpty()) {
            rules.add("$fakePackageName.Application")
        }

        // dotted requested → slashed target (1:1 self-resolve)
        return rules.map { it to it.replace('.', '/') }
    }

    /**
     * คืน fake Context สำหรับใช้แทน real context
     */
    fun getFakeContext(): Context? {
        return fakeApplication ?: realContext
    }

    /**
     * คืน fake package name
     */
    fun getFakePackageName(): String = fakePackageName

    /**
     * คืน real package name
     */
    fun getRealPackageName(): String = realPackageName

    /**
     * คืน fake ApplicationInfo
     */
    fun getFakeApplicationInfo(): ApplicationInfo? = fakeApplicationInfo

    /**
     * คืน VirtualFS instance (สำหรับ manual path redirect)
     */
    fun getVirtualFS(): VirtualFSWrapper = virtualFS

    /**
     * ตรวจสอบว่า initialized หรือไม่
     */
    fun isReady(): Boolean = isInitialized

    /**
     * true เมื่อ fake package ต่างจาก real (โหมด virtual app จริง — โหลด target
     * อื่นเข้ามารันใน sandbox). false = self/host mode (fake == real).
     */
    fun isVirtualTarget(): Boolean =
        fakePackageName.isNotEmpty() && fakePackageName != realPackageName

    // ══════════════════════════════════════════
    //  Phase 3.5.A: Virtual App state surface
    //  (used by EngineBridge.getVirtualAppStatus for Flutter UI)
    // ══════════════════════════════════════════

    /** นับจำนวน VirtualFS redirects ที่ active (path binding) */
    fun getRedirectCount(): Int = virtualFS.size()

    /** นับจำนวน service proxies ที่ active */
    fun getServiceProxyCount(): Int = ServiceBinderProxy.listProxiedServices().size

    /** ทดสอบ VirtualFS: resolve fakePkg path → ควรคืน real dataDir */
    fun testVirtualFSResolve(): String {
        val testPath = "/data/user/0/$fakePackageName/files/test.txt"
        val resolved = virtualFS.resolve(testPath)
        return if (resolved != testPath && resolved.isNotEmpty()) {
            "OK: $testPath → $resolved"
        } else {
            "NO REDIRECT: $testPath"
        }
    }

    // ══════════════════════════════════════════
    //  Path Resolution
    // ══════════════════════════════════════════

    /**
     * Resolve path ผ่าน VirtualFS
     * ใช้แทน context.getFilesDir().path เมื่อต้องการ redirect
     */
    fun resolvePath(path: String): String {
        return virtualFS.resolve(path)
    }

    /**
     * คืน fake data directory
     */
    fun getFakeDataDir(): String {
        return "/data/data/$fakePackageName"
    }

    /**
     * คืน fake native library directory
     */
    fun getFakeNativeLibDir(): String {
        return "/data/app/~~${fakePackageName}/lib/arm64"
    }

    // ══════════════════════════════════════════
    //  Package Query (for hooked methods)
    // ══════════════════════════════════════════

    /**
     * Override getApplicationInfo — return fake ApplicationInfo
     */
    fun overrideApplicationInfo(packageName: String): ApplicationInfo? {
        if (packageName == realPackageName || packageName == fakePackageName) {
            return fakeApplicationInfo
        }
        // สำหรับ package อื่น — delegate ไป real PackageManager
        return realContext?.packageManager?.getApplicationInfo(packageName, 0)
    }

    /**
     * Override getPackageName — return fake package name
     */
    fun overridePackageName(): String {
        return fakePackageName
    }

    /**
     * Override sourceDir — return fake path
     */
    fun overrideSourceDir(): String {
        return fakeApplicationInfo?.sourceDir ?: ""
    }

    /**
     * Override nativeLibraryDir — return fake path
     */
    fun overrideNativeLibDir(): String {
        return fakeApplicationInfo?.nativeLibraryDir ?: ""
    }

    // ══════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════

    /**
     * Clear all state — เรียกตอน engine shutdown
     */
    fun shutdown() {
        ServiceBinderProxy.shutdown()
        // ArtHookEngine.unhookAll() removed (was no-op)
        virtualFS.clearAll()
        fakeApplication = null
        fakeApplicationInfo = null
        isInitialized = false
        Log.i(TAG, "Shutdown complete")
    }

    // ══════════════════════════════════════════
    //  Internal: VirtualFS Setup
    // ══════════════════════════════════════════

    private fun setupVirtualFS() {
        val context = realContext ?: return

        // 1. Data directory redirects
        // When a real target is set (fake != real), redirect the target's
        // data paths INTO the engine sandbox (vision/data/user/0/<target>) so
        // the guest reads sandbox data, not its own real install. When fake ==
        // real (self/host mode) fall back to the host's own dataDir.
        val hostDataDir = context.dataDir?.absolutePath ?: "/data/data/$realPackageName"
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val externalDir = context.getExternalFilesDir(null)?.parent ?: "/sdcard"

        val dataDir = if (isVirtualTarget()) {
            val sandboxTargetDir = File(
                SandboxManager.getSandboxRoot(),
                "data/user/0/$fakePackageName"
            )
            sandboxTargetDir.mkdirs()
            sandboxTargetDir.absolutePath
        } else {
            hostDataDir
        }

        virtualFS.setupForApp(dataDir, nativeLibDir, externalDir, fakePackageName)

        // 2. Root detection bypass
        virtualFS.setupRootBypass()

        // 3. Cmdline redirect
        virtualFS.setupCmdlineRedirect(fakePackageName)

        Log.d(TAG, "VirtualFS setup complete: ${virtualFS.listRedirects().size} redirects (target=$fakePackageName)")
    }

    // ══════════════════════════════════════════
    //  Internal: Fake ApplicationInfo
    // ══════════════════════════════════════════

    private fun createFakeApplicationInfo() {
        val context = realContext ?: return
        val realInfo = context.applicationInfo

        // Clone real ApplicationInfo
        fakeApplicationInfo = ApplicationInfo().apply {
            packageName = fakePackageName
            sourceDir = realInfo.sourceDir.replace(realPackageName, fakePackageName)
            nativeLibraryDir = realInfo.nativeLibraryDir.replace(realPackageName, fakePackageName)
            dataDir = realInfo.dataDir?.replace(realPackageName, fakePackageName)
            uid = realInfo.uid
            flags = realInfo.flags
            targetSdkVersion = realInfo.targetSdkVersion
            minSdkVersion = realInfo.minSdkVersion
        }

        Log.d(TAG, "Fake ApplicationInfo created: sourceDir=${fakeApplicationInfo?.sourceDir}")
    }

    // ══════════════════════════════════════════
    //  Internal: Fake Application Context
    // ══════════════════════════════════════════

    private fun createFakeApplication() {
        val context = realContext ?: return
        // Application is a concrete class, not an interface — java.lang.reflect.Proxy
        // cannot proxy it (that threw "Application is not an interface"). Use the
        // REAL host Application as the fake app context holder; package identity is
        // overridden separately via getFakePackageName()/overrideApplicationInfo().
        // The guest's own Application is created + context-bound by VirtualAppLoader
        // (newApplication + attachBaseContext), so we do NOT need a Proxy here.
        fakeApplication = context as? Application
        if (fakeApplication != null) {
            Log.d(TAG, "fakeApplication bound to host Application (${fakeApplication?.javaClass?.name})")
        } else {
            Log.w(TAG, "realContext is not an Application — fakeApplication null")
        }
    }
}
