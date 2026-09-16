#!/usr/bin/env python3
"""
system_link_audit.py — deep cross-layer coherence audit (snake <-> AetherEngine)

Reads ONLY what physically exists. Every finding carries file:line.

Checks
  A. JNI binding coherence   : Kotlin `external fun`  <->  C++ JNINativeMethod tables
  B. RegisterNatives targets : every FindClass string in C++ has a table
  C. Stale file references   : gate scripts / CMake / docs name files that do not exist
  D. Chain wiring (CH-01..11): snake mechanism -> engine implementation -> call-site
  E. Snake-side re-verify    : call_linkage.csv edges vs extracted snake artifacts

Exit 0 = no FAIL.  FAIL = hard break (would throw / would not build / would not run).
QUAL = qualified (exists but not wired).  WARN = cosmetic/stale doc.
"""
import csv
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, "aether-native/src/main/cpp")
SNAKE_LOGIC = "/var/minis/workspace/codes/Codes/SnakeLogic"
SNAKE_X = "/var/minis/workspace/AetherEngine_clean/reference/SNAKE_extract"

KOTLIN_GLOBS = ["aether-core/src/main/kotlin", "aether-android", "app/src/main/kotlin"]

fails, quals, warns = [], [], []
_FCACHE, _LCACHE = {}, {}


def rel(p):
    return os.path.relpath(p, ROOT)


def all_files(base, exts):
    """Memoised walk — the audit touches the same trees many times."""
    key = (base, tuple(sorted(exts)))
    if key in _FCACHE:
        return _FCACHE[key]
    out = []
    if os.path.isdir(base):
        for d, ds, fs in os.walk(base):
            if "/.git" in d or "/build/" in d or d.endswith("/build"):
                continue
            for f in fs:
                if f.endswith(exts):
                    out.append(os.path.join(d, f))
    out.sort()
    _FCACHE[key] = out
    return out


def lines_of(f):
    if f not in _LCACHE:
        try:
            _LCACHE[f] = open(f, errors="ignore").read().splitlines()
        except OSError:
            _LCACHE[f] = []
    return _LCACHE[f]


# ───────────────────────── A. JNI binding coherence ─────────────────────────
def kotlin_externals():
    """-> {(fqn_class, method): (descriptor, file, line)}"""
    tbl = {}
    desc_map = {
        "Int": "I", "Long": "J", "Boolean": "Z", "Byte": "B", "Char": "C",
        "Short": "S", "Float": "F", "Double": "D", "Unit": "V", "String": "Ljava/lang/String;",
        "Any": "Ljava/lang/Object;", "ByteArray": "[B",
    }

    def jtype(t):
        t = t.strip()
        star = t.endswith("*")
        if star:
            t = t[:-1].strip()
        null = t.endswith("?")
        if null:
            t = t[:-1]
        base = t.split("<")[0].strip()
        if base in desc_map:
            d = desc_map[base]
        elif base == "List" or base == "Array":
            d = "[Ljava/lang/Object;"
        elif re.match(r"^[a-z]", base):
            d = "Ljava/lang/Object;"
        elif "." in base:
            # fully-qualified Kotlin type (java.lang.reflect.Method, android.content.Context)
            d = "L" + base.replace(".", "/") + ";"
        else:
            # simple name -> resolve through the file's imports, else same package
            c = imports.get(base)
            if c:
                d = "L" + c + ";"
            else:
                d = "L" + pkg.replace(".", "/") + "/" + base + ";" if pkg else "Ljava/lang/Object;"
        return d

    for g in KOTLIN_GLOBS:
        for f in all_files(os.path.join(ROOT, g), (".kt",)):
            lines = open(f, errors="ignore").read().splitlines()
            pkg = ""
            imports = {}
            cls_stack = []
            for i, ln in enumerate(lines, 1):
                m = re.match(r"\s*package\s+([\w.]+)", ln)
                if m:
                    pkg = m.group(1)
                m = re.match(r"\s*import\s+([\w.]+(?:\.\w+)+)", ln)
                if m:
                    fq = m.group(1)
                    imports[fq.rsplit(".", 1)[1]] = fq.replace(".", "/")
                m = re.match(r"\s*(?:/\*.*?\*/\s*)?(?:public\s+|internal\s+|open\s+|abstract\s+|"
                             r"final\s+|sealed\s+|data\s+|@\w+\s*)*"
                             r"(class|object|interface)\s+(\w+)", ln)
                if m and "{" in ln:
                    cls_stack.append(m.group(2))
                m = re.search(r"external\s+fun\s+(\w+)\s*\((.*?)\)\s*(?::\s*([\w.<>\[\]?*]+))?\s*", ln)
                if m:
                    name, args, ret = m.group(1), m.group(2), m.group(3) or "Unit"
                    ps = []
                    if args.strip():
                        for a in args.split(","):
                            if ":" in a:
                                ps.append(jtype(a.split(":", 1)[1]))
                    sig = "(" + "".join(ps) + ")" + jtype(ret)
                    fqn = pkg + "." + "$".join(cls_stack) if cls_stack else pkg
                    tbl[(fqn, name)] = (sig, rel(f), i)
                if m is None and re.match(r"^\s*\}\s*$", ln) and cls_stack:
                    cls_stack.pop()
    return tbl


