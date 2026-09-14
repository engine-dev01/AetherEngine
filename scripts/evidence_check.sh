#!/bin/sh
# C10/C16 gate: ทุก citation เอกสารใน source ต้อง resolve ได้จริง
# (T1 = files ที่ repo, หรือ sibling bundle; T2 transcript = committed;
#  เอกสารที่หายไป = UNVERIFIED และต้องถูก listing ไว้ใน reference/README.md)
cd "$(dirname "$0")/.." || exit 1
echo "=== [EC1] T1 vendored files exist ==="
ok=0; fail=0
for f in reference/snake/F2_dex_natives.txt reference/snake/F3_manifest.txt reference/NATIVE_CALLSITE_MAP.md; do
  if [ -f "$f" ]; then echo "  OK   $f"; ok=$((ok+1))
  else echo "  MISS $f"; fail=$((fail+1)); fi
done
echo "=== [EC2] T1 self-consistency (13 custom natives == header) ==="
python3 - <<'PY'
import re, sys
t = open('reference/snake/F2_dex_natives.txt', encoding='utf-8', errors='replace').read()
hdr = re.search(r"native declarations: (\d+) \((\d+) custom \+ (\d+) Flutter engine\)", t)
sec = t.split("--- custom native methods")[1].split("--- Flutter engine")[0]
per = re.findall(r"CLASS (L[\w/$]+;).*?(\d+) native method\(s\)", sec)
tot = sum(int(n) for _, n in per)
assert hdr and int(hdr.group(2)) == tot == 13, f"counts {hdr and hdr.group(2)} vs {tot}"
print("  OK 13 custom natives across", len(per), "classes")
PY
[ $? -eq 0 ] || fail=$((fail+1))
echo "=== [EC3] UNVERIFIED doc refs are declared ==="
for d in "DATA_DUMP" "NATIVE_LOGIC"; do
  # บทเรียน: BusyBox grep ไม่ซัพ --include → false negative; ใช้ find|xargs
  refs=$(find . -type f \( -name '*.kt' -o -name '*.cpp' -o -name '*.md' -o -name '*.hpp' \) 2>/dev/null | grep -v '/build/' | xargs grep -l "$d" 2>/dev/null | wc -l)
  note=$(grep -c "$d" reference/README.md 2>/dev/null)
  if [ "$refs" -gt 0 ] && [ "$note" -eq 0 ]; then
    echo "  FAIL $d ถูกอ้าง $refs ที่แต่ไม่มีใน UNVERIFIED list"; fail=$((fail+1))
  else echo "  OK   $d declared UNVERIFIED (refs=$refs)"; fi
done
echo "=== EC result: ok=$ok fail=$fail ==="
exit $fail
