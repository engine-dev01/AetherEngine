#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""structural_gates.py — audit §7.8: gate ใหม่ 3 ตัว (orphan-TU / manifest↔slots
/ stub-body) ที่ปิดช่องโหว่ 'เขียวหลอก' แบบ deterministic
Exit 0=PASS, 1=FAIL, 2=FAIL(critical orphan)
"""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
fails = []

# ── [S1] orphan-TU: ทุก source ใน CMakeLists ต้อง reachable จาก aether_core.cpp ──
cmk = (ROOT / "aether-native/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
cpp_dir = ROOT / "aether-native/src/main/cpp"
sources = re.findall(r"\$\{CMAKE_CURRENT_LIST_DIR\}/([\w/.]+\.(?:cpp|c))", cmk)
def include_closure(entry, seen=None):
    seen = seen if seen is not None else set()
    p = cpp_dir / entry
    if not p.exists() or entry in seen: return seen
    seen.add(entry)
    for inc in re.findall(r'#include\s+"([^"]+)"', p.read_text(encoding="utf-8", errors="replace")):
        # resolve relative include
        for cand in [ (p.parent / inc), (cpp_dir / inc), (cpp_dir / "core" / inc),
                      (cpp_dir / "layer" / "bindmount" / inc) ]:
            if cand.exists():
                include_closure(str(cand.relative_to(cpp_dir)), seen)
                break
    return seen
closure = include_closure("aether_core.cpp")
for s in sources:
    if s in ("third_party/lz4/lz4.c",):  # vendor lib linked-in intentionally
        continue
    base = pathlib.Path(s).stem
    # reachable = header อยู่ใน closure หรือเป็น TU หลักที่ header ถูก include
    hdr_ok = any(base == pathlib.Path(c).stem for c in closure)
    if not hdr_ok:
        fails.append(f"[S1] ORPHAN-TU: {s} compile อยู่แต่ unreachable จาก aether_core.cpp closure")
print(f"[S1] orphan-TU: {len(sources)} sources, closure {len(closure)} files — "
      + ("OK" if not any('[S1]' in f for f in fails) else "FAIL"))

# ── [S2] manifest↔slots: provider ฝั่ง :pN ต้องครบ = MAX_SLOTS ทุก manifest ──
reg = (ROOT / "aether-android/aether-app/src/main/kotlin/com/aether/engine/proxy/GuestProcessRegistry.kt")
MAX = int(re.search(r"MAX_SLOTS = (\d+)", reg.read_text(encoding="utf-8")).group(1))
for m in ROOT.rglob("AndroidManifest.xml"):
    if "/build/" in str(m) or "/.gradle/" in str(m): continue
    t = m.read_text(encoding="utf-8")
    n_prov = len(re.findall(r"com\.aether\.proxy\.content\.\d", t))
    n_slot_act = len(re.findall(r'ProxyActivity\$P\d"', t))
    if n_prov and n_prov != MAX:
        fails.append(f"[S2] {m}: authorities {n_prov} != MAX_SLOTS {MAX}")
    if n_slot_act and n_slot_act % MAX:
        fails.append(f"[S2] {m}: P<slot> activities {n_slot_act} ไม่ตรง {MAX}")
print(f"[S2] manifest↔MAX_SLOTS({MAX}): " + ("OK" if not any('[S2]' in f for f in fails) else "FAIL"))

# ── [S3] stub-body: hop chain-required ต้องมี marker สถานะใน C++ ทุกตัว ──
cpp = (cpp_dir / "aether_core.cpp").read_text(encoding="utf-8")
chain = {"nativeInitContext": "ic", "nativeProcessPair": "ac",
         "nativeProcessTriple": "pjowqpxe", "nativeReflectUpdate": "update"}
def one_line_body(fn):
    m = re.search(r"JNICALL Java_com_aether_Engine_" + fn + r"\([^)]*\)\s*\{(.*)", cpp)
    if not m: return None
    line = m.group(1)
    if line.rstrip().endswith("}"):
        return re.sub(r"//.*", "", line.rstrip()[:-1]).strip()
    return None
bodies = {fn: one_line_body(fn) for fn in chain}
for fn, sym in chain.items():
    body = bodies.get(fn) or ""
    is_logonly = body == "" or re.fullmatch(r'LOGD\([^)]*\);?', body) is not None
    marked = f"AETHER-NOOP({sym})" in cpp
    if is_logonly and not marked:
        fails.append(f"[S3] {fn} ({sym}) = LOG-ONLY/empty แต่ไม่มี AETHER-NOOP marker (เขียวหลอก)")
    status = "NO-OP(deliberate, marked)" if (is_logonly and marked) else \
             ("REAL" if body else "MISSING") if not is_logonly else "LOG-ONLY UNMARKED"
    print(f"    {fn:<22} {sym:<11} → {status}")
print("[S3] stub-body markers: " + ("OK" if not any('[S3]' in f for f in fails) else "FAIL"))

if fails:
    print("\n".join("  - " + f for f in fails)); sys.exit(1)
print("PASS: structural gates (S1-S3)")
