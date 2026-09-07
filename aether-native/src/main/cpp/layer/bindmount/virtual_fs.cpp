#include "virtual_fs.hpp"
#include <sys/mount.h>
#include <unistd.h>
#include <fstream>
#include <cstring>
#include <algorithm>
#include <cstdint>

namespace aether::l1 {

// ─── Root detection paths ───
static const std::vector<std::string> ROOT_CHECK_PATHS = {
    "/sbin/su",
    "/system/bin/su",
    "/system/xbin/su",
    "/system/app/Superuser.apk",
    "/system/app/SuperSU.apk",
    "/system/etc/init.d/99sudaemon",
    "/system/xbin/busybox",
    "/system/bin/busybox"
};

// ─── Common detection paths ───
static const std::vector<std::string> DETECTION_PATHS = {
    "/proc/self/status",
    "/proc/self/maps",
    "/proc/self/cmdline",
    "/proc/self/mountinfo",
    "/proc/self/smaps"
};

// ══════════════════════════════════════════
//  Core API
// ══════════════════════════════════════════

void VirtualFS::addRedirect(const std::string& fakePath, const std::string& realPath) {
    mounts_[fakePath] = realPath;
}

void VirtualFS::removeRedirect(const std::string& fakePath) {
    mounts_.erase(fakePath);
}

void VirtualFS::updateRedirect(const std::string& fakePath, const std::string& newRealPath) {
    mounts_[fakePath] = newRealPath;
}

void VirtualFS::clearAll() {
    mounts_.clear();
}

std::string VirtualFS::resolve(const std::string& path) const {
    // Exact match first
    auto it = mounts_.find(path);
    if (it != mounts_.end()) {
        return it->second;
    }

    // Prefix match — find longest matching prefix
    std::string bestMatch;
    std::string bestReplacement;
    for (const auto& [fake, real] : mounts_) {
        if (path.find(fake) == 0) {
            if (fake.length() > bestMatch.length()) {
                bestMatch = fake;
                bestReplacement = real;
            }
        }
    }

    if (!bestMatch.empty()) {
        // Replace prefix and append rest
        std::string rest = path.substr(bestMatch.length());
        return bestReplacement + rest;
    }

    return path;
}

bool VirtualFS::hasRedirect(const std::string& path) const {
    if (mounts_.count(path)) return true;

    for (const auto& [fake, _] : mounts_) {
        if (path.find(fake) == 0) return true;
    }
    return false;
}

std::vector<std::string> VirtualFS::listRedirects() const {
    std::vector<std::string> result;
    result.reserve(mounts_.size());
    for (const auto& [fake, real] : mounts_) {
        result.push_back(fake + " → " + real);
    }
    return result;
}

const std::unordered_map<std::string, std::string>& VirtualFS::getAllRedirects() const {
    return mounts_;
}

// ══════════════════════════════════════════
//  Setup Methods
// ══════════════════════════════════════════

void VirtualFS::setupForApp(const std::string& dataDir,
                            const std::string& nativeLibDir,
                            const std::string& externalDir,
                            const std::string& fakePackage) {
    // 1. Data directory redirects
    //    /data/data/<fake_pkg> → <real_dataDir>
    if (!fakePackage.empty()) {
        addRedirect("/data/data/" + fakePackage, dataDir);
        addRedirect("/data/user/0/" + fakePackage, dataDir);
        addRedirect("/data/user_de/0/" + fakePackage, dataDir);
    }

    // 2. Native library redirects
    //    หลาย path ที่ system อาจใช้ค้นหา .so
    addRedirect("/data/app", nativeLibDir.substr(0, nativeLibDir.find("/lib/")));
    addRedirect(nativeLibDir + "/lib/arm64-v8a", nativeLibDir);
    addRedirect(nativeLibDir + "/lib/armeabi-v7a", nativeLibDir);

    // 3. External storage
    addRedirect("/sdcard", externalDir);
    addRedirect("/storage/emulated/0", externalDir);

    // 4. Common detection paths — redirect to safe versions
    //    (ป้องกัน game scan หา lib path จริง)
    addRedirect("/proc/self/fd", "/proc/self/fd");  // keep as-is
}

void VirtualFS::setupRootBypass(const std::string& dummyFile) {
    // สร้าง stub files สำหรับ root detection paths
    for (const auto& path : ROOT_CHECK_PATHS) {
        ensureStubFile(path);
        addRedirect(path, dummyFile);
    }

    // ซ่อน Magisk/Magisk-related paths
    addRedirect("/sbin/.magisk", dummyFile);
    addRedirect("/data/adb/magisk", dummyFile);
    addRedirect("/data/adb/modules", dummyFile);
    addRedirect("/data/adb/services.d", dummyFile);
    addRedirect("/data/adb/post-fs-data.d", dummyFile);
    addRedirect("/data/adb/lspd", dummyFile);

    // ซ่อน KernelSU paths
    addRedirect("/data/adb/ksu", dummyFile);
    addRedirect("/data/adb/ksud", dummyFile);
    addRedirect("/data/adb/ksu/modules", dummyFile);

    // ซ่อน APatch paths
    addRedirect("/data/adb/ap", dummyFile);
    addRedirect("/data/adb/apd", dummyFile);

    // ซ่อน /proc/self/mountinfo ที่แสดง bind mount
    addRedirect("/proc/self/mountinfo", dummyFile);
}

void VirtualFS::setupCmdlineRedirect(const std::string& fakeCmdline) {
    // Redirect /proc/self/cmdline → ไฟล์ dummy ที่มี cmdline ปลอม
    // (ใช้ bind-mount กับไฟล์ dummy ที่เขียน cmdline ปลอมไว้)

    // สร้าง dummy cmdline file
    static const std::string CMDLINE_DUMMY = "/data/local/tmp/.aether_cmdline";
    std::ofstream cmdFile(CMDLINE_DUMMY, std::ios::binary);
    if (cmdFile) {
        // เขียน fake cmdline (null-terminated)
        cmdFile.write(fakeCmdline.c_str(), fakeCmdline.length());
        cmdFile.put('\0');
        cmdFile.close();
    }

    addRedirect("/proc/self/cmdline", CMDLINE_DUMMY);
    addRedirect("/proc/" + std::to_string(getpid()) + "/cmdline", CMDLINE_DUMMY);
}

// ══════════════════════════════════════════
//  Legacy API
// ══════════════════════════════════════════

void VirtualFS::mount(const std::string& realPath, const std::string& fakePath) {
    addRedirect(fakePath, realPath);
}

void VirtualFS::umount(const std::string& fakePath) {
    removeRedirect(fakePath);
}

bool VirtualFS::isMounted(const std::string& path) const {
    return hasRedirect(path);
}

// ══════════════════════════════════════════
//  Helper
// ══════════════════════════════════════════

bool VirtualFS::ensureStubFile(const std::string& path, size_t minSize) {
    // ถ้า file มีอยู่แล้วและมี size พอ → return true
    std::ifstream check(path, std::ios::binary | std::ios::ate);
    if (check) {
        size_t size = static_cast<size_t>(check.tellg());
        check.close();
        if (size >= minSize) return true;
    }

    // สร้าง stub file ขนาด minSize bytes (ELF-like header)
    std::ofstream stub(path, std::ios::binary);
    if (!stub) return false;

    // เขียน ELF magic + padding (ดูเหมือน .so file จริง)
    static const uint8_t ELF_HEADER[] = {
        0x7f, 0x45, 0x4c, 0x46,  // magic: .ELF
        0x02,                      // 64-bit
        0x01,                      // little endian
        0x01,                      // ELF version 1
        0x00, 0x00, 0x00, 0x00,   // OS/ABI
        0x00, 0x00, 0x00, 0x00,   // padding
        0x02, 0x00,                // ET_EXEC
        0xB7, 0x00,                // AARCH64
    };

    stub.write(reinterpret_cast<const char*>(ELF_HEADER), sizeof(ELF_HEADER));

    // Pad with zeros to minSize
    std::vector<char> padding(minSize - sizeof(ELF_HEADER), 0);
    stub.write(padding.data(), padding.size());

    stub.close();
    return true;
}

} // namespace aether::l1
