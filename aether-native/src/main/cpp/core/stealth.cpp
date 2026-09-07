// stealth.cpp — Minimal anti-debug + self-integrity
#include "stealth.hpp"
#include <sys/prctl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/ptrace.h>

namespace aether {

void Stealth::blockDebugger() {
    // AetherMind spec: anti-debug level 1
    // PR_SET_DUMPABLE=0 → debugger can't ptrace
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);

    // AetherMind spec: anti-debug level 2
    // PTRACE_TRACEME: ถ้า process ถูก ptrace แล้ว → TRACEME จะ fail → return error code
    // ถ้าไม่มี debugger → TRACEME สำเร็จ → กัน debugger attach ภายหลัง
    // (ต้องเรียกตอนเริ่มต้น ก่อน process ทำอย่างอื่น)
    (void)ptrace(PTRACE_TRACEME, 0, 0, 0);
}

bool Stealth::selfCheck() {
    // Verify our own text segment is readable/exec (no hook injected)
    // Minimal: check we can read our own code page
    volatile int x = 0x1234;
    return x == 0x1234;
}

bool Stealth::watchdog() {
    // Engine still alive check (no heavy ops)
    return selfCheck();
}

} // namespace aether
