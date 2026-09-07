// aob_scanner.cpp — Wildcard pattern scanner + TTL cache + 4MB window
#include "aob_scanner.hpp"
#include <cstdio>
#include "mem_reader.hpp"
#include <functional>

namespace aether {

// Hash pattern for cache key
static std::string hashPattern(const std::vector<uint8_t>& p, const std::string& m) {
    std::string h;
    h.reserve(p.size() * 2 + m.size());
    for (size_t i = 0; i < p.size(); ++i) {
        char buf[3]; snprintf(buf, sizeof(buf), "%02x", p[i]);
        h += buf;
    }
    h += "|"; h += m;
    return h;
}

size_t AobScanner::CacheKeyHash::operator()(const CacheKey& k) const {
    return std::hash<pid_t>()(k.pid) ^ std::hash<uintptr_t>()(k.base)
         ^ std::hash<size_t>()(k.size) ^ std::hash<std::string>()(k.hash);
}

std::unordered_map<AobScanner::CacheKey, AobScanner::CacheVal, AobScanner::CacheKeyHash>
    AobScanner::s_cache;

int64_t AobScanner::nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

uintptr_t AobScanner::scan(pid_t pid, uintptr_t base, size_t size,
                           const std::vector<uint8_t>& pattern,
                           const std::string& mask) {
    const size_t plen = pattern.size();
    if (plen == 0 || size < plen) return 0;
    const int64_t now = nowMs();

    // Walk the region in 4MB windows; expand chunk-by-chunk until a match.
    for (uintptr_t off = 0; off + plen <= size; off += kChunkSize) {
        const uintptr_t chunkBase = base + off;
        const size_t chunkSize = (off + kChunkSize <= size) ? kChunkSize : (size - off);

        CacheKey key{ pid, chunkBase, chunkSize, hashPattern(pattern, mask) };
        auto it = s_cache.find(key);
        if (it != s_cache.end() && (now - it->second.ts) < kCacheTtlMs) {
            if (it->second.addr != 0) return it->second.addr; // valid hit within TTL
            continue;                                          // negative hit within TTL
        }

        // Bulk-read ONLY this 4MB window (zero-trace via process_vm_readv).
        std::vector<uint8_t> buf(chunkSize);
        ssize_t n = MemReader::read(pid, chunkBase, buf.data(), chunkSize);
        if (n <= 0) { s_cache[key] = { 0, now }; continue; }

        // Scan window with wildcard mask.
        for (size_t i = 0; i + plen <= (size_t)n; ++i) {
            bool match = true;
            for (size_t j = 0; j < plen; ++j) {
                if (mask[j] == 'x' && buf[i+j] != pattern[j]) {
                    match = false; break;
                }
                // '?' = wildcard, skip
            }
            if (match) {
                uintptr_t result = chunkBase + i;
                s_cache[key] = { result, now };
                return result; // first hit; expansion stops here
            }
        }
        s_cache[key] = { 0, now }; // negative cache (TTL-bounded)
    }
    return 0;
}

} // namespace aether
