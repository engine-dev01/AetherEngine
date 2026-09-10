package com.aether

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Mirror Sandbox — Aether-aligned virtual data storage.
 *
 * เส้นทาง sandbox root: /data/user/0/com.aether/vision/
 * (ย้ายออกจาก files/ มาไว้ที่ dataDir root ตรง — /data/user/0/com.aether/vision)
 *
 * โครงสร้าง (ตาม blueprint จาก data dump analysis):
 *   vision/
 *   ├── data/app/<pkg>/                    ← package.conf (manifest snapshot)
 *   ├── data/user/0/<pkg>/
 *   │   ├── a0rjgdfbjd8fhfglkew6/<ver>/arm64-v8a/  ← PGL modules
 *   │   ├── 706d4946...<hash>/ed9d...      ← encrypted device token
 *   │   ├── j9g29zqf0cfqd3vvu2bw/          ← profile/cache dir
 *   │   ├── files/ shared_prefs/ databases/ cache/ no_backup/ code_cache/
 *   │   ├── app_textures/ app_webview_0:.../
 *   ├── data/user_de/0/<pkg>/
 *   ├── proc/0/cmdline                     ← fake (ชี้ไป pkg)
 *   └── system/uid.conf, user.conf, shared-user.conf
 */
object SandboxManager {

    private const val TAG = "AetherSandbox"
    private var appContext: Context? = null
    private var sandboxRoot: File? = null
    // Phase 1+2: in-process only. targetPkg is now the engine's own
    // package (com.aether), not an external game. We keep the bootstrap
    // path (vision/data/user/0/...) because VirtualFS uses it, but
    // no external package is read/written.
    private var targetPkg = "com.aether"
    private var targetUid: Int = -1

 /** PGL version hash — ตรงกับ blueprint the reference engine (90d8aa15a2de2cb4) */
    private const val DEFAULT_PGL_VERSION = "90d8aa15a2de2cb4"
    private const val PGL_DIR_HASH = "a0rjgdfbjd8fhfglkew6"

    // PGL hash dirs from real game dumps (56.23.2 → 90d8aa..., 56.29.1 → 9e75dd...)
    // Hash เปลี่ยนตามเวอร์ชันเกมที่ติดตั้ง — ใช้ resolvePglVersions() อ่านสดจากเครื่อง
    // (รายการนี้เป็นแค่ fallback เมื่อไม่มีสิทธิ์ list dir จริง)
    private val KNOWN_PGL_VERSIONS = listOf(
        "9e75dd17d258d07f",  // 8BP 56.29.1 (code 4013) — dump เกมจริง 2026-09-11
        "90d8aa15a2de2cb4",  // 8BP 56.23.2 (code 3965) — blueprint เดิม
    )

 /** 706d494674354b747939547a3839354b4e43626776773d3d = base64-ish hash dir (จาก data dump) */
    private val ENC_DIR_BASE = "706d494674354b747939547a3839354b4e43626776773d3d"
    private const val ENC_TOKEN_FILE = "ed9d0e2eaae14a4bba0f853a071cd8d2"

 /** j9g29zqf0cfqd3vvu2bw — profile cache dir (จาก data dump) */
    private const val CACHE_DIR_HASH = "j9g29zqf0cfqd3vvu2bw"

