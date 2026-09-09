#!/bin/bash
# pre_flight_check.sh — ตรวจก่อน push ทุกครั้ง (lesson จาก PR #1 CI fail: entropy cascade)
# ใช้ก่อนถึงจะ push ได้ — จับ 3 ชั้นที่ audit เดิมมองไม่เห็น:
#   1. C++-internal call graph: ทุกสัญลักษณ์ที่ถูกตัด ต้องไม่ถูกเรียกจาก C++ ตัวอื่น
#   2. JNI register table ↔ external fun ตรงกัน 1:1
#   3. Kotlin dangling refs ต่อ external fun ที่ไม่มี declaration แล้ว
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root (script อยู่ที่ scripts/pre_flight_check.sh)

CPP_DIR="aether-native/src/main/cpp"
KT_ENGINE="aether-core/src/main/kotlin/com/aether/Engine.kt"
FAIL=0

echo "═══ Pre-flight: C++-internal call graph ═══"
# สัญลักษณ์ C++ ที่ยังถูกตัดอยู่ในปัจจุบัน (อัปเดต 2026-09-09 หลัง restore entropy):
# ต้องไม่ถูกเรียกจาก C++ ตัวอื่น และต้องไม่มี declaration ค้างใน header
# (entropy ถูก restore แล้ว — มี caller จริงคือ nativeCompute จึงไม่อยู่ในลิสต์นี้)
CUT_SYMS=(
  "Config::deriveKey"
  "Binder::overridePid" "Binder::overrideUid"
  "Binder::restorePid" "Binder::restoreUid"
  "PayloadStore::decrypt"
)
for sym in "${CUT_SYMS[@]}"; do
  # มีใครยังเรียก? (นอกไฟล์ผู้ให้บริการ + นอก comment)
  refs=$(grep -rn "$sym" $CPP_DIR --include="*.cpp" --include="*.hpp" 2>/dev/null \
    | grep -v "config.cpp\|config.hpp\|binder.cpp\|binder.hpp\|payload_store" \
    | grep -v "^\s*//\|#\s" || true)
  if [ -n "$refs" ]; then
    echo "✗ DANGLING: $sym ยังถูกเรียกโดย:"
    echo "$refs" | head -5
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "✓ ไม่มี dangling C++ call"

echo ""
echo "═══ Pre-flight: JNI register ↔ Kotlin declaration 1:1 ═══"
# ชื่อใน register table (aether_core.cpp) ต้องมี external fun ใน Engine.kt ทุกตัว
for name in $(grep -oP '^\s*\{"\K\w+(?=")' $CPP_DIR/aether_core.cpp 2>/dev/null || grep -o '{"[a-zA-Z]*",' $CPP_DIR/aether_core.cpp | sed 's/{"//;s/",//'); do
  if ! grep -q "external fun $name" "$KT_ENGINE"; then
    echo "✗ REGISTERED แต่ไม่มี declaration: $name"
    FAIL=1
  fi
done
# ทิศกลับ: external fun ทุกตัวต้องมีใน register table (หรือเป็น overload ที่รวมไว้)
for name in $(grep -o "external fun [a-zA-Z]*" "$KT_ENGINE" | awk '{print $3}'); do
  if ! grep -q "\"$name\"" $CPP_DIR/aether_core.cpp; then
    echo "✗ DECLARED แต่ไม่ได้ register: $name (UnsatisfiedLinkError ที่ runtime!)"
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "✓ JNI table ↔ declaration สอดคล้อง"

echo ""
echo "═══ Pre-flight: Kotlin callers ↔ declarations ═══"
# caller ใน repo ที่เรียก Engine.<fn> ที่ไม่มีอีกต่อไป
while IFS= read -r ref; do
  fn=$(echo "$ref" | grep -oP 'Engine\.\K\w+' || true)
  [ -z "$fn" ] && continue
  if ! grep -q "external fun $fn" "$KT_ENGINE" 2>/dev/null && ! grep -q "fun $fn" aether-core/src/main/kotlin/com/aether/Engine.kt 2>/dev/null; then
    echo "✗ KOTLIN CALLS MISSING: $ref"
    FAIL=1
  fi
done < <(grep -rn "Engine\.\w*(" aether-android app --include="*.kt" 2>/dev/null | grep -v "EngineBridge\|// " || true)
[ $FAIL -eq 0 ] && echo "✓ ไม่มี Kotlin caller ค้าง"

echo ""
if [ $FAIL -ne 0 ]; then
  echo "╳ PRE-FLIGHT FAIL — ห้าม push (แก้ก่อน)"
  exit 1
fi
echo "═══ PRE-FLIGHT PASS — พร้อม push ═══"
