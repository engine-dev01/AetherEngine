// class_map.hpp — Virtual class registry (NATIVE_LOGIC.md §B)
// ต้นแบบ: hash_lookup @0x8285B8 (SIMD hash + modulo + std::string::compare)
// Aether: std::unordered_map<string,string> + same dotted→slashed contract
#pragma once
#include <jni.h>
#include <string>

namespace aether::ClassMap {

// ลงทะเบียน mapping: requested dotted name → redirect slashed name
// ตัวอย่าง: register("com.foo.Bar","com/aether/engine/proxy/StubBar")
void put(const std::string& dottedRequested, const std::string& slashedTarget);
void clear();
size_t size();

// ลอง redirect — เรียกจาก JniHook custom_loadClass
// return jclass (local ref) ถ้าเจอ mapping, else nullptr (fallback ให้ caller ใช้ original)
jobject tryRedirect(JNIEnv* env, jstring classNameJstr);

} // namespace aether::ClassMap
