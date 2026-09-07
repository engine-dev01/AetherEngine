#pragma once
#include <string>
#include <vector>
#include <unordered_map>

namespace aether::l1 {
struct ManifestSnapshot {
    std::string packageName = "com.miniclip.eightballpool";
    std::string versionName = "56.23.2";
    int versionCode = 56232;
    std::vector<std::string> activities;
    std::vector<std::string> services;
    std::vector<std::string> providers;
    std::vector<std::string> permissions;
    std::unordered_map<std::string,std::string> metaData;

    // Text format (legacy, still supported for deserialize fallback)
    bool serialize(const std::string& outPath);
    bool deserialize(const std::string& inPath);

    // Binary format: [len:4 LE][UTF-16LE chars] per entry (matches SandboxManager.kt generatePackageConf)
    bool serializeBinary(const std::string& outPath);
    bool deserializeBinary(const std::string& inPath);

    // เติม prototype entries จาก DATA_DUMP §3 (18 acts / 14 svcs / 12 provs)
    void populatePrototype(const std::string& pkg = "com.miniclip.eightballpool");
};
} // namespace aether::l1
