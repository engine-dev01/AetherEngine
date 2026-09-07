// module_resolver.hpp — /proc/pid/maps parser with LRU cache
// Cached: parse once per PID, invalidate on timeout or signal.
#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>
#include <sys/types.h>

namespace aether {
struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    uint32_t perms; // bit0=R,1=W,2=X
    std::string path;
};

class ModuleResolver {
public:
    // Find base address of `moduleName` in `pid` (e.g. "libgame.so").
    // Returns 0 if not found.
    static uintptr_t findBase(pid_t pid, const std::string& moduleName);

    // Get all map entries for pid (cached).
    static const std::vector<MapEntry>* getMaps(pid_t pid);

private:
    struct Cache {
        std::vector<MapEntry> entries;
        long timestamp;
    };
    static std::unordered_map<pid_t, Cache> s_cache;
    static const long CACHE_TTL_MS = 2000; // invalidate after 2s
    static void reload(pid_t pid);
};
} // namespace aether
