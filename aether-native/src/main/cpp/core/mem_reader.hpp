// mem_reader.hpp — Zero-trace cross-process memory reader
// Uses process_vm_readv (no /proc/pid/mem fd left open)
#pragma once
#include <cstdint>
#include <vector>
#include <string>
#include <sys/types.h>

namespace aether {
class MemReader {
public:
    // Read `size` bytes from `pid` at `addr` into `out`.
    // Returns bytes read (0 on failure). Uses process_vm_readv with iovec.
    static ssize_t read(pid_t pid, uintptr_t addr, void* out, size_t size);

    // Bulk read: multiple (addr,size) pairs in one process_vm_readv call.
    static ssize_t readBulk(pid_t pid,
                            const std::vector<uintptr_t>& addrs,
                            const std::vector<size_t>& sizes,
                            std::vector<std::vector<uint8_t>>& out);

    // Validate addr is in a readable mapped region (avoid SIGSEGV).
    static bool isReadable(pid_t pid, uintptr_t addr, size_t size);
};
} // namespace aether
