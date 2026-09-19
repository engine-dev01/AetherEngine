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
 * เส้นทาง sandbox root: /data/user/0/com.aether/root/
 * (dataDir root ตรง — ชื่อ "root" ≡ blueprint L0; snake ใช้ getExternalFilesDir("root")
 *  lv0.java:72 — เส้นทางเต็มเทียบตอน P2)
 *
 * โครงสร้าง (ตาม blueprint จาก data dump analysis):
 *   root/
 *   ├── data/app/<pkg>/                    ← package.conf (manifest snapshot)
 *   ├── data/user/0/<pkg>/
 *   │   ├── a0rjgdfbjd8fhfglkew6/<ver>/arm64-v8a/  ← PGL modules
 *   │   ├── 706d4946...<hash>/ed9d...      ← encrypted device token
 *   │   ├── j9g29zqf0cfqd3vvu2bw/          ← profile/cache dir
 *   │   ├── files/ shared_prefs/ databases/ cache/ no_backup/ code_cache/
 *   │   ├── app_textures/ app_webview_0:.../
 *   ├── data/user_de/0/<pkg>/
 *   ├── proc/0/                            ← engine เขียนเมื่อ virtual-UID พร้อม
 *   └── system/                            ← ว่างตอนเปิด (≡ T1 com.snake.zip)
 */
object SandboxManager {

    private const val TAG = "AetherSandbox"
    private var appContext: Context? = null
    private var sandboxRoot: File? = null
    // Phase 1+2: in-process only. targetPkg is now the engine's own
    // package (com.aether), not an external game. We keep the bootstrap
    // path (root/data/user/0/...) because VirtualFS uses it, but
    // no external package is read/written.
    private var targetPkg = "com.aether"
    private var targetUid: Int = -1

 /** PGL version hash — ตรงกับ blueprint the reference engine (90d8aa15a2de2cb4) */
    private const val DEFAULT_PGL_VERSION = "90d8aa15a2de2cb4"
    private const val PGL_DIR_HASH = "a0rjgdfbjd8fhfglkew6"

    // Single endpoint literal for the Kotlin side — the license/config API.
    // Both the PGL map and the package version are read from here, so the URL
    // exists exactly once in this module. Dart keeps its own copy
    // (LicenseStore) because the license flow is Dart-only
    // (T1: rest.snakeseller.com = 1 hit in libapp.so, 0 in smali).
    // [OFFLINE-FALLBACK] callers fall back to the DEFAULT_* tables whenever the
    // endpoint is unreachable — the engine never requires it to be up.
    private const val CONFIG_ENDPOINT = "https://rest.snakeseller.com/api/request/"

    // ---- FLEXIBLE VERSION MAP (dynamic, DEFAULT_* = offline fallback) ----
    // resolvePglVersions() reads the version → pglVersion mapping from
    // CONFIG_ENDPOINT. When the API answer carries no map (or the call
    // fails), the DEFAULT_* tables below are used so the engine stays
    // fully usable offline.
    //
    // API response shape (optional):
    //   {"pgl_map": { "56.30.0": "90d8aa...", "19.4.0": "6e72ab..." }}
    private val DEFAULT_PGL_VERSIONS = listOf(
        "90d8aa15a2de2cb4",  // 8 Ball Pool (baseline)
    )
    private val DEFAULT_VERSION_CODE_TO_PGL = mapOf(
        3965L to "90d8aa15a2de2cb4",
        4013L to "9e75dd17d258d07f",  // 8 Ball Pool 56.29.1
    )

    // ไฟล์ PGL ตามเวอร์ชัน — ตรวจจาก dump ต้นแบบ (ไม่ใช่รายการเดียวกันทุกเวอร์ชัน)
    //   snake 56.23.2: 3 ไฟล์ = libbuffer_pgl.so, libpglarmor.so, libgame-BPM-...-Module-3965.so
    //   ninja 56.29.1: 7 ไฟล์ = 3 ตัวนั้น + libadsurge* 4 ตัว (Module-4013)
    //   libfile_lock_pgl.so ไม่มีในต้นแบบทั้ง 2 เวอร์ชัน — อย่าสร้าง (ของแต่ง)
    private val DEFAULT_PGL_FILES_BY_VERSION = mapOf(
        "90d8aa15a2de2cb4" to listOf(   // 8BP 56.23.2 — ตรงต้นแบบ snake
            "libbuffer_pgl.so",
            "libpglarmor.so",
            // libgame-BPM-...-Module-3965.so — scan ชื่อจริงจากเครื่อง (ผูก version)
        ),
        "9e75dd17d258d07f" to listOf(   // 8BP 56.29.1 — ตรงต้นแบบ ninja
            "libbuffer_pgl.so",
            "libpglarmor.so",
            "libadsurgeav1d.so",
            "libadsurgeav1d_jni.so",
            "libadsurgeflex.so",
            "libadsurgeqjs.so",
            // libgame-BPM-...-Module-4013.so — scan ชื่อจริงจากเครื่อง (ผูก version)
        ),
    )

