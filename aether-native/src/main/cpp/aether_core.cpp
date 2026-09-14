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
#include "core/string_decryptor.hpp"
#include "core/payload_store.hpp"
#include "core/key_store.hpp"
#include "layer/bindmount/virtual_fs.hpp"

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

    // audit C5-uncalled: env_check.cpp ถอดออก (ทั้ง probe ถูก [CUT] และ blueprint
    // B3 ตัด detection/stealth — snake รันด้วย virtual mechanism ไม่ใช่การซ่อน)
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
        {"nativeProcessPair","(Ljava/lang/Object;Ljava/lang/Object;)V",(void*)Java_com_aether_Engine_nativeProcessPair},
        {"nativeProcessTriple","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",(void*)Java_com_aether_Engine_nativeProcessTriple},
        {"nativeReflectUpdate","(Ljava/lang/Object;Ljava/lang/reflect/Method;)V",(void*)Java_com_aether_Engine_nativeReflectUpdate},
        {"nativeInitContext","(Landroid/content/Context;)V",(void*)Java_com_aether_Engine_nativeInitContext},
        {"nativeSetSeed","(I)V",(void*)Java_com_aether_Engine_nativeSetSeed},
        {"nativeOffset","()J",(void*)Java_com_aether_Engine_nativeOffset},
        {"nativeOffset2","()J",(void*)Java_com_aether_Engine_nativeOffset2},
        {"setBinderCallingPidOverride","(I)I",(void*)Java_com_aether_Engine_setBinderCallingPidOverride},
        {"setBinderCallingUidOverride","(I)I",(void*)Java_com_aether_Engine_setBinderCallingUidOverride},
        {"restoreBinderCallingPidOverride","(I)V",(void*)Java_com_aether_Engine_restoreBinderCallingPidOverride},
        {"restoreBinderCallingUidOverride","(I)V",(void*)Java_com_aether_Engine_restoreBinderCallingUidOverride},
        {"enableIO","()V",(void*)Java_com_aether_Engine_enableIO},
        {"addIORule","(Ljava/lang/String;Ljava/lang/String;)V",(void*)Java_com_aether_Engine_addIORule},
        {"nativeHydratePayloads","(Ljava/lang/String;Ljava/lang/String;)I",(void*)Java_com_aether_Engine_nativeHydratePayloads},
        {"nativeExemptHiddenApi","()Z",(void*)Java_com_aether_Engine_nativeExemptHiddenApi},
        {"nativeResolvePath","(Ljava/lang/String;)Ljava/lang/String;",(void*)Java_com_aether_Engine_nativeResolvePath},
        {"nativeIORuleCount","()I",(void*)Java_com_aether_Engine_nativeIORuleCount},
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
// AETHER-NOOP(ac): hook-proxy semantics (hidden-dex ของ snake) ไม่ถูก port —
// trampoline คงตำแหน่ง/ signature ตาม T1 เพื่อ chain เดินต่อได้
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeProcessPair(JNIEnv*, jclass, jobject, jobject) { LOGD("processPair"); }
// AETHER-NOOP(pjowqpxe): caller-side hidden dex = D6 — body deliberately empty
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeProcessTriple(JNIEnv*, jclass, jobject, jobject, jobject) { LOGD("processTriple"); }
// AETHER-NOOP(update): FromReflectedMethod pipeline = D7 — body deliberately empty
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeReflectUpdate(JNIEnv*, jclass, jobject, jobject) { LOGD("reflectUpdate"); }
// AETHER-NOOP(ic): body = snake protection init ที่ยังไม่ port — Kotlin chain ถือ
// identity จริง (jv0 bind path); gate ต้องพิมพ์ NO-OP ไม่ใช่ PASS
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeInitContext(JNIEnv*, jclass, jobject) { LOGD("initContext"); }
JNIEXPORT void JNICALL Java_com_aether_Engine_nativeSetSeed(JNIEnv*, jclass, jint seed) { aether::Config::setSeed(seed); }

