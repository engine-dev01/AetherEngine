package com.aether

/**
 * PackageConfParser — reader for the Snake-engine `package.conf` artifact
 * (Android Parcel serialization of a target app's manifest snapshot).
 *
 * Port of tools/parse_packageconf.py — see docs/PROVISIONING_8BP_56.23.2.md.
 *
 * The parcel mixes two string encodings in one stream:
 *   - a UTF-16LE header (repackaged classloader names)
 *   - a UTF-8 length-prefixed body (component / manifest names)
 *
 * String layout (both encodings):
 *   int32 len            (char count for UTF-16, byte count for UTF-8; -1 = null)
 *   payload              (len*2 bytes UTF-16LE, or len bytes UTF-8)
 *   null terminator      (2 bytes UTF-16, 1 byte UTF-8)
 *   zero pad to 4-byte alignment
 *
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 */
object PackageConfParser {

    data class PackageConf(
        val packageName: String?,
        val version: String?,
        val applicationClass: String?,
        val appComponentFactory: String?,
        val launcherActivity: String?,
        val apkPath: String?,
        val activities: List<String>,
        val services: List<String>,
        val providers: List<String>,
        val receivers: List<String>,
        val metadata: Map<String, String>,
    ) {
        /** All fully-qualified component class names (for class-map seeding). */
        fun allComponents(): List<String> =
            (listOfNotNull(applicationClass) + activities + services + providers + receivers)
                .distinct()
    }

    private fun align4(n: Int): Int = (n + 3) and 3.inv()

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or
        ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or
        ((b[i + 3].toInt() and 0xff) shl 24)

    private fun printableAscii(s: String): Boolean =
        s.all { it.code in 32..126 }

    /** Try to read a UTF-8 length-prefixed string at [i]. Returns (value, consumed) or null. */
    private fun tryUtf8(b: ByteArray, i: Int): Pair<String, Int>? {
        if (i + 4 > b.size) return null
        val ln = le32(b, i)
        if (ln <= 0 || ln >= 4000 || i + 4 + ln + 1 > b.size) return null
        if (b[i + 4 + ln].toInt() != 0) return null
        val seg = b.copyOfRange(i + 4, i + 4 + ln)
        if (seg.any { (it.toInt() and 0xff) !in 32..126 }) return null
        val s = String(seg, Charsets.UTF_8)
        return s to align4(4 + ln + 1)
    }

    /** Try to read a UTF-16LE length-prefixed string at [i]. Returns (value, consumed) or null. */
    private fun tryUtf16(b: ByteArray, i: Int): Pair<String, Int>? {
        if (i + 4 > b.size) return null
        val ln = le32(b, i)
        if (ln < 0 || ln >= 4000 || i + 4 + ln * 2 + 2 > b.size) return null
        if (b[i + 4 + ln * 2].toInt() != 0 || b[i + 4 + ln * 2 + 1].toInt() != 0) return null
        val s = String(b, i + 4, ln * 2, Charsets.UTF_16LE)
        if (ln > 0 && !printableAscii(s)) return null
        return s to align4(4 + ln * 2 + 2)
    }

    /** Sequentially walk the parcel and collect all decodable strings. */
    fun readStrings(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = data.size
        while (i <= n - 4) {
            if (le32(data, i) == -1) { i += 4; continue }
            val r = tryUtf8(data, i) ?: tryUtf16(data, i)
            if (r != null) {
                if (r.first.isNotEmpty()) out.add(r.first)
                i += r.second
            } else {
                i += 1
            }
        }
        return out
    }

    private val META_KEYS = setOf(
        "com.facebook.sdk.ApplicationId", "com.facebook.sdk.ClientToken",
        "com.google.android.gms.ads.APPLICATION_ID", "com.google.android.gms.games.APP_ID",
        "com.google.android.gms.version", "com.google.android.gms.games.version",
        "com.google.android.play.billingclient.version", "com.bytedance.sdk.pangle.version",
        "com.android.stamp.type", "APP_NAME",
    )

    private fun isFqcn(s: String): Boolean =
        "." in s &&
        !s.startsWith("android.content.pm") &&
        !s.startsWith("android.permission") &&
        !s.startsWith("android.intent") &&
        s.none { it == ' ' || it == '/' }

    fun parse(data: ByteArray): PackageConf {
        val vals = readStrings(data)

        val activities = LinkedHashSet<String>()
        val services = LinkedHashSet<String>()
        val providers = LinkedHashSet<String>()
        val receivers = LinkedHashSet<String>()
        var application: String? = null

        for (s in vals) {
            if (!isFqcn(s)) continue
            when {
                s.endsWith("Application") -> if (application == null) application = s
                s.endsWith("Receiver") -> receivers.add(s)
                s.endsWith("Service") -> services.add(s)
                "Provider" in s -> providers.add(s)
                s.endsWith("Activity") || "Activity" in s -> activities.add(s)
            }
        }

        val metadata = HashMap<String, String>()
        for (idx in vals.indices) {
            val k = vals[idx]
            if (k in META_KEYS && idx + 1 < vals.size) metadata[k] = vals[idx + 1]
        }

        // App version sits in the ApplicationInfo block, immediately BEFORE a
        // base.apk path. Other x.y.z strings (SDK versions like gms "21.0.0",
        // pangle "7.7.0.2") appear elsewhere — so scan every base.apk
        // occurrence and take the semver that directly precedes one.
        val semver = Regex("""^\d+\.\d+(\.\d+)*$""")
        var version: String? = null
        for (idx in vals.indices) {
            if (vals[idx].endsWith("base.apk") && idx > 0 && semver.matches(vals[idx - 1])) {
                version = vals[idx - 1]; break
            }
        }
        if (version == null) version = vals.firstOrNull { semver.matches(it) }

        return PackageConf(
            packageName = vals.firstOrNull { it == "com.miniclip.eightballpool" }
                ?: metadata["APP_NAME"],
            version = version,
            applicationClass = application,
            appComponentFactory = vals.firstOrNull { it.endsWith("ComponentFactory") },
            launcherActivity = activities.firstOrNull { "EightBallPool" in it }
                ?: activities.firstOrNull(),
            apkPath = vals.firstOrNull { it.endsWith("base.apk") },
            activities = activities.toList(),
            services = services.toList(),
            providers = providers.toList(),
            receivers = receivers.toList(),
            metadata = metadata,
        )
    }
}
