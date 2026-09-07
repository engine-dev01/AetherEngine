// string_decryptor.cpp — portable XOR stream (maps to prototype key)
#include "string_decryptor.hpp"
#include <cctype>

namespace aether::StrDecrypt {

// key override state (thread-unsafe by design — caller lock ภายนอก)
static uint64_t g_key_override = 0;

void setKeyOverride(uint64_t key) { g_key_override = key; }
void clearKeyOverride()            { g_key_override = 0; }

std::string decrypt(const std::vector<uint8_t>& cipher) {
    std::string out;
    out.reserve(cipher.size());
    // state { key: kKey (or override), counter: 0 } — same as prototype §C.2
    uint64_t key = (g_key_override != 0) ? g_key_override : kKey;
    uint8_t counter = 0;
    for (size_t i = 0; i < cipher.size(); ++i) {
        uint8_t k = (uint8_t)((key >> ((counter % 8) * 8)) & 0xFF) ^ counter;
        out.push_back((char)(cipher[i] ^ k));
        ++counter;
        if (counter == 0) key = key * 6364136223846793005ULL + 1; // LCG rotate
    }
    return out;
}

std::string decryptHex(const std::string& hex) {
    std::vector<uint8_t> bytes;
    bytes.reserve(hex.size()/2);
    for (size_t i=0;i+1<hex.size();i+=2) {
        auto hv=[&](char c)->int{
            if(c>='0'&&c<='9') return c-'0';
            if(c>='a'&&c<='f') return c-'a'+10;
            if(c>='A'&&c<='F') return c-'A'+10;
            return -1;
        };
        int hi=hv(hex[i]), lo=hv(hex[i+1]);
        if (hi<0||lo<0) break;
        bytes.push_back((uint8_t)((hi<<4)|lo));
    }
    return decrypt(bytes);
}

bool isPrintable(const std::string& s) {
    if (s.empty()) return false;
    for (unsigned char c : s) if (!isprint(c) && c!='\n' && c!='\r' && c!='\t') return false;
    return true;
}

} // namespace aether::StrDecrypt
