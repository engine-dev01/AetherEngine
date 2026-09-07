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

std::string Config::deriveKey(int seed) {
    std::mt19937 rng(seed ^ s_seed);
    std::string k;
    k.resize(16);
    for (int i = 0; i < 16; ++i) k[i] = (char)(rng() & 0xFF);
    return k;
}

void Config::setSeed(int seed) {
    s_seed = (uint32_t)seed;
}

} // namespace aether
