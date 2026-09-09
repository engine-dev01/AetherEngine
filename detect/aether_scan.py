#!/usr/bin/env python3
"""
aether-detect — scanner ตรวจจับตระกูล SNAKE/Aether cheat engine
สถาปัตยกรรม: pack-driven matching (Tier1) — ทุกความรู้อยู่ใน pack, core ไม่แตะเมื่อเพิ่มตระกูลใหม่
อ้างอิง: DESIGN_PLAN.md §2, §5 (aether-pivot-2026-09-09)

usage:
  aether_scan.py scan <sample> [--packs-dir packs] [--json out.json]
  aether_scan.py packs [--packs-dir packs]
  aether_scan.py verify-pack <pack> [--packs-dir packs]
"""
import argparse
import hashlib
import json
import re
import sys
import time
import zipfile
from pathlib import Path

PACK_SCHEMA_VERSION = 1
REPORT_SCHEMA_VERSION = 1


# ─────────────────────────────────────────────
# Tier 1 — Matching core (immutable)
# ─────────────────────────────────────────────
def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ScanTarget:
    """สิ่งที่ถูกสแกน: apk/zip หรือ directory (extracted sample)"""

    def __init__(self, path: Path):
        self.path = path
        self.name = path.name
        self.container_hash = None
        self.entries = {}   # rel_path → {'size':, 'sha256':}
        self.n_entries = 0

    def hash_entries(self, max_files: int = 8000):
        """index entries + hash — deterministic; ผล scan ผูกกับเนื้อหาจริงของ sample"""
        if self.path.is_dir():
            files = sorted(p for p in self.path.rglob("*") if p.is_file())
            for f in files[:max_files]:
                rel = str(f.relative_to(self.path))
                with open(f, "rb") as fh:
                    self.entries[rel] = {"size": f.stat().st_size, "sha256": sha256_bytes(fh.read())}
                if len(self.entries) >= max_files:
                    break
        else:
            with zipfile.ZipFile(self.path) as z:
                for info in z.infolist():
                    if len(self.entries) >= max_files:
                        break
                    try:
                        data = z.read(info.filename)
                    except Exception:
                        continue
                    self.entries[info.filename] = {"size": len(data), "sha256": sha256_bytes(data)}
        self.n_entries = len(self.entries)
        return self


class RuleHit:
    __slots__ = ("rule_id", "pack_id", "entry", "evidence", "detail")

    def __init__(self, rule_id, pack_id, entry, evidence, detail=""):
        self.rule_id = rule_id
        self.pack_id = pack_id
        self.entry = entry
        self.evidence = evidence    # e.g. 'sha256', 'string', 'regex'
        self.detail = detail


def match_pack(target: "ScanTarget", pack: dict) -> list:
    """ประเมิน pack หนึ่งตัวกับ target — คืน list[RuleHit]"""
    hits = []
    for rule in pack.get("rules", []):
        rtype = rule.get("type")
        rid = rule.get("id", "?")
        weight = int(rule.get("weight", 1))

        if rtype == "sha256":
            want = rule["value"].lower()
            for rel, meta in target.entries.items():
                if meta["sha256"] == want:
                    hits.append(RuleHit(rid, pack["id"], rel, "sha256", f"weight={weight}"))
                    break

        elif rtype == "filename":
            pat = rule["value"]
            contains = rule.get("entry_contains")
            for rel in target.entries:
                if fnmatch_like(rel, pat) and (not contains or contains in rel):
                    hits.append(RuleHit(rid, pack["id"], rel, "filename", f"weight={weight}"))
                    break

        elif rtype == "string":
            # string อยู่ใน entry ที่ระบุ (หรือทุก entry) — ต้องอ่าน bytes จริง
            needle = rule["value"]
            scope = rule.get("entry_glob", "**")
            for rel in target.entries:
                if not fnmatch_like(rel, scope):
                    continue
                data = read_entry(target, rel)
                if data and needle.encode("utf-8", "ignore") in data:
                    hits.append(RuleHit(rid, pack["id"], rel, "string", f"weight={weight}"))
                    break

        elif rtype == "regex":
            pat = re.compile(rule["value"].encode())
            scope = rule.get("entry_glob", "**")
            for rel in target.entries:
                if not fnmatch_like(rel, scope):
                    continue
                data = read_entry(target, rel)
                if data:
                    m = pat.search(data)
                    if m:
                        hits.append(RuleHit(rid, pack["id"], rel, "regex", f"match@{m.start()} weight={weight}"))
                        break

    return hits


