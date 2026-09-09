// aether_core.hpp — JNI contract matching Engine.kt
#pragma once
#include <jni.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Memory — process attach / read / scan
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeAttach(JNIEnv*, jclass, jint, jstring);
JNIEXPORT jlong    JNICALL Java_com_aether_Engine_nativeFindModuleBase(JNIEnv*, jclass, jint, jstring);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeRead(JNIEnv*, jclass, jint, jlong, jlong);
JNIEXPORT jlong    JNICALL Java_com_aether_Engine_nativeScanAOB(JNIEnv*, jclass, jint, jlong, jlong, jbyteArray, jstring);

// Payload encoding (slots 3,4,5,9,12)
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompressPayload(JNIEnv*, jclass, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecompressPayload(JNIEnv*, jclass, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeEncryptPayload(JNIEnv*, jclass, jbyteArray, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecryptPayload(JNIEnv*, jclass, jbyteArray, jbyteArray);
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeWatchdogCheck(JNIEnv*, jclass);

// Native bridge (used — WIRING_AUDIT §B)
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompute(JNIEnv*, jclass, jint);
JNIEXPORT void     JNICALL Java_com_aether_Engine_nativeWriteLog(JNIEnv*, jclass, jstring);
JNIEXPORT void     JNICALL Java_com_aether_Engine_nativeSetSeed(JNIEnv*, jclass, jint);

// Hook + Binder
JNIEXPORT jlong    JNICALL Java_com_aether_Engine_nativeOffset(JNIEnv*, jclass);
JNIEXPORT jlong    JNICALL Java_com_aether_Engine_nativeOffset2(JNIEnv*, jclass);
// setAccessible/set/restoreBinderCalling* — removed: no callers (WIRING_AUDIT §B)

// String Encryption
JNIEXPORT jstring  JNICALL Java_com_aether_Engine_decryptString(JNIEnv*, jclass, jlong, jobjectArray);

// IO virtualization + hide detection + DEX loading
JNIEXPORT void     JNICALL Java_com_aether_Engine_enableIO(JNIEnv*, jclass);
JNIEXPORT void     JNICALL Java_com_aether_Engine_addIORule(JNIEnv*, jclass, jstring, jstring);
JNIEXPORT void     JNICALL Java_com_aether_Engine_hideXposed(JNIEnv*, jclass);
JNIEXPORT void     JNICALL Java_com_aether_Engine_installNetworkHttpProbe(JNIEnv*, jclass);
JNIEXPORT jlongArray JNICALL Java_com_aether_Engine_loadEmptyDex(JNIEnv*, jclass);

// Phase 3.1 — hydrate payloads (DATA_DUMP §4)
JNIEXPORT jint      JNICALL Java_com_aether_Engine_nativeHydratePayloads(JNIEnv*, jclass, jstring, jstring);

// Phase 3.5.D — class-map registry (NATIVE_LOGIC.md §B): feed loadClass redirect table
JNIEXPORT void     JNICALL Java_com_aether_Engine_addClassRule(JNIEnv*, jclass, jstring, jstring);
JNIEXPORT void     JNICALL Java_com_aether_Engine_clearClassRules(JNIEnv*, jclass);
JNIEXPORT jint     JNICALL Java_com_aether_Engine_classRuleCount(JNIEnv*, jclass);

#ifdef __cplusplus
}
#endif
