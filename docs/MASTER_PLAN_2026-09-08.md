# AetherEngine — Master Plan (2026-09-08)
## มัดรวมจาก: เกมแท้ 56.29.2 (dump ใหม่) + KOS reference + Snake 2.2.6 report + 5 รอบ device log

---

## สถานะปัจจุบัน (ยืนยันด้วย log รอบ 5 ล่าสุด)

สิ่งที่ **ทำงานแล้วจริง** (ไม่ต้องแตะอีก):
- [x] Guest Application โหลด + bind + onCreate รัน (mx_mixpanel DB + prefs เขียนใน sandbox)
- [x] Data redirect ที่ Java level (mCredentialProtectedDataDirFile — ENOENT storm หาย 20+→0)
- [x] Activity swap: `newActivity swapped stub → EightBallPoolActivity` + `recreate()` single-launch
- [x] `mBase → guest ctx` + `guest ActivityInfo installed (theme=0x7f14000c)`
- [x] Guest firewall (ไม่มี FATAL จาก GMS/WorkManager อีก)

ค้างอยู่ที่ **ชั้นสุดท้ายเดียว**: crash ใน guest onCreate ที่ `AppCompatDelegateImpl.attachToWindow` —
`Resources$NotFoundException: com.aether:id/design_menu_item_action_area_stub (0x7f080080)`
TypedValue ยัง resolve ผ่าน **resource space ของ HOST** ทางใดทางหนึ่ง (กำลังวิเคราะห์ PhoneWindow/AppCompat chain — งานที่ค้าง)

---

## Gap audit vs ต้นแบบ (จาก Snake 2.2.6 report + KOS) — เรียงตามผลต่อ "เกมรันได้"

### P0 — บล็อกทันที
| # | Gap | หลักฐาน | แผนแก้ |
|---|-----|---------|--------|
| 1 | **AppCompat Resources mismatch** (งานค้าง) | crash 0x7f080080 ผ่าน host table รอบ 5 | จบวิเคราะห์ PhoneWindow.getContext().getTheme() chain — ผู้ต้องสงสัยลำดับ 1: PhoneWindow สร้างด้วย activity ctx ตอน attach (ยังเป็น stub) แล้ว decorView/Theme ถูก cache ไว้; ทางแก้ที่ deterministic คือ rebuild window theme หลัง mBase เปลี่ยน (windowContext เปลี่ยนไม่ได้ แต่ Theme ของ window apply ใหม่ได้) |
| 2 | **Payload store ว่าง** (`nativeHydratePayloads: 0`) | log ทุกรอบ | ฝัง payload blobs ใน APK (assets/payloads/) + hydrate จากที่นี่ (ต้นแบบ 92+ SHA-256 blobs มากับ engine) — อย่างน้อย ELF stub ต้องมีจริง ไม่ใช่ก้อนเปล่า 120B |

### P1 — จำเป็นทันทีหลัง P0 ผ่าน
| # | Gap | หลักฐาน | แผนแก้ |
|---|-----|---------|--------|
| 3 | **PGL เวอร์ชั่นเก่า** — เรา provision `90d8aa15a2de2cb4` + Module-3965 (67KB) แต่เกมแท้ 56.29.2 ใช้ `89110b74e26c1c0e` + **Module-4014** (71KB) + libadsurge* (4 ไฟล์) | dump เกมแท้ | SandboxManager: หา PGL dir จากเครื่องจริง (list a0rjgdfbjd8fhfglkew6/*/) แทน hardcode; copy PGL .so จากเกมติดตั้งจริงตอน bootstrap |
| 4 | **enableIO/addIORule เป็น stub** ทั้งที่ VirtualFS.cpp มีโค้ดจริง (dead code) | grep | เชื่อม `enableIO` → `VirtualFS::mount` จริง + `addIORule` → resolve table — จำเป็นเมื่อ libgame-BPM (native) เปิดไฟล์ตรงผ่าน syscall ไม่ผ่าน Java |
| 5 | **nativeInitContext stub** | grep | ส่ง Context จริงเข้า native (ต้นแบบ ic(Context)) — เก็บ global ref สำหรับ engine |

### P2 — เสร็จสมบูรณ์ชั้น architecture
| # | Gap | แผนแก้ |
|---|-----|--------|
| 6 | setAccessible(Field/Method) stub | port ART access_flags bypass (ต้นแบบ Native.update) — สำรองเมื่อ reflection โดน block |
| 7 | ProxyService/JobService 18 บรรทัด ไม่มี dispatch | เพิ่ม route logic ตามต้นแบบ (startService ของ guest ต้อง route เข้า :p0–p3) |
| 8 | sandbox ขาด dirs: fTjs9rArCiJEVX (+oat) | เพิ่มใน ensureSandboxStructure (รวมถึง files/Contents/Documents/__user_defaults__/ ที่เกมต้องการ) |

### ไม่ทำ (นอก scope)
- ระบบ login/seller/C2 ของต้นแบบ (ตกลงตามคำขอ — "ไม่ยึดติดระบบ login")
- snakeengine.com / snakeseller.com endpoints
- FCM push-command plane

---

## ข้อมูลสำคัญที่ได้จากเกมแท้ 56.29.2 (ใช้ในแผน)

- Version ต้องอ่านสดจาก installed package (PM.getPackageInfo) — ห้าม hardcode 56.23.2
- PGL: `a0rjgdfbjd8fhfglkew6/<pgl-version>/arm64-v8a/` — pgl-version ต่างกันตาม game version
- libgame-BPM-...-Module-4014.so = 71,267 bytes (เราฝังตัว 3965 = 67,904 เก่า)
- Game state แบบ cocos2d-x iOS-style: `files/Contents/Documents/__user_defaults__/*.plist` (12 ไฟล์)
- Marker `if9rflTffx` = "unknown" (เกมแท้) — KOS ใช้ marker เดียวกัน
- 14 dirs ที่เกมสร้างจริง — เราสร้างครบแล้ว 13 เหลือ fTjs9rArCiJEVX

---

## ลำดับ execution (commit ต่อ commit, CI gate ทุกรอบ)

1. **[P0.1] จบ AppCompat Resources fix** ← งานที่ค้าง — วิเคราะห์ PhoneWindow chain ให้จบ แล้ว device-test
2. **[P0.2] Embed payloads** — ย้าย blob set จริงเข้า APK + hydrate จาก assets
3. **[P1.3] PGL dynamic** — resolve จากเครื่องจริง แทน hardcode
4. **[P1.4] เปิดใช้ VirtualFS จริง** — ทำ native I/O redirect ให้ทำงาน (mount/resolve)
5. **[P1.5] nativeInitContext จริง**
6. **[P2] setAccessible + ProxyService dispatch + sandbox dirs เพิ่ม**

กฎทุก commit: preflight (hidden-API scan + syntax) → push CI → รอ success → device test → dump วิเคราะห์ log

---

## หมายเหตุความเสี่ยง
- หมายเลข resource id (0x7f080080) ต่างกันตาม build — อย่า hardcode ใน fix
- PGL .so ของเกมแท้ต้อง copy มาจากเครื่องที่ติดตั้งเกม (มันมีอยู่แล้ว) — ไม่ต้องฝังใน APK เรา
- Module-4014 = 71KB (ใหญ่กว่าของเรา 3.3KB) — ถ้า hydrate ตัวเก่าอาจโดน version-check ของ PGL
