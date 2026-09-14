package com.aether

object Engine {
    // NOTE: libaether.so is loaded exactly ONCE in AetherApp.onCreate() with
    // proper error handling. Do NOT call System.loadLibrary here — if the load
    // fails, this object initializer would throw ExceptionInInitializerError
    // (a java.lang.Error, not Exception) which escapes every catch(e: Exception)
    // in the callers and force-closes the app at launch.

    // Memory — process attach / read / scan
    external fun nativeAttach(pid: Int, moduleName: String): Boolean
    external fun nativeFindModuleBase(pid: Int, moduleName: String): Long
    external fun nativeRead(pid: Int, address: Long, size: Long): ByteArray?
    external fun nativeScanAOB(pid: Int, base: Long, size: Long, pattern: ByteArray, mask: String): Long

 // ─── Payload encoding (slots 3,4,5,7,9,12) ───
    external fun nativeCompressPayload(data: ByteArray): ByteArray?      // slot 3 — zlib deflate
    external fun nativeDecompressPayload(data: ByteArray): ByteArray?    // slot 3 — inflate
    external fun nativeEncryptPayload(data: ByteArray, key: ByteArray): ByteArray?  // slot 4
    external fun nativeDecryptPayload(data: ByteArray, key: ByteArray): ByteArray?  // slot 5
    external fun nativeWatchdogCheck(): Boolean  // slot 12

    // ─── Native bridge (11 core methods) ───
    external fun nativeCompute(input: Int): ByteArray?                  // compute — main algorithm
    external fun nativeProcessPair(o1: Any?, o2: Any?)                       // processPair
    external fun nativeProcessTriple(o1: Any?, o2: Any?, o3: Any?)      // processTriple
    external fun nativeReflectUpdate(obj: Any?, method: java.lang.reflect.Method?) // reflectUpdate
    external fun nativeInitContext(context: android.content.Context?)        // initContext
    external fun nativeSetSeed(seed: Int)                                  // setSeed

    // ══════════════════════════════════════════
    //  Phase 3: JNI Hook + Binder PID/UID Override
    // ══════════════════════════════════════════
    external fun nativeOffset(): Long                      // Native offset discovery
    external fun nativeOffset2(): Long                     // Secondary offset discovery

    // ══════════════════════════════════════════
    //  Phase 4: String Encryption (native decryptor)
    // ══════════════════════════════════════════

    // ══════════════════════════════════════════
    //  Phase 5: Network IO + HideXposed + DEX Loading
    // ══════════════════════════════════════════
    external fun enableIO()                                   // Enable IO virtualization
    external fun addIORule(path: String, redirect: String)    // Add virtual FS rule
    external fun nativeResolvePath(path: String): String?     // native VirtualFS resolve (round-2: self-test ผ่าน native จริง)
    external fun nativeIORuleCount(): Int                     // จำนวน rule ที่ native ถืออยู่จริง
    external fun nativeExemptHiddenApi(): Boolean             // VMRuntime.setHiddenApiExemptions(["L"]) — audit C6

    // ══════════════════════════════════════════
    //  Phase 3.1+3.2 — Payload hydration + decrypt (DATA_DUMP(UNVERIFIED) §4)
    // ══════════════════════════════════════════
    external fun nativeHydratePayloads(dirPath: String, jklHex: String): Int   // loadDir + return count

    // ══════════════════════════════════════════
    //  Phase 3.5.D — class-map registry (NATIVE_LOGIC(transcript สูญ-UNVERIFIED) §B)
    //  Feeds the loadClass redirect table (JniHook custom_loadClass).
    // ══════════════════════════════════════════
    external fun addClassRule(dottedRequested: String, slashedTarget: String)  // register redirect
    external fun clearClassRules()                                             // clear registry
    external fun classRuleCount(): Int                                         // active rule count
}
