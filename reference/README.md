# reference/ — หลักฐานที่โค้ดอ้าง (audit C10/C16)

| ไฟล์ | Tier | ใช้โดย | หมายเหตุ |
|---|---|---|---|
| snake/F2_dex_natives.txt | **T1** (machine-rederive จาก SNAKE.apk) | scripts/native_chain_parity.py, scripts/jni_parity.py |ชื่อ+JNI signature ของ Native/flagger ทั้ง 13 ตัว — re-hash ได้ที่ปลายสาย |
| snake/F3_manifest.txt | **T1** | P2 manifest parity (51 components) | ชื่อ component/authority/process จาก AXML decode |
| NATIVE_CALLSITE_MAP.md | **T2** (jadx transcript) | citation ใน kotlin comments (a7.java:171 ฯลฯ) | ตัวเลขบรรทัด jadx **ไม่ใช่ T1** — ใช้อ้างตำแหน่ง hop, ห้ามใช้ออกแบบสิ่งที่ไม่ปรากฏใน F2 ยกเว้นมีหลักฐาน T1 อื่นรองรับ (เช่น gcuid — พบใน `SNAKE_extract/apktool/smali/.../Native.smali:53` + classes.dex = T1-backed) |
| packageconf.8bp-56.23.2.json | (ลบออกจาก repo 2026-09-14) | — | ลบตาม commit b2a8f43/2a8f738 — SandboxManager ใช้ generatePackageConf (Kotlin) แทน |

หมายเหตุ C10-missing-evidence: DATA_DUMP(transcript สูญ-UNVERIFIED) / NATIVE_LOGIC(transcript สูญ-UNVERIFIED) = เอกสารรอบก่อน
ที่ไม่ได้ commit — ทุก citation ที่อ้างเอกสารเหล่านี้ถือว่า **UNVERIFIED** จนกว่า
จะมีหลักฐาน T1 รองรับ (ดู docs/CUTS.md ประกอบ)
