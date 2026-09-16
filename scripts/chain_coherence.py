#!/usr/bin/env python3
"""
chain_coherence.py — snake-chain ↔ AetherEngine coherence matrix (call-site grade)

Why this exists: grepping a method NAME is vacuous — the name appears in the
JNINativeMethod table, in comments and in declarations, so a chain can read
"WIRED" while every hop is an empty stub with no caller. This gate only
accepts:

  K  = a real Kotlin/Java CALL-SITE  (Engine.<name>(  or  <name>(  outside the
       declaration itself, inside a shipped module source set)
  B  = a real C++ BODY               (the Java_* function's body is not just a
       LOGD/return-constant stub)

and maps the 11 snake chains (CALL_LINKAGE.md / call_linkage.csv) onto the
engine anchors that must exist for each chain to run end to end.

Exit 1 on any FAIL: a chain hop whose mechanism is absent, or whose supporting
native is declared but never called, or whose body is a stub while something
else already calls it (silent no-op in production).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, "aether-native/src/main/cpp")
MODULES = [  # shipped source sets only (no test-stubs, no Flutter scaffold)
    os.path.join(ROOT, "aether-core/src/main"),
    os.path.join(ROOT, "aether-android/aether-app/src/main"),
]
KOTLIN = os.path.join(ROOT, "aether-core/src/main/kotlin/com/aether/Engine.kt")

fails, notes = [], []


def kt_files():
    out = []
    for b in MODULES:
        for d, _, fs in os.walk(b):
            out += [os.path.join(d, f) for f in fs if f.endswith((".kt", ".java"))]
    return sorted(out)


def cpp_files():
    out = []
    for d, _, fs in os.walk(CPP):
        out += [os.path.join(d, f) for f in fs if f.endswith((".cpp", ".hpp", ".h"))]
    return sorted(out)


KT = {f: open(f, errors="ignore").read() for f in kt_files()}
CP = {f: open(f, errors="ignore").read() for f in cpp_files()}
REL = lambda p: os.path.relpath(p, ROOT)


# ── 1. native inventory: declaration / table / body / call-site ──────────────
def natives():
    decl = re.findall(r"external fun (\w+)\s*\(", open(KOTLIN).read())
    rows = {}
    # table entries (single-line form as used by aether_core.cpp)
    tbl = {}
    for f, t in CP.items():
        for m in re.finditer(r'\{\s*"(\w+)"\s*,\s*"([^"]+)"\s*,\s*\(void\s*\*\)', t):
            tbl[m.group(1)] = (m.group(2), REL(f), t[:m.start()].count("\n") + 1)
    # bodies: Java_com_aether_Engine_<name>(...) { .... }
    body = {}
    for f, t in CP.items():
        for m in re.finditer(
                r"Java_com_aether_Engine_(\w+)\s*\([^)]*\)\s*(?:noexcept\s*)?\{(.*?)\}",
                t, re.S):
            body[m.group(1)] = (m.group(2).strip(), REL(f), t[:m.start()].count("\n") + 1)
    # call-sites
    calls = {}
    for name in decl:
        hits = []
        for f, t in KT.items():
            if f == KOTLIN:
                for i, ln in enumerate(t.splitlines(), 1):
                    if re.search(r"external\s+fun\s+" + name + r"\b", ln):
                        continue
                    if re.search(r"\b" + name + r"\s*\(", ln):
                        hits.append(f"{REL(f)}:{i}")
            else:
                for i, ln in enumerate(t.splitlines(), 1):
                    if re.search(r"(Engine\.|Companion\.|native\w*|\b)" + name + r"\s*\(", ln):
                        hits.append(f"{REL(f)}:{i}")
        calls[name] = hits
    for name in decl:
        b = body.get(name, ("", None, None))
        code = b[0]
        # a body is a STUB if it contains no statement other than LOGD/return 0/""/false
        core = re.sub(r"LOGD\([^;]*\);?", "", code)
        core = re.sub(r"return\s+(0|false|nullptr|JNI_VERSION_\w+|JNI_OK|);", "", core).strip()
        stub = (code == "") or (core == "")
        rows[name] = {
            "table": tbl.get(name),
            "body_at": f"{b[1]}:{b[2]}" if b[1] else None,
            "stub": stub,
            "code": code,
            "calls": calls[name],
        }
    return rows


# ── 2. chain → required anchors ─────────────────────────────────────────────
# Each chain: what snake does (evidence) and what the engine must provide.
CHAINS = [
    ("CH-01", "load libengine.so -> JNI_OnLoad -> com/aether/Engine",
     {"system_load": r"loadLibrary\(\s*\"(aether|engine)",
      "jni_onload": r"JNI_OnLoad",
      "init_call": r"nativeInitContext\s*\("}),
    ("CH-02", "dynamic loader -> 44 constructors (init_array)",
     {"ctor_or_init": r"__attribute__\s*\(\s*\(\s*constructor",
      "seed_call": r"nativeSetSeed\s*\("}),
    ("CH-03", "JNI_OnLoad builds RWX pages + synthesises branch opcode",
     {"rwx_pages": r"PROT_EXEC\s*\|\s*PROT_WRITE|mmap\([^)]*PROT_ALL",
      "branch_synth": r"0x14000000|B\s*opcode|mov w\d+,\s*#0x14"}),
    ("CH-04", "registration sites -> hidden API exemption (sCache/initialApplication)",
     {"exempt_call": r"nativeExemptHiddenApi\s*\(",
      "exempt_impl": r"setHiddenApiExemptions",
      "sCache_mech": r"sCache"}),
    ("CH-05", "registration site -> flagger (anti-debug + hook slots)",
     {"ptrace": r"ptrace\s*\(\s*PTRACE",
      "slot_hook": r"origTable|hookSlot",
      "callback_proxy": r"mCallback"}),
    ("CH-06", "one recoverable native handler (.mytext): stealth RegisterNatives",
     {"jni_env_hook": r"GetMethodID|CallObjectMethod",
      "mprotect_art": r"mprotect",
      "trio_native": r"nativeProcessTriple"}),
    ("CH-07", "registration site -> VirtualFS payload redirect",
     {"vfs_enable": r"enableIO\s*\(",
      "vfs_rule": r"addIORule\s*\(",
      "vfs_resolve": r"virtualResolvePath|nativeResolvePath\s*\("}),
    ("CH-08", "20 Java invoke sites -> 13 registered natives (svc/syscall)",
     {"svc_syscall": r"svc\s+#0x0|__NR_|syscall\s*\(",
      "reflect_update": r"nativeReflectUpdate",
      "pair_call": r"nativeProcessPair\s*\("}),
    ("CH-09", "Flutter side uses the same mechanism (channel)",
     {"channel": r"MethodChannel",
      "dart_bridge": r"nativeGetStatus|EngineBridge"}),
    ("CH-10", "Dart platform-channel handler + engine symbols",
     {"receiver": r"AetherStubReceiver|BroadcastReceiver",
      "handler": r"onReceive"}),
    ("CH-11", "Dart call edges by fan-in (job/pending intent plumbing)",
     {"job": r"onStartJob|JobService",
      "pending": r"PendingIntent"}),
]


def probe(pattern, corpus):
    rx = re.compile(pattern)
    for f, t in corpus.items():
        for i, ln in enumerate(t.splitlines(), 1):
            if rx.search(ln):
                return f"{REL(f)}:{i}"
    return None


def main():
    N = natives()
    print("═" * 86)
    print("A. native hop grade   K = real Kotlin call-site   B = real C++ body")
    print("═" * 86)
    hdr = f"{'native':26s} {'tbl':>4s} {'K':>3s} {'B':>4s}  {'note':30s}  anchor"
    print(hdr)
    print("-" * 86)
    stub_called, orphan_real = [], []
    for name, r in N.items():
        tk = "✓" if r["table"] else "✗"
        k = len(r["calls"])
        b = "STUB" if r["stub"] else "real"
        note = ""
        if not r["table"]:
            fails.append(("A1", f"{name}: declared external but NOT in JNINativeMethod table",
                          REL(KOTLIN), "would throw UnsatisfiedLinkError"))
        if k == 0 and not r["stub"]:
            note = "real body, zero call-site"
            orphan_real.append(name)
        if k == 0 and r["stub"]:
            note = "stub + zero call-site"
            fails.append(("A2", f"{name}: chain hop wired nowhere (stub body, no caller)",
                          r["body_at"] or REL(KOTLIN), "dead chain segment"))
        if k > 0 and r["stub"]:
            note = "CALLED but body is a stub -> silent no-op"
            stub_called.append(name)
            fails.append(("A3", f"{name}: has {k} call-site(s) but C++ body is empty/LOGD",
                          r["body_at"] or "?", "runtime silently does nothing"))
        print(f"{name:26s} {tk:>4s} {k:>3d} {b:>4s}  {note:30s}  "
              f"{(r['calls'][0] if r['calls'] else (r['body_at'] or ''))}")
    print("-" * 86)
    print(f"  externals={len(N)}  called={sum(1 for r in N.values() if r['calls'])}  "
          f"stub-bodies={sum(1 for r in N.values() if r['stub'])}  "
          f"real-body-orphans={len(orphan_real)}  called-but-stub={len(stub_called)}")

    print("\n" + "═" * 86)
    print("B. chain coherence    snake mechanism -> engine evidence (file:line)")
    print("═" * 86)
    print(f"{'chain':7s} {'required anchor':16s} {'present':8s}  evidence")
    print("-" * 86)
    for ch, what, reqs in CHAINS:
        print(f"{ch:7s} {what[:66]}")
        miss = []
        for label, pat in reqs.items():
            hit = probe(pat, CP) or probe(pat, KT)
            print(f"{'':8s}{label:16s} {'YES' if hit else 'NO':8s}  {hit or '—'}")
            if not hit:
                miss.append(label)
        if miss:
            fails.append(("B1", f"{ch}: engine lacks {', '.join(miss)}",
                          "chain spec: CALL_LINKAGE.md", what))
    print("-" * 86)

    print("\n" + "═" * 86)
    print("RESULT")
    print("═" * 86)
    seen = set()
    for code, what, where, detail in fails:
        if (code, what) in seen:
            continue
        seen.add((code, what))
        print(f"  ✗ [{code}] {what}\n      {where}\n      {detail}")
    print(f"\n  FAIL={len(fails)}   notes={len(notes)}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
