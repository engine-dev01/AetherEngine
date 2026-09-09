#!/usr/bin/env python3
"""Functional scan test — ทำงานใน CI (detect-lab job) และรัน local ได้
ทดสอบ scanner + packs กับ synthetic fixtures ที่สร้างจากหลักฐาน 4 ชั้น:
  1. fixture_snake_like.zip   → snake-8bp ต้อง DETECTED (≥ threshold)
  2. fixture_clean_game.zip   → ทุก pack ต้อง CLEAN (anti false-positive)
  3. fixture_data_dump.zip    → snake-8bp ต้อง SUSPICIOUS ขึ้นไป (sandbox artifact)
  4. --determinism: สแกนซ้ำ 2 ครั้ง → content-hash + verdicts ต้องเหมือนกันเป๊ะ
ออกจากโปรแกรมด้วย exit code ไม่ใช่ print — CI ใช้เป็น gate
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).parent
FIXTURES = HERE / "fixtures"
REPO = HERE.parent.parent
SCANNER = REPO / "detect" / "aether_scan.py"
PACKS = REPO / "detect" / "packs"


def ensure_fixtures():
    m = FIXTURES / "manifest.json"
    if not m.exists():
        subprocess.run([sys.executable, str(HERE / "make_fixtures.py")], check=True)


def scan(sample: Path) -> dict:
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tf:
        out = Path(tf.name)
    r = subprocess.run(
        [sys.executable, str(SCANNER), "scan", str(sample),
         "--packs-dir", str(PACKS), "--json", str(out)],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        raise SystemExit(f"scanner failed on {sample.name}")
    return json.loads(out.read_text())


def verdict_of(report: dict, pack_id: str) -> str:
    for v in report["verdicts"]:
        if v["pack"] == pack_id:
            return v["verdict"]
    raise SystemExit(f"pack {pack_id} not in report")


def main():
    import sys
    determinism = "--determinism" in sys.argv
    ensure_fixtures()
    failures = []

    # ── 1. snake-like → DETECTED ──
    r = scan(FIXTURES / "fixture_snake_like.zip")
    v = verdict_of(r, "snake-8bp")
    print(f"[{'PASS' if v == 'DETECTED' else 'FAIL'}] snake-like fixture → snake-8bp = {v}")
    if v != "DETECTED":
        failures.append("snake-like should be DETECTED")
    # hits ต้องอ้างหลักฐานจริง ไม่ใช่ generic strings
    hit_rules = {h["rule"] for h in r["hits"] if h["pack"] == "snake-8bp"}
    for expected in ("c2-rest-snakeseller", "libengine-process-vm-readv", "libapp-c2-action"):
        ok = expected in hit_rules
        print(f"[{'PASS' if ok else 'FAIL'}] hit ต้องมี rule หลักฐาน: {expected}")
        if not ok:
            failures.append(f"missing expected hit: {expected}")

    # ── 2. clean game → CLEAN ทุก pack ──
    r = scan(FIXTURES / "fixture_clean_game.zip")
    for v_ in r["verdicts"]:
        print(f"[{'PASS' if v_['verdict'] == 'CLEAN' else 'FAIL'}] clean game → {v_['pack']} = {v_['verdict']}")
        if v_["verdict"] != "CLEAN":
            failures.append(f"false positive on clean game: {v_['pack']}={v_['verdict']}")

    # ── 3. data dump → SUSPICIOUS+ ──
    r = scan(FIXTURES / "fixture_data_dump.zip")
    v = verdict_of(r, "snake-8bp")
    ok = v in ("SUSPICIOUS", "DETECTED")
    print(f"[{'PASS' if ok else 'FAIL'}] data dump → snake-8bp = {v} (ต้อง ≥ SUSPICIOUS)")
    if not ok:
        failures.append(f"data dump should be ≥ SUSPICIOUS, got {v}")

    # ── 4. determinism ──
    r1 = scan(FIXTURES / "fixture_snake_like.zip")
    r2 = scan(FIXTURES / "fixture_snake_like.zip")
    same_hash = r1["sample"]["deterministic_content_hash"] == r2["sample"]["deterministic_content_hash"]
    same_verdicts = r1["verdicts"] == r2["verdicts"]
    same_hits = r1["hits"] == r2["hits"]
    print(f"[{'PASS' if same_hash else 'FAIL'}] determinism: content-hash สแกนซ้ำเหมือนกัน")
    print(f"[{'PASS' if same_verdicts and same_hits else 'FAIL'}] determinism: verdicts + hits เหมือนกันทุกบรรทัด")
    if not same_hash:
        failures.append("content-hash not deterministic")
    if not (same_verdicts and same_hits):
        failures.append("verdicts/hits not deterministic")

    print()
    if failures:
        print(f"╳ {len(failures)} FAILURES:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("═══ ALL DETECT-LAB TESTS PASS ═══")
    return 0


if __name__ == "__main__":
    sys.exit(main())
