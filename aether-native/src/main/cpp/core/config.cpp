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

// entropy()/deriveKey() — removed: no callers after WIRING_AUDIT §B cut

void Config::setSeed(int seed) {
    s_seed = (uint32_t)seed;
}

} // namespace aether
