// aob_scanner.hpp — Smart pattern scanner: wildcard + TTL cache + 4MB window
// Pattern: byte array. Mask: 'x' = must match, '?' = wildcard.
// Design (UpNew.md "Zero-Lag Memory Caching"):
//   - Initial search window = 4MB; expands chunk-by-chunk until a match is found.
//   - Scan result cached with a 30s TTL (re-scan at most once per 30 seconds).
//   - Bulk reads use process_vm_readv (zero-trace, no ptrace) — see mem_reader.
#pragma once
#include <cstdint>
#include <vector>
#include <string>
#include <unordered_map>
#include <chrono>

namespace aether {
class AobScanner {
public:
    static constexpr size_t  kChunkSize  = 4 * 1024 * 1024;  // 4MB initial/expand window
    static constexpr int64_t kCacheTtlMs = 30000;            // 30s result TTL

    // Scan `pid` region [base, base+size) for pattern.
    // Walks the region in 4MB windows (expands as needed); returns the first
    // match address, or 0 if not found. Results are TTL-cached per window.
    static uintptr_t scan(pid_t pid, uintptr_t base, size_t size,
                          const std::vector<uint8_t>& pattern,
                          const std::string& mask);

private:
    struct CacheKey {
        pid_t pid; uintptr_t base; size_t size;
        std::string hash;
        bool operator==(const CacheKey& o) const {
            return pid==o.pid && base==o.base && size==o.size && hash==o.hash;
        }
    };
    struct CacheKeyHash {
        size_t operator()(const CacheKey& k) const;
    };
    struct CacheVal {
        uintptr_t addr;   // 0 == negative cache
        int64_t   ts;     // insertion time (ms, monotonic)
    };
    static std::unordered_map<CacheKey, CacheVal, CacheKeyHash> s_cache;
    static int64_t nowMs();
};
} // namespace aether
