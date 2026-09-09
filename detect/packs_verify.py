#!/usr/bin/env python3
"""Pack verifier — ใช้เป็น CI gate (detect-lab job)
ตรวจทุก pack ใน dir: schema + rule semantics + scoring sanity + อ้างอิงหลักฐาน
อ้างอิง: DESIGN_PLAN.md §4 (pack integrity = กัน IOC poisoning), §5 (extensibility contract)
"""
import json
import sys
from pathlib import Path

PACK_SCHEMA_VERSION = 1
RULE_TYPES = ("sha256", "filename", "string", "regex")
REQUIRED = ("schema", "id", "version", "family", "description", "threshold", "references", "rules")


def verify(pack_path: Path) -> list:
    errs = []
    try:
        pack = json.loads(pack_path.read_text())
    except json.JSONDecodeError as e:
        return [f"{pack_path.name}: JSON parse error: {e}"]

    for k in REQUIRED:
        if k not in pack:
            errs.append(f"{pack_path.name}: missing key '{k}'")

    if pack.get("schema") != PACK_SCHEMA_VERSION:
        errs.append(f"{pack_path.name}: schema must be {PACK_SCHEMA_VERSION} (got {pack.get('schema')})")

    threshold = pack.get("threshold")
    if not isinstance(threshold, (int, float)) or not (0 < threshold <= 100):
        errs.append(f"{pack_path.name}: threshold must be 0-100 (got {threshold})")

    refs = pack.get("references", [])
    if not isinstance(refs, list) or not refs:
        errs.append(f"{pack_path.name}: references ต้องเป็น list ไม่ว่าง (ทุก pack ต้องอ้างหลักฐาน — กัน rule ลอย)")

    rules = pack.get("rules", [])
    ids = set()
    total_w = 0
    for r in rules:
        rid = r.get("id", "?")
        if r.get("type") not in RULE_TYPES:
            errs.append(f"{pack_path.name}: rule '{rid}' bad type {r.get('type')}")
        if not rid or rid == "?":
            errs.append(f"{pack_path.name}: rule missing id")
        if rid in ids:
            errs.append(f"{pack_path.name}: duplicate rule id '{rid}'")
        ids.add(rid)
        if "value" not in r:
            errs.append(f"{pack_path.name}: rule '{rid}' missing value")
        w = r.get("weight", 1)
        if not isinstance(w, int) or w <= 0:
            errs.append(f"{pack_path.name}: rule '{rid}' weight must be positive int")
        total_w += w if isinstance(w, int) else 0
        if "note" not in r:
            errs.append(f"{pack_path.name}: rule '{rid}' missing note (ต้องมีที่มา rule ทุกตัว)")

    if rules and total_w == 0:
        errs.append(f"{pack_path.name}: total weight = 0 (scoring ใช้ไม่ได้)")

    # single-rule pack ไม่ควรผ่าน threshold เดี่ยว ๆ (กัน one-hit verdict)
    max_w = max((r.get("weight", 1) for r in rules), default=0)
    if rules and (max_w / total_w * 100) >= threshold:
        errs.append(
            f"{pack_path.name}: rule เดี่ยวหนัก {max_w}/{total_w} ทำ verdict ได้เดี่ยว ๆ "
            f"({max_w/total_w*100:.0f}% ≥ threshold {threshold}) — ต้องกระจาย weight กัน one-hit"
        )

    return errs


def main():
    packs_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("detect/packs")
    if not packs_dir.is_dir():
        print(f"error: no packs dir: {packs_dir}", file=sys.stderr)
        return 2
    files = sorted(packs_dir.glob("*.pack.json"))
    if not files:
        print(f"error: no packs found in {packs_dir}", file=sys.stderr)
        return 2
    all_errs = []
    for pf in files:
        errs = verify(pf)
        pack = json.loads(pf.read_text())
        status = "OK " if not errs else "BAD"
        print(f"[{status}] {pack.get('id', '?'):24} v{pack.get('version', '?'):20} "
              f"rules={len(pack.get('rules', [])):2} threshold={pack.get('threshold')}")
        all_errs.extend(errs)
    if all_errs:
        print(f"\n╳ {len(all_errs)} pack errors:")
        for e in all_errs:
            print(f"  - {e}")
        return 1
    print("═══ ALL PACKS VERIFIED ═══")
    return 0


if __name__ == "__main__":
    sys.exit(main())