    // ---- Remote PGL map fetch (optional) ----
    // Returns map versionString->pglHash or null if fetch fails.
    private fun fetchRemotePglMap(): Map<String, String>? {
        return try {
            val conn = (java.net.URL(CONFIG_ENDPOINT).openConnection()
                as java.net.HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Expected JSON: {"pgl_map": {"56.30.0":"90d8aa...","19.4.0":"6e72ab..."}}
            val mapMatch = "\\\"pgl_map\\\"\\s*:\\s*\\{([^}]+)\\}".toRegex().find(text) ?: return null
            val inner = mapMatch.groupValues[1]
            val result = mutableMapOf<String, String>()
            inner.split(',').forEach { entry ->
                val kv = "\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"".toRegex().find(entry.trim())
                if (kv != null) {
                    result[kv.groupValues[1]] = kv.groupValues[2]
                }
            }
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.w(TAG, "Failed remote PGL map fetch: ${e.message}")
            null
        }
    }

 /** 706d494674354b747939547a3839354b4e43626776773d3d = base64-ish hash dir (จาก data dump) */
    private val ENC_DIR_BASE = "706d494674354b747939547a3839354b4e43626776773d3d"
    private const val ENC_TOKEN_FILE = "ed9d0e2eaae14a4bba0f853a071cd8d2"

 /** j9g29zqf0cfqd3vvu2bw — profile cache dir (จาก data dump) */
    private const val CACHE_DIR_HASH = "j9g29zqf0cfqd3vvu2bw"

    /**
     * Resolve PGL version dir(s) ตามเกมเวอร์ชันที่ผู้ใช้ติดตั้งจริง.
     * ลำดับ: (1) versionCode จาก PackageManager (ground truth — ตรงหลักฐาน
     * dump: 3965→90d8aa..., 4013→9e75dd...) (2) list dir จริงของเครื่อง
     * (3) fallback KNOWN_PGL_VERSIONS. คืนค่า list เพื่อให้ caller sync
     * ทุกเวอร์ชันที่มี (เผื่อเกมอ่านหลายชั้น).
     */
    fun resolvePglVersions(targetPkg: String = this.targetPkg): List<String> {
        // 1. ground truth จาก versionCode (ไม่ต้องพึ่งสิทธิ์อ่าน dir)
        val vc = try {
            appContext?.packageManager?.getPackageInfo(targetPkg, 0)?.longVersionCode
        } catch (_: Throwable) { null }
        vc?.let { code ->
            DEFAULT_VERSION_CODE_TO_PGL[code]?.let { return listOf(it) }
        }
        // 2. อ่านสดจากเครื่อง
        val base = File("/data/user/0/$targetPkg/$PGL_DIR_HASH")
        val found = try {
            base.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending()
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        if (found.isNotEmpty()) return found
        // 3. remote fetch (new): try to get version → pglVersion mapping from the API.
        val remoteMap = fetchRemotePglMap()
        if (remoteMap != null && remoteMap.isNotEmpty()) return remoteMap.values.toList()
        // 4. fallback to built‑in defaults (backwards‑compatible list, newest first).
        return DEFAULT_PGL_VERSIONS
    }

    /** ปกติใช้รุ่นแรกของ resolvePglVersions() (รุ่นที่ติดตั้งจริง/ใหม่สุด) */
    private fun resolvePglVersion(targetPkg: String): String {
        return resolvePglVersions(targetPkg).firstOrNull() ?: DEFAULT_PGL_VERSION
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // sandbox root อยู่ที่ dataDir root ตรง:
        //   /data/user/0/com.aether/root   (ไม่ใช่ .../files/root)
        sandboxRoot = File(context.dataDir, "root")
        // T1 (com.snake.zip = ต้นแบบตอนเปิดแอพ): root/{cache,data,data/app,system}
        // มีอยู่จริงเป็น dir ว่าง — engine สร้างโครงเปล่าไว้, ไฟล์ทุกตัวถูกเขียน
        // ตอน runtime เท่านั้น (ไม่ใช่ fabricated stub)
        sandboxRoot?.apply {
            mkdirs()
            java.io.File(this, "cache").mkdirs()
            java.io.File(this, "data").mkdirs()
            java.io.File(this, "data/app").mkdirs()
            java.io.File(this, "system").mkdirs()
        }
        // [CUT 2026-09-11] spoofRootEnvironment — resetprop/magiskpolicy
        // (ปลอม env root/debuggable/secure) ทำให้แอพกั๊กตัวเอง งดก่อนทดสอบ
        // spoofRootEnvironment()
        Log.i(TAG, "sandbox root = ${sandboxRoot?.absolutePath}")
    }

    fun getSandboxRoot(): File? = sandboxRoot

    // ══════════════════════════════════════════
 // 1. package.conf — Manifest Snapshot (Binary format)
    // ══════════════════════════════════════════
    /** Metadata keys ที่ serializer เขียนลง package.conf — ชุดเดียวกับที่
     *  PackageConfParser รู้จัก (com.facebook/gms/play/pangle keys จาก §3) */
    private val PACKAGE_CONF_META_KEYS = setOf(
        "com.facebook.sdk.ApplicationId", "com.facebook.sdk.ClientToken",
        "com.google.android.gms.ads.APPLICATION_ID", "com.google.android.gms.games.APP_ID",
        "com.google.android.gms.version", "com.google.android.gms.games.version",
        "com.google.android.play.billingclient.version", "com.bytedance.sdk.pangle.version",
        "com.android.stamp.type", "APP_NAME",
    )
    /**
     * สร้าง package.conf (manifest snapshot) จาก PackageManager ของเครื่องจริง
     * — ตาม blueprint ต้นแบบ (SNAKE lv0.p: root/data/app/<pkg>/package.conf
     * ที่ engine เขียนตอน install หลัง PackageParser.parse + collectCertificates).
     *
     * format: UTF-16LE header (classloader name) + UTF-8 length-prefixed body
     * (component names + metadata + version + apk path) — ตรงกับที่
     * PackageConfParser อ่าน (dual-encoding — test ยืนยัน)
     *
     * ไม่ใช่ stub/คัดลอกไฟล์ข้ามเครื่อง — สร้างจากข้อมูลจริงของเครื่องนี้
     * (per-install: sourceDir/signature/versionCode ต่างกันทุกเครื่อง)
     */
    fun generatePackageConf(target: String = targetPkg): ByteArray {
        val ctx = appContext ?: return ByteArray(0)
        return try {
            val pm = ctx.packageManager
            val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_PROVIDERS or
                android.content.pm.PackageManager.GET_META_DATA
            val pi = pm.getPackageInfo(target, flags)
            val appInfo = pi.applicationInfo ?: return ByteArray(0)

            // components (FQCN ตามที่ parser จำแนก: Activity/Service/Provider/Receiver)
            val activities = pi.activities?.mapNotNull { it.name } ?: emptyList()
            val services = pi.services?.mapNotNull { it.name } ?: emptyList()
            val providers = pi.providers?.mapNotNull { it.name } ?: emptyList()
            val receivers = pi.receivers?.mapNotNull { it.name } ?: emptyList()

            // launcher activity (MAIN/LAUNCHER intent resolve — เหมือน readGuestManifest)
            val launcher = try {
                pm.getLaunchIntentForPackage(target)?.component?.className
            } catch (_: Throwable) { null }

            // metadata that parser recognises — from ApplicationInfo.metaData
            val meta = appInfo.metaData
            val metadata = LinkedHashMap<String, String>()
            if (meta != null) {
                for (key in PACKAGE_CONF_META_KEYS) {
                    val v = meta.get(key) ?: continue
                    metadata[key] = v.toString()
                }
            }

            // ---- NEW: fetch official version from remote endpoint ----
            // The API returns JSON like {"version":"56.30.0"}. If fetching fails
            // or the JSON does not contain a version field, fall back to the
            // locally‑resolved versionName.
            fun fetchRemoteVersion(): String? {
                return try {
                    val conn = (java.net.URL(CONFIG_ENDPOINT).openConnection()
                        as java.net.HttpURLConnection)
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    if (conn.responseCode != 200) return null
                    val stream = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    val text = stream.use { it.readText() }
                    // Very small JSON – simple regex extraction avoids pulling a JSON lib.
                    val match = "\"version\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(text)
                    match?.groupValues?.get(1)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch remote version: ${e.message}")
                    null
                }
            }

            val resolvedVersion = fetchRemoteVersion() ?: pi.versionName

            val out = java.io.ByteArrayOutputStream()
            // UTF-16LE header (classloader name — mimics real artifact)
            utf16(out, "com.app.framework.core.system.pm.BPackage")
            // UTF-8 body — components + metadata + version + apk path
            appInfo.className?.let { utf8(out, it) }                    // Application
            utf8(out, "androidx.core.app.CoreComponentFactory")         // appComponentFactory
            launcher?.let { utf8(out, it) }                             // launcher activity
            activities.forEach { utf8(out, it) }
            services.forEach { utf8(out, it) }
            providers.forEach { utf8(out, it) }
            receivers.forEach { utf8(out, it) }
            metadata.forEach { (k, v) -> utf8(out, k); utf8(out, v) }
            resolvedVersion?.let { utf8(out, it) }                     // version from API or local
            appInfo.sourceDir?.let { utf8(out, it) }                    // apk path (base.apk)
            out.toByteArray()
        } catch (e: Throwable) {
            Log.e(TAG, "generatePackageConf failed: ${e.message}")
            ByteArray(0)
        }
    }

    // ── Parcel string encoder (ตรงกับ PackageConfParser.readStrings) ──

    /** UTF-8 length-prefixed parcel string: int32 len + bytes + null + pad4 */
    private fun utf8(out: java.io.ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val ln = b.size
        out.write(ln and 0xff); out.write((ln ushr 8) and 0xff)
        out.write((ln ushr 16) and 0xff); out.write((ln ushr 24) and 0xff)
        out.write(b); out.write(0)
        var consumed = 4 + ln + 1
        while (consumed % 4 != 0) { out.write(0); consumed++ }
    }

    /** UTF-16LE length-prefixed parcel string: int32 charCount + chars + u16 null + pad4 */
    private fun utf16(out: java.io.ByteArrayOutputStream, s: String) {
        val ln = s.length
        out.write(ln and 0xff); out.write((ln ushr 8) and 0xff)
        out.write((ln ushr 16) and 0xff); out.write((ln ushr 24) and 0xff)
        out.write(s.toByteArray(Charsets.UTF_16LE))
        out.write(0); out.write(0)
        var consumed = 4 + ln * 2 + 2
        while (consumed % 4 != 0) { out.write(0); consumed++ }
    }

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
        // PGL modules path — เกมสร้างเองตอนรัน (หลักฐาน: ขนาดไฟล์ PGL ต่างข้ามเครื่อง
        //   = เกม generate ตามเวอร์ชัน/ตำแหน่งของมันเอง เหมือนแอปปกติ)
        //   engine อย่า pre-create — ไม่งั้น dir ผิดเวอร์ชัน (ของเก่า) ปนเข้ามา
        // Encrypted token dir + cache dir (จาก data dump)
        val encDir = File(pkgDir, ENC_DIR_BASE)
        encDir.mkdirs()
        File(pkgDir, CACHE_DIR_HASH).mkdirs()
        // data/app/<pkg>/ — package.conf
        File(root, "data/app/$targetPkg").mkdirs()
        // data/user_de
        File(root, "data/user_de/0/$targetPkg").mkdirs()
        // /system conf dir: ≡ T1 (com.snake.zip: root/system ว่างตอนเปิด)
        // /proc: ไม่สร้างตอนเปิด — จะมาพร้อม virtual-UID (P4+) ที่มี parser จริง
        File(root, "system").mkdirs()
        File(root, "cache").mkdirs()   // ≡ T1: root/cache ว่าง (snake สร้าง cache ใต้ virtual root)
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

 // 1. package.conf — engine เขียน registry เองจากเครื่องจริง (per-install);
            //    guest manifest อ่าน live จาก PackageManager เป็น primary path
            //    (ProxyActivity.readGuestManifest) — conf ใช้เป็น fallback/offline
            //    snapshot เท่านั้น (= generatePackageConf ด้านล่าง ไม่ใช่ stub)

            // 3. package.conf — manifest snapshot (engine เขียน registry เอง
            //    จากเครื่องจริง — per-install: sourceDir/signature/versionCode
            //    ต่างกันทุกเครื่อง ห้ามคัดลอกไฟล์ข้ามเครื่อง)
            //    blueprint: SNAKE lv0.p() = root/data/app/<pkg>/package.conf
            try {
                val confFile = File(root, "data/app/$targetPkg/package.conf")
                // ★ freshness (device proof 18:11 รอบนี้): log อ้าง sourceDir
                //   /data/app/~~qYhfc… (path เก่า) แต่ dumpsys codePath ปัจจุบันคือ
                //   /data/app/~~DpQg8… — เกมถูก reinstall ⇒ conf เก่าชี้ path ตาย;
                //   เดิม skip rule ดูแค่ exists&&≥32B → ไม่มีวัน refresh
                val stale = !confFile.exists() || confFile.length() < 32 || run {
                    runCatching {
                        val live = context.packageManager
                            .getApplicationInfo(targetPkg, 0).sourceDir
                        val conf = com.aether.PackageConfParser.parse(confFile.readBytes())
                        // conf ฝัง apkPath ไว้ — ไม่ตรง = stale (path เปลี่ยน/ pkg หาย)
                        live != conf.apkPath
                    }.getOrDefault(true)   // parse fail = ถือว่า stale
                }
                if (stale) {
                    val bytes = generatePackageConf(targetPkg)
                    if (bytes.isNotEmpty()) {
                        confFile.parentFile?.mkdirs()
                        confFile.writeBytes(bytes)
                        Log.i(TAG, "package.conf written (${bytes.size}B) for $targetPkg")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "package.conf write failed: ${e.message}")
            }

            // 4. PGL modules — เกมเขียนเองตอนรัน (หลักฐาน: ขนาดไฟล์ต่างข้ามเครื่อง
            //    = เกม generate ตามเวอร์ชัน/ตำแหน่งของมัน เหมือนแอปปกติ)
            //    engine ไม่ต้อง pre-create dir หรือ copy .so ใด ๆ — redirect
            //    path ทำให้เกมเขียนลง sandbox เอง (SNAKE dump พิสูจน์: ไฟล์เกม
            //    ใน sandbox ต้นแบบ = เกมสร้างตอน runtime)

            // 5. fake /proc + /system — T1 (com.snake.zip ดัมป์ตอนเปิดแอพ 13:17):
            //    ต้นแบบมีแค่ root/{cache,data,data/app,system} เป็น dir ว่าง;
            //    ไม่มี proc/** และไม่มี *.conf ใด ๆ ตอนเปิด — ของเก่าที่เขียน here
            //    (cmdline + uid/user/shared-user) = fabrication ไร้ consumer (grep
            //    ยืนยัน: ไม่มีโค้ดอ่าน) → ตัด; จะกลับมาพร้อม virtual-UID (P4+)
            //    เมื่อมี parser ที่อ่านจริง ≡ x6/y6 ฝั่งต้นแบบ

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
                // log นับอย่างเดียว (Engine.nativeWriteLog ถูกตัด 2026-09-14 — docs/CUTS.md) — ไม่ฝัง binary ใน APK
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
     * Bind-mount PGL ตาม blueprint.
     * libgame-BPM-*.so ขนาดใหญ่ (67KB) จะ copy จาก data เกมจริง (ไม่ฝัง binary ใน repo)
     * ⚠️ ไฟล์จริงของเกมเป็น data payload (ไม่ใช่ ELF) — ห้ามสร้าง stub ปลอม
     */
    fun mountPglStubs(targetPkg: String, pglVersion: String) {
        val targetDir = "/data/user/0/$targetPkg"
        val pglPath = "$targetDir/$PGL_DIR_HASH/$pglVersion/arm64-v8a"
        val stubDir = "${sandboxRoot?.absolutePath}/pgl"
        File(stubDir).mkdirs()

        // per-version (ตรงต้นแบบ: 56.23.2=3 ไฟล์, 56.29.1=7 ไฟล์) — ไม่มี libfile_lock
        val stubFiles = DEFAULT_PGL_FILES_BY_VERSION[pglVersion] ?: emptyList()
        val realPglDir = File("/data/user/0/$targetPkg/$PGL_DIR_HASH/$pglVersion/arm64-v8a")
        for (stubName in stubFiles) {
            val stubFile = File(stubDir, stubName)
            if (!stubFile.exists()) {
                val real = File(realPglDir, stubName)
                val copied = real.exists() && try {
                    real.copyTo(stubFile, overwrite = true); true
                } catch (_: Exception) { false }
                if (!copied) {
                    // ไม่สร้าง stub ปลอม — เกมเขียน payload จริงเองตอนรัน
                    Log.w(TAG, "PGL $stubName ไม่มีไฟล์จริง — ข้าม mount (เกมจะเขียนเอง)")
                }
            }
            if (stubFile.exists()) {
                execShell("mkdir -p $pglPath && mount --bind ${stubFile.absolutePath} $pglPath/$stubName")
            }
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

