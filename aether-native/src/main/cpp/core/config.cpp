// config.cpp — Offset database (config-driven, no hardcoded addrs)
#include "config.hpp"
#include <random>

namespace aether {

std::unordered_map<std::string, int64_t> Config::s_offsets;
uint32_t Config::s_seed = 0x12345678u;

void Config::setOffset(const std::string& key, int64_t value) {
    s_offsets[key] = value;
}

int64_t Config::getOffset(const std::string& key) {
    auto it = s_offsets.find(key);
    return (it != s_offsets.end()) ? it->second : 0;
}

void Config::applyOffsets(const std::vector<std::string>& keys,
                          const std::vector<int64_t>& values) {
    size_t n = std::min(keys.size(), values.size());
    for (size_t i = 0; i < n; ++i) s_offsets[keys[i]] = values[i];
}

// entropy() — restored 2026-09-09: transitive dependency of nativeCompute
// (JNI USED). First cut was wrong — wiring audit only checked the JNI surface,
// not the C++-internal call graph. Lesson recorded in pre_flight_check.sh §2.
uint64_t Config::entropy() {
    // Combine clock + seed + address entropy
    uint64_t v = 0;
#if defined(__aarch64__)
    asm volatile("mrs %0, cntvct_el0" : "=r"(v));
#else
    v = (uint64_t)clock() ^ (uint64_t)time(nullptr);
#endif
    v ^= (uint64_t)&entropy; // code address
    v ^= s_seed;
    return v;
}
// deriveKey() stays cut — no callers anywhere (Config::deriveKey ≠ KeyStore::deriveKey)

void Config::setSeed(int seed) {
    s_seed = (uint32_t)seed;
}

} // namespace aether
