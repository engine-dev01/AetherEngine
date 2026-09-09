// config.hpp — Offset database, loaded from RemoteConfig (no hardcoded addrs)
#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

namespace aether {
class Config {
public:
    // Update offset from Kotlin RemoteConfig callback.
    static void setOffset(const std::string& key, int64_t value);
    // Get offset (returns 0 if unknown).
    static int64_t getOffset(const std::string& key);
    // Bulk update from JSON string (parsed in Kotlin, passed as map).
    static void applyOffsets(const std::vector<std::string>& keys,
                             const std::vector<int64_t>& values);

    // Entropy source (used by nativeCompute) — restored 2026-09-09.
    static uint64_t entropy();
    // Set RNG seed.
    static void setSeed(int seed);

private:
    static std::unordered_map<std::string, int64_t> s_offsets;
    static uint32_t s_seed;
};
} // namespace aether