    /**
     * Resolve PGL version dir(s) ตามเกมเวอร์ชันที่ผู้ใช้ติดตั้งจริง.
     * อ่านสดจาก /data/user/0/<pkg>/a0rjgdfbjd8fhfglkew6/*/ — ถ้า list ได้
     * ใช้ของจริง (support ทุกเวอร์ชัน 56.23.2 → 56.29.1+); ถ้าไม่มีสิทธิ์
     * fallback ไป KNOWN_PGL_VERSIONS (ใหม่สุดก่อน). คืนค่า list เพื่อให้
     * caller sync ทุกเวอร์ชันที่มี (เผื่อเกมอ่านหลายชั้น).
     */
    fun resolvePglVersions(targetPkg: String = this.targetPkg): List<String> {
        val base = File("/data/user/0/$targetPkg/$PGL_DIR_HASH")
        val found = try {
            base.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending()
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        if (found.isNotEmpty()) return found
        // fallback: ใช้ known list (ใหม่สุดก่อน == 9e75dd... สำหรับ 56.29.1)
        return KNOWN_PGL_VERSIONS
    }

    /** ปกติใช้รุ่นแรกของ resolvePglVersions() (รุ่นที่ติดตั้งจริง/ใหม่สุด) */
    private fun resolvePglVersion(targetPkg: String): String {
        return resolvePglVersions(targetPkg).firstOrNull() ?: DEFAULT_PGL_VERSION
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // sandbox root ย้ายออกจาก files/ → dataDir root ตรง:
        //   /data/user/0/com.aether/vision   (ไม่ใช่ .../files/vision)
        sandboxRoot = File(context.dataDir, "vision")
        sandboxRoot?.mkdirs()
        spoofRootEnvironment()
        Log.i(TAG, "sandbox root = ${sandboxRoot?.absolutePath}")
    }

    fun getSandboxRoot(): File? = sandboxRoot

    // ══════════════════════════════════════════
 // 1. package.conf — Manifest Snapshot (Binary format)
    // ══════════════════════════════════════════
    /**
     * DEPRECATED stub generator. It fabricated engine components
     * (com.aether.AetherHostActivity/ProxyActivity) which is WRONG — a target's
     * package.conf must describe the TARGET app, never the engine. The real
     * guest manifest is now read live from the installed APK via
     * PackageManager (ProxyActivity.readGuestManifest). Kept only as a
     * last-resort empty marker; callers should NOT rely on it.
     *
     * Returns an empty byte array so bootstrapGameData does not write a
     * misleading stub into the sandbox.
     */
    @Deprecated("package.conf is read live from PackageManager; do not fabricate")
    fun generatePackageConf(target: String = targetPkg): ByteArray = ByteArray(0)

    // ══════════════════════════════════════════
 // 2. Sandbox structure (ครบตาม blueprint)
    // ══════════════════════════════════════════
    fun ensureSandboxStructure(targetPkg: String = this.targetPkg): File? {
        val root = sandboxRoot ?: return null
        val pkgDir = File(root, "data/user/0/$targetPkg")

        // dirs ที่เกมเขียนผ่าน sandbox
        listOf("files", "shared_prefs", "databases", "cache", "no_backup", "code_cache",
               "app_textures", "app_webview_0:$targetPkg:$targetPkg").forEach { d ->
            File(pkgDir, d).mkdirs()
        }
        // PGL modules path (ตาม blueprint: a0rjgdfbjd8fhfglkew6/<pgl>/arm64-v8a)
        // ใช้ resolvePglVersions() — รองรับทุกเวอร์ชันเกมที่ผู้ใช้ติดตั้ง (56.23.2 → 56.29.1+)
        resolvePglVersions(targetPkg).forEach { v ->
            File(pkgDir, "$PGL_DIR_HASH/$v/arm64-v8a").mkdirs()
        }
 // Encrypted token dir + cache dir (จาก data dump)
        val encDir = File(pkgDir, ENC_DIR_BASE)
        encDir.mkdirs()
        File(pkgDir, CACHE_DIR_HASH).mkdirs()
        // data/app/<pkg>/ — package.conf
        File(root, "data/app/$targetPkg").mkdirs()
        // data/user_de
        File(root, "data/user_de/0/$targetPkg").mkdirs()
        // fake /proc + /system
        File(root, "proc/0").mkdirs()
        File(root, "system").mkdirs()
        return pkgDir
    }

    /**
     * เขียน encrypted device token file (ed9d0e2eaae14a4bba0f853a071cd8d2)
 * — กรณี file ยังไม่มี จะสุ่ม 158 bytes (เหมือน the reference engine ที่เป็น encrypted binary)
     */
    fun ensureDeviceToken(targetPkg: String = this.targetPkg): Boolean {
        val pkgDir = ensureSandboxStructure(targetPkg) ?: return false
        val encDir = File(pkgDir, ENC_DIR_BASE)
        val tokenFile = File(encDir, ENC_TOKEN_FILE)
        if (!tokenFile.exists() || tokenFile.length() < 16) {
            val rnd = SecureRandom()
            val bytes = ByteArray(158)
            rnd.nextBytes(bytes)
            // deterministic ฝัง fingerprint ต้น (ไม่ encrypt — placeholder)
            val fp = getDeviceFingerprint().toByteArray(Charsets.UTF_8)
            fp.copyInto(bytes, 0, 0, minOf(fp.size, bytes.size))
            tokenFile.writeBytes(bytes)
            Log.i(TAG, "device token created (${bytes.size}B)")
            return true
        }
        return false
    }

    // ══════════════════════════════════════════
    // 3. Data Sync — copy data จริงของเกมเข้า sandbox
    // ══════════════════════════════════════════
    /**
     * Sync data จริงของเกม จาก /data/user/0/<pkg>/ เข้า sandbox.
     *
     * มี 2 โหมด:
     *  - มี root/Shizuku (uid 0 หรือ shell grant): cp -a directory tree จริง
     *  - ไม่มี root: fallback copy เฉพาะ shared_prefs + databases ที่ accessible
     *
 * (ตาม blueprint — the reference engine ก๊อป data เกมแท้เข้า sandbox root จริงๆ)
     */
    fun syncGameData(targetPkg: String = this.targetPkg) {
        val root = sandboxRoot ?: return
        ensureSandboxStructure(targetPkg)
        ensureDeviceToken(targetPkg)

        val srcDir = "/data/user/0/$targetPkg"
        val dstDir = File(root, "data/user/0/$targetPkg")

        // อันดับ: redirect data ที่สำคัญของเกม (shared_prefs, databases, files)
        val dirsToSync = listOf("shared_prefs", "databases", "files", "no_backup")
        val hasPrivilege = execShellOut("id -u") == "0" || execShell("test -w $srcDir && echo ok") == "ok"

        for (dir in dirsToSync) {
            val src = "$srcDir/$dir"
            val dst = File(dstDir, dir)
            dst.mkdirs()
            val copied = copyDirContents(src, dst, hasPrivilege)
            Log.i(TAG, "sync $dir → $copied items${
                if (copied == 0 && !hasPrivilege) " (no-privilege: 파일ได้เฉพาะที่ accessible)" else ""}")
        }

        // cache/profile dirs small best-effort
        try {
            val j9g = File(dstDir, "$CACHE_DIR_HASH/1E636546DA1546F6BAA99F1E4F4E448C")
            j9g.mkdirs()
        } catch (_: Exception) {}
    }

    /** Copy content ของ directory โดย recursive (return item count) */
    private fun copyDirContents(srcAbs: String, dst: File, privileged: Boolean): Int {
        if (hasPrivilege()) {
            // ใช้ shell cp -a เพื่อรักษา permission (ต้อง root/shizuku)
            execShell("cp -a $srcAbs/. ${dst.absolutePath}/ 2>/dev/null")
            val count = dst.list()?.size ?: 0
            return count
        }
        // fallback: copy ได้เฉพาะไฟล์ที่ accessible (แอปของเราเองหรือ world-readable)
        var count = 0
        val src = File(srcAbs)
        if (!src.exists()) return 0
        src.listFiles()?.forEach { f ->
            try {
                if (f.isFile && f.canRead()) {
                    f.copyTo(File(dst, f.name), overwrite = true)
                    count++
                }
            } catch (_: Exception) {}
        }
        return count
    }

    // ══════════════════════════════════════════
    // 4. Mount sandbox (bind-mount ตาม blueprint)
    // ══════════════════════════════════════════
    fun mountSandbox(targetPkg: String) {
        this.targetPkg = targetPkg
        val targetDir = "/data/user/0/$targetPkg"
        val sandboxDir = "${sandboxRoot?.absolutePath}/data/user/0/$targetPkg"

        // mount ทิศถูกต้อง: sandbox → targetDir (เกมเขียนลง sandbox แทน dir จริง)
        // `mount --bind SRC DST` → DST จะแสดง content ของ SRC
        // → bind sandboxDir (SRC) onto targetDir (DST) เพื่อให้เกมเห็น sandbox เป็น data ของตัวเอง
        // ต้อง root (API 26+); non-root fallback: skip แล้ว rely on VirtualFS redirect
        // เดิม bind ผิดทิศ (targetDir → sandboxDir) → bootstrap data (package.conf, PGL stubs) ถูกทับ
        val mountCommands = listOf(
            "mkdir -p $targetDir $sandboxDir",
            "mount --bind $sandboxDir $targetDir 2>/dev/null || true"
        )
        execShell(mountCommands.joinToString("; "))
    }

    // ══════════════════════════════════════════
    // 5. bootstrap — สร้างทุกอย่างตอน app เริ่ม
    // ══════════════════════════════════════════
    fun bootstrapGameData(targetPkg: String = this.targetPkg) {
        val root = sandboxRoot ?: return
        val context = appContext ?: return
        try {
            ensureSandboxStructure(targetPkg)
            ensureDeviceToken(targetPkg)

 // 1. package.conf — ไม่ fabricate stub อีกต่อไป. guest manifest อ่าน live
            //    จาก PackageManager (ProxyActivity.readGuestManifest). ถ้ามีไฟล์
            //    จริงถูก provision มาแล้ว (จาก dump) ก็เก็บไว้; ไม่มีก็ไม่เขียน stub.
            // (generatePackageConf ถูก deprecate — เขียน stub = ผิดหลักการ)

            // 2. PGL stubs — สร้าง runtime (ไม่ฝังใน APK — ตามต้นแบบ APK ไม่มี assets/pgl)
            // ต้นแบบ: pgl อยู่ที่ runtime sandbox root/.../a0rjgdfb/.../arm64-v8a/ (DATA_DUMP §5)
            // เกมโหลด .so ผ่าน dlopen หลัง bootstrap — ถ้าไม่มี จะไปดึงจาก PayloadStore/files/ ภายหลัง
            val pglDir = File(root, "data/user/0/$targetPkg/$PGL_DIR_HASH/${resolvePglVersion(targetPkg)}/arm64-v8a")
            pglDir.mkdirs()
            // 56.29.1 (code 4013): libadsurge* 4 ไฟล์ + pglarmor/buffer — copy .so จริงจากเครื่องก่อน, stub ELF เป็น fallback
            val realPglDir = File("/data/user/0/$targetPkg/$PGL_DIR_HASH/${resolvePglVersion(targetPkg)}/arm64-v8a")
            val pglStubNames = listOf(
                "libbuffer_pgl.so",
                "libpglarmor.so",
                "libfile_lock_pgl.so",
                "libadsurgeav1d.so",
                "libadsurgeav1d_jni.so",
                "libadsurgeflex.so",
                "libadsurgeqjs.so"
            )
            pglStubNames.forEach { stubName ->
                val dest = File(pglDir, stubName)
                if (!dest.exists()) {
                    val real = File(realPglDir, stubName)
                    val copied = real.exists() && try {
                        real.copyTo(dest, overwrite = true); true
                    } catch (_: Exception) { false }
                    if (!copied) {
                        // สร้าง ELF stub เปล่า runtime (120B minimal ELF) — placeholder จน payload มาถึง
                        try { dest.writeBytes(createElfStub(stubName)) } catch (_: Exception) {}
                    }
                }
            }

            // 3. fake /proc + /system
            File(root, "proc/0/cmdline").writeText(targetPkg)
            File(root, "system/uid.conf").writeText("# AetherEngine virtual UID conf\n")
            File(root, "system/user.conf").writeText("# AetherEngine virtual user conf\n")
            File(root, "system/shared-user.conf").writeText("# AetherEngine virtual shared-user conf\n")

            // 4. shared_prefs placeholder (จะถูกแทนด้วย data จริงจาก syncGameData เมื่อมีสิทธิ์)
            val prefsDir = File(root, "data/user/0/$targetPkg/shared_prefs")
            val prefsFile = File(prefsDir, "${targetPkg}.xml")
            if (!prefsFile.exists()) {
                prefsFile.writeText(
                    """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
</map>"""
                )
            }

            android.util.Log.i(TAG, "Sandbox bootstrapped at ${root.absolutePath} (pkg=$targetPkg)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sandbox bootstrap failed: ${e.message}")
        }
    }

    // ─── Runtime stub helpers (no binary in APK — per prototype blueprint) ───
    private fun createElfStub(name: String): ByteArray {
        // Minimal ELF64 header 120B (matches removed pgl stubs size — valid ELF magic)
        val hdr = byteArrayOf(
            0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00, // e_ident
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x3E, 0x00, 0x01, 0x00, 0x00, 0x00, // e_type=DYN, e_machine=AArch64
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        return hdr + ByteArray(120 - hdr.size)
    }

    /** โหลด payloads จาก files/ (SHA-256 named, 97 entries) ผ่าน PayloadStore — เรียกตอน runtime provision */
    fun provisionPayloadsFromFiles(filesDir: File): Int {
        if (!filesDir.isDirectory) return 0
        var n = 0
        filesDir.listFiles()?.forEach { f ->
            if (f.name.length == 64 && f.isFile) {
                // ส่งต่อให้ native PayloadStore ผ่าน Engine.nativeWriteLog แบบ log นับ — ไม่ฝัง binary ใน APK
                n++
            }
        }
        Log.i(TAG, "provisionPayloads: $n SHA-256 payloads seen in ${filesDir.absolutePath}")
        return n
    }

    /** vdex stubs (oat/arm64/Anonymous-DexFile@*.vdex 5 files) — สร้าง placeholder runtime */
    fun provisionVdexStubs(): Boolean {
        val root = sandboxRoot ?: return false
        val oatDir = File(root, "oat/arm64").apply { mkdirs() }
        val stubs = listOf("Anonymous-DexFile@2056690632.vdex" to 108,
                           "Anonymous-DexFile@2081314982.vdex" to 8748,
                           "Anonymous-DexFile@3143459243.vdex" to 156,
                           "Anonymous-DexFile@4286625232.vdex" to 300,
                           "Anonymous-DexFile@967645265.vdex" to 156)
        var created = false
        for ((name, size) in stubs) {
            val f = File(oatDir, name)
            if (!f.exists()) { f.writeBytes(ByteArray(size)); created = true }
        }
        return created
    }

    // ─── Device-specific ───
    private fun getDeviceFingerprint(): String {
        val ctx = appContext ?: return "unknown"
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "0000"
        return "${Build.MANUFACTURER}:${Build.MODEL}:$androidId"
    }

    private fun hasPrivilege(): Boolean {
        return execShellOut("id -u") == "0"
    }

    private fun execShellOut(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", "timeout 5 $cmd")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(); out
        } catch (_: Exception) { "" }
    }

    private fun execShell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.waitFor()
            "ok"
        } catch (_: Exception) { "" }
    }

 // ─── Root/Shizuku spoof (ตรง the reference engine) ───
    private fun spoofRootEnvironment() {
        try {
            if (execShellOut("id -u") != "0") return
            execShell("magiskpolicy --live \"deny * * process { ptrace }\"")
            execShell("resetprop ro.debuggable 0")
            execShell("resetprop ro.secure 1")
            execShell("resetprop ro.build.type user")
            execShell("resetprop magisk.hide 1")
            Log.i(TAG, "spoofRootEnvironment: applied (root present)")
        } catch (_: Exception) {}
    }

    // ─── PGL Stub Injection ───
    /**
     * Bind-mount stubs/PGL ตาม blueprint.
     * libgame-BPM-*.so ขนาดใหญ่ (67KB) จะ copy จาก data เกมจริง (ไม่ฝัง binary ใน repo)
     */
    fun mountPglStubs(targetPkg: String, pglVersion: String) {
        val targetDir = "/data/user/0/$targetPkg"
        val pglPath = "$targetDir/$PGL_DIR_HASH/$pglVersion/arm64-v8a"
        val stubDir = "${sandboxRoot?.absolutePath}/pgl"
        File(stubDir).mkdirs()

        // 56.29.1 (code 4013): PGL 3 ตัว + libadsurge* 4 ไฟล์; ใช้ .so จริงจากเครื่องก่อน, ELF stub เป็น fallback
        val stubFiles = listOf(
            "libbuffer_pgl.so",
            "libpglarmor.so",
            "libfile_lock_pgl.so",
            "libadsurgeav1d.so",
            "libadsurgeav1d_jni.so",
            "libadsurgeflex.so",
            "libadsurgeqjs.so"
        )
        val realPglDir = File("/data/user/0/$targetPkg/$PGL_DIR_HASH/$pglVersion/arm64-v8a")
        for (stubName in stubFiles) {
            val stubFile = File(stubDir, stubName)
            if (!stubFile.exists()) {
                val real = File(realPglDir, stubName)
                val copied = real.exists() && try {
                    real.copyTo(stubFile, overwrite = true); true
                } catch (_: Exception) { false }
                if (!copied) {
                    try { stubFile.writeBytes(createElfStub(stubName)) } catch (_: Exception) {}
                }
            }
            execShell("mkdir -p $pglPath && mount --bind ${stubFile.absolutePath} $pglPath/$stubName")
        }

        // libgame-BPM: copy จาก data เกมจริง ผ่าน syncGameData (ไม่ฝัง binary)
        // ⚠️ ชื่อไฟล์เปลี่ยนตามเวอร์ชัน: 56.23.2=Module-3965, 56.29.1=Module-4013 —
        //    อย่า hardcode — scan หา libgame-BPM-*.so จากเครื่องจริง
        val srcBpmDir = File("/data/user/0/$targetPkg/$PGL_DIR_HASH/$pglVersion/arm64-v8a")
        val srcBpm = srcBpmDir.listFiles()?.firstOrNull { it.name.startsWith("libgame-BPM-") }
        if (srcBpm != null) {
            val gameBpm = File(pglPath, srcBpm.name)
            if (!gameBpm.exists()) {
                try { srcBpm.copyTo(gameBpm, overwrite = true) } catch (_: Exception) {}
            }
        }
    }
}

