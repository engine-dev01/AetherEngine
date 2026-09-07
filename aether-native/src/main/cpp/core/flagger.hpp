// flagger.hpp — tamper/state flag bitset (Aether-aligned atomic flag bitset)
// Mirrors Bit 0x1d (29)
// marks the Android 10 (API 29) scoped-storage boundary.
#pragma once
#include <atomic>
#include <cstdint>

namespace aether {

class Flagger {
public:
    enum Flag : uint32_t {
        FLAG_DEBUGGER     = 1u << 0,
        FLAG_ROOT         = 1u << 1,
        FLAG_EMULATOR     = 1u << 2,
        FLAG_XPOSED       = 1u << 3,
        FLAG_FRIDA        = 1u << 4,
        FLAG_HOOKED       = 1u << 5,
        FLAG_SCOPED_STORE = 1u << 0x1d, // API 29 scoped-storage boundary
    };

    static void mark(Flag f);
    static void clear(Flag f);
    static bool test(Flag f);
    static uint32_t snapshot();

 // SDK version probe (ro.build.version.sdk) — marks FLAG_SCOPED_STORE on API >= 29
    static void probeSdk(int apiLevel);

private:
    static std::atomic<uint32_t> s_flags;
};

} // namespace aether
