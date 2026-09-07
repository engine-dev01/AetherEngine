// string_decryptor.hpp — Stream decryptor (NATIVE_LOGIC.md §C)
// ต้นแบบ: key 0x30261adb60b7b4f4, ciphertext 9B + len/flag, decrypt loop @0x9063c
// ต้นแบบทำ JIT-generated decryptor (mmap RWX + rand). Aether: portable C++ เทียบเท่า
// — ไม่ทำ RWX/JIT เพื่อผ่าน Play Protect + NDK W^X
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace aether::StrDecrypt {

// key เดียวกับต้นแบบ
static constexpr uint64_t kKey = 0x30261adb60b7b4f4ULL;

// ถอด 1 entry: ciphertext (N bytes) + key stream
// ใช้ XOR stream เดียวกับ Crypto::xorCrypt แต่ state แบบ counter (ต้นแบบ §C.2)
std::string decrypt(const std::vector<uint8_t>& cipher);

// helper: decrypt จาก hex string (สำหรับ table ใน .rodata แบบ hex dump)
std::string decryptHex(const std::string& hexCipher);

// ตรวจว่า decrypt สำเร็จ (printable ASCII)
bool isPrintable(const std::string& s);

// key override API — สำหรับ variant cipher (JIT-generated keys ใน reference engine)
// key=0 → กลับใช้ kKey (default aetherKey); non-zero → ใช้ค่า override
// ต้องเรียก clearKeyOverride() หลังใช้เสร็จ (thread-unsafe by design — caller lock ภายนอก)
void setKeyOverride(uint64_t key);
void clearKeyOverride();

} // namespace aether::StrDecrypt
