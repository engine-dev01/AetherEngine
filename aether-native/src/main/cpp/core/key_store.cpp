// key_store.cpp
#include "key_store.hpp"
#include <cctype>

namespace aether::KeyStore {

static int hexNibble(char c) {
    if (c>='0'&&c<='9') return c-'0';
    if (c>='a'&&c<='f') return c-'a'+10;
    if (c>='A'&&c<='F') return c-'A'+10;
    return -1;
}

std::vector<uint8_t> prototypeBytes() {
    std::vector<uint8_t> out;
    std::string h = kPrototypeHex;
    for (size_t i=0;i+1<h.size();i+=2) {
        int hi=hexNibble(h[i]), lo=hexNibble(h[i+1]);
        if (hi<0||lo<0) break;
        out.push_back((uint8_t)((hi<<4)|lo));
    }
    return out;
}

bool isValid(const std::vector<uint8_t>& b) {
    if (b.size()!=30) return false;
    // version byte 0x01
    if (b[0]!=0x01) return false;
    return true;
}

std::vector<uint8_t> deriveKey(const std::vector<uint8_t>& jkl,
                               const std::string& name) {
    std::vector<uint8_t> k = jkl;
    for (size_t i=0;i<name.size();++i) k[i % k.size()] ^= (uint8_t)name[i];
    return k;
}

} // namespace aether::KeyStore