def cpp_tables():
    """-> ({name: (sig, fn, file, line)}, {registered_class: [names]}, findclass strings)"""
    entries, bound, findclass = {}, {}, set()
    for f in all_files(CPP, (".cpp", ".hpp", ".h")):
        src = open(f, errors="ignore").read()
        lines = src.splitlines()
        # JNINativeMethod arrays, tolerant of 1-or-2-line entries
        for m in re.finditer(r"JNINativeMethod\s+(\w+)\s*\[\]\s*=\s*\{(.*?)\n\s*\};", src, re.S):
            tname, body = m.group(1), m.group(2)
            for em in re.finditer(r'\{\s*"(\w+)"\s*,\s*"([^"]+)"\s*,\s*\(void\s*\*\)\s*\(?([\w:]+)\)?\s*\}', body):
                n, s, fn = em.groups()
                ln = src[:m.start()].count("\n") + 1
                entries[n] = (s, fn, rel(f), ln)
                bound.setdefault("com/aether/Engine", []).append(n)
        # RegisterNatives target classes (FindClass literal nearby)
        for m in re.finditer(r'FindClass\("([\w/$_]+)"\)', src):
            findclass.add(m.group(1))
        for m in re.finditer(r'RegisterNatives\(\s*([\w]+)', src):
            pass
    return entries, bound, findclass


def check_binding():
    kt = kotlin_externals()
    ent, bound, fcl = cpp_tables()
    print("── A. JNI binding coherence " + "─" * 42)
    print(f"   Kotlin external fun : {len(kt)}   (across {len(set(c for c, _ in kt))} classes)")
    print(f"   C++ table entries   : {len(ent)}")
    dangling, dead = [], []
    for (cls, name), (sig, f, ln) in sorted(kt.items()):
        if name not in ent:
            dangling.append((cls, name, sig, f, ln))
    for name, (sig, fn, f, ln) in sorted(ent.items()):
        if not any(n == name for _, n in kt):
            dead.append((name, sig, fn, f, ln))
    for cls, name, sig, f, ln in dangling:
        fails.append(("A1", f"external fun with NO C++ binding -> UnsatisfiedLinkError at call",
                      f"{f}:{ln}", f"{cls}.{name} : {sig}"))
    for name, sig, fn, f, ln in dead:
        warns.append(("A2", "C++ table entry not declared external in Kotlin (dead binding)",
                      f"{f}:{ln}", f"{name} : {sig} -> {fn}"))
    # signature agreement for those bound both ways
    for (cls, name), (ksig, kf, kln) in kt.items():
        if name in ent:
            csig = ent[name][0]
            if csig != ksig:
                fails.append(("A3", "descriptor mismatch Kotlin vs C++ table",
                              f"{kf}:{kln}", f"{name}: kt={ksig} cpp={csig}"))
    print(f"   dangling (kt only)  : {len(dangling)}")
    print(f"   dead (cpp only)     : {len(dead)}")
    print(f"   FindClass strings   : {sorted(fcl) or '(none)'}")
    for c in sorted(fcl):
        if c not in bound and not c.startswith("java/lang") and not c.startswith("android"):
            quals.append(("A4", "FindClass target has no JNINativeMethod table in C++",
                          "aether-native/src/main/cpp", c))
    return kt, ent


