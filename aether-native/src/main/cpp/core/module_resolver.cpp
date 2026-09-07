// module_resolver.cpp — module base resolution
// Aether-aligned: uses dl_iterate_phdr when scanning the CURRENT process
// (engine injected inside the target game), and falls back to /proc/pid/maps
// for cross-process resolution. dl_iterate_phdr only enumerates the caller's
// own loaded libraries, so remote pids must use the maps parser.
#include "module_resolver.hpp"
#include <fstream>
#include <cstdio>
#include <sstream>
#include <sys/time.h>
#include <dlfcn.h>
#include <link.h>
#include <unistd.h>

namespace aether {

std::unordered_map<pid_t, ModuleResolver::Cache> ModuleResolver::s_cache;

static int dlCb(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* out = static_cast<std::vector<MapEntry>*>(data);
    if (!info->dlpi_name || !*info->dlpi_name) return 0;
    MapEntry e;
    e.start = (uintptr_t)info->dlpi_addr; // load bias == module base
    e.end   = e.start;
    e.perms = 5; // R+X (loaded, executable module)
    e.path  = info->dlpi_name;
    out->push_back(e);
    return 0;
}

void ModuleResolver::reload(pid_t pid) {
    Cache c;
    if ((pid_t)getpid() == pid) {
 // Self (injected case): engine-style library scanner via dl_iterate_phdr
        std::vector<MapEntry> selfMods;
        dl_iterate_phdr(dlCb, &selfMods);
        for (auto& e : selfMods) c.entries.push_back(e);
    } else {
        // Remote: parse /proc/pid/maps (cross-process, dl_iterate_phdr N/A)
        std::string path = "/proc/" + std::to_string(pid) + "/maps";
        std::ifstream f(path);
        std::string line;
        while (std::getline(f, line)) {
            MapEntry e;
            char perm[5];
            unsigned long long s, en;
            if (sscanf(line.c_str(), "%llx-%llx %4s", &s, &en, perm) < 3) continue;
            e.start = (uintptr_t)s;
            e.end   = (uintptr_t)en;
            e.perms = 0;
            if (perm[0]=='r') e.perms |= 1;
            if (perm[1]=='w') e.perms |= 2;
            if (perm[2]=='x') e.perms |= 4;
            size_t p = line.find_last_of(' ');
            if (p != std::string::npos) e.path = line.substr(p+1);
            c.entries.push_back(e);
        }
    }
    struct timeval tv; gettimeofday(&tv, nullptr);
    c.timestamp = tv.tv_sec * 1000 + tv.tv_usec / 1000;
    s_cache[pid] = std::move(c);
}

const std::vector<MapEntry>* ModuleResolver::getMaps(pid_t pid) {
    auto it = s_cache.find(pid);
    struct timeval tv; gettimeofday(&tv, nullptr);
    long now = tv.tv_sec * 1000 + tv.tv_usec / 1000;
    if (it == s_cache.end() || (now - it->second.timestamp) > CACHE_TTL_MS) {
        reload(pid);
        it = s_cache.find(pid);
    }
    return (it != s_cache.end()) ? &it->second.entries : nullptr;
}

uintptr_t ModuleResolver::findBase(pid_t pid, const std::string& moduleName) {
    auto* maps = getMaps(pid);
    if (!maps) return 0;
    for (const auto& e : *maps) {
        size_t pos = e.path.rfind(moduleName);
        if (pos != std::string::npos &&
            pos + moduleName.size() == e.path.size() &&
            (e.perms & 0x5)) { // R or X (executable module base)
            return e.start;
        }
    }
    return 0;
}

} // namespace aether
