// payload_store.hpp — 92 encrypted payloads (DATA_DUMP.md §4)
// ชื่อไฟล์ = SHA-256 hex (64 chars), เนื้อ = encrypted bytes
#pragma once
#include <string>
#include <vector>
#include <cstdint>

namespace aether::PayloadStore {

// ลงทะเบียน payload (ชื่อ = sha256 hex, data = encrypted bytes)
void put(const std::string& sha256hex, const std::vector<uint8_t>& data);
bool has(const std::string& sha256hex);
std::vector<uint8_t> get(const std::string& sha256hex);
size_t count();
void clear();

// load จาก directory (files/ — SHA-256 named)
size_t loadDir(const std::string& dirPath);

// ถอด payload โดยใช้ jkl_key (DATA_DUMP.md §4.3) + KeyStore::deriveKey (per-name XOR)
// return empty vector ถ้าไม่เจอ payload หรือ key ไม่ valid (size != 30)
std::vector<uint8_t> decrypt(const std::string& sha256hex, const std::vector<uint8_t>& jklKey);

} // namespace aether::PayloadStore
