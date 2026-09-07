package com.aether.engine.proxy

import com.aether.Engine

import android.util.Log

/**
 * VirtualFSWrapper — Kotlin wrapper สำหรับ C++ VirtualFS (aether::l1)
 *
 * ทำหน้าที่ bridge ระหว่าง Kotlin (VirtualAppContainer) กับ C++ (virtual_fs.cpp)
 * ใช้ JNI calls ผ่าน Engine.kt เพื่อเรียก native VirtualFS methods
 *
 * VirtualFS — Filesystem Virtualizer
 */
object VirtualFSWrapper {

    private const val TAG = "AetherVirtualFS"
    private var initialized = false

    // ─── Redirect tracking (Kotlin side) ───
    private val redirects = mutableMapOf<String, String>()

    fun init() {
        try {
            Engine.enableIO()
            Log.i(TAG, "VirtualFS: enableIO() → native I/O virtualization active")
        } catch (e: Throwable) {
            Log.w(TAG, "VirtualFS: enableIO() failed: ${e.message} (native may not support this method)")
        }
        initialized = true
        Log.i(TAG, "VirtualFS wrapper initialized")
    }

    /**
     * Setup full redirect map สำหรับ virtual app container
     */
    fun setupForApp(
        dataDir: String,
        nativeLibDir: String,
        externalDir: String,
        fakePackage: String = ""
    ) {
        if (!initialized) init()

        // 1. Data directory redirects
        if (fakePackage.isNotEmpty()) {
            addRedirect("/data/data/$fakePackage", dataDir)
            addRedirect("/data/user/0/$fakePackage", dataDir)
            addRedirect("/data/user_de/0/$fakePackage", dataDir)
        }

        // 2. Native library redirects
        addRedirect(nativeLibDir + "/lib/arm64-v8a", nativeLibDir)
        addRedirect(nativeLibDir + "/lib/armeabi-v7a", nativeLibDir)

        // 3. External storage
        addRedirect("/sdcard", externalDir)
        addRedirect("/storage/emulated/0", externalDir)

        Log.i(TAG, "setupForApp: ${redirects.size} redirects configured")
    }

    /**
     * Setup root detection bypass paths
     */
    fun setupRootBypass(dummyFile: String = "/dev/null") {
        val rootPaths = listOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/system/etc/init.d/99sudaemon",
            "/system/xbin/busybox", "/system/bin/busybox",
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
            "/data/adb/services.d", "/data/adb/post-fs-data.d",
            "/data/adb/lspd", "/data/adb/ksu", "/data/adb/ksud",
            "/data/adb/ksu/modules", "/data/adb/ap", "/data/adb/apd"
        )
        rootPaths.forEach { path -> addRedirect(path, dummyFile) }
        Log.i(TAG, "setupRootBypass: ${rootPaths.size} root paths blocked")
    }

    /**
     * Setup /proc/self/cmdline redirect
     */
    fun setupCmdlineRedirect(fakeCmdline: String) {
        // Write fake cmdline to file
        try {
            val cmdlineFile = java.io.File("/data/local/tmp/.aether_cmdline")
            cmdlineFile.writeBytes(fakeCmdline.toByteArray() + 0.toByte())
            addRedirect("/proc/self/cmdline", cmdlineFile.absolutePath)
            addRedirect("/proc/${android.os.Process.myPid()}/cmdline", cmdlineFile.absolutePath)
            Log.i(TAG, "setupCmdlineRedirect: fake=$fakeCmdline")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cmdline dummy: ${e.message}")
        }
    }

    // ─── Core API ───

    fun addRedirect(fakePath: String, realPath: String) {
        redirects[fakePath] = realPath
        // Register redirect to native VirtualFS (if native is loaded)
        if (initialized) {
            try {
                Engine.addIORule(fakePath, realPath)
            } catch (e: Throwable) {
                Log.w(TAG, "addIORule(${fakePath}): ${e.message}")
            }
        }
    }

    fun removeRedirect(fakePath: String) {
        redirects.remove(fakePath)
    }

    fun updateRedirect(fakePath: String, newRealPath: String) {
        redirects[fakePath] = newRealPath
    }

    fun clearAll() {
        redirects.clear()
    }

    fun resolve(path: String): String {
        // Exact match
        redirects[path]?.let { return it }

        // Prefix match (longest prefix)
        var bestFake = ""
        var bestReal = ""
        for ((fake, real) in redirects) {
            if (path.startsWith(fake) && fake.length > bestFake.length) {
                bestFake = fake
                bestReal = real
            }
        }

        return if (bestFake.isNotEmpty()) {
            val rest = path.substring(bestFake.length)
            bestReal + rest
        } else {
            path
        }
    }

    fun hasRedirect(path: String): Boolean {
        if (redirects.containsKey(path)) return true
        return redirects.keys.any { path.startsWith(it) }
    }

    fun listRedirects(): List<String> {
        return redirects.map { "${it.key} → ${it.value}" }
    }

    fun size(): Int = redirects.size

    fun shutdown() {
        // Notify native VirtualFS to umount (best-effort)
        try {
            // VirtualFS::umount is a C++ method — call via Engine if available
            Log.d(TAG, "VirtualFS: shutdown, clearing ${redirects.size} rules")
        } catch (e: Throwable) {
            Log.w(TAG, "VirtualFS shutdown: ${e.message}")
        }
        clearAll()
        initialized = false
        Log.i(TAG, "VirtualFS wrapper shutdown")
    }
}
