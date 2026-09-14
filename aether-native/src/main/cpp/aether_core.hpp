// aether-core JNI contract — matches Engine.kt external funs + JNINativeMethod table
// (28 entries: 26 คงเดิม + nativeResolvePath/nativeIORuleCount — audit round 2026-09-14)
//
// NOTE 2026-09-14 (audit C3/C4): ตัดประกาศของ native ที่ไม่มี Kotlin call-site
// และ body เป็น LOG-ONLY: nativeValidate, nativeExchangeKeys, nativeWriteLog,
// nativeDeriveKey, nativeEntropy, setAccessible(Field/Method), decryptString,
// hideXposed, installNetworkHttpProbe, loadEmptyDex, nativeDecryptPayloadByHash
// — คืนค่าได้จาก git history; เหตุผลรายตัวใน docs/CUTS.md
#pragma once
#include <jni.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Memory — process attach / read / scan
JNIEXPORT jboolean   JNICALL Java_com_aether_Engine_nativeAttach(JNIEnv*, jclass, jint, jstring);
JNIEXPORT jlong      JNICALL Java_com_aether_Engine_nativeFindModuleBase(JNIEnv*, jclass, jint, jstring);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeRead(JNIEnv*, jclass, jint, jlong, jlong);
JNIEXPORT jlong      JNICALL Java_com_aether_Engine_nativeScanAOB(JNIEnv*, jclass, jint, jlong, jlong, jbyteArray, jstring);

// Payload encoding (slots 3,4,5,9,12)
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompressPayload(JNIEnv*, jclass, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecompressPayload(JNIEnv*, jclass, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeEncryptPayload(JNIEnv*, jclass, jbyteArray, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecryptPayload(JNIEnv*, jclass, jbyteArray, jbyteArray);
JNIEXPORT jboolean   JNICALL Java_com_aether_Engine_nativeWatchdogCheck(JNIEnv*, jclass);

// Native bridge (chain hops — T1 F2 counterparts: ic/i/ac/pjowqpxe/update)
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompute(JNIEnv*, jclass, jint);
JNIEXPORT void       JNICALL Java_com_aether_Engine_nativeProcessPair(JNIEnv*, jclass, jobject, jobject);
JNIEXPORT void       JNICALL Java_com_aether_Engine_nativeProcessTriple(JNIEnv*, jclass, jobject, jobject, jobject);
JNIEXPORT void       JNICALL Java_com_aether_Engine_nativeReflectUpdate(JNIEnv*, jclass, jobject, jobject);
JNIEXPORT void       JNICALL Java_com_aether_Engine_nativeInitContext(JNIEnv*, jclass, jobject);
JNIEXPORT void       JNICALL Java_com_aether_Engine_nativeSetSeed(JNIEnv*, jclass, jint);

// Hook + Binder
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset(JNIEnv*, jclass);
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset2(JNIEnv*, jclass);
// (binder override ×4 cut 2026-09-14 — audit C13-binder; docs/CUTS.md)

// Hidden-API exemption (audit C6) + IO virtualization ผ่าน L1 VirtualFS (C4/C5)
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeExemptHiddenApi(JNIEnv*, jclass);
JNIEXPORT void     JNICALL Java_com_aether_Engine_enableIO(JNIEnv*, jclass);
JNIEXPORT void     JNICALL Java_com_aether_Engine_addIORule(JNIEnv*, jclass, jstring, jstring);
JNIEXPORT jstring  JNICALL Java_com_aether_Engine_nativeResolvePath(JNIEnv*, jclass, jstring);
JNIEXPORT jint     JNICALL Java_com_aether_Engine_nativeIORuleCount(JNIEnv*, jclass);

// Phase 3.1 — hydrate payloads (DATA_DUMP(UNVERIFIED) §4)
JNIEXPORT jint JNICALL Java_com_aether_Engine_nativeHydratePayloads(JNIEnv*, jclass, jstring, jstring);

// Phase 3.5.D — class-map registry (NATIVE_LOGIC(transcript สูญ-UNVERIFIED) §B)
JNIEXPORT void JNICALL Java_com_aether_Engine_addClassRule(JNIEnv*, jclass, jstring, jstring);
JNIEXPORT void JNICALL Java_com_aether_Engine_clearClassRules(JNIEnv*, jclass);
JNIEXPORT jint  JNICALL Java_com_aether_Engine_classRuleCount(JNIEnv*, jclass);

#ifdef __cplusplus
}
#endif
