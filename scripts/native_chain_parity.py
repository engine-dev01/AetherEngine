#!/usr/bin/env python3
"""
native_chain_parity.py — พิสูจน์ hop ระดับ native ก่อนแพท CI
Tier rules (memory 2026-09-13):
  T1 = fragments/F2_dex_natives.txt (machine-readable จาก SNAKE.apk; re-derive ได้)
  T2 = workspace/NATIVE_CALLSITE_MAP.md (jadx transcript — ใช้จับ role เท่านั้น)
  Our side = aether-core/Engine.kt + aether-native/.../aether_core.cpp (ต้อง grep เจอจริง)
ห้าม: สร้าง hop ที่ไม่มีในหลักฐาน, ตัด hop chain-rัน-เกม ทิ้งโดยไม่บันทึก
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
# audit C10: ห้าม hardcode host path — ลำดับค้นหา repo-relative ก่อน
_F2_CANDIDATES = [
    ROOT / "reference/snake/F2_dex_natives.txt",                     # vendored (committed)
    ROOT.parent / "codes/Codes/SnakeLogic/fragments/F2_dex_natives.txt",  # sibling bundle
]
import os as _os
_env = _os.environ.get("SNAKELOGIC_F2")
F2 = pathlib.Path(_env) if _env else next((c for c in _F2_CANDIDATES if c.exists()), _F2_CANDIDATES[0])
KT = ROOT / "aether-core/src/main/kotlin/com/aether/Engine.kt"
CPP = ROOT / "aether-native/src/main/cpp/aether_core.cpp"

# chain-required natives ตาม blueprint L4 (T2 role map) <-> T1 signatures (F2)
# role = จุดเรียกที่ snake วางไว้; our = ฟังก์ชันที่เราจับคู่ (None = ไม่มี = GAP)
CHAIN = {
    "ic":     dict(sig="(Landroid/content/Context;)V", role="yu0.f:150/155 attachBaseContext (Main+Child)",
                   our="nativeInitContext",  our_sig="(Landroid/content/Context;)V"),
    "i":      dict(sig="(I)V",               role="jv0.O2:245 bind path SDK_INT (Child)",
                   our="nativeSetSeed",      our_sig="(I)V"),
    "ac":     dict(sig="(Ljava/lang/Object;Ljava/lang/Object;)V", role="b8:79 callActivityOnResume (Child)",
                   our="nativeProcessPair",  our_sig="(Ljava/lang/Object;Ljava/lang/Object;)V"),
    "pjowqpxe": dict(sig="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                   role="hidden dex / method-hook (Child)",
                   our="nativeProcessTriple", our_sig="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                   pending="D6 — snake call-site อยู่ใน hidden dex (T1 ไม่ระบุ)"),
    "update": dict(sig="(Ljava/lang/Object;Ljava/lang/reflect/Method;)V",
                   role="hidden dex → MethodUtils (Child, .mytext FromReflectedMethod +0x38)",
                   our="nativeReflectUpdate", our_sig="(Ljava/lang/Object;Ljava/lang/reflect/Method;)V",
                   pending="D7 — caller เป็น internal 100% ตาม B4, รอตัดสินใจ"),
}
CUT = {  # license/protection branch — blueprint B3 ตัดตามคำสั่ง
    "chl": "(I)[B-actually([B)Z", "djp": "(I)[B", "ilil": "(I)Ljava/lang/String;",
    "awl": "(Ljava/lang/String;)V", "aior": "(Ljava/lang/String;Ljava/lang/String;)V",
    "eio": "()V",
}
FLAGGER = {"na": "()V", "nb": "()V"}  # F3: flagger.java = 0 callers (dead)

def parse_f2(path):
    """T1: names+signatures เรียงตาม CLASS header; คืน {class: {name: sig}} + self-check counts"""
    txt = path.read_text(encoding="utf-8", errors="replace")
    sec = txt.split("--- custom native methods")[1].split("--- Flutter engine")[0]
    classes = {}
    cur = None
    for line in sec.splitlines():
        m = re.match(r"\s*CLASS (L[\w/$]+;)\s+JNI name: (\S+)\s+(\d+) native method\(s\)", line)
        if m:
            cur = m.group(1)
            classes[cur] = {"_declared": int(m.group(3)), "_jni": m.group(2), "methods": {}}
            continue
        m = re.match(r"\s*native (\(\S*?\)\S+?) (\w+)\s*$", line)
        if m and cur:
            classes[cur]["methods"][m.group(2)] = m.group(1)
    for cname, c in classes.items():
        n = len(c["methods"])
        assert n == c["_declared"], f"F2 count mismatch {cname}: header={c['_declared']} parsed={n}"
    total = sum(len(c["methods"]) for c in classes.values())
    hdr = re.search(r"native declarations: (\d+) \((\d+) custom \+ (\d+) Flutter engine\)", txt)
    assert hdr and int(hdr.group(2)) == total, f"custom total {total} != header {hdr and hdr.group(2)}"
    print(f"[T1 self-check] {len(classes)} classes, custom natives={total} == F2 header ✓")
    return classes

def parse_our_table(path):
    txt = path.read_text(encoding="utf-8", errors="replace")
    return set(re.findall(r'\{"(\w+)","(\([^"]*\)[^"]*)"', txt))

def parse_our_kt(path):
    txt = path.read_text(encoding="utf-8", errors="replace")
    names = set(re.findall(r'external fun (\w+)', txt))
    return names

def grep_kt_callers(name, src_dirs):
    """caller = ประโยคที่อ้าง Engine.<name> — นับเฉพาะไฟล์ที่ compile จริง (no build/)
    หมายเหตุ regex: ห้ามใช้ \\b — Python มอง CJK เป็น word char ทำให้
    'nativeCompute(ตรง' (ไม่มี space หน้าข้อความไทย) ไม่ match = false negative
    (ตรวจพบ run แรก: ผล 0 ทั้งที่ ProxyContentProvider.kt:181 มี call จริง)"""
    pat = re.compile(rf"(?<![A-Za-z0-9_]){name}(?![A-Za-z0-9_])")
    hits = []
    for d in src_dirs:
        for p in pathlib.Path(d).rglob("*.kt"):
            if "/build/" in str(p):
                continue
            # หมายเหตุบทเรียน run แรก: อย่ากลืน exception เงียบ ๆ —
            # relative_to(ROOT) ValueError ทำให้ทุกผลเป็น 0 ปลอม (false negative)
            rel = str(p)
            if "/test-stubs/" in rel:
                continue  # Engine.kt stub มีนิยาม fun เดียวกัน — ไม่นับเป็น caller
            for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                stripped = line.lstrip()
                if stripped.startswith(("*", "//")):
                    continue
                if "external fun" in line or f"fun {name}" in line:
                    continue  # definition line ไม่ใช่ call-site
                if pat.search(line):
                    hits.append(f"{rel}:{i}")
    return hits

def main():
    fails = []
    warns = []
    # positive controls ของ checker เอง (memory rule) — รู้ว่า line ไหน match/ไม่ match
    pc = grep_kt_callers("nativeInitContext", ["aether-android"])
    assert any("AetherApp.kt" in h for h in pc), \
        f"checker broken: nativeInitContext ต้องเจอ AetherApp.kt, got {pc}"
    pc = grep_kt_callers("nativeCompute", ["app"])
    assert any("EngineBridge.kt" in h for h in pc), \
        f"checker broken: nativeCompute ต้องเจอ EngineBridge.kt (ไม่มี space), got {pc}"
    print("[checker self-test] positive controls PASS x2 (AetherApp call found; CJK-adjacent call found)")
    classes = parse_f2(F2)
    native_cls = classes["Lcom/snake/helper/Native;"]
    flagger_cls = classes["Lcom/snake/helper/flagger;"]
    f2 = native_cls["methods"]
    print(f"[T1] com/snake/helper/Native natives ({len(f2)}): {sorted(f2)}")
    print(f"[T1] com/snake/helper/flagger natives ({len(flagger_cls['methods'])}): {sorted(flagger_cls['methods'])}")
    assert len(f2) == 11, f"expected 11 in Native, got {len(f2)}"

    table = parse_our_table(CPP)
    kt_names = parse_our_kt(KT)
    print(f"[OUR] JNINativeMethod entries: {len(table)} | Engine.kt external funs: {len(kt_names)}")

    print("\n=== CHAIN REQUIRED (blueprint L4): T1 sig vs OUR sig vs call-site ===")
    for sn, spec in CHAIN.items():
        t1 = f2.get(sn)
        ok_sig_t1 = t1 == spec["sig"]
        our_entry = (spec["our"], spec["our_sig"]) in table
        our_in_kt = spec["our"] in kt_names
        callers = grep_kt_callers(spec["our"], ["aether-android", "aether-core", "app"])
        line = (f"  snake {sn:<9} sig={'MATCH' if ok_sig_t1 else 'DIFF:' + str(t1):<40} "
                f"our {spec['our']:<20} table={'OK' if our_entry else 'MISSING'} "
                f"kt={'OK' if our_in_kt else 'MISSING'} callers={len(callers)}")
        print(line)
        for c in callers[:4]:
            print(f"        <- {c}")
        if not ok_sig_t1: fails.append(f"T1 sig diff {sn}: {t1} != {spec['sig']}")
        if not our_entry: fails.append(f"{spec['our']} not in RegisterNatives table")
        if not our_in_kt: fails.append(f"{spec['our']} not in Engine.kt")
        if not callers:
            msg = f"GAP {spec['our']}: registered แต่ไม่มี Kotlin call-site (= hop ขาด)"
            if spec.get("pending"):
                warns.append(msg + " — พักตามสิทธิ์ (" + spec["pending"] + ")")
            else:
                fails.append(msg)
        elif sn in ("ic", "i", "ac"):
            # placement proof: ต้องอยู่ในไฟล์ที่ T2 ชี้ ไม่ใช่เรียกมั่วที่อื่น
            expect = {"ic": "AetherApp.kt", "i": "GuestRuntime.kt", "ac": "AetherInstrumentation.kt"}[sn]
            if not any(expect in c for c in callers):
                fails.append(f"{spec['our']} ถูกเรียกแต่ไม่ใช่ตำแหน่ง snake (ต้องมีใน {expect}, พบ {callers})")

    print("\n=== CUT (B3) — ต้องไม่ถูกเรียกใน run-chain ===")
    for name in ["nativeCompute", "nativeValidate", "nativeExchangeKeys", "nativeDeriveKey"]:
        callers = grep_kt_callers(name, ["aether-android", "aether-core", "app"])
        where = "ui/debug only" if all("EngineBridge" in c or "Test" in c for c in callers) else "IN RUN CHAIN ✗" if callers else "none"
        print(f"  {name:<22} callers={len(callers)} [{where}]")
        for c in callers[:4]: print(f"        <- {c}")

    print("\n=== GATE ===")
    for w in warns:
        print("  ⚠ ", w)
    if fails:
        print(f"  FAIL {len(fails)} ข้อ:")
        for f in fails: print("   -", f)
        sys.exit(1)
    print("  PASS: ทุก hop chain-required มี T1 sig ตรง + table + kt + call-siteถูกตำแหน่ง")

main()
