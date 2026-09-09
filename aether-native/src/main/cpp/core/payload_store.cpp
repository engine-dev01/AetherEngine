// payload_store.cpp
#include "payload_store.hpp"
#include "key_store.hpp"
#include <unordered_map>
#include <mutex>
#include <fstream>
#include <filesystem>

namespace aether::PayloadStore {

static std::unordered_map<std::string,std::vector<uint8_t>> g_map;
static std::mutex g_mu;

void put(const std::string& h, const std::vector<uint8_t>& d) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_map[h]=d;
}
bool has(const std::string& h) {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_map.find(h)!=g_map.end();
}
std::vector<uint8_t> get(const std::string& h) {
    std::lock_guard<std::mutex> lk(g_mu);
    auto it=g_map.find(h);
    return it==g_map.end()? std::vector<uint8_t>{}: it->second;
}
size_t count() {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_map.size();
}
void clear() {
    std::lock_guard<std::mutex> lk(g_mu);
    g_map.clear();
}
size_t loadDir(const std::string& dir) {
    namespace fs=std::filesystem;
    size_t n=0;
    try {
        for (auto& e: fs::directory_iterator(dir)) {
            if (!e.is_regular_file()) continue;
            std::string name=e.path().filename().string();
            // SHA-256 hex = 64 hex chars
            if (name.size()!=64) continue;
            std::ifstream f(e.path(), std::ios::binary);
            std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
            put(name, data);
            ++n;
        }
    } catch(...) {}
    return n;
}

// decrypt() — removed 2026-09-09: only caller was nativeDecryptPayloadByHash (cut — WIRING_AUDIT §B)

} // namespace aether::PayloadStore
