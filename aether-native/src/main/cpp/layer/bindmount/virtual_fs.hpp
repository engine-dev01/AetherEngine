#pragma once
#include <string>
#include <unordered_map>
#include <vector>

namespace aether::l1 {

/**
 * VirtualFS — Filesystem virtualizer
 *
 * สร้าง mapping ของ fake path → real path สำหรับ:
 * 1. Bind-mount redirects (nativeLib, data dir, sdcard)
 * 2. Root detection bypass (fake su paths, fake Superuser.apk)
 * 3. Process identity hiding (fake /proc/self/cmdline)
 * 4. Native library redirect (的游戏 lib → real nativeLibDir)
 *
 * ใช้ใน context ของ virtual app container
 * ไม่ต้อง root — ใช้ JNI hook ของ open/readlink/faccessat
 */
class VirtualFS {
public:
    // ─── Core API ───

    /** เพิ่ม path redirect rule */
    void addRedirect(const std::string& fakePath, const std::string& realPath);

    /** ลบ path redirect rule */
    void removeRedirect(const std::string& fakePath);

    /** แก้ไข redirect ที่มีอยู่ */
    void updateRedirect(const std::string& fakePath, const std::string& newRealPath);

    /** ลบ redirect rules ทั้งหมด */
    void clearAll();

    /** Resolve path — คืนค่า real path ถ้ามี redirect, ไม่ก็คืน path เดิม */
    std::string resolve(const std::string& path) const;

    /** ตรวจสอบว่า path นี้มี redirect หรือไม่ */
    bool hasRedirect(const std::string& path) const;

    /** คืน list ของ fake paths ทั้งหมด (สำหรับ debug) */
    std::vector<std::string> listRedirects() const;

    /** คืน redirect map ทั้งหมด (สำหรับ serialize) */
    const std::unordered_map<std::string, std::string>& getAllRedirects() const;

    // ─── Setup Methods (เรียกตอน init engine) ───

    /**
     * Setup full redirect map สำหรับ virtual app container
     * @param dataDir        — /data/data/<pkg> จริง
     * @param nativeLibDir   — nativeLibraryDir จริง (e.g., /data/app/.../lib/arm64)
     * @param externalDir    — /sdcard จริง (อาจเป็น emulated หรือ real)
     * @param fakePackage    — package name ปลอม (ถ้าต้องการ hide)
     */
    void setupForApp(const std::string& dataDir,
                      const std::string& nativeLibDir,
                      const std::string& externalDir,
                      const std::string& fakePackage = "");

    /**
     * Setup root detection bypass paths
     * สร้าง fake su paths + Superuser.apk ชี้ไปไฟล์ dummy
     * @param dummyFile — ไฟล์ dummy สำหรับ redirect (เช่น /dev/null)
     */
    void setupRootBypass(const std::string& dummyFile = "/dev/null");

    /**
     * Setup /proc/self/cmdline redirect
     * @param fakeCmdline — cmdline ปลอม (เช่น package name ของ app หลัก)
     */
    void setupCmdlineRedirect(const std::string& fakeCmdline);

    // ─── Legacy API (compatibility with old code) ───

    void mount(const std::string& realPath, const std::string& fakePath);
    void umount(const std::string& fakePath);
    bool isMounted(const std::string& path) const;

private:
    std::unordered_map<std::string, std::string> mounts_;

    /** Helper: สร้าง stub file ถ้ายังไม่มี */
    static bool ensureStubFile(const std::string& path, size_t minSize = 128);
};

} // namespace aether::l1
