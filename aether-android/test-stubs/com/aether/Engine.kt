/*
 * Test-time Engine stub — production com.aether.Engine declares `external fun`s
 * (JNI, in libaether.so). A JVM-only unit test has no native lib, so the stub
 * provides the same callable surface as plain no-op functions.
 *
 * Scope: only the members referenced by sources compiled in localTest
 * (AetherInstrumentation.kt → nativeProcessPair; add more when used).
 * Signature must stay assignment-compatible with the real object.
 */
package com.aether

object Engine {
    // ≡ T1 F2: Native;->ac(Ljava/lang/Object;Ljava/lang/Object;)V
    @JvmStatic
    fun nativeProcessPair(o1: Any?, o2: Any?) { /* no-op for JVM unit test */ }

    @JvmStatic
    fun nativeProcessTriple(o1: Any?, o2: Any?, o3: Any?) { /* no-op */ }

    @JvmStatic
    fun nativeInitContext(context: android.content.Context?) { /* no-op */ }

    @JvmStatic
    fun nativeSetSeed(seed: Int) { /* no-op */ }
}
