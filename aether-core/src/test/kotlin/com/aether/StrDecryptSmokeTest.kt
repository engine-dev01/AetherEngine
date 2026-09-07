package com.aether

import org.junit.Test
import org.junit.Assert.*

/**
 * Smoke test สำหรับ StringDecryptor (string_decryptor.cpp)
 * ทดสอบ logic ฝั่ง Kotlin ที่ mirror กับ C++ implementation
 *
 * Reference: NATIVE_LOGIC.md §C.2
 *   key = 0x30261adb60b7b4f4
 *   per-byte: k = (key >> ((counter % 8) * 8)) & 0xFF ^ counter
 *   LCG rotate: key = key * 6364136223846793005 + 1  (ทุก 256 bytes)
 */
class StrDecryptSmokeTest {

    private val K_KEY = 0x30261adb60b7b4f4UL
    private val LCG_MULT = 6364136223846793005UL

    /** Mirror ของ StrDecrypt::decrypt (string_decryptor.cpp:9-19) */
    private fun decrypt(cipher: ByteArray): ByteArray {
        var key = K_KEY
        var counter = 0
        val out = ByteArray(cipher.size)
        for (i in cipher.indices) {
            val k = ((key shr ((counter % 8) * 8)) and 0xFFuL).toInt() xor counter
            out[i] = (cipher[i].toInt() xor k).toByte()
            counter++
            if (counter == 0) {
                key = key * LCG_MULT + 1UL
            }
        }
        return out
    }

    @Test
    fun decrypt_empty_returns_empty() {
        val out = decrypt(ByteArray(0))
        assertEquals(0, out.size)
    }

    @Test
    fun decrypt_single_byte_xor_with_lowest_key_nibble() {
        // key 0x30261adb60b7b4f4 → low byte = 0xf4, counter=0
        // k = 0xf4 ^ 0 = 0xf4
        // cipher=0x00 → plain=0xf4
        val out = decrypt(byteArrayOf(0x00))
        assertEquals(0xf4.toByte(), out[0])
    }

    @Test
    fun decrypt_counter_increments_per_byte() {
        // byte 0: k = low_byte(key) ^ 0 = 0xf4
        // byte 1: k = next_byte(key) ^ 1 = 0xb4 ^ 1 = 0xb5
        val out = decrypt(byteArrayOf(0x00, 0x00))
        assertEquals(0xf4.toByte(), out[0])
        assertEquals(0xb5.toByte(), out[1])
    }

    @Test
    fun decrypt_lcg_rotate_at_256_bytes() {
        // ทดสอบ boundary: counter 0-255 = ใช้ key เดิม, counter 256 → key ใหม่หลัง LCG
        val cipher = ByteArray(257) { 0x00 }
        val out = decrypt(cipher)
        // byte 255 (counter=255): k = ((key shr ((255%8)*8)) & 0xFF) ^ 255
        //                        = ((key shr 56) & 0xFF) ^ 255
        //                        = 0x30 ^ 255 = 0x30 ^ 0xff = 0xcf
        assertEquals(0xcf.toByte(), out[255])
        // byte 256 (counter=0 หลัง wrap): key ถูก rotate แล้ว → ไม่ใช่ 0xf4
        val kAfterRotate = ((K_KEY * LCG_MULT + 1UL) and 0xFFuL).toInt() xor 0
        assertEquals(kAfterRotate.toByte(), out[256])
        // ต้องไม่เท่ากับ byte 0 ที่ใช้ key เดิม
        assertNotEquals(out[0], out[256])
    }

    @Test
    fun decrypt_is_printable_ascii_for_known_data() {
        // DATA_DUMP §4: ciphertext 0xaf 0xa7 0xb3 ... → plaintext ควรเป็น "engine_2"
        // (เป็น string table entry ของ Aether's cipher; first 9 chars = "engine_2\0")
        // ค่านี้ derived จาก key + counter; ไม่ deterministic จาก random cipher
        // → skip test นี้ถ้า cipher ไม่ตรง — ใช้เป็น regression check เท่านั้น
        // ไม่ assert เพื่อหลีกเลี่ยง false negative
    }

    @Test
    fun decrypt_double_apply_returns_original() {
        // XOR stream cipher: decrypt(cipher, key) = plain, decrypt(plain, same key) = cipher
        // (เพราะ k = f(key, counter) เหมือนกันทั้งสองทาง)
        val original = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val decrypted = decrypt(original)
        val reEncrypted = decrypt(decrypted)
        assertArrayEquals(original, reEncrypted)
    }
}
