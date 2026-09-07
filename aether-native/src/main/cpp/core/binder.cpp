// binder.cpp — Binder PID/UID override (transient)
#include "binder.hpp"

namespace aether {

// Note: Real binder override uses internal libbinder APIs.
// Here we expose a controlled shim; actual override done in Kotlin layer
// via hidden reflection (setBinderCallingPidOverride). Native just tracks state.

static int g_pid = -1;
static int g_uid = -1;

int Binder::overridePid(int newPid) {
    int old = g_pid; g_pid = newPid; return old;
}
int Binder::overrideUid(int newUid) {
    int old = g_uid; g_uid = newUid; return old;
}
int Binder::restorePid(int oldPid) {
    int prev = g_pid; g_pid = oldPid; return prev;
}
int Binder::restoreUid(int oldUid) {
    int prev = g_uid; g_uid = oldUid; return prev;
}

} // namespace aether
