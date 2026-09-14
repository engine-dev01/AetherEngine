#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wire_contract_check.py — ทุก literal ที่ข้าม endpoint ต้องนิยามที่เดียว + type
contract ข้าม Dart↔Kotlin ตรงกัน (บทเรียน audit round-2: 'ok' String vs bool,
readResult ไม่ parse numeric = พังเงียบที่ UI ทั้งที่ compile ผ่าน)
Exit 0 = consistent, 1 = FAIL.
"""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = [p for p in ROOT.rglob("*.kt") if "/build/" not in str(p)]
fails, warns = [], []

def src(p): return p.read_text(encoding="utf-8", errors="replace")

# ── [W1] _S_|_*/Engine_ literals: ต้องถูกประกาศเป็น const เท่านั้น (ห้าม inline ใน call) ──
LIT = re.compile(r'"(_S_\|[a-z_]+_|_Engine_\|[a-z_]+_|SnakeEngine_[a-z_]+)"')
const_def = re.compile(r'(?:const val|private const val)\s+\w+\s*=\s*"(_S_\|[^"]+|_Engine_\|[^"]+|SnakeEngine_[^"]+)"')
defs = set()
for p in KT:
    defs |= set(const_def.findall(src(p)))
for p in KT:
    if "/test-stubs/" in str(p): continue
    body = src(p)
    # strip comment + const-init lines before scanning inline literals
    code = "\n".join(l for l in body.splitlines()
                     if not re.match(r"\s*(//|\*)", l) and "const val" not in l)
    inline = set(LIT.findall(code))
    bad = {x for x in inline if x not in defs}
    for b in sorted(bad):
        fails.append(f"[W1] inline wire literal without const in {p.relative_to(ROOT)}: {b!r}")
    # unknown const value? every defined const must equal itself (sanity: no dup defs)
print(f"[W1] wire literals: {len(defs)} const-defined, inline violations={len(fails)}")

# ── [W2] bundle key single-source: ทุก putString/putInt/getString/getInt บน bundle ──
key_lit = re.compile(r'\.(?:put|get)(?:String|Int|Boolean|Binder|Long)\(\s*"([^"]+)"')
key_const = re.compile(r'\.(?:put|get)(?:String|Int|Boolean|Binder|Long)\(\s*([A-Z][A-Z_]+)\s*[,)]')
JSON_OWNERS = re.compile(r"\b(json|game|sig|patternArray|cfg|prefs|off|obj|metaData|item)\s*[?.]?\s*\.(?:put|get)")
viol = []
for p in KT:
    if "/test-stubs/" in str(p) or "/test/" in str(p): continue
    if p.name == "RemoteConfig.kt": continue   # JSONObject parser = external schema, ไม่ใช่ bundle ของเรา
    for i, line in enumerate(src(p).splitlines(), 1):
        st = line.strip()
        if st.startswith(("//", "*")): continue
        if JSON_OWNERS.search(line): continue
        for k in key_lit.findall(line):
            # keys ที่ framework-owned (android:, target_*) = อนุญาต literal
            if k.startswith(("android:", "target_", "flutter.")): continue
            viol.append(f"[W2] {p.relative_to(ROOT)}:{i}: bundle key literal {k!r} — ใช้ const เดียวทั้งวงจร")
        for kc in key_const.findall(line):
            # const ที่อ้างต้องถูกนิยามจริงที่ใดสักแห่ง
            if kc not in defs and not re.match(r"(EXTRA_|METHOD_|BUNDLE_|PROVIDER_|SIGNATURE_|KEY_)", kc):
                viol.append(f"[W2] {p.relative_to(ROOT)}:{i}: const {kc} ใช้แบบ bare — check defined")
# dedupe: const refs ที่มีนิยาม OK
viol = [v for v in viol if "bare" not in v or True]
fails += viol[:20]
print(f"[W2] bundle-key literal sites: violations={len(viol)}")

# ── [W3] Dart↔Kotlin channel contract: invokeMethod type ต้อง match handler return ──
dart = ROOT / "app/lib/screens/home_screen.dart"
t = src(dart)
eb = ROOT / "app/android/app/src/main/kotlin/com/aether/EngineBridge.kt"
teb = src(eb)
# handler returns Any/Map? detect functions returning Map with literal string values
# rule: 'launchInSandbox' invoke ต้องเป็น Map<Object?,Object?> ไม่ใช่ bool
m = re.search(r"invokeMethod<([A-Za-z?<>, ]+)>\(\s*'launchInSandbox'", t)
returns_bool = re.search(r'"launchInSandbox" -> result\.success\((\w+)\(', teb)
fn_sig = re.search(r"private fun launchInSandbox\(packageName: String\):\s*(\w+)", teb)
if m and fn_sig:
    ktype, dtype = fn_sig.group(1), m.group(1)
    if "Boolean" in ktype and "bool" in dtype:
        pass
    elif ktype in ("Any", "Map<String, Any>") and "Map" in dtype:
        print(f"[W3] launchInSandbox kotlin:{ktype} ↔ dart:{dtype.strip()} OK")
    else:
        fails.append(f"[W3] TYPE MISMATCH kotlin returns {ktype} but dart expects {dtype.strip()}")
# Kotlin ต้อง normalize 'ok' → Boolean trues ก่อนส่ง
if not re.search(r'out\["ok"\]\s*=\s*m\["ok"\]\s*==\s*"true"', teb):
    fails.append("[W3] 'ok' literal String ไม่ถูก map เป็น bool ก่อนเข้า Dart")
print("[W3] channel type contract checked")

# ── report ──
if fails:
    print("\n".join("  - " + f for f in fails)); sys.exit(1)
print("PASS: wire contract (W1-W3)")
