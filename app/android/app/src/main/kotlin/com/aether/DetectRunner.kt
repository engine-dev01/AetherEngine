// DetectRunner.kt — Kotlin port ของ detect/aether_scan.py (สูตรเดียวกับ CI detect-lab)
// หลักการเดิม: pack-driven matching, ทุกความรู้อยู่ใน pack, core ไม่แตะเมื่อเพิ่มตระกูล
// ทำงานในเครื่องที่รันแอพ — ไม่มี network I/O ใด ๆ ทั้งสาย (อ่านไฟล์ + เทียบ pack ใน assets เท่านั้น)
package com.aether

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object DetectRunner {
    private const val ASSET_DIR = "detect_packs"
    private const val MAX_ENTRIES = 8000

    data class Hit(val packId: String, val ruleId: String, val entry: String, val evidence: String)
    data class PackResult(val verdict: String, val score: Double)
    data class Report(val packResults: Map<String, PackResult>, val hits: List<Hit>)

    /** แตก packs จาก assets ไปได้เรกทอรี่ cache — ครั้งเดียวต่อ run */
    fun extractBundledPacks(ctx: Context): File {
        val out = File(ctx.cacheDir, ASSET_DIR)
        out.mkdirs()
        val names = ctx.assets.list(ASSET_DIR) ?: emptyArray()
        for (n in names) {
            val f = File(out, n)
            if (f.exists()) continue
            ctx.assets.open("$ASSET_DIR/$n").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    /** scan หลัก — port ตรงจาก aether_scan.py: hash_entries → match_pack → score → verdict */
    fun scan(path: String, packsDir: File): Report {
        val packs = loadPacks(packsDir)
        val entries = indexEntries(File(path))
        val allHits = mutableListOf<Hit>()
        val results = HashMap<String, PackResult>()
        for (pack in packs) {
            val hits = matchPack(entries, pack, File(path))
            val s = score(hits, pack)
            results[pack.getString("id")] = PackResult(verdict(pack.optDouble("threshold", 40.0), s), s)
            allHits.addAll(hits)
        }
        return Report(results, allHits)
    }

    private fun loadPacks(dir: File): List<JSONObject> =
        dir.listFiles { f -> f.name.endsWith(".pack.json") }
            ?.sortedBy { it.name }
            ?.map { JSONObject(it.readText()) } ?: emptyList()

    /** index entries ของ zip/apk — เก็บ size + sha256 (deterministic เหมือน Python) */
    private fun indexEntries(sample: File): Map<String, Pair<Long, String>> {
        val m = HashMap<String, Pair<Long, String>>()
        ZipFile(sample).use { z ->
            for (info in z.entries()) {
                if (m.size >= MAX_ENTRIES) break
                val data = z.getInputStream(info).readBytes()
                m[info.name] = data.size.toLong() to sha256(data)
            }
        }
        return m
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun matchPack(
        entries: Map<String, Pair<Long, String>>,
        pack: JSONObject,
        sample: File,
    ): List<Hit> {
        val hits = mutableListOf<Hit>()
        val rules: JSONArray = pack.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val rid = rule.optString("id", "?")
            val w = rule.optInt("weight", 1)
            when (rule.optString("type")) {
                "sha256" -> {
                    val want = rule.getString("value").lowercase()
                    for ((rel, meta) in entries) {
                        if (meta.second == want) { hits.add(Hit(pack.getString("id"), rid, rel, "sha256")); break }
                    }
                }
                "filename" -> {
                    val pat = rule.getString("value")
                    val contains = rule.optString("entry_contains", "")
                    for (rel in entries.keys) {
                        if (fnmatchLike(rel, pat) && (contains.isEmpty() || rel.contains(contains))) {
                            hits.add(Hit(pack.getString("id"), rid, rel, "filename")); break
                        }
                    }
                }
                "string", "regex" -> {
                    val scope = rule.optString("entry_glob", "**")
                    val needle = rule.getString("value").toByteArray(Charsets.UTF_8)
                    for (rel in entries.keys) {
                        if (!fnmatchLike(rel, scope)) continue
                        val data = readEntry(sample, rel) ?: continue
                        val found = if (rule.optString("type") == "regex")
                            Regex(rule.getString("value")).containsMatchIn(String(data, Charsets.ISO_8859_1))
                        else containsBytes(data, needle)
                        if (found) { hits.add(Hit(pack.getString("id"), rid, rel, rule.optString("type"))); break }
                    }
                }
            }
        }
        return hits
    }

    private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun readEntry(sample: File, rel: String): ByteArray? = try {
        ZipFile(sample).use { z -> z.getEntry(rel)?.let { z.getInputStream(it).readBytes() } }
    } catch (e: Exception) { null }

    /** glob เดียวกับ aether_scan.py — ดาวคู่ตาม dir-slash หมายนำหน้า 0+ โฟลเดอร์,
     *  ดาวคู่ลอย ๆ ข้าม slash ได้, ดาวเดี่ยวไม่ข้าม slash
     *  แปลง pattern → regex ด้วย escaping ทีละส่วน (ไม่ escape แล้ว replace — ลำดับผิด) */
    private fun fnmatchLike(name: String, pattern: String): Boolean {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3 }
                pattern.startsWith("**", i)   -> { sb.append(".*"); i += 2 }
                pattern[i] == '*'            -> { sb.append("[^/]*"); i += 1 }
                else -> { sb.append(Regex.escape(pattern[i].toString())); i += 1 }
            }
        }
        return Regex(sb.toString()).matches(name)
    }

    private fun score(hits: List<Hit>, pack: JSONObject): Double {
        val rules: JSONArray = pack.optJSONArray("rules") ?: JSONArray()
        var total = 0L; val weights = HashMap<String, Int>()
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            weights[r.optString("id")] = r.optInt("weight", 1)
            total += r.optInt("weight", 1)
        }
        val achieved = hits.map { weights[it.ruleId] ?: 0 }.sum()
        return if (total > 0) achieved * 100.0 / total else 0.0
    }

    private fun verdict(threshold: Double, s: Double): String =
        if (s >= threshold) "DETECTED" else if (s >= threshold / 2) "SUSPICIOUS" else "CLEAN"
}
