// binder.hpp — Binder calling PID/UID override (transient)
#pragma once
#include <cstdint>

namespace aether {
class Binder {
public:
    // Override binder calling PID (returns previous).
    static int overridePid(int newPid);
    // Override binder calling UID (returns previous).
    static int overrideUid(int newUid);
    // Restore previous PID.
    static int restorePid(int oldPid);
    // Restore previous UID.
    static int restoreUid(int oldUid);
};
} // namespace aether
