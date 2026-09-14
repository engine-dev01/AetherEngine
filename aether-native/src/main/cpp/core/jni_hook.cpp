// jni_hook.cpp — JNIEnv table hook 4 จุด (NATIVE_LOGIC(transcript สูญ-UNVERIFIED) §A)
// ต้นแบบ libengine_2.so: 0x8f614 archive .bss @0x8285F0 แล้ว patch indices 33-36
// Aether: portable trampoline แบบ documented — ไม่ทำ mmap RWX/JIT (ต่างจากต้นแบบ)
// Hook เฉพาะ loadClass เพื่อ class-map redirection (core/class_map)

#include "jni_hook.hpp"
#include "class_map.hpp"

#include <jni.h>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>

#define LOG_TAG "AetherJniHook"
#define AETHER_DEBUG 0
#if AETHER_DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

namespace aether::JniHook {

// ── originals (copied ก่อน patch) ──
static JNINativeInterface origTable{};
static JNINativeInterface* g_live = nullptr;
static jmethodID g_loadClassMid = nullptr;
static bool g_installed = false;
// ป้องกัน double-install: เก็บ magic ที่ index 0 (reserved field) ของ origTable
// ถ้า install() รัน 2 รอบ magic จะตรง → return false (no-op)
// ถ้า origTable ถูก clobber โดย lib อื่น magic หาย → restore invalid → return false
static constexpr uintptr_t kHookMagic = 0xA37E2C5F1B8D4E69ULL;

// ต้นแบบ strings (NATIVE_LOGIC(transcript สูญ-UNVERIFIED) §A.2)
static constexpr const char* kLoadClassName = "loadClass";
static constexpr const char* kLoadClassSig  = "(Ljava/lang/String;)Ljava/lang/Class;";

// helpers: make region writable
static bool makeWritable(void* addr) {
    uintptr_t page = (uintptr_t)addr & ~(4096ull - 1);
    return mprotect((void*)page, 4096, PROT_READ | PROT_WRITE) == 0;
}

// ── hook bodies ──
static jmethodID hook_GetMethodID(JNIEnv* env, jclass cls,
                                  const char* name, const char* sig) {
    // call original
    jmethodID mid = origTable.GetMethodID(env, cls, name, sig);
    if (mid && name && sig
        && strcmp(name, kLoadClassName) == 0
        && strcmp(sig, kLoadClassSig) == 0) {
        g_loadClassMid = mid;
        LOGD("GetMethodID cache loadClass mid=%p", (void*)mid);
    }
    return mid;
}

static jobject hook_CallObjectMethod(JNIEnv* env, jobject obj, jmethodID mid, ...) {
    if (g_loadClassMid && mid == g_loadClassMid) {
        va_list ap; va_start(ap, mid);
        jstring cname = va_arg(ap, jstring);
        va_end(ap);
        if (cname) {
            jobject redirected = ClassMap::tryRedirect(env, cname);
            if (redirected) return redirected;
        }
    }
    // forward to original via va_list entry point (CallObjectMethodV signature)
    // เดิมเรียก origTable.CallObjectMethod ตรง ๆ ซึ่ง signature ... ไม่ตรง va_list
    // ใช้ va_list ของ caller + CallObjectMethodV (compatible signature)
    va_list ap; va_start(ap, mid);
    jobject result = origTable.CallObjectMethodV(env, obj, mid, ap);
    va_end(ap);
    return result;
}

static jobject hook_CallObjectMethodV(JNIEnv* env, jobject obj, jmethodID mid, va_list args) {
    if (g_loadClassMid && mid == g_loadClassMid) {
        va_list copy; va_copy(copy, args);
        jstring cname = va_arg(copy, jstring);
        va_end(copy);
        if (cname) {
            jobject redirected = ClassMap::tryRedirect(env, cname);
            if (redirected) return redirected;
        }
    }
    return origTable.CallObjectMethodV(env, obj, mid, args);
}

static jobject hook_CallObjectMethodA(JNIEnv* env, jobject obj, jmethodID mid, const jvalue* args) {
    if (g_loadClassMid && mid == g_loadClassMid) {
        if (args && args[0].l) {
            jstring cname = (jstring)args[0].l;
            jobject redirected = ClassMap::tryRedirect(env, cname);
            if (redirected) return redirected;
        }
    }
    return origTable.CallObjectMethodA(env, obj, mid, args);
}

bool install(JNIEnv* env) {
    if (g_installed) return true;  // double-install guard
    if (!env) return false;
    void* live = *reinterpret_cast<void**>(env); // JNINativeInterface*
    if (!live) return false;
    g_live = reinterpret_cast<JNINativeInterface*>(live);

    // archive whole table (1864B = 233 ptrs) — same as prototype
    memcpy(&origTable, g_live, sizeof(JNINativeInterface));
    // เก็บ magic ใน reserved field (index 0 = reserved[0] ใน JNINativeInterface struct)
    // ป้องกัน double-install clobber + verify table integrity ก่อน patch
    origTable.reserved0 = reinterpret_cast<void*>(kHookMagic);

    if (!makeWritable(g_live)) return false;

    g_live->GetMethodID          = hook_GetMethodID;
    g_live->CallObjectMethod     = hook_CallObjectMethod;
    g_live->CallObjectMethodV    = hook_CallObjectMethodV;
    g_live->CallObjectMethodA    = hook_CallObjectMethodA;

    // restore RO (best-effort)
    mprotect((void*)((uintptr_t)g_live & ~(4096ull-1)), 4096, PROT_READ);

    g_installed = true;
    LOGD("JniHook installed (4 slots, magic=0x%llx)", (unsigned long long)kHookMagic);
    return true;
}

void uninstall() {
    if (!g_installed || !g_live) return;
    // verify magic ก่อน restore — ถ้า origTable ถูก clobber จะ restore ผิด
    if (reinterpret_cast<uintptr_t>(origTable.reserved0) != kHookMagic) {
        LOGD("JniHook uninstall: magic mismatch (origTable clobbered) — abort");
        g_installed = false;  // mark uninstalled เพื่อไม่ให้พยายามซ้ำ
        return;
    }
    if (!makeWritable(g_live)) return;
    g_live->GetMethodID       = origTable.GetMethodID;
    g_live->CallObjectMethod  = origTable.CallObjectMethod;
    g_live->CallObjectMethodV = origTable.CallObjectMethodV;
    g_live->CallObjectMethodA = origTable.CallObjectMethodA;
    mprotect((void*)((uintptr_t)g_live & ~(4096ull-1)), 4096, PROT_READ);
    g_installed = false;
    g_loadClassMid = nullptr;
}

bool isInstalled() { return g_installed; }

// exposed originals (thin wrappers over archive)
jmethodID origGetMethodID(JNIEnv* e, jclass c, const char* n, const char* s) {
    return origTable.GetMethodID(e, c, n, s);
}

jclass origFindClass(JNIEnv* e, const char* name) {
    return origTable.FindClass(e, name);
}

} // namespace aether::JniHook
