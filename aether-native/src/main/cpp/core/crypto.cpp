// crypto.cpp — LZ4 + Xor crypto (LZ4 vendored from third_party/lz4)
#include "crypto.hpp"
#include "lz4.h"
#include <algorithm>

namespace aether {

bool Crypto::compress(const std::vector<uint8_t>& in, std::vector<uint8_t>& out) {
 // Aether-aligned: LZ4 block compression (vendored third_party/lz4)
    const int bound = LZ4_compressBound((int)in.size());
    out.resize((size_t)bound);
    const int written = LZ4_compress_default(
        (const char*)in.data(), (char*)out.data(), (int)in.size(), bound);
    if (written <= 0) return false;
    out.resize((size_t)written);
    return true;
}

bool Crypto::decompress(const std::vector<uint8_t>& in, std::vector<uint8_t>& out) {
    // LZ4 needs an upper bound on decompressed size; grow heuristically like the
    // prior zlib path did.
    size_t dest = in.size() * 8 + 1024;
    for (int attempt = 0; attempt < 2; ++attempt) {
        out.resize(dest);
        const int written = LZ4_decompress_safe(
            (const char*)in.data(), (char*)out.data(), (int)in.size(), (int)dest);
        if (written >= 0) {
            out.resize((size_t)written);
            return true;
        }
        dest = dest * 2 + 1024;
    }
    return false;
}

void Crypto::xorCrypt(const std::vector<uint8_t>& in,
                      const std::vector<uint8_t>& key,
                      std::vector<uint8_t>& out) {
    if (key.empty()) { out = in; return; }
    out.resize(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        out[i] = in[i] ^ key[i % key.size()];
    }
}

} // namespace aether