// ─── Hook + Binder ───
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset(JNIEnv*, jclass) { return aether::Config::getOffset("offset"); }
JNIEXPORT jlong JNICALL Java_com_aether_Engine_nativeOffset2(JNIEnv*, jclass) { return aether::Config::getOffset("offset2"); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_setBinderCallingPidOverride(JNIEnv*, jclass, jint pid) { return aether::Binder::overridePid(pid); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_setBinderCallingUidOverride(JNIEnv*, jclass, jint uid) { return aether::Binder::overrideUid(uid); }
JNIEXPORT void JNICALL Java_com_aether_Engine_restoreBinderCallingPidOverride(JNIEnv*, jclass, jint old) { aether::Binder::restorePid(old); }
JNIEXPORT void JNICALL Java_com_aether_Engine_restoreBinderCallingUidOverride(JNIEnv*, jclass, jint old) { aether::Binder::restoreUid(old); }

// (decryptString/nativeValidate/... ตัด 2026-09-14 — audit C3/C4: ไม่มี caller
//  ใน Kotlin run-chain; ดู docs/CUTS.md)

// ─── IO virtualization (L1 VirtualFS — layer/bindmount/virtual_fs.cpp) ───
// audit C4/C5: เดิม enableIO/addIORule เป็น LOG-ONLY และ virtual_fs.cpp เป็น
// orphan TU (gc-sections ทิ้ง) — ตอนนี้ rules อยู่ใน VirtualFS singleton ระดับ
// process; nativeResolvePath/nativeIORuleCount ให้ Kotlin self-test ผ่าน
// native จริง (P2 C13-vfs-selftest) แทนการ assert กับ map ฝั่ง JVM อย่างเดียว
static aether::l1::VirtualFS& g_vfs() {
    static aether::l1::VirtualFS fs;
    return fs;
}
static bool g_io_enabled = false;

JNIEXPORT void JNICALL Java_com_aether_Engine_enableIO(JNIEnv*, jclass) {
    g_io_enabled = true;
    LOGD("enableIO: VirtualFS active (rules=%zu)", g_vfs().getAllRedirects().size());
}
JNIEXPORT void JNICALL Java_com_aether_Engine_addIORule(JNIEnv* e, jclass, jstring p, jstring r) {
    if (!p || !r) return;
    std::string fake = jstr(e, p), real = jstr(e, r);
    g_vfs().addRedirect(fake, real);
    LOGD("addIORule %s->%s (count=%zu enabled=%d)", fake.c_str(), real.c_str(),
         g_vfs().getAllRedirects().size(), g_io_enabled ? 1 : 0);
}
JNIEXPORT jstring JNICALL Java_com_aether_Engine_nativeResolvePath(JNIEnv* e, jclass, jstring path) {
    if (!path) return nullptr;
    std::string resolved = g_vfs().resolve(jstr(e, path));
    return e->NewStringUTF(resolved.c_str());
}
JNIEXPORT jint JNICALL Java_com_aether_Engine_nativeIORuleCount(JNIEnv*, jclass) {
    return (jint)g_vfs().getAllRedirects().size();
}

// ─── Hidden-API exemption (audit C6) ───
// chain ยิง reflection ~20 restricted members แต่ทั้ง repo ไม่เคยขอ exemption —
// native เรียก dalvik.system.VMRuntime.setHiddenApiExemptions(["L"]) ได้โดยไม่
// ติด restriction; AetherApp เรียกทันทีหลัง loadLibrary (ทุก process/role)
JNIEXPORT jboolean JNICALL Java_com_aether_Engine_nativeExemptHiddenApi(JNIEnv* e, jclass) {
    jclass vmrt = e->FindClass("dalvik/system/VMRuntime");
    if (!vmrt) { e->ExceptionClear(); return JNI_FALSE; }
    jmethodID getrt = e->GetStaticMethodID(vmrt, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jmethodID setex = e->GetMethodID(vmrt, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    jobject rt = getrt ? e->CallStaticObjectMethod(vmrt, getrt) : nullptr;
    if (!rt || !setex) { e->ExceptionClear(); return JNI_FALSE; }
    jclass strcls = e->FindClass("java/lang/String");
    if (!strcls) { e->ExceptionClear(); return JNI_FALSE; }
    jobjectArray arr = e->NewObjectArray(1, strcls, e->NewStringUTF("L"));
    if (!arr) { e->ExceptionClear(); return JNI_FALSE; }
    e->CallVoidMethod(rt, setex, arr);
    if (e->ExceptionCheck()) { e->ExceptionClear(); return JNI_FALSE; }
    LOGD("hidden-API exemptions set (prefix L)");
    return JNI_TRUE;
}

// ─── Phase 3.5.D — class-map registry (NATIVE_LOGIC.md §B) ───
// feed loadClass redirect table used by JniHook custom_loadClass → ClassMap::tryRedirect
JNIEXPORT void JNICALL Java_com_aether_Engine_addClassRule(JNIEnv* e, jclass, jstring dotted, jstring slashedTarget) {
    if (!dotted || !slashedTarget) return;
    aether::ClassMap::put(jstr(e, dotted), jstr(e, slashedTarget));
    LOGD("addClassRule %s->%s", jstr(e,dotted).c_str(), jstr(e,slashedTarget).c_str());
}
JNIEXPORT void JNICALL Java_com_aether_Engine_clearClassRules(JNIEnv*, jclass) { aether::ClassMap::clear(); }
JNIEXPORT jint JNICALL Java_com_aether_Engine_classRuleCount(JNIEnv*, jclass) { return (jint)aether::ClassMap::size(); }

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
