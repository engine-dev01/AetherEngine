// stealth.hpp — Minimal anti-debug + self-integrity (no traces)
#pragma once
#include <cstdint>

namespace aether {
class Stealth {
public:
    // Block debugger attach (prctl PR_SET_DUMPABLE=0).
    static void blockDebugger();
    // Self-integrity check (returns false if tampered).
    static bool selfCheck();
    // Watchdog: verify engine still attached/active.
    static bool watchdog();
};
} // namespace aether