def fnmatch_like(name: str, pattern: str) -> bool:
    """glob แบบง่าย — **/ ข้ามได้ 0+  directory, ** เท่านั้นข้าม / ได้, * ไม่ข้าม /
    (แก้ 2026-09-09: เดิม **/ บังคับมี / นำหน้า → entry ที่เริ่มที่ root/ โดยตรง miss ทั้งหมด
    — fixture data-dump จับได้ก่อน production — บันทึกใน scan_test.py)"""
    rx = re.escape(pattern)
    rx = rx.replace(r"\*\*/", "(?:.*/)?")   # **/ → นำหน้า 0 หรือหลาย dir
    rx = rx.replace(r"\*\*", ".*")          # ** ลอย → ข้าม / ได้
    rx = rx.replace(r"\*", "[^/]*")         # * เดี่ยว → ไม่ข้าม /
    return re.fullmatch(rx, name) is not None


def read_entry(target: "ScanTarget", rel: str) -> bytes | None:
    """อ่าน bytes ของ entry (สำหรับ string/regex match) — แคชไว้ไม่ได้เพราะ zip เปิดใหม่ทุกครั้ง จึงอ่านสด"""
    if target.path.is_dir():
        p = target.path / rel
        if p.is_file():
            return p.read_bytes()
        return None
    try:
        with zipfile.ZipFile(target.path) as z:
            return z.read(rel)
    except Exception:
        return None


# ─────────────────────────────────────────────
# Tier 2 — Pack loading + scoring
# ─────────────────────────────────────────────
def load_packs(packs_dir: Path) -> list:
    packs = []
    for pf in sorted(packs_dir.glob("*.pack.json")):
        with open(pf) as f:
            pack = json.load(f)
        packs.append(pack)
    return packs


def score(hits: list, pack: dict) -> float:
    weights = {r.get("id"): int(r.get("weight", 1)) for r in pack.get("rules", [])}
    total_possible = sum(weights.values())
    matched_ids = {h.rule_id for h in hits}
    achieved = sum(weights[rid] for rid in matched_ids if rid in weights)
    return (achieved / total_possible * 100.0) if total_possible else 0.0


def verdict(threshold: float, s: float) -> str:
    if s >= threshold:
        return "DETECTED"
    if s >= threshold / 2:
        return "SUSPICIOUS"
    return "CLEAN"


# ─────────────────oter────────────────────
# Report
# ─────────────────────────────────────────────
def build_report(target: ScanTarget, packs: list, results: list) -> dict:
    all_hits = []
    for (pack, hits, s) in results:
        for h in hits:
            all_hits.append({
                "pack": pack["id"],
                "rule": h.rule_id,
                "entry": h.entry,
                "evidence": h.evidence,
                "detail": h.detail,
            })
    return {
        "schema": REPORT_SCHEMA_VERSION,
        "tool": "aether-detect/0.1",
        "sample": {
            "name": target.name,
            "n_entries": target.n_entries,
            "deterministic_content_hash": deterministic_hash(target),
        },
        "packs_evaluated": [p["id"] for p in packs],
        "verdicts": [
            {"pack": pack["id"], "score": round(s, 1), "verdict": verdict(pack.get("threshold", 40.0), s)}
            for pack, hits, s in results
        ],
        "hits": all_hits,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }


def deterministic_hash(target: ScanTarget) -> str:
    """hash จาก (path, sha256) ของทุก entry — แสดงพฤติกรรมตัวอย่าง ไม่ใช่ zip metadata"""
    h = hashlib.sha256()
    for rel in sorted(target.entries):
        h.update(rel.encode())
        h.update(target.entries[rel]["sha256"].encode())
    return h.hexdigest()


