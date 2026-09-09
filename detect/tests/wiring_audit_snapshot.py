#!/usr/bin/env python3
"""wiring_audit.py — ตรวจว่าแต่ละส่วนของ Aether "ถูกเรียกใช้จริงหรือโค้ดตาย"
แม่แบบ: โครง SNAKE (หลักฐาน 4 ชั้น) — ตัดส่วน license/C2
4 มิติ: (A) manifest↔class (B) JNI callers (C) Kotlin↔Kotlin (D) Flutter↔Kotlin
"""
import re
import sys
from pathlib import Path

REPO = Path('/tmp/AetherEngine')
sys.path.insert(0, str(REPO))


def load_kt():
    files = {}
    for f in REPO.rglob('*.kt'):
        files[str(f)] = f.read_text()
    return files


def load_dart():
    files = {}
    for f in REPO.rglob('*.dart'):
        files[str(f)] = f.read_text()
    return files


def audit_a_manifest():
    """(A) ทุก component ใน manifest ต้องมี class จริง — และ class ใน proxy ต้องถูก instantiate บ้าง"""
    print("═" * 70)
    print("(A) MANIFEST ↔ CLASS — สิ่งที่ Android จะเรียกจริง")
    print("═" * 70)
    xml = (REPO / 'aether-android/aether-app/src/main/AndroidManifest.xml').read_text()
    comps = re.findall(r'android:name="((?:com\.)?aether[^"]+)"', xml)
    kt = load_kt()
    for c in sorted(set(comps)):
        simple = c.split('$')[-1].split('.')[-1]
        holder = c.split('$')[0].split('.')[-1]
        hits = [f.split('/')[-1] for f in kt if f'/{holder}.kt' in f]
        status = 'OK' if hits else 'MISSING'
        print(f"  [{status:7}] {c:58} → {hits[0] if hits else 'no file'}")
    return comps


def audit_b_jni():
    """(B) external fun ↔ callers — ตัวชี้วัดโค้ดตายหลัก"""
    print()
    print("═" * 70)
    print("(B) JNI SURFACE — external fun ↔ Kotlin callers")
    print("═" * 70)
    engine_kt = (REPO / 'aether-core/src/main/kotlin/com/aether/Engine.kt').read_text()
    externals = re.findall(r'external fun (\w+)', engine_kt)
    kt = load_kt()
    dead, used = [], []
    for fn in externals:
        pat = re.compile(rf'\bEngine\.{fn}\b')
        callers = []
        for f, src in kt.items():
            if f.endswith('Engine.kt'):
                continue
            for m in pat.finditer(src):
                line = src[:m.start()].count('\n') + 1
                callers.append(f"{f.split('AetherEngine/')[-1]}:{line}")
        if callers:
            used.append((fn, callers))
            print(f"  [USED   ] {fn:34} ← {callers[0]}")
        else:
            dead.append(fn)
            print(f"  [DEAD   ] {fn}")
    print(f"\n  สรุป JNI: USED={len(used)}  DEAD={len(dead)} / {len(externals)}")
    return dead


def audit_c_kotlin():
    """(C) public fun ใน aether-android + app ที่ไม่มีใครเรียก"""
    print()
    print("═" * 70)
    print("(C) KOTLIN↔KOTLIN — public fun ที่ไม่มี caller")
    print("═" * 70)
    kt = load_kt()
    targets = {f: src for f, src in kt.items()
               if '/aether-android/' in f or '/app/android/' in f}
    # เก็บ public fun ทั้งหมด
    all_defs = []
    for f, src in targets.items():
        for m in re.finditer(r'^\s*(?:public\s+)?fun (\w+)', src, re.M):
            all_defs.append((f, m.group(1)))
    all_src = '\n'.join(kt.values())
    dead = []
    for f, fn in all_defs:
        # ตัวนับการเรียกใช้ = จำนวน occurrence ของชื่อ ที่ไม่ใช่ definition บรรทัดนั้น
        occurrences = len(re.findall(rf'\b{fn}\b', all_src)) - 1
        if occurrences <= 0:
            dead.append((f.split('AetherEngine/')[-1], fn))
    print(f"  นิยาม public fun: {len(all_defs)}  |  ไม่มี caller: {len(dead)}")
    for f, fn in dead[:30]:
        print(f"  [ORPHAN ] {fn:34} @ {f}")
    if len(dead) > 30:
        print(f"  … +{len(dead)-30} more")
    return dead


def audit_d_flutter():
    """(D) Flutter → Kotlin method channel calls"""
    print()
    print("═" * 70)
    print("(D) FLUTTER↔KOTLIN — MethodChannel ว่าเรียกถึงกันจริงไหม")
    print("═" * 70)
    dart = load_dart()
    kt = load_kt()
    bridge_kt = [f for f in kt if 'EngineBridge.kt' in f][0]
    bridge_src = kt[bridge_kt]
    handled = set(re.findall(r'"(\w+)"\s*->', bridge_src))
    called = set()
    inv = re.compile(r"invokeMethod(?:<[^(]*>)?\(\s*['\"](\w+)['\"]")
    for f, src in dart.items():
        for m in inv.finditer(src):
            called.add(m.group(1))
    print(f"  Flutter เรียก: {sorted(called) if called else '(none — Dart ไม่ invoke อะไรเลย!)'}")
    print(f"  Kotlin รองรับ: {sorted(handled)}")
    for c in sorted(called - handled):
        print(f"  [UNHANDLED] Dart เรียก '{c}' แต่ Kotlin ไม่มี handler")
    for h in sorted(handled - called):
        print(f"  [UNTRIGGERED] Kotlin มี handler '{h}' แต่ Dart ไม่เรียก")
    return called, handled


def main():
    print("=" * 70)
    print("AETHER WIRING AUDIT — เทียบแม่แบบ SNAKE (หลักฐาน 4 ชั้น ตัด license/C2)")
    print("=" * 70)
    audit_a_manifest()
    dead_jni = audit_b_jni()
    audit_c_kotlin()
    audit_d_flutter()
    print()
    print("=" * 70)
    print("สรุปท้ายสุด: การเรียกใช้จริงของทั้งระบบ")
    print("=" * 70)
    print(f"  JNI dead: {len(dead_jni)} ตัว — {', '.join(dead_jni) if dead_jni else '-'}")
    print("\nหมายเหตุ: นี่คือ static wiring audit — 'USED' แปลว่ามี call-site ใน source,")
    print("ไม่ได้แปลว่า runtime จะไปถึงบรรทัดนั้น (ต้อง trace path จริงจาก entry point)")


if __name__ == '__main__':
    main()
