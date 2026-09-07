// env_check.cpp — portable env detection (no RWX, no direct svc)
#include "env_check.hpp"
#include <sys/system_properties.h>
#include <unistd.h>
#include <string>
#include <cstring>

namespace aether::EnvCheck {

static int getSdk() {
    char v[PROP_VALUE_MAX]; int n = __system_property_get("ro.build.version.sdk", v);
    return n > 0 ? atoi(v) : 0;
}

static std::string getProp(const char* name) {
    char v[PROP_VALUE_MAX]; int n = __system_property_get(name, v);
    return n > 0 ? std::string(v, n) : std::string();
}

// ตรวจสัญญาณ linker hijack (AetherMind §G: "ld-android" detection)
// ตรวจ: ro.dalvik.vm.lib ควรเป็น libart.so (default) — ถ้าเป็น ld-android* = Zygisk/ReFrameWork
bool isLinkerHijacked() {
    std::string lib = getProp("ro.dalvik.vm.lib");
    if (lib.empty()) return false;
    if (lib.find("ld-android") != std::string::npos) return true;
    if (lib.find("libart") == std::string::npos) return true;  // unexpected
    return false;
}

// ตรวจ Samsung SoC (AetherMind §G: "exynos9810" — game อาจ bypass SoC-specific check)
bool isSamsungExynos() {
    std::string hw = getProp("ro.hardware");
    std::string chip = getProp("ro.hardware.chipname");
    if (hw.find("exynos") != std::string::npos) return true;
    if (chip.find("exynos") != std::string::npos) return true;
    if (chip.find("9810") != std::string::npos) return true;
    return false;
}

bool isEmulator() {
    std::string arch = getProp("ro.arch");
    std::string sdk  = getProp("ro.build.version.sdk");
    // ต้นแบบเช็ค ro.arch + exynos9810; เราเช็คสัญญาณ emulator ทั่วไป
    if (arch.find("x86") != std::string::npos) return true;
    std::string fp = getProp("ro.build.fingerprint");
    if (fp.find("generic") != std::string::npos) return true;
    if (fp.find("sdk_gphone") != std::string::npos) return true;
    // access /dev/socket/qemud เป็นสัญญาณ emulator (ไม่ทำ open จริงใน W^X env)
    return false;
}

bool isRooted() {
    // สัญญาณ root: su binary, magisk props — minimal check
    if (access("/system/xbin/su", F_OK) == 0) return true;
    if (access("/system/bin/su", F_OK) == 0) return true;
    std::string debuggable = getProp("ro.debuggable");
    if (debuggable == "1") return true;
    return false;
}

Report probe() {
    Report r;
    r.sdkInt = getSdk();
    r.arch   = getProp("ro.arch");
    r.isEmulator       = isEmulator();
    r.isRooted         = isRooted();
    r.isLinkerHijacked = isLinkerHijacked();
    r.isSamsungExynos  = isSamsungExynos();
    r.isDebugged       = false; // Flagger/Stealth ดูแล
    return r;
}

} // namespace aether::EnvCheck
