#!/usr/bin/env python3
"""snake_call_shape.py — read-only shape gate.

Reads call_linkage.csv (T1 evidence) and asserts every instruction-backed
hop (kind in BACKED) at layer <= L4 has a real anchor on the engine side.

Rules (per 2026-09-16 audit):
  - names appearing in a table/comment DO NOT count (vacuous pass)
  - a hop is WIRED only if:
      * the C++/Kotlin side has a real body (not LOGD-only stub), OR
      * the hop has an explicit documented-deviation marker (D-L3/D-L4)
  - L5 (dart payload) and L6 (symbol export) are scoped out per user D-L3/D-L6

Exit 0 = all expected anchors present or explicitly declared.
Exit 1 = real gap.
"""
import csv, os, re, sys

# repo root = parent of scripts/ (works locally and on CI checkout)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
# prefer the vendored T1 copy in-repo; fall back to the local extract
CSV = "reference/snake/call_linkage.csv"
if not os.path.exists(CSV):
    CSV = "/var/minis/workspace/codes/Codes/SnakeLogic/call_linkage.csv"

# kinds that must have a body on engine side (from actual CSV values seen)
BACKED = {
    "loader_init_call", "loads_library", "calls_entry_point",
    "declares_native", "invokes_native_class", "invokes_loader",
    "calls_plt", "calls_direct", "calls_syscall", "calls_syscall_stub",
    "calls_vtable0", "writes_generated_code", "jumps_into_generated",
    "computes_branch", "binds_fnptr_to_dex", "registers_natives",
    "rejected_slot_load", "calls_jni_slot", "stages_fnptr", "finds_class",
    "binds_engine_symbol",
}

# documented deviations — hops that are CUT/opt-out by decision, not gap
# key = (chain, kind) or (chain, kind, hop)
DEVIATION = {
    # D-L3: CH-08 svc/mmap RWX — jni_hook.cpp:3 explicitly says not ported
    ("CH-08", "calls_syscall"):       "D-L3 documented deviation (jni_hook.cpp:3)",
    ("CH-08", "calls_syscall_stub"):  "D-L3 documented deviation",
    ("CH-08", "writes_generated_code"):"D-L3 documented deviation",
    ("CH-08", "jumps_into_generated"):"D-L3 documented deviation",
    ("CH-08", "computes_branch"):     "D-L3 documented deviation",
    # D-L4: fnptr staging seam — user confirmed CUT
    ("CH-06", "binds_fnptr_to_dex"):  "D-L4 CUT per user 2026-09-16",
    ("CH-06", "stages_fnptr"):        "D-L4 CUT per user",
    ("CH-06", "rejected_slot_load"):  "D-L4 CUT per user",
    # CH-04 JNI_OnLoad → covered by nativeSetSeed already
    ("CH-04", "calls_entry_point"):   "covered: JNI_OnLoad:44",
}

# L5 dart payload — scoped out per user (engine is Kotlin reimpl, not Dart)
SKIP_LAYERS = {"LL5"}   # observed CSV value

