# aether-detect — Pack-driven cheat-family scanner (P2+P3)

สแกนเนอร์ตรวจตระกูล SNAKE/Aether cheat engine — ทำงาน offline ทั้งเส้น
อ้างอิง: `../DESIGN_PLAN.md` (aether-pivot) §2 Tier1 matching, §5 extensibility, §6 P2/P3

## ใช้งาน

```bash
python3 detect/aether_scan.py scan <sample.apk|zip|dir> --packs-dir detect/packs [--json out.json]
python3 detect/aether_scan.py packs --packs-dir detect/packs
python3 detect/aether_scan.py verify-pack detect/packs/snake-8bp-56.23.2.pack.json
```

## โครงสร้าง

```
detect/
  aether_scan.py        core matcher (immutable — เพิ่มตระกูลใหม่ไม่ต้องแต้ไฟล์นี้)
  packs_verify.py       pack integrity gate (CI ใช้ — กัน IOC poisoning)
  packs/
    snake-8bp-56.23.2.pack.json   จากหลักฐาน 4 ชั้น (23 rules ทุกตัวมี note ที่มา)
    aether-family.pack.json       จาก source ของ repo นี้เอง (12 rules)
  tests/
    make_fixtures.py    สร้าง synthetic fixtures จากโครงหลักฐานจริง (ไม่ใช่ sample จริง)
    scan_test.py        functional test 9 ข้อ + determinism (CI gate)
    fixtures/           generated (ไม่ commit — สร้างเมื่อรัน test)
```

## Pack contract (schema 1)

ทุก rule ต้องมี `note` อ้างหลักฐานที่มา — verifier ปฏิเสธ rule ลอย ๆ
rule types: `sha256` / `filename` (รองรับ `entry_contains`) / `string` / `regex` (รองรับ `entry_glob`)
glob semantics: `**/` = นำหน้า 0+ dirs, `**` ปลาย = ข้าม `/` ได้, `*` = ไม่ข้าม `/`
threshold: 0-100 (% ของ weight รวม) — verdict: DETECTED ≥ threshold, SUSPICIOUS ≥ threshold/2

## การทดสอบ (3 ชั้นตามที่เลือกใช้)

1. **Local pre-flight** `./scripts/pre_flight_check.sh` (~2s) — dangling/JNI-mismatch/caller
2. **detect-lab (CI)** — pack verify + scan_test 9 ข้อ + determinism บน synthetic fixtures
3. **Validation กับตัวจริง** (เครื่องวิเคราะห์เท่านั้น — sample ไม่ขึ้น repo):
   SNAKE.apk → DETECTED 52.1 · เกมแท้ → CLEAN 0.0 · com.snake_1.zip → SUSPICIOUS 39.6

## บั๊กที่ test จับได้และแก้แล้ว (บันทึกกันเกิดใหม่)

- **glob semantics**: `**/pattern` เดิมบังคับ `/` นำหน้า → entry ที่เริ่มที่ root โดยตรง miss ทั้งหมด (data-dump fixture จับได้ = ทำงานก่อน production)
- **one-hit guard**: verifier ปฏิเสธ pack ที่ rule เดียวหนักพอทำ verdict เดี่ยว ๆ (กัน IOC เดี่ยวถูก manipulate)
- **weight รวม**: rule แรกหนัก 10 + ที่เหลือเบา → จับ miss ของ C2 string หนึ่งตัวก็ยัง DETECTED ได้ (ตรวจสอบ score ให้ดู hits คู่กันเสมอ)
