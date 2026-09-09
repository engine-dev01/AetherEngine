// binder.hpp — Binder calling PID/UID override (transient)
// API removed 2026-09-09 (WIRING_AUDIT §B: no callers + restore-without-set bug).
// Class kept so the translation unit stays in the build; restore members from
// git history at 718f5c9 if override state is ever needed again.
#pragma once
#include <cstdint>

namespace aether {
class Binder {
    // (members removed — see binder.cpp)
};
} // namespace aether