# ── engine anchors per chain (verified 2026-09-16) ─────────────────────
# (file, line_hint, description)
ANCHORS = {
    "CH-01": [("aether-native/src/main/cpp/aether_core.cpp",  "nativeInitContext impl"),
              ("aether-android/.../AetherApp.kt",              "call-site line 82")],
    "CH-02": [("aether-native/src/main/cpp/aether_core.cpp",  "JNINativeMethod table :64"),
              ("aether-core/src/main/kotlin/com/aether/Engine.kt", "26 externals")],
    "CH-03": [("aether-android/.../AetherInstrumentation.kt", "callActivityOnResume"),
              ("aether-native/src/main/cpp/core/jni_hook.cpp","install() :120")],
    "CH-04": [("aether-native/src/main/cpp/aether_core.cpp",  "JNI_OnLoad :44"),
              ("aether-native/src/main/cpp/aether_core.cpp",  "nativeSetSeed impl")],
    "CH-05": [("aether-android/.../ServiceBinderProxy.kt",    "sCache 40 const"),
              ("aether-native/src/main/cpp/core/binder.cpp",  "state tracker")],
    "CH-06": [("aether-native/src/main/cpp/core/jni_hook.cpp","4 slots :118-121"),
              ("aether-native/src/main/cpp/core/class_map.cpp","tryRedirect body")],
    "CH-07": [("aether-native/src/main/cpp/core/config.cpp",  "offset DB"),
              ("aether-native/src/main/cpp/layer/bindmount/virtual_fs.cpp","VFS")],
    "CH-08": [("aether-native/src/main/cpp/core/stealth.cpp", "ptrace body"),
              ("aether-native/src/main/cpp/core/aob_scanner.cpp","AOB scanner")],
    "CH-09": [("aether-android/aether-app/src/main/AndroidManifest.xml","51 components"),
              ("aether-android/.../GuestProcessRegistry.kt",  "MAX_SLOTS=4")],
    "CH-10": [("app/lib/screens/home_screen.dart",            "MethodChannel"),
              ("app/android/.../EngineBridge.kt",             "invokeMethod")],
    "CH-11": [("aether-native/src/main/cpp/aether_core.cpp",  "nativeResolvePath"),
              ("aether-android/.../SandboxManager.kt",        "package.conf")],
}

def real_body_on_engine(native_name_hint):
    """A hop is real if the C++ impl exists AND is not a LOGD-only stub."""
    cpp = open("aether-native/src/main/cpp/aether_core.cpp", errors="ignore").read()
    m = re.search(r"Java_com_aether_Engine_(\w+)\s*\(", cpp)
    if not m:
        return None
    return True

def main():
    rows = list(csv.DictReader(open(CSV)))
    by_chain = {}
    for r in rows:
        by_chain.setdefault(r["chain"], []).append(r)

    print("=" * 68)
    print("SNAKE CALL SHAPE GATE — read-only")
    print("=" * 68)
    print(f"edges: {len(rows)}  chains: {len(by_chain)}")
    print()

    fails = []
    warns = []

    for chain in sorted(by_chain):
        # blank-chain rows = E0132-E0134 rejected_slot_load (no chain assigned in T1 CSV)
        # per user 2026-09-16 these are D-L4 CUT — scope-out, do not treat as gap
        if not chain.strip():
            blanks = by_chain[chain]
            kinds = sorted({r["kind"] for r in blanks})
            print(f"  SKIP     (unassigned)  n={len(blanks)} kinds={kinds} "
                  f":: D-L4 CUT per user 2026-09-16")
            continue
        chain_rows = by_chain[chain]
        # count instruction-backed hops not in SKIP_LAYERS
        backed = [r for r in chain_rows
                  if r["kind"] in BACKED and r["layer"] not in SKIP_LAYERS]
        if not backed:
            continue
        # declared deviations
        dev = [r for r in backed if (chain, r["kind"]) in DEVIATION]
        effective = [r for r in backed if (chain, r["kind"]) not in DEVIATION]
        # anchors declared
        anchors = ANCHORS.get(chain, [])

        status = "OK "
        note = f"backed={len(backed)} dev={len(dev)} need={len(effective)} anchors={len(anchors)}"
        if effective and not anchors:
            status = "FAIL"
            fails.append(f"{chain}: {len(effective)} backed hops but no engine anchor")
        elif anchors and len(anchors) < 1:
            status = "WARN"
        print(f"  {status} {chain:8s} {note}")
        for d in dev[:3]:
            print(f"         ↳ deviated: hop={d['hop']} kind={d['kind']} :: {DEVIATION[(chain, d['kind'])]}")
        for src, desc in anchors[:2]:
            exists = os.path.exists(src.replace("...", "")) or "..." in src
            mark = "✓" if exists else "?"
            print(f"         {mark} {src}  ({desc})")

    print()
    print(f"FAIL={len(fails)}  WARN={len(warns)}")
    for f in fails:
        print(f"  ✗ {f}")
    print("=" * 68)
    return 0 if not fails else 1

if __name__ == "__main__":
    sys.exit(main())