package com.aether.engine.proxy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.aether.SandboxManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CrashHandler — AetherEngine Crash Logger (ครอบคลุม 14 ส่วน)
 *
 * จัดการ exception logging แล้วเขียนไฟล์ crash_<timestamp>.log:
 * 1. Header (timestamp, build, device, ABI, fingerprint)
 * 2. App Info (package, version, data dir)
 * 3. Process Info (PID, TID, thread, priority)
 * 4. Memory (heap + native + vm)
 * 5. Disk (internal/external)
 * 6. Battery (level, status, plugged)
 * 7. Network (connectivity type, operator)
 * 8. Locale & Time (locale, TZ, uptime, boot time)
 * 9. System Services (active procs, services count)
 * 10. Engine Stats (AetherOrchestrator state)
 * 11. Sandbox State (vision/ tree, package.conf, pgl, vdex)
 * 12. Permissions (granted)
 * 13. Installed Apps (count + sample)
 * 14. Exception (stack + cause chain + thread dump)
 *
 * แล้ว upload ไป server แบบ async (best-effort)
 */
object CrashHandler {

    private const val TAG = "AetherCrashHandler"
    private const val CRASH_DIR_NAME = "crash_logs"
    private const val MAX_CRASH_FILES = 50

    private val isInstalled = AtomicBoolean(false)
    private var crashDir: File? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    // ══════════════════════════════════════════
    //  Installation
    // ══════════════════════════════════════════

    fun install(context: Context, crashDir: File? = null): Boolean {
        if (isInstalled.get()) {
            Log.w(TAG, "Already installed")
            return true
        }
        appContext = context.applicationContext
        this.crashDir = crashDir ?: File(appContext?.filesDir, CRASH_DIR_NAME)
        this.crashDir?.mkdirs()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleException(thread, throwable)
        }
        isInstalled.set(true)
        Log.i(TAG, "Crash handler installed: ${this.crashDir?.absolutePath}")
        return true
    }

    fun uninstall() {
        if (!isInstalled.get()) return
        originalHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        isInstalled.set(false)
    }

    // ══════════════════════════════════════════
    //  Exception Handling
    // ══════════════════════════════════════════

    private fun handleException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildCrashReport(thread, throwable)
            val crashFile = writeCrashReport(report)
            Log.e(TAG, "Crash logged to: ${crashFile?.absolutePath}")
            crashFile?.let { sendCrashReport(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle crash: ${e.message}")
        }
        originalHandler?.uncaughtException(thread, throwable)
    }

    // ══════════════════════════════════════════
    //  Report Building — 14 sections
    // ══════════════════════════════════════════

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        val ctx = appContext
        val pkgName = ctx?.packageName ?: "unknown"

        // ═══ Section 1: Header ═══
        sb.appendLine("=== AETHER CRASH REPORT ===")
        sb.appendLine("Report Version: 2.0 (14 sections)")
        sb.appendLine("Captured at: ${timeFormat.format(Date())}")
        sb.appendLine("Timestamp: ${dateFormat.format(Date())} (epoch=${System.currentTimeMillis()})")
        sb.appendLine("Build: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT} ${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, codename=${Build.VERSION.CODENAME})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine("Build Type: ${Build.TYPE} (debuggable=${(ctx?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0})")
        sb.appendLine()

        // ═══ Section 2: App Info ═══
        if (ctx != null) {
            sb.appendLine("═══ [2] APP INFO ═══")
            sb.appendLine("Package: $pkgName")
            try {
                val pm = ctx.packageManager
                val pkgInfo = pm.getPackageInfo(pkgName, 0)
                sb.appendLine("Version: ${pkgInfo.versionName} (code ${pkgInfo.longVersionCode})")
                val appInfo = ctx.applicationInfo
                sb.appendLine("Target SDK: ${appInfo.targetSdkVersion}")
                sb.appendLine("Min SDK: ${appInfo.minSdkVersion}")
                sb.appendLine("Compile SDK: ${appInfo.compileSdkVersion}")
                sb.appendLine("App Dir: ${appInfo.dataDir}")
                sb.appendLine("Source Dir: ${appInfo.sourceDir}")
                sb.appendLine("Native Lib Dir: ${appInfo.nativeLibraryDir}")
                sb.appendLine("UID: ${appInfo.uid}, Label: ${pm.getApplicationLabel(appInfo)}")
                val firstInstall = pkgInfo.firstInstallTime?.let { dateFormat.format(Date(it)) } ?: "?"
                val lastUpdate = pkgInfo.lastUpdateTime?.let { dateFormat.format(Date(it)) } ?: "?"
                sb.appendLine("First Install: $firstInstall")
                sb.appendLine("Last Update: $lastUpdate")
            } catch (e: Exception) {
                sb.appendLine("Package info error: ${e.message}")
            }
            sb.appendLine()
        }

        // ═══ Section 3: Process Info ═══
        sb.appendLine("═══ [3] PROCESS INFO ═══")
        sb.appendLine("PID: ${android.os.Process.myPid()}")
        sb.appendLine("TID: ${thread.id} (${thread.name})")
        sb.appendLine("State: ${thread.state.name} (priority=${thread.priority}, daemon=${thread.isDaemon})")
        sb.appendLine("Group: ${thread.threadGroup?.name}")
        sb.appendLine("Uncaught in thread: ${thread.name}")
        sb.appendLine()

        // ═══ Section 4: Memory ═══
        sb.appendLine("═══ [4] MEMORY (bytes) ═══")
        val rt = Runtime.getRuntime()
        val totalMem = rt.totalMemory(); val freeMem = rt.freeMemory()
        val maxMem = rt.maxMemory()
        val nativeAlloc = Debug.getNativeHeapAllocatedSize()
        val nativeSize = Debug.getNativeHeapSize()
        sb.appendLine("Java Heap: total=${totalMem} free=${freeMem} used=${totalMem - freeMem} max=${maxMem}")
        sb.appendLine("Native Heap: allocated=${nativeAlloc} size=${nativeSize}")
        try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            sb.appendLine("Pss: totalPss=${mi.totalPss}KB privateDirty=${mi.totalPrivateDirty}KB")
        } catch (_: Exception) {}
        sb.appendLine()

        // ═══ Section 5: Disk ═══
        sb.appendLine("═══ [5] DISK (bytes) ═══")
        try {
            val dataDir = ctx?.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(dataDir.absolutePath)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availBlocks = stat.availableBlocksLong
            sb.appendLine("Internal: dataDir=${dataDir.absolutePath}")
            sb.appendLine("  total=${blockSize * totalBlocks} free=${blockSize * availBlocks} (${availBlocks}/${totalBlocks} blocks)")
        } catch (e: Exception) { sb.appendLine("Internal storage error: ${e.message}") }
        try {
            val extStat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val extBlock = extStat.blockSizeLong
            val extTotal = extStat.blockCountLong
            val extAvail = extStat.availableBlocksLong
            sb.appendLine("External: total=${extBlock * extTotal} free=${extBlock * extAvail}")
        } catch (e: Exception) { sb.appendLine("External storage N/A") }
        sb.appendLine()

        // ═══ Section 6: Battery ═══
        sb.appendLine("═══ [6] BATTERY ═══")
        if (ctx != null) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = ctx.registerReceiver(null, filter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            sb.appendLine("Level: ${level}/${scale} (${pct}%)")
            sb.appendLine("Status: ${statusName(status)}")
            sb.appendLine("Plugged: ${plugName(plugged)}")
            sb.appendLine("Temperature: ${temperature}°C (${temperature / 10.0}°C)")
        } else { sb.appendLine("(no context)") }
        sb.appendLine()

        // ═══ Section 7: Network ═══
        sb.appendLine("═══ [7] NETWORK ═══")
        if (ctx != null) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val net = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(net)
                if (caps != null) {
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    sb.appendLine("Active: ${if (wifi) "WiFi " else ""}${if (cellular) "Cellular " else ""}${if (ethernet) "Ethernet " else ""}${if (vpn) "VPN" else ""}".trim())
                    sb.appendLine("Validated: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                    sb.appendLine("Internet: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
                } else {
                    sb.appendLine("Active: none")
                }
            } catch (e: Exception) { sb.appendLine("Network error: ${e.message}") }
        } else { sb.appendLine("(no context)") }
        sb.appendLine()

        // ═══ Section 8: Locale & Time ═══
        sb.appendLine("═══ [8] LOCALE & TIME ═══")
        sb.appendLine("Locale: ${Locale.getDefault()} (country=${Locale.getDefault().country})")
        sb.appendLine("Timezone: ${java.util.TimeZone.getDefault().id}")
        sb.appendLine("Uptime: ${formatDuration(SystemClock.uptimeMillis())}")
        sb.appendLine("ElapsedRealtime: ${formatDuration(SystemClock.elapsedRealtime())}")
        sb.appendLine("")

        // ═══ Section 9: System Services ═══
        sb.appendLine("═══ [9] SYSTEM SERVICES ═══")
        if (ctx != null) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                sb.appendLine("System Memory: avail=${memInfo.availMem} total=${memInfo.totalMem} lowMemory=${memInfo.lowMemory}")
                sb.appendLine("Processes: ${if (Build.VERSION.SDK_INT >= 28) "<see dumpsys>" else am.runningAppProcesses.size}")
            } catch (e: Exception) { sb.appendLine("Activity service error: ${e.message}") }
        }
        sb.appendLine()

        // ═══ Section 10: Engine Stats ═══
        sb.appendLine("═══ [10] AETHERENGINE STATS ═══")
        if (AetherOrchestrator.isInitialized()) {
            val stats = AetherOrchestrator.getStats()
            sb.appendLine("Initialized: ${stats.initialized}")
            sb.appendLine("Attached: ${stats.attached}")
            sb.appendLine("Running: ${stats.running}")
            sb.appendLine("Target PID: ${stats.targetPid}")
            sb.appendLine("Target Module: ${stats.targetModule}")
            sb.appendLine("Uptime: ${formatDuration(stats.uptimeMs)}")
            sb.appendLine("Read count: ${stats.readCount}")
            sb.appendLine("Scan count: ${stats.scanCount}")
            sb.appendLine("ART Hooks: 0")  // was stats.artHookCount (field removed in V3)
            sb.appendLine("Service Proxies: ${stats.serviceProxyCount}")
        } else { sb.appendLine("Engine not initialized") }
        sb.appendLine()

        // ═══ Section 11: Sandbox State ═══
        sb.appendLine("═══ [11] SANDBOX STATE ═══")
        try {
            val visionDir = SandboxManager.getSandboxRoot() ?: File(ctx?.dataDir, "vision")
            if (visionDir.exists()) {
                val allFiles = visionDir.walkTopDown().filter { it.isFile }.toList()
                // sandbox now holds the TARGET (guest) tree, keyed by target pkg.
                val target = com.aether.engine.proxy.VirtualAppContainer.getFakePackageName()
                val packageConf = File(visionDir, "data/app/$target/package.conf")
                val pglDir = File(visionDir, "data/user/0/$target/a0rjgdfbjd8fhfglkew6")
                val oatDir = File(visionDir, "oat/arm64")
                sb.appendLine("vision/ root: ${visionDir.absolutePath}")
                sb.appendLine("Total files: ${allFiles.size}")
                sb.appendLine("package.conf: ${if (packageConf.exists()) "exists (${packageConf.length()}B)" else "missing"}")
                sb.appendLine("PGL libs: ${if (pglDir.exists()) {
                    pglDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }
                        .joinToString(", ") { "${it.name} (${it.length()}B)" }
                } else "missing"}")
                sb.appendLine("vdex stubs: ${if (oatDir.exists()) {
                    oatDir.listFiles()?.size ?: 0
                } else 0} files in oat/arm64/")
            } else {
                sb.appendLine("vision/ NOT created (sandbox not bootstrapped yet)")
            }
        } catch (e: Exception) { sb.appendLine("Sandbox state error: ${e.message}") }
        sb.appendLine()

        // ═══ Section 12: Permissions ═══
        sb.appendLine("═══ [12] RUNTIME PERMISSIONS ═══")
        if (ctx != null) {
            val criticalPerms = arrayOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_PHONE_STATE",
                "android.permission.QUERY_ALL_PACKAGES",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
                "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            )
            for (perm in criticalPerms) {
                val granted = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                sb.appendLine("  ${if (granted) "[GRANTED] " else "[denied]  "} $perm")
            }
        }
        sb.appendLine()

        // ═══ Section 13: Installed Apps ═══
        sb.appendLine("═══ [13] INSTALLED APPS (sample) ═══")
        if (ctx != null) {
            try {
                val pm = ctx.packageManager
                val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                sb.appendLine("Total: ${allApps.size} apps")
                // Phase 1+2: in-process. Look up engine's own package, not external game.
                val target = allApps.find { it.packageName == "com.aether" }
                if (target != null) {
                    val tInfo = pm.getPackageInfo(target.packageName, 0)
                    sb.appendLine("ENGINE PKG FOUND: ${target.packageName} v${tInfo.versionName}")
                    sb.appendLine("  dataDir=${target.dataDir} sourceDir=${target.sourceDir}")
                } else {
                    sb.appendLine("ENGINE PKG MISSING: com.aether NOT installed (unexpected)")
                }
                // ตัวอย่าง apps ที่น่าสนใจ (Phase 1+2: removed 8 Ball Pool from list — engine is in-process)
                val interesting = listOf(
                    "com.facebook.katana", "com.facebook.lite",
                    "com.google.android.gms", "com.google.android.gsf",
                    "com.aether", "com.android.vending"
                )
                for (pname in interesting) {
                    val app = allApps.find { it.packageName == pname }
                    if (app != null) {
                        try {
                            val info = pm.getPackageInfo(pname, 0)
                            sb.appendLine("  [x] $pname v${info.versionName}")
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { sb.appendLine("Apps error: ${e.message}") }
        }
        sb.appendLine()

        // ═══ Section 14: Exception ═══
        sb.appendLine("═══ [14] EXCEPTION ═══")
        sb.appendLine("Type: ${throwable.javaClass.name}")
        sb.appendLine("Message: ${throwable.message ?: "(none)"}")
        sb.appendLine()
        sb.appendLine("Stack Trace:")
        for (element in throwable.stackTrace) {
            sb.appendLine("  at $element")
        }
        sb.appendLine()
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.appendLine("Caused by [depth=$depth]: ${cause.javaClass.name}: ${cause.message}")
            for (element in cause.stackTrace.take(10)) {
                sb.appendLine("  at $element")
            }
            sb.appendLine()
            cause = cause.cause
            depth++
        }

        sb.appendLine("==========================")
        sb.appendLine("END OF REPORT")
        sb.appendLine("==========================")
        return sb.toString()
    }

    private fun statusName(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        else -> "?($s)"
    }

    private fun plugName(p: Int): String = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        0 -> "BATTERY"
        else -> "?($p)"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "${h}h${m}m${sec}s" else if (m > 0) "${m}m${sec}s" else "${sec}s"
    }

    // ══════════════════════════════════════════
    //  File Operations
    // ══════════════════════════════════════════

    private fun writeCrashReport(report: String): File? {
        return try {
            val timestamp = System.currentTimeMillis()
            val crashFile = File(crashDir, "crash_${timestamp}.log")
            crashFile.writeText(report)
            cleanupOldCrashes()
            crashFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report: ${e.message}")
            null
        }
    }

    private fun cleanupOldCrashes() {
        try {
            val crashFiles = crashDir?.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (crashFiles.size > MAX_CRASH_FILES) {
                crashFiles.drop(MAX_CRASH_FILES).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup crash files: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    //  Server Reporting (disabled — no remote endpoint)
    // ══════════════════════════════════════════

    private fun sendCrashReport(crashFile: File) {
        // Crash log เก็บใน local file เท่านั้น (Aether ไม่มี remote crash server)
        // Caller (handleException) log "Crash logged to: <path>" ให้แล้ว
        // ถ้าต้อง upload ในอนาคต เพิ่ม HttpURLConnection POST ที่นี่
        Log.i(TAG, "sendCrashReport: no-op (local file only) — ${crashFile.name}")
    }

    // ══════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════

    fun getCrashLogs(): List<File> =
        crashDir?.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun getLatestCrashLog(): String? = getCrashLogs().firstOrNull()?.readText()

    fun clearCrashLogs() {
        crashDir?.listFiles()?.filter { it.name.startsWith("crash_") }?.forEach { it.delete() }
    }

    fun getCrashDir(): File? = crashDir
}
