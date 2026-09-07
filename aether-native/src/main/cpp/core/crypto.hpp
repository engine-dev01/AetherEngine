// crypto.hpp — zlib compress + Xor/ChaCha payload crypto
#pragma once
#include <vector>
#include <cstdint>
#include <string>

namespace aether {
class Crypto {
public:
    // zlib deflate (slot 3)
    static bool compress(const std::vector<uint8_t>& in, std::vector<uint8_t>& out);
    // zlib inflate (slot 3)
    static bool decompress(const std::vector<uint8_t>& in, std::vector<uint8_t>& out);

    // Xor stream cipher (slot 4/5). key applied repeating.
    static void xorCrypt(const std::vector<uint8_t>& in,
                         const std::vector<uint8_t>& key,
                         std::vector<uint8_t>& out);

private:
    static const uint32_t CRC_SEED = 0xEDB88320u;
};
} // namespace aether
