# SNAKE UI BLUEPRINT — รื้อเพื่อเหมือน snake 100%
T1 evidence: blutter object pool (`reference` output/pp.txt, 18,786 entries) + smali.
หลักฐานนี้คือ **ข้อกำหนดความเหมือน 100%** — จุดใดเราไม่สอดคล้อง ต้อง**ติดป้ายกำกับ**ไว้เพื่อพิสูจน์

## A. ข้อเท็จจริงหลัก (T1, pp.txt)

| # | หลักฐาน (offset) | ข้อเท็จจริง |
|---|---|---|
| A1 | pool ทั้งหมด (18,786 string) | **ไม่มีชื่อเกมใดๆ** (pool/carrom/soccer = 0 hits) |
| A2 | `pp+0xf638..0xf658` | ตรวจอินเทอร์เน็ตก่อนเข้าแอป, **6 ภาษา** |
| A3 | `pp+0xf620` | ชื่อแอป = **"Snake Engine"** |
| A4 | `pp+0xfb38..0xfbc0` | ตัวเลือก 3 ตัว: **Game / Subscription / Duration Selection** |
| A5 | `pp+0xf998..0xfb38` | Create Key / Get Key / Send Now / Activate For (*) |
| A6 | `pp+0xfd50..0xfdd8` | แท็บ: Your Keys / New Keys / Used Keys |
| A7 | `pp+0xfde0..0xfe38` | หน้า Accounts List (Account *, Never Used) |
| A8 | `pp+0xfe68..0xffc0` | Key Details: Tier Name / Cost / Bonus / Locked For / Activated For |
| A9 | `pp+0x112b0..0x11308` | **เกทติดตั้งเกม**: "This game is not Installed on your device. please install it from Google Play first." + **version_lock**: "This game version is not supported. please install other version. **from \* to #**" |
| A10 | `pp+0x11508..0x115e8` | **Access Token** (2 ภาษา) "This is your secure access token. Keep it safe and do not share it with others." |
| A11 | `pp+0x139d8` | license endpoint `https://rest.snakeseller.com/api/request/` |
| A12 | `pp+0x178b0..0x17d90` | ลิงก์: apkpure / play.google.com / flagsapi / discord / t.me / wa.me / facebook |
| A13 | `pp+0x3c70..0x43c0` | license fn: decompressLicenses / utf8DecodeLicenses / parseLicenses / deviceId |
| A14 | `pp+0xf6e8..0xf990` | ข้อความสถานะ: There was an unknown error / This device is already added / Your account is banned / Offline offline offline |
| A14b | `pp+0xf6c0` | **"Offline"** |

## B. แผนผัง UI ของ snake (reconstructed from pool clusters)

```
MaterialApp
└─ (gate) ตรวจอินเทอร์เน็ต — ไม่มี → แสดงข้อความ 6 ภาษา + ปุ่ม "try again"/ออก
└─ (gate) ตรวจเวอร์ชั่นเกม (version_lock: from * to #)
│    ├─ เกมไม่ได้ติดตั้ง → "please install it from Google Play first" → ลิงก์ play.google.com/apkpure
│    └─ เวอร์ชั่นไม่รองรับ → "install other version from * to #" → ลิงก์
└─ Scaffold หลัก
   ├─ bottom nav หรือ tab bar: Keys / Accounts / (Orders) / (Notifications) / Profile
   ├─ หน้า Keys: 3 แท็บ (Your Keys / New Keys / Used Keys) + "Get Key" / "Create Key"
   ├─ หน้า Accounts: Accounts List, Account *, Never Used / Used / valid (VÁLIDO) / locked/unlocked
   ├─ Key Details: Tier Name / Cost / Bonus / Locked For / Activated For / Date / Unlock it / Lock it
   ├─ Game Selection (A4) — รายชื่อเกมมาจาก server (A1: ไม่มีใน binary)
   ├─ Subscription Selection / Duration Selection (A4)
   ├─ Access Token page (A10) — แสดง "Your Access Token" + คำเตือน
   └─ ลิงก์โซเชียล (A12)
```

## C. ข้อกำหนด "เหมือน 100%" ที่ยาก → **ต้องติดป้ายกำกับ** (ส่วนที่เราไม่มี server จริง)

| ป้าย | สิ่งที่ต้องทำ | หลักฐาน |
|---|---|---|
| L1 | รายชื่อเกม/ไทร์/ราคา **ทั้งหมดมาจาก server JSON** — offline build เราต้องใช้ bundled license (offline-fallback) และติดป้ายว่า **"OFFLINE fallback — not the live server list"** | A1, A4 |
| L2 | **version_lock** คือช่วงเวอร์ชั่น (from * to #) — เราต้องมีโครงสร้างข้อมูล `versionLock` แบบช่วง ไม่ใช่ string เดียว | A9 |
| L2b | "install other version from * to #" → รายละเอียดต้องมาจาก server | A9 |
| L3 | 6 ภาษาทั้งหมดต้องมี | A2 |
| L4 | Access Token ต้องเป็น "secure access token. Keep it safe and do not share" | A10 |
| L5 | **Key = device-bound**: "generate a CODE that will only work for this device id (*) and not others" | 0xfbf8 |
| L6 | Device can be "already added" / account "banned" / "Offline" — สถานะเหล่านี้ต้องมี | A14 |

## D. สิ่งที่จะทำ (ขั้นต่อที่ยังเป็น "เหมือน 100%" ได้แม้ offline)

1. **รื้อ home_screen.dart** ออกทั้งหมด — เดิมเป็น "diagnostic console" ของ AetherEngine (memhex/scan/compute), ไม่ใช่ UI ของ snake
2. **สร้างหน้าตาม B**: gate อินเทอร์เน็ต → gate เวอร์ชั่นเกม → tab scaffold (Keys/Accounts) + Access Token page + Game Selection (list จาก LicenseStore)
3. **6 ภาษา** (en/ar/es/hi/ms/tl) สำหรับทุกสตริงหลัก
4. **version_lock** แบบช่วง (from-to)
5. **ติดป้ายกำกับ** ทุกจุดที่เป็น offline-fallback หรือยังไม่มี server จริง — เพื่อให้พิสูจน์ได้ว่าจุดไหนคือจุดที่ต้องต่อ server จริง
6. **ปรับ LicenseStore** ให้รองรับ version_lock แบบช่วง + 6 ภาษาไม่ใช่แค่ status

## E. สิ่งที่จะไม่ทำ (เพราะเป็น engine-internal, ไม่ใช่ UI ของ snake)

- ปุ่ม chainCheck/memhex/scan/compute ต่างๆ (เป็นของ AetherEngine เก่า ไม่ใช่ snake UI)
- ปุ่ม "Play Store"/"Play" ที่เดิมใช้ launchInSandbox — snake ใช้ "install it from Google Play" เป็น deep-link ไป play store ไม่ใช่ launch sandbox
