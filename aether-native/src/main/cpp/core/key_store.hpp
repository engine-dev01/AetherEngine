// key_store.hpp — jkl_key 30-byte blob (DATA_DUMP.md §4.3)
// ต้นแบบ: 010100640100000000000000000100001400000000006464000000000100
// โครง: [version:4][flags:4][...][...][0x14][0x6464][0x00][0x0100]
#pragma once
#include <vector>
#include <cstdint>
#include <string>

namespace aether::KeyStore {

// prototype blob (hex 60 = 30 bytes)
static constexpr const char* kPrototypeHex =
    "010100640100000000000000000100001400000000006464000000000100";

// parse hex → bytes (30B)
std::vector<uint8_t> prototypeBytes();

// validate blob length + version
bool isValid(const std::vector<uint8_t>& blob);

// derive per-payload key (XOR with jkl_key)
std::vector<uint8_t> deriveKey(const std::vector<uint8_t>& jkl,
                               const std::string& payloadName);

} // namespace aether::KeyStore
