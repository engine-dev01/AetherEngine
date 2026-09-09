// binder.cpp — Binder PID/UID override state (transient)
// overridePid/overrideUid/restorePid/restoreUid — removed 2026-09-09:
// JNI surface cut (no Kotlin callers, restore-without-set bug — WIRING_AUDIT §B).
// Kept as placeholder: if override state tracking is needed again, restore
// from git history at commit 718f5c9.
#include "binder.hpp"

namespace aether {
// (intentionally empty — state removed with the dead API)
} // namespace aether
