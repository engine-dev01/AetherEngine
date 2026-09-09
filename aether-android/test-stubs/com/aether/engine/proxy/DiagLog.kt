/*
 * Test-time DiagLog stub — replaces the production DiagLog only in the
 * unit-test classpath so that AetherInstrumentation (which calls DiagLog.d
 * and DiagLog.err internally) can compile and run on plain JVM without
 * android.*, java.io.File, or android.util.Log dependencies.
 *
 * Production DiagLog is unchanged: it writes timestamped lines to
 * <filesDir>/diag/trace.log. This stub does nothing, which is correct for
 * a JVM-only unit test — we never assert on log content.
 */
package com.aether.engine.proxy

object DiagLog {
    @JvmStatic
    fun d(tag: String, msg: String) { /* no-op for JVM unit test */ }
    @JvmStatic
    fun i(tag: String, msg: String) { /* no-op */ }
    @JvmStatic
    fun w(tag: String, msg: String) { /* no-op */ }
    @JvmStatic
    fun e(tag: String, msg: String) { /* no-op */ }
    @JvmStatic
    fun err(tag: String, op: String, t: Throwable) { /* no-op */ }
}
