// env_check.hpp — Environment detection (NATIVE_LOGIC.md §E)
// ต้นแบบ strings: ro.build.version.sdk, ro.arch, exynos9810, /dev/urandom, ld-android
#pragma once
#include <string>

namespace aether::EnvCheck {

struct Report {
    bool isEmulator = false;
    bool isRooted   = false;
    bool isDebugged = false;
    bool isLinkerHijacked = false;  // AetherMind §G: ld-android detection
    bool isSamsungExynos  = false;  // AetherMind §G: exynos9810 (SoC bypass)
    int  sdkInt     = 0;
    std::string arch;
};

// ตรวจครบทุกสัญญาณ (เรียกหลัง JNI_OnLoad, ก่อน Flagger)
Report probe();

// helpers (เรียกเดี่ยวได้)
bool isEmulator();
bool isRooted();
bool isLinkerHijacked();
bool isSamsungExynos();

} // namespace aether::EnvCheck