# ─────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(prog="aether-detect", description="SNAKE/Aether cheat-engine family scanner")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sc = sub.add_parser("scan", help="scan sample (apk/zip/dir)")
    sc.add_argument("sample")
    sc.add_argument("--packs-dir", default=None)
    sc.add_argument("--json", default=None, help="write JSON report to file")
    sc.add_argument("--max-entries", type=int, default=8000)

    pk = sub.add_parser("packs", help="list loaded packs")
    pk.add_argument("--packs-dir", default=None)

    vp = sub.add_parser("verify-pack", help="validate pack structure")
    vp.add_argument("pack")
    vp.add_argument("--packs-dir", default=None)

    args = ap.parse_args()
    packs_dir = Path(args.packs_dir) if args.packs_dir else default_packs_dir()

    if args.cmd == "packs":
        packs = load_packs(packs_dir)
        for p in packs:
            print(f"{p['id']:24} v{p.get('version','?')}  rules={len(p.get('rules',[]))}  threshold={p.get('threshold','?')}")
        if not packs:
            print(f"(no packs in {packs_dir})")
        return 0

    if args.cmd == "verify-pack":
        pack = json.loads(Path(args.pack).read_text())
        errs = validate_pack(pack)
        if errs:
            print("INVALID:")
            for e in errs:
                print(f"  - {e}")
            return 1
        print(f"OK: {pack['id']} — {len(pack.get('rules', []))} rules, "
              f"total weight {sum(int(r.get('weight',1)) for r in pack.get('rules', []))}")
        return 0

    # scan
    sample = Path(args.sample)
    if not sample.exists():
        print(f"error: sample not found: {sample}", file=sys.stderr)
        return 2

    packs = load_packs(packs_dir)
    if not packs:
        print(f"error: no packs in {packs_dir}", file=sys.stderr)
        return 2

    t0 = time.time()
    target = ScanTarget(sample).hash_entries(max_files=args.max_entries)
    results = []
    for pack in packs:
        hits = match_pack(target, pack)
        s = score(hits, pack)
        results.append((pack, hits, s))
    report = build_report(target, packs, results)
    report["scan_time_ms"] = int((time.time() - t0) * 1000)

    # console summary
    print(f"sample: {target.name}  entries={target.n_entries}  time={report['scan_time_ms']}ms")
    print(f"content-hash: {report['sample']['deterministic_content_hash'][:16]}…")
    for v in report["verdicts"]:
        print(f"  [{v['verdict']:10}] {v['pack']:24} score={v['score']}")
    if report["hits"]:
        print(f"hits ({len(report['hits'])}):")
        for h in report["hits"][:20]:
            print(f"  {h['pack']}/{h['rule']:28} {h['evidence']:8} {h['entry'][:60]}")
        if len(report["hits"]) > 20:
            print(f"  … +{len(report['hits'])-20} more")

    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2, ensure_ascii=False))
        print(f"\nreport → {args.json}")
    return 0


def default_packs_dir() -> Path:
    return Path(__file__).resolve().parent / "packs"


def validate_pack(pack: dict) -> list:
    errs = []
    if pack.get("schema") != PACK_SCHEMA_VERSION:
        errs.append(f"schema must be {PACK_SCHEMA_VERSION}")
    for k in ("id", "description", "rules", "threshold"):
        if k not in pack:
            errs.append(f"missing key: {k}")
    if errs:
        return errs
    ids = set()
    for r in pack["rules"]:
        if r.get("type") not in ("sha256", "filename", "string", "regex"):
            errs.append(f"bad rule type: {r.get('type')}")
        if not r.get("id"):
            errs.append("rule missing id")
        if r["id"] in ids:
            errs.append(f"duplicate rule id: {r['id']}")
        ids.add(r["id"])
        if r.get("type") in ("string", "regex") and "value" not in r:
            errs.append(f"rule {r.get('id')} missing value")
    return errs


if __name__ == "__main__":
    sys.exit(main())
