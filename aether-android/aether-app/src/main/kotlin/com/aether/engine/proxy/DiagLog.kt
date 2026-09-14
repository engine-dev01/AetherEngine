package com.aether.engine.proxy

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DiagLog — in-app diagnostic tracer (no adb required).
 *
 * Writes timestamped, immediately-flushed trace lines to
 *   <filesDir>/diag/trace.log
 * so every scaffold step (S1-S4) is captured even if the process crashes a
 * moment later. Also dumps the process's own logcat (framework lines like
 * ActivityThread/AndroidRuntime included) on demand.
 *
 * Retrieve by zipping the app's files/ dir (same as crash_logs).
 *
 * Every method is fully guarded — diagnostics must never crash the app.
 */
object DiagLog {

    private const val TAG = "AetherDiag"
    private const val DIR = "diag"
    private const val FILE = "trace.log"

    @Volatile private var dir: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        try {
            val base = File(context.filesDir, DIR).apply { mkdirs() }
            dir = base
            d(TAG, "──────── DiagLog session start pid=${Process.myPid()} ────────")
        } catch (e: Throwable) {
            Log.w(TAG, "init: ${e.message}")
        }
    }

    /** Trace one line: logcat + append to trace.log (flushed). */
    fun d(tag: String, msg: String) {
        Log.i(tag, msg)
        val base = dir ?: return
        try {
            synchronized(lock) {
                val line = "${fmt.format(Date())} [${Process.myPid()}/${android.os.Process.myTid()}] $tag: $msg\n"
                File(base, FILE).appendText(line)
            }
        } catch (_: Throwable) { /* diagnostics never crash */ }
    }

    /**
     * audit C2: เขียนผล launch แบบ machine-readable ข้าม process
     * (main → :pN → UI) — files/diag/launch_result.json
     * bridge อ่านกลับเป็น Map ให้ Dart แสดง {ok, stage, reason, slot, handshake, identity}
     */
    fun writeResult(result: Map<String, String>) {
        val base = dir ?: return
        try {
            val body = result.entries.joinToString(",") {
                "\"${it.key}\":\"${it.value.replace("\"", "'")}\""
            }
            synchronized(lock) {
                File(base, "launch_result.json")
                    .writeText("{${body},\"ts\":${System.currentTimeMillis()},\"pid\":${Process.myPid()}}")
            }
            d(TAG, "launch_result: $result")
        } catch (_: Throwable) { /* diagnostics never crash */ }
    }

    /** อ่าน launch_result.json ล่าสุด (caller = main process หลัง dispatch) */
    fun readResult(): Map<String, String> {
        val base = dir ?: return emptyMap()
        return try {
            val f = File(base, "launch_result.json")
            if (!f.exists()) return emptyMap()
            Regex("\"([a-zA-Z_]+)\":(\"[^\"]*\"|[0-9]+)").findAll(f.readText())
                .associate { m ->
                    m.groupValues[1] to m.groupValues[2].trim('"')
                }
        } catch (_: Throwable) { emptyMap() }
    }

    /** Trace a throwable with full stack + cause chain. */
    fun err(tag: String, label: String, t: Throwable) {
        val sb = StringBuilder("$label: ${t.javaClass.name}: ${t.message}\n")
        for (e in t.stackTrace) sb.append("    at $e\n")
        var c = t.cause; var depth = 0
        while (c != null && depth < 6) {
            sb.append("  Caused by: ${c.javaClass.name}: ${c.message}\n")
            for (e in c.stackTrace.take(12)) sb.append("      at $e\n")
            c = c.cause; depth++
        }
        d(tag, sb.toString())
    }

    /**
     * Dump this process's own logcat into diag/logcat_<ts>.log.
     * Apps can always read their OWN log output; READ_LOGS (declared) widens
     * this to system lines on devices that honor it. Best-effort.
     */
    fun dumpLogcat(reason: String) {
        val d = dir ?: return
        try {
            val ts = System.currentTimeMillis()
            val out = File(d, "logcat_$ts.log")
            // -d = dump and exit; threadtime = pid/tid/timestamp
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            proc.inputStream.bufferedReader().use { r ->
                out.writeText("=== logcat dump ($reason) @${ts} ===\n")
                out.appendText(r.readText())
            }
            proc.waitFor()
            d(TAG, "dumpLogcat → ${out.name} (${out.length()}B)")        } catch (e: Throwable) {
            d(TAG, "dumpLogcat failed: ${e.message}")
        }
    }

    fun dir(): File? = dir
}
