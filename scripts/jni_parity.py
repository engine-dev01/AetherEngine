#!/usr/bin/env python3
"""
jni_parity.py — verify Engine.kt <-> aether_core.cpp JNI parity INCLUDING
return-type / descriptor, not just method-name presence.

A return-type mismatch (e.g. Kotlin `fun x()` = ()V but native table says ()J)
makes RegisterNatives fail at runtime → JNI_OnLoad returns JNI_ERR → the WHOLE
libaether.so fails to load (not just that one method). Name-only parity misses
this. This checker maps each Kotlin external fun's signature to a JNI descriptor
and compares against the native JNINativeMethod table.

Exit 0 = parity OK, 1 = mismatch (prints details).
"""
import re
import sys
import pathlib

KT = pathlib.Path("aether-core/src/main/kotlin/com/aether/Engine.kt")
CPP = pathlib.Path("aether-native/src/main/cpp/aether_core.cpp")

# Kotlin type -> JNI type descriptor
KT_TO_JNI = {
    "": "V", "Unit": "V",
    "Int": "I", "Long": "J", "Boolean": "Z", "Float": "F", "Double": "D",
    "Short": "S", "Byte": "B", "Char": "C",
    "String": "Ljava/lang/String;", "String?": "Ljava/lang/String;",
    "ByteArray": "[B", "ByteArray?": "[B",
    "LongArray": "[J", "LongArray?": "[J",
    "IntArray": "[I", "IntArray?": "[I",
    "Any": "Ljava/lang/Object;", "Any?": "Ljava/lang/Object;",
}

def kt_type_to_jni(t: str) -> str:
    t = t.strip()
    if t in KT_TO_JNI:
        return KT_TO_JNI[t]
    # Array<String> / Array<String>?
    if t.startswith("Array<") :
        return "[Ljava/lang/String;"
    # fully-qualified param types used in Engine.kt
    fq = t.rstrip("?").replace(".", "/")
    return "L" + fq + ";"

def parse_kotlin():
    """name -> jni descriptor string like (I)J"""
    src = KT.read_text()
    out = {}
    # external fun NAME(params): Ret     (Ret optional)
    for m in re.finditer(r'external fun (\w+)\s*\(([^)]*)\)\s*(?::\s*([\w.?<>]+))?', src):
        name, params, ret = m.group(1), m.group(2), m.group(3) or ""
        # params: "a: Int, b: String?" — take the types after each colon
        ptypes = []
        for part in params.split(","):
            part = part.strip()
            if not part:
                continue
            if ":" in part:
                ptypes.append(kt_type_to_jni(part.split(":", 1)[1]))
        desc = "(" + "".join(ptypes) + ")" + kt_type_to_jni(ret)
        # overloads (setAccessible) — keep a set
        out.setdefault(name, set()).add(desc)
    return out

def parse_native():
    """name -> set of descriptor strings from the JNINativeMethod table"""
    src = CPP.read_text()
    out = {}
    for m in re.finditer(r'\{\s*"(\w+)"\s*,\s*"([^"]+)"', src):
        out.setdefault(m.group(1), set()).add(m.group(2))
    return out

def main():
    kt = parse_kotlin()
    nat = parse_native()
    kset, nset = set(kt), set(nat)

    errors = []
    only_kt = kset - nset
    only_nat = nset - kset
    if only_kt:
        errors.append(f"Kotlin-only methods (no native): {sorted(only_kt)}")
    if only_nat:
        errors.append(f"Native-only methods (no Kotlin): {sorted(only_nat)}")

    for name in kset & nset:
        # every kotlin descriptor should have a matching native descriptor
        if kt[name] != nat[name]:
            errors.append(
                f"{name}: kotlin={sorted(kt[name])} != native={sorted(nat[name])}"
            )

    if errors:
        print("  \u274c JNI parity (incl. descriptors):")
        for e in errors:
            print(f"     - {e}")
        return 1
    print(f"  \u2705 1:1 incl. descriptors ({len(kset)} methods)")
    return 0

def dead_fun_guard():
    """external fun ที่ไม่มี Kotlin call-site (นอก Engine.kt) = hop ที่ยังไม่ได้
    wire หรือของตาย (audit C3) → FAIL; pending ที่อนุมัติแล้ว = WARN"""
    dead_exempt = {"nativeProcessTriple", "nativeReflectUpdate",   # D6/D7
                   "setBinderCallingPidOverride", "setBinderCallingUidOverride"}  # รอ virtual-UID (C13)
    kt_dirs = [pathlib.Path("aether-android"), pathlib.Path("aether-core"), pathlib.Path("app")]
    srcs = [q for d in kt_dirs for q in d.rglob("*.kt") if "/build/" not in str(q)]
    funs = re.findall(r"external fun (\w+)", KT.read_text(encoding="utf-8"))
    dead = []
    for fn in funs:
        pat = re.compile(r"(?<![A-Za-z0-9_])" + fn + r"(?![A-Za-z0-9_])")
        used = False
        for s in srcs:
            if s == KT: continue
            for line in s.read_text(encoding="utf-8", errors="replace").splitlines():
                st = line.strip()
                if st.startswith(("*", "//")) or "external fun" in line or ("fun " + fn) in line:
                    continue
                if pat.search(line):
                    used = True; break
            if used: break
        if not used:
            dead.append(fn)
    rc = 0
    for f in dead:
        if f in dead_exempt:
            print(f"  \u26a0 DEAD-PENDING (D6/D7 approved): {f}")
        else:
            print(f"  \u274c DEAD external fun (no call-site): {f}"); rc = 1
    if rc == 0:
        print("  \u2705 no unplanned dead external funs")
    return rc


if __name__ == "__main__":
    rc = main()
    if rc == 0:
        rc = dead_fun_guard()
    sys.exit(rc)