# ───────────────────────── C. stale references ─────────────────────────
def check_stale():
    print("── C. stale file references " + "─" * 46)
    refs = []
    for base, pats in ((os.path.join(ROOT, "scripts"), (".py", ".sh")),
                       (CPP, (".txt",)),
                       (os.path.join(ROOT, "docs"), (".md",))):
        for f in all_files(base, pats):
            for i, ln in enumerate(open(f, errors="ignore").read().splitlines(), 1):
                for m in re.finditer(r"([\w./-]+\.(?:cpp|hpp|kt|py|sh|md|java))\b", ln):
                    refs.append((rel(f), i, m.group(1)))
    # CMake include dirs
    cml = open(os.path.join(CPP, "CMakeLists.txt"), errors="ignore").read()
    for m in re.finditer(r"\$\{CMAKE_CURRENT_LIST_DIR\}/([\w/.-]+)", cml):
        p = os.path.join(CPP, m.group(1))
        if not os.path.exists(p):
            fails.append(("C2", "CMakeLists references path that does not exist",
                          rel(os.path.join(CPP, "CMakeLists.txt")), m.group(1)))
    # one-pass index instead of one `find` per reference (was the timeout cause)
    index = set()
    for d, ds, fs in os.walk(ROOT):
        if "/.git" in d or "/build" in d:
            ds[:] = [x for x in ds if x not in (".git", "build")]
        for f in fs:
            index.add(f)
    seen = set()
    for src, i, ref in refs:
        base = os.path.basename(ref)
        if base in seen or "/" not in ref:
            continue
        if base not in index:
            seen.add(base)
            warns.append(("C1", "named file absent from repo", f"{src}:{i}", ref))
    print(f"   checked {len(refs)} filename mentions against {len(index)} files; "
          f"{len(seen)} absent")


# ───────────────────────── D. chain wiring ─────────────────────────
# mechanism probe: (chain, what snake does, grep pattern for engine impl, files to look in)
PROBE = [
    ("CH-01", "Application.onCreate -> native init (nativeInitContext)",
     r"nativeInitContext", ["aether-android", "aether-core"]),
    ("CH-01", "attachBaseContext seeds process/uid (nativeSetSeed)",
     r"nativeSetSeed", ["aether-core", "aether-android"]),
    ("CH-02", "nativeProcessPair/Triple/Quartet chain",
     r"nativeProcess(Pair|Triple|Quartet)", ["aether-core", "aether-android", CPP]),
    ("CH-04", "sCache clear/refill of ActivityThread",
     r"sCache", ["aether-core", "aether-android", CPP]),
    ("CH-04", "mInitialApplication / currentActivityThread reflection",
     r"mInitialApplication|currentActivityThread", ["aether-core", "aether-android"]),
    ("CH-05", "Instrumentation.mCallback proxy",
     r"mCallback", ["aether-android", "aether-core"]),
    ("CH-05", "ptrace anti-debug",
     r"ptrace|PTRACE", [CPP]),
    ("CH-05", "ActivityInstrumentation hook slots",
     r"hookSlot|origTable|slot", [CPP]),
    ("CH-06", "JNI env hook (GetMethodID/Call*Method) = RegisterNatives stealth",
     r"GetMethodID|CallObjectMethod", [CPP]),
    ("CH-06", "mprotect on ART tables",
     r"mprotect", [CPP]),
    ("CH-07", "VirtualFS redirect (bindmount layer)",
     r"virtualResolvePath|enableIO|addIORule|VirtualFS", [CPP, "aether-core", "aether-android"]),
    ("CH-08", "direct svc #0 syscall",
     r"svc\s|#0|__NR_|syscall\(", [CPP]),
    ("CH-09", "JobService onStartJob proxy",
     r"onStartJob|JobService", ["aether-android", "aether-core"]),
    ("CH-10", "BroadcastReceiver proxy",
     r"onReceive|BroadcastReceiver", ["aether-android", "aether-core"]),
    ("CH-11", "PendingIntent send proxy",
     r"PendingIntent", ["aether-android", "aether-core"]),
]


def check_chains():
    print("── D. chain wiring CH-01..11 " + "─" * 43)
    rows = []
    for chain, what, pat, places in PROBE:
        rx = re.compile(pat)
        hits = []
        for p in places:
            base = p if os.path.isabs(p) else os.path.join(ROOT, p)
            if os.path.isdir(base):
                files = all_files(base, (".kt", ".java", ".cpp", ".hpp", ".h"))
            elif os.path.isfile(base):
                files = [base]
            else:
                continue
            for f in files:
                for i, ln in enumerate(lines_of(f), 1):
                    if rx.search(ln) and "import " not in ln:
                        hits.append(f"{rel(f)}:{i}")
        n = len(hits)
        status = "WIRED" if n else "ABSENT"
        if not n:
            quals.append(("D1", f"{chain}: snake mechanism has no engine implementation",
                          "(probe)", f'{what}   grep "{pat}"'))
        rows.append((chain, what, n, status, hits[0] if hits else "—"))
    w = max(len(r[1]) for r in rows) + 2
    print(f"   {'chain':7s} {'impl':{w}s} {'hits':>5s}  {'status':7s}  first-anchor")
    for c, what, n, st, h in rows:
        print(f"   {c:7s} {what:{w}s} {n:5d}  {st:7s}  {h}")
    return rows


