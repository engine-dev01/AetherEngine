// jni_hook.hpp — JNIEnv Function-Table Hooking (NATIVE_LOGIC(transcript สูญ-UNVERIFIED) §A) — ตรวจ novalid
// ต้นแบบ: 0x8f614 hook GetMethodID + CallObjectMethod{,V,A} 4 จุด เพื่อสกัด loadClass
#pragma once
#include <jni.h>
#include <cstdint>

namespace aether::JniHook {

// ติดตั้ง hooks — เรียกจาก JNI_OnLoad หลัง blockDebugger + probeSdk
// return true = ติดตั้งสำเร็จ, false = ไม่รองรับ/ตารางไม่ครบ
bool install(JNIEnv* env);

// ถอน hooks (สำหับทดสอบ)
void uninstall();

// สถานะ
bool isInstalled();

// สำหรับเรียกคืน original (ใช้ภายใน custom_loadClass)
jmethodID    origGetMethodID(JNIEnv* e, jclass c, const char* name, const char* sig);
jobject      origCallObjectMethod(JNIEnv* e, jobject o, jmethodID mid, ...);
jobject      origCallObjectMethodV(JNIEnv* e, jobject o, jmethodID mid, va_list args);
jobject      origCallObjectMethodA(JNIEnv* e, jobject o, jmethodID mid, const jvalue* args);
jclass       origFindClass(JNIEnv* e, const char* name);  // ป้องกัน recursion ใน class_map

} // namespace aether::JniHook
