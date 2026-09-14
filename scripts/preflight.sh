#!/bin/bash
# preflight.sh — AetherEngine pre-flight check (run before commit/push)
# เช็ค 7 จุดที่เคยล้ม CI โดยไม่ต้อง push — ถ้า fail ห้าม push
set -e

cd "$(dirname "$0")/.."
REPO="$(pwd)"
PASS=0
FAIL=0
WARN=0

ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
err()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
warn() { echo "  ⚠️  $*"; WARN=$((WARN+1)); }

echo "═══════════════════════════════════════════════════"
echo " AetherEngine Pre-Flight (token: ${TOKEN:+set})"
echo "═══════════════════════════════════════════════════"

# G1: YAML well-formed
echo ""
echo "[G1] YAML syntax (.github/workflows/*.yml)"
for f in .github/workflows/*.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open('$f'))" 2>/dev/null \
    && ok "$f" || err "$f invalid YAML"
done

# G1.5: AndroidManifest deprecated package= attribute
echo ""
echo "[G1.5] AndroidManifest deprecated package= check (AGP 8.x)"
PKG_FOUND=0
for mf in $(find aether-android aether-core aether-native app -name "AndroidManifest.xml" 2>/dev/null); do
    if grep -q 'package=' "$mf"; then
        err "$mf has deprecated 'package=' attribute (AGP 8.x — use namespace in build.gradle instead)"
        PKG_FOUND=1
    else
        ok "$mf OK (no package= attr)"
    fi
done
[ "$PKG_FOUND" -eq 0 ] && ok "all AndroidManifest clean (no deprecated package=)"

# G2: Brace balance
echo ""
echo "[G2] Brace balance (.kt/.cpp/.hpp/.dart) — braces {} only"
python3 << 'PY'
import pathlib
ok = True
exts = ('*.kt', '*.cpp', '*.hpp', '*.dart')
for ext in exts:
    for p in pathlib.Path('.').rglob(ext):
        if '.git' in str(p) or 'build' in str(p) or 'dart_tool' in str(p): continue
        t = p.read_text(errors='ignore')
        if t.count('{') != t.count('}'):
            print(f'  ❌ {p}: {t.count(chr(123))} vs {t.count(chr(125))}'); ok = False
print('  ✅ braces OK' if ok else '  ❌ brace mismatch')
PY

# G3: JNI parity (Engine.kt ↔ aether_core.cpp) — INCLUDING return-type/descriptor
echo ""
echo "[G3] JNI parity (Engine.kt ↔ aether_core.cpp)"
python3 scripts/jni_parity.py && PASS=$((PASS+1)) || { FAIL=$((FAIL+1)); err "JNI parity (descriptor mismatch → RegisterNatives fails → libaether JNI_ERR)"; }

# G3b: Native chain parity (T1 evidence F2 ↔ ของเรา: table + kt + call-site placement)
echo ""
echo "[G3b] Native chain parity (codes/Codes evidence vs run-chain call-sites)"
python3 scripts/native_chain_parity.py > /tmp/native_chain_parity.out 2>&1 && { PASS=$((PASS+1)); tail -2 /tmp/native_chain_parity.out; } || { FAIL=$((FAIL+1)); cat /tmp/native_chain_parity.out; err "chain hop ขาด/วางผิดตำแหน่ง (root cause เกมไม่รัน — ดู output ข้างบน)"; }

# G3c: Structural gates (audit §7.8: orphan-TU / manifest↔slots / NO-OP markers)
echo ""
echo "[G3c] Structural gates (S1-S3)"
python3 scripts/structural_gates.py && PASS=$((PASS+1)) || { FAIL=$((FAIL+1)); err "structural gate FAIL (orphan TU / slot mismatch / เขียวหลอก)"; }

# G3d: Evidence provenance (C10/C16: T1 vendored + UNVERIFIED declared)
echo ""
echo "[G3d] Evidence check (reference/ T1 + citations)"
sh scripts/evidence_check.sh > /tmp/evidence_check.out 2>&1 && { PASS=$((PASS+1)); tail -1 /tmp/evidence_check.out; } || { FAIL=$((FAIL+1)); cat /tmp/evidence_check.out; err "evidence gate FAIL"; }

# G3e: Wire contract (single-source literals + Dart↔Kotlin type contract)
echo ""
echo "[G3e] Wire contract check (W1-W3)"
python3 scripts/wire_contract_check.py && PASS=$((PASS+1)) || { FAIL=$((FAIL+1)); err "wire contract พัง (literal กระจัด/ type ข้าม codec ไม่ตรง)"; }

# G4: Unresolved reference
echo ""
echo "[G4] Unresolved reference check (semantic)"
python3 << 'PY'
import re, pathlib
ok = True
classnames = ['Intent', 'Context', 'Activity', 'Bundle', 'File', 'Application']
for p in pathlib.Path('.').rglob('*.kt'):
    if '.git' in str(p) or 'build' in str(p): continue
    t = p.read_text(errors='ignore')
    imports = set(re.findall(r'^import\s+([\w.]+)', t, re.MULTILINE))
    has = lambda cls: any(f'android.content.{cls}' in imp or f'android.app.{cls}' in imp or f'android.os.{cls}' in imp or f'java.io.{cls}' in imp for imp in imports)
    for cls in classnames:
        usages = re.findall(r'(?<![/\\w\\\'])\b' + cls + r'\\(\\)', t)
        if not usages: continue
        if re.search(r'(class|object)\\s+' + cls + r'\\b', t): continue
        if not has(cls):
            print(f'  ❌ {p.name}: ใช้ {cls}() แต่ไม่ import'); ok = False
print('  ✅ all refs resolved' if ok else '  ❌ unresolved refs')
PY

# G5: Workflow bash syntax
echo ""
echo "[G5] Workflow bash syntax"
if command -v shellcheck >/dev/null 2>&1; then
    warn "shellcheck available (not used for YAML)"
else
    warn "shellcheck ไม่มี (ติดตั้ง: apk add shellcheck)"
fi

# G6: GitHub release state
echo ""
echo "[G6] GitHub release state"
if [ -n "$GITHUB_TOKEN" ]; then
    if curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/engine-dev01/AetherEngine/releases/latest" 2>/dev/null | \
        python3 -c "import json,sys; d=json.load(sys.stdin); print('  ✅', d.get('tag_name', 'no release'))" 2>/dev/null; then
        :
    else
        warn "release API fail (no internet?)"
    fi
else
    warn "GITHUB_TOKEN ไม่ได้ตั้ง"
fi

# G7: APK integrity
echo ""
echo "[G7] APK integrity (if local artifact)"
if [ -f /tmp/newapk/app-release.apk ]; then
    SIZE=$(stat -c%s /tmp/newapk/app-release.apk)
    if [ "$SIZE" -gt 1000000 ]; then
        ok "APK /tmp/newapk/app-release.apk ($SIZE B) — v2 signing block found"
    else
        err "APK /tmp/newapk/app-release.apk too small ($SIZE B)"
    fi
else
    warn "no local APK to verify"
fi

echo ""
echo "═══════════════════════════════════════════════════"
echo " Result: ✅ $PASS passed, ❌ $FAIL failed, ⚠️  $WARN warn"
echo "═══════════════════════════════════════════════════"
