// aether_core.cpp — AetherCore JNI bridge (matches Engine.kt)
#include "aether_core.hpp"
#include "core/mem_reader.hpp"
#include "core/module_resolver.hpp"
#include "core/aob_scanner.hpp"
#include "core/crypto.hpp"
#include "core/config.hpp"
#include "core/stealth.hpp"
#include "core/binder.hpp"
#include "core/flagger.hpp"
#include "core/jni_hook.hpp"
#include "core/class_map.hpp"
#include "core/env_check.hpp"
#include "core/string_decryptor.hpp"
#include "core/payload_store.hpp"
#include "core/key_store.hpp"

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include <sys/system_properties.h>

#define AETHER_DEBUG 0
#if AETHER_DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AetherCore", __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

static JavaVM* g_vm = nullptr;

static std::string jstr(JNIEnv* e, jstring s) {
    if (!s) return "";
    const char* p = e->GetStringUTFChars(s, nullptr);
    std::string r(p ? p : "");
    if (p) e->ReleaseStringUTFChars(s, p);
    return r;
}

// ─── JNI_OnLoad ───
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* e;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    // [CUT 2026-09-11] anti-debug/protection — ทำให้แอพปิดตัวเองบนเครื่องจริง
    // (PTRACE_TRACEME + PR_SET_DUMPABLE=0) — งดก่อนเพื่อทดสอบการทำงานหลัก
    // aether::Stealth::blockDebugger();

    char sdk[PROP_VALUE_MAX];
    int api = (__system_property_get("ro.build.version.sdk", sdk) > 0) ? atoi(sdk) : 0;
    // [CUT] aether::Flagger::probeSdk(api);

    // Portable env probe (no hard-fail)
    // [CUT] (void)aether::EnvCheck::probe();
    // JNIEnv hook 4 slots — best-effort (no hard-fail)
    (void)aether::JniHook::install(e);

    jclass cls = e->FindClass("com/aether/Engine");
    if (!cls) return JNI_ERR;

    static const JNINativeMethod m[] = {
        {"nativeAttach","(ILjava/lang/String;)Z",(void*)Java_com_aether_Engine_nativeAttach},
        {"nativeFindModuleBase","(ILjava/lang/String;)J",(void*)Java_com_aether_Engine_nativeFindModuleBase},
        {"nativeRead","(IJJ)[B",(void*)Java_com_aether_Engine_nativeRead},
        {"nativeScanAOB","(IJJ[BLjava/lang/String;)J",(void*)Java_com_aether_Engine_nativeScanAOB},
        {"nativeCompressPayload","([B)[B",(void*)Java_com_aether_Engine_nativeCompressPayload},
        {"nativeDecompressPayload","([B)[B",(void*)Java_com_aether_Engine_nativeDecompressPayload},
        {"nativeEncryptPayload","([B[B)[B",(void*)Java_com_aether_Engine_nativeEncryptPayload},
        {"nativeDecryptPayload","([B[B)[B",(void*)Java_com_aether_Engine_nativeDecryptPayload},
        {"nativeWatchdogCheck","()Z",(void*)Java_com_aether_Engine_nativeWatchdogCheck},
        {"nativeCompute","(I)[B",(void*)Java_com_aether_Engine_nativeCompute},
        {"nativeValidate","([B)Z",(void*)Java_com_aether_Engine_nativeValidate},
        {"nativeExchangeKeys","(Ljava/lang/String;Ljava/lang/String;)V",(void*)Java_com_aether_Engine_nativeExchangeKeys},
        {"nativeWriteLog","(Ljava/lang/String;)V",(void*)Java_com_aether_Engine_nativeWriteLog},
        {"nativeDeriveKey","(I)Ljava/lang/String;",(void*)Java_com_aether_Engine_nativeDeriveKey},
        {"nativeProcessPair","(Ljava/lang/Object;Ljava/lang/Object;)V",(void*)Java_com_aether_Engine_nativeProcessPair},
        {"nativeProcessTriple","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",(void*)Java_com_aether_Engine_nativeProcessTriple},
        {"nativeReflectUpdate","(Ljava/lang/Object;Ljava/lang/reflect/Method;)V",(void*)Java_com_aether_Engine_nativeReflectUpdate},
        {"nativeInitContext","(Landroid/content/Context;)V",(void*)Java_com_aether_Engine_nativeInitContext},
        {"nativeEntropy","()J",(void*)Java_com_aether_Engine_nativeEntropy},
        {"nativeSetSeed","(I)V",(void*)Java_com_aether_Engine_nativeSetSeed},
        {"nativeOffset","()J",(void*)Java_com_aether_Engine_nativeOffset},
        {"nativeOffset2","()J",(void*)Java_com_aether_Engine_nativeOffset2},
        {"setAccessible","(Ljava/lang/reflect/Field;)V",(void*)Java_com_aether_Engine_setAccessible__Ljava_lang_reflect_Field_2},
        {"setAccessible","(Ljava/lang/reflect/Method;)V",(void*)Java_com_aether_Engine_setAccessible__Ljava_lang_reflect_Method_2},
        {"setBinderCallingPidOverride","(I)I",(void*)Java_com_aether_Engine_setBinderCallingPidOverride},
        {"setBinderCallingUidOverride","(I)I",(void*)Java_com_aether_Engine_setBinderCallingUidOverride},
        {"restoreBinderCallingPidOverride","(I)V",(void*)Java_com_aether_Engine_restoreBinderCallingPidOverride},
        {"restoreBinderCallingUidOverride","(I)V",(void*)Java_com_aether_Engine_restoreBinderCallingUidOverride},
        {"decryptString","(J[Ljava/lang/String;)Ljava/lang/String;",(void*)Java_com_aether_Engine_decryptString},
        {"enableIO","()V",(void*)Java_com_aether_Engine_enableIO},
        {"addIORule","(Ljava/lang/String;Ljava/lang/String;)V",(void*)Java_com_aether_Engine_addIORule},
        {"hideXposed","()V",(void*)Java_com_aether_Engine_hideXposed},
        {"installNetworkHttpProbe","()V",(void*)Java_com_aether_Engine_installNetworkHttpProbe},
        {"loadEmptyDex","()[J",(void*)Java_com_aether_Engine_loadEmptyDex},
        {"nativeHydratePayloads","(Ljava/lang/String;Ljava/lang/String;)I",(void*)Java_com_aether_Engine_nativeHydratePayloads},
        {"nativeDecryptPayloadByHash","(Ljava/lang/String;[B)[B",(void*)Java_com_aether_Engine_nativeDecryptPayloadByHash},
        {"addClassRule","(Ljava/lang/String;Ljava/lang/String;)V",(void*)Java_com_aether_Engine_addClassRule},
        {"clearClassRules","()V",(void*)Java_com_aether_Engine_clearClassRules},
        {"classRuleCount","()I",(void*)Java_com_aether_Engine_classRuleCount},
    };
    if (e->RegisterNatives(cls, m, sizeof(m)/sizeof(m[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}

// ─── Memory ───
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeAttach(JNIEnv* e, jclass, jint pid, jstring mod) {
    auto* maps = aether::ModuleResolver::getMaps(pid);
    return (maps && !maps->empty() && aether::ModuleResolver::findBase(pid, jstr(e, mod)) != 0) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeFindModuleBase(JNIEnv* e, jclass, jint pid, jstring mod) {
    return (jlong)aether::ModuleResolver::findBase(pid, jstr(e, mod));
}
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeRead(JNIEnv* e, jclass, jint pid, jlong addr, jlong size) {
    if (size <= 0 || size > 64LL*1024*1024) return nullptr;
    std::vector<uint8_t> buf((size_t)size);
    ssize_t n = aether::MemReader::read(pid, (uintptr_t)addr, buf.data(), (size_t)size);
    if (n <= 0) return nullptr;
    jbyteArray arr = e->NewByteArray((jsize)n);
    e->SetByteArrayRegion(arr, 0, (jsize)n, reinterpret_cast<const jbyte*>(buf.data()));
    return arr;
}
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeScanAOB(JNIEnv* e, jclass, jint pid, jlong base, jlong size, jbyteArray pat, jstring mask) {
    jsize plen = e->GetArrayLength(pat);
    std::vector<uint8_t> pattern(plen);
    e->GetByteArrayRegion(pat, 0, plen, reinterpret_cast<jbyte*>(pattern.data()));
    std::string m = jstr(e, mask);
    return (jlong)aether::AobScanner::scan(pid, (uintptr_t)base, (size_t)size, pattern, m);
}

// ─── Payload encoding (slots 3,4,5,9,12) ───
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompressPayload(JNIEnv* e, jclass, jbyteArray in) {
    jsize n = e->GetArrayLength(in); std::vector<uint8_t> src(n);
    e->GetByteArrayRegion(in, 0, n, reinterpret_cast<jbyte*>(src.data()));
    std::vector<uint8_t> out; if (!aether::Crypto::compress(src, out)) return nullptr;
    jbyteArray r = e->NewByteArray((jsize)out.size());
    e->SetByteArrayRegion(r, 0, (jsize)out.size(), reinterpret_cast<const jbyte*>(out.data())); return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecompressPayload(JNIEnv* e, jclass, jbyteArray in) {
    jsize n = e->GetArrayLength(in); std::vector<uint8_t> src(n);
    e->GetByteArrayRegion(in, 0, n, reinterpret_cast<jbyte*>(src.data()));
    std::vector<uint8_t> out; if (!aether::Crypto::decompress(src, out)) return nullptr;
    jbyteArray r = e->NewByteArray((jsize)out.size());
    e->SetByteArrayRegion(r, 0, (jsize)out.size(), reinterpret_cast<const jbyte*>(out.data())); return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeEncryptPayload(JNIEnv* e, jclass, jbyteArray in, jbyteArray key) {
    jsize n = e->GetArrayLength(in), kn = e->GetArrayLength(key);
    std::vector<uint8_t> src(n), k(kn);
    e->GetByteArrayRegion(in, 0, n, reinterpret_cast<jbyte*>(src.data()));
    e->GetByteArrayRegion(key, 0, kn, reinterpret_cast<jbyte*>(k.data()));
    std::vector<uint8_t> out; aether::Crypto::xorCrypt(src, k, out);
    jbyteArray r = e->NewByteArray((jsize)out.size());
    e->SetByteArrayRegion(r, 0, (jsize)out.size(), reinterpret_cast<const jbyte*>(out.data())); return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecryptPayload(JNIEnv* e, jclass, jbyteArray in, jbyteArray key) {
    return Java_com_aether_Engine_nativeEncryptPayload(e, nullptr, in, key); // xor symmetric
}
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeWatchdogCheck(JNIEnv*, jclass) {
    return aether::Stealth::watchdog() ? JNI_TRUE : JNI_FALSE;
}

// ─── Native bridge (11 core) ───
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeCompute(JNIEnv* e, jclass, jint input) {
    std::vector<uint8_t> out(8);
    uint64_t v = aether::Config::entropy() ^ (uint64_t)input;
    memcpy(out.data(), &v, 8);
    jbyteArray r = e->NewByteArray(8);
    e->SetByteArrayRegion(r, 0, 8, reinterpret_cast<const jbyte*>(out.data())); return r;
}
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeValidate(JNIEnv* e, jclass, jbyteArray data) {
    return data ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeExchangeKeys(JNIEnv*, jclass, jstring, jstring) { LOGD("exchangeKeys"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeWriteLog(JNIEnv* e, jclass, jstring msg) { LOGD("log: %s", jstr(e, msg).c_str()); }
JNIEXPORT jstring JNICALL Java_com_aether_Engine_nativeDeriveKey(JNIEnv* e, jclass, jint seed) {
    std::string k = aether::Config::deriveKey(seed); return e->NewStringUTF(k.c_str());
}
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeProcessPair(JNIEnv*, jclass, jobject, jobject) { LOGD("processPair"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeProcessTriple(JNIEnv*, jclass, jobject, jobject, jobject) { LOGD("processTriple"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeReflectUpdate(JNIEnv*, jclass, jobject, jobject) { LOGD("reflectUpdate"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeInitContext(JNIEnv*, jclass, jobject) { LOGD("initContext"); }
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeEntropy(JNIEnv*, jclass) { return (jlong)aether::Config::entropy(); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeSetSeed(JNIEnv*, jclass, jint seed) { aether::Config::setSeed(seed); }

// ─── Hook + Binder ───
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset(JNIEnv*, jclass) { return aether::Config::getOffset("offset"); }
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset2(JNIEnv*, jclass) { return aether::Config::getOffset("offset2"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_setAccessible__Ljava_lang_reflect_Field_2(JNIEnv*, jclass, jobject) { LOGD("setAccessible(Field)"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_setAccessible__Ljava_lang_reflect_Method_2(JNIEnv*, jclass, jobject) { LOGD("setAccessible(Method)"); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_setBinderCallingPidOverride(JNIEnv*, jclass, jint pid) { return aether::Binder::overridePid(pid); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_setBinderCallingUidOverride(JNIEnv*, jclass, jint uid) { return aether::Binder::overrideUid(uid); }
JNIEXPORT void JNICALL Java_com_aether_Engine_restoreBinderCallingPidOverride(JNIEnv*, jclass, jint old) { aether::Binder::restorePid(old); }
JNIEXPORT void JNICALL Java_com_aether_Engine_restoreBinderCallingUidOverride(JNIEnv*, jclass, jint old) { aether::Binder::restoreUid(old); }

// ─── String Encryption ───
// เรียก StrDecrypt::decrypt ตาม AetherMind/NATIVE_LOGIC.md §C (key 0x30261adb60b7b4f4 + counter + LCG rotate)
// เลิกใช้ Crypto::xorCrypt (simple repeating-XOR) — ไม่ตรงสเปก Aether stream cipher
// `key` parameter (jlong) ใช้เป็น kKey override (0 = default aetherKey)
// `args` = ciphertext chunks (เพื่อรองรับ multi-part string table entry)
JNIEXPORT jstring JNICALL Java_com_aether_Engine_decryptString(JNIEnv* e, jclass, jlong key, jobjectArray args) {
    if (!args) return nullptr;
    jsize n = e->GetArrayLength(args);
    if (n == 0) return e->NewStringUTF("");

    // ใช้ aetherKey เป็น default; key param ไม่ใช่ 0 → override (สำหรับ variant cipher)
    if (key != 0) {
        aether::StrDecrypt::setKeyOverride(static_cast<uint64_t>(key));
    }

    std::string result;
    for (jsize i = 0; i < n; ++i) {
        jstring s = (jstring)e->GetObjectArrayElement(args, i);
        std::string str = jstr(e, s);
        std::vector<uint8_t> in(str.begin(), str.end());
        result.append(aether::StrDecrypt::decrypt(in));
        e->DeleteLocalRef(s);
    }

    if (key != 0) {
        aether::StrDecrypt::clearKeyOverride();
    }
    return e->NewStringUTF(result.c_str());
}

// ─── IO virtualization + hide detection + DEX loading ───
JNIEXPORT void JNICALL Java_com_aether_Engine_enableIO(JNIEnv*, jclass) { LOGD("enableIO"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_addIORule(JNIEnv* e, jclass, jstring p, jstring r) { LOGD("addIORule %s->%s", jstr(e,p).c_str(), jstr(e,r).c_str()); }

// ─── Phase 3.5.D — class-map registry (NATIVE_LOGIC.md §B) ───
// feed loadClass redirect table used by JniHook custom_loadClass → ClassMap::tryRedirect
JNIEXPORT void JNICALL Java_com_aether_Engine_addClassRule(JNIEnv* e, jclass, jstring dotted, jstring slashedTarget) {
    if (!dotted || !slashedTarget) return;
    aether::ClassMap::put(jstr(e, dotted), jstr(e, slashedTarget));
    LOGD("addClassRule %s->%s", jstr(e,dotted).c_str(), jstr(e,slashedTarget).c_str());
}
JNIEXPORT void JNICALL Java_com_aether_Engine_clearClassRules(JNIEnv*, jclass) { aether::ClassMap::clear(); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_classRuleCount(JNIEnv*, jclass) { return (jint)aether::ClassMap::size(); }
JNIEXPORT void JNICALL Java_com_aether_Engine_hideXposed(JNIEnv*, jclass) { LOGD("hideXposed"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_installNetworkHttpProbe(JNIEnv*, jclass) { LOGD("installNetworkHttpProbe"); }
JNIEXPORT jlongArray JNICALL Java_com_aether_Engine_loadEmptyDex(JNIEnv* e, jclass) {
    jlongArray out = e->NewLongArray(1); jlong v[1] = { 0 };
    e->SetLongArrayRegion(out, 0, 1, v); return out;
}

// ─── Phase 3.1+3.2 — payload hydration + decrypt (DATA_DUMP §4) ───
// nativeHydratePayloads(filesDir, jklHex) → loadDir(filesDir) + return count
// jklHex = "010100640100000000000000000100001400000000006464000000000100" (DATA_DUMP §4.3)
JNIEXPORT jint JNICALL Java_com_aether_Engine_nativeHydratePayloads(JNIEnv* e, jclass, jstring dir, jstring jklHex) {
    std::string dirPath = jstr(e, dir);
    std::string hex     = jstr(e, jklHex);
    size_t n = aether::PayloadStore::loadDir(dirPath);
    LOGD("nativeHydratePayloads: dir=%s loaded=%zu jklHex=%s", dirPath.c_str(), n, hex.c_str());
    return (jint)n;
}

// nativeDecryptPayloadByHash(sha256hex, jklKeyBytes) → plaintext bytes
// return null array ถ้าไม่เจอหรือ jkl invalid
JNIEXPORT jbyteArray JNICALL Java_com_aether_Engine_nativeDecryptPayloadByHash(JNIEnv* e, jclass, jstring sha, jbyteArray jklKey) {
    std::string hash = jstr(e, sha);
    jsize kn = e->GetArrayLength(jklKey);
    std::vector<uint8_t> key(kn);
    e->GetByteArrayRegion(jklKey, 0, kn, reinterpret_cast<jbyte*>(key.data()));
    std::vector<uint8_t> plain = aether::PayloadStore::decrypt(hash, key);
    if (plain.empty()) return nullptr;
    jbyteArray out = e->NewByteArray((jsize)plain.size());
    e->SetByteArrayRegion(out, 0, (jsize)plain.size(), reinterpret_cast<const jbyte*>(plain.data()));
    return out;
}
