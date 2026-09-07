// mem_reader.cpp — Zero-trace process_vm_readv reader
#include "mem_reader.hpp"
#include "module_resolver.hpp"
#include <sys/uio.h>
#include <unistd.h>
#include <errno.h>

namespace aether {

ssize_t MemReader::read(pid_t pid, uintptr_t addr, void* out, size_t size) {
    if (size == 0 || !out) return 0;
    struct iovec local = { out, size };
    struct iovec remote = { reinterpret_cast<void*>(addr), size };
    // Single syscall, no /proc/pid/mem fd opened → no trace in /proc/self/fd
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (n < 0) return 0;
    return n;
}

ssize_t MemReader::readBulk(pid_t pid,
                            const std::vector<uintptr_t>& addrs,
                            const std::vector<size_t>& sizes,
                            std::vector<std::vector<uint8_t>>& out) {
    const size_t n = addrs.size();
    if (n == 0) return 0;
    std::vector<struct iovec> local(n), remote(n);
    out.resize(n);
    for (size_t i = 0; i < n; ++i) {
        out[i].resize(sizes[i]);
        local[i].iov_base = out[i].data();
        local[i].iov_len  = sizes[i];
        remote[i].iov_base = reinterpret_cast<void*>(addrs[i]);
        remote[i].iov_len  = sizes[i];
    }
    ssize_t total = process_vm_readv(pid, local.data(), n, remote.data(), n, 0);
    if (total < 0) return 0;
    return total;
}

bool MemReader::isReadable(pid_t pid, uintptr_t addr, size_t size) {
    // Quick /proc/pid/maps permission check (no actual read).
    // Uses ModuleResolver cache to avoid repeated syscalls.
    auto* maps = ModuleResolver::getMaps(pid);
    if (!maps) return false;
    for (const auto& e : *maps) {
        if (addr >= e.start && addr + size <= e.end) {
            return (e.perms & 0x1) != 0; // R bit
        }
    }
    return false;
}

} // namespace aether