# ───────────────────────── E. snake-side re-verify ─────────────────────────
def check_snake():
    print("── E. snake evidence re-verify " + "─" * 38)
    csvp = os.path.join(SNAKE_LOGIC, "call_linkage.csv")
    if not os.path.exists(csvp):
        warns.append(("E0", "call_linkage.csv not found", csvp, ""))
        return
    rows = list(csv.DictReader(open(csvp, errors="ignore")))
    print(f"   edges={len(rows)}  chains={len(set(r['chain'] for r in rows))}")
    # instruction-backed kinds (verified against the actual CSV value list)
    BACKED = {"calls_direct", "calls_plt", "calls_syscall", "calls_syscall_stub",
              "calls_jni_slot", "calls_vtable0", "calls_entry_point",
              "registers_natives", "binds_engine_symbol", "binds_fnptr_to_dex",
              "stages_fnptr", "jumps_into_generated", "writes_generated_code",
              "loader_init_call", "invokes_loader", "invokes_native_class",
              "declares_native", "finds_class", "rejected_slot_load", "loads_library"}
    backed = [r for r in rows if r["kind"] in BACKED]
    print(f"   instruction/artifact-backed edges: {len(backed)} of {len(rows)}")
    lib = os.path.join(SNAKE_X, "raw/lib/arm64-v8a/libengine.so")
    dart = os.path.join(SNAKE_X, "raw/lib/arm64-v8a/libapp.so")
    bad = 0
    for r in backed:
        tgt = r["dst_offset"]
        mod = r["dst_module"] or r["src_module"]
        f = lib if "libengine" in mod else dart
        if not os.path.exists(f):
            continue
        try:
            off = int(tgt, 16)
        except ValueError:
            bad += 1
            continue
        sz = os.path.getsize(f)
        if off >= sz:
            bad += 1
            fails.append(("E1", f"{r['edge_id']}: instruction offset beyond file size",
                          f"call_linkage.csv hop {r['hop']} {r['chain']}",
                          f"{mod}+0x{off:x} > 0x{sz:x}"))
    print(f"   instruction-backed edges: {len(backed)}  out-of-range: {bad}")
    # dex classes referenced by L2/L3/L4 edges must exist in extracted smali
    dexnames = set()
    for r in rows:
        for fld in ("src_name", "dst_name"):
            for m in re.finditer(r"L([\w/$]+);", r[fld] or ""):
                dexnames.add(m.group(1))
    missing = []
    if os.path.isdir(os.path.join(SNAKE_X, "smali")):
        for dn in sorted(dexnames):
            p = os.path.join(SNAKE_X, "smali", dn + ".smali")
            if not os.path.exists(p) and not os.path.exists(p.replace("/$", "$")):
                missing.append(dn)
    print(f"   dex class descriptors seen: {len(dexnames)}  not in smali tree: {len(missing)}")
    for dn in missing[:6]:
        warns.append(("E2", "descriptor cited in linkage but smali file absent (inner/static?)",
                      "call_linkage.csv", dn))
    return rows


# ───────────────────────── report ─────────────────────────
def report():
    print("\n" + "=" * 78)
    for tag, arr, mark in (("FAIL", fails, "✗"), ("QUALIFIED", quals, "◐"), ("WARN", warns, "~")):
        if not arr:
            continue
        print(f"{mark} {tag}: {len(arr)}")
        for code, what, where, detail in arr:
            print(f"   [{code}] {what}\n        at {where}\n        {detail[:120]}")
    print("=" * 78)
    print(f"RESULT  FAIL={len(fails)}  QUALIFIED={len(quals)}  WARN={len(warns)}")
    return 1 if fails else 0


if __name__ == "__main__":
    check_binding()
    check_stale()
    check_chains()
    check_snake()
    sys.exit(report())
