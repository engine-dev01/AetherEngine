// class_map.cpp — Virtual class registry
#include "class_map.hpp"
#include "jni_hook.hpp"  // origFindClass (ป้องกัน recursion)
#include <unordered_map>
#include <mutex>

namespace aether::ClassMap {

static std::unordered_map<std::string,std::string> g_map;
static std::mutex g_mu;

static std::string toSlashed(const std::string& dotted) {
    std::string s = dotted;
    for (char& c : s) if (c == '.') c = '/';
    return s;
}

void put(const std::string& dottedRequested, const std::string& slashedTarget) {
    std::lock_guard<std::mutex> lk(g_mu);
    // key เก็บแบบ slashed เพื่อ match กับ custom_loadClass ที่แปลงแล้ว
    std::string key = toSlashed(dottedRequested);
    g_map[key] = slashedTarget;
}

void clear() {
    std::lock_guard<std::mutex> lk(g_mu);
    g_map.clear();
}

size_t size() {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_map.size();
}

jobject tryRedirect(JNIEnv* env, jstring cname) {
    if (!env || !cname) return nullptr;
    const char* p = env->GetStringUTFChars(cname, nullptr);
    if (!p) return nullptr;
    std::string slashed = toSlashed(std::string(p));
    env->ReleaseStringUTFChars(cname, p);

    std::string target;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        auto it = g_map.find(slashed);
        if (it == g_map.end()) return nullptr;
        target = it->second;
    }
    // FindClass ผ่าน archived original (ป้องกัน recursion ถ้า env->FindClass ถูก hook)
    // คืน jclass (local ref) — caller ต้อง DeleteLocalRef หลัง cast เป็น jobject
    // (hook_GetMethodID/hook_CallObjectMethod เป็นคนเรียก — caller เก็บในสแต็ก)
    jclass cls = JniHook::origFindClass(env, target.c_str());
    if (!cls) return nullptr;
    // คืน jobject โดยไม่ DeleteLocalRef (caller ตอนนี้เป็น hook chain ที่ return ทันที)
    // เสี่ยง overflow ถ้าเรียกซ้ำใน tight loop — caller ควรเคลียร์เมื่อใช้เสร็จ
    return (jobject)cls;
}

} // namespace aether::ClassMap
