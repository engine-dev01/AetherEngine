# DART_LICENSE_FLOW.md — หลักฐาน: ใบอนุญาต + package/version มาจาก Dart (ไม่ใช่ Java/smali)

> ที่มา: T1 machine-derived จาก `reference/SNAKE_extract/raw/lib/arm64-v8a/libapp.so` (5,637,024 B)
> ยืนยันโดย `scripts/` string-offset windows รอบ anchor แต่ละตัว

## สรุปหลัก (T1-verified)

**“ไฟล์ UI ถูกพรางชื่อ” คือ Dart-side license/auth flow ทั้งหมด** ไม่ใช่หน้าจอแยก
และ **package + version ไม่ได้ยึดติดกับชื่อไฟล์ UI ที่พรางไว้** แต่มาจากใบอนุญาต:

```
rest.snakeseller.com/api/request/   ← ฝั่งเซิร์ฟเวอร์ (Dart-only, ไม่มีใน smali)
deviceId / encryptedData            ← request payload
authorization / “Access Token”      ← response → Bearer auth
decompressLicenses / parseLicenses  ← ประมวลผลใบอนุญาต
version_lock                        ← ล็อกเวอร์ชั่นเกม (F5:52)
```

## 1. พบ / ไม่พบ ในหลักฐาน

| สิ่งที่ค้นหา | ผล | ความหมาย |
|---|---|---|
| `com.miniclip.carrom` / `eightballpool` | **0 ทุกที่** (smali+java_out+res_out+Dart+libapp) | เกมเป้าไม่ได้ยึดติดกับชื่อไฟล์ UI ที่พรางไว้ |
| `rest.snakeseller.com` | libapp.so ×1 (0x43fed) — **smali ×0** | ใบอนุญาต = Dart-only |
| `deviceId` | libapp ×1 (0x43ce3) + smali ×1 | device-bound license |
| `authorization` | libapp ×2 (0x408d7, 0x408d9f) — smali ×0 | Bearer header |
| `encryptedData` / `ENCRYPTED_SIZE` | libapp ×1 / ×3 | payload เข้ารหัส |
| `license` | libapp 3 fn — **smali ×0** | decompress/parse/utf8Decode |
| `version_lock` | F5:52 (×3) | ล็อกเวอร์ชั่นในใบอนุญาต |

## 2. ส่วนรับ Package (Java/smali) — ที่ชี้ถูกแล้ว

`Entry.smali` dispatch ตาม `method.equals(name)`:

```smali
const-string v1, "H"
invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
if-eqz v0, :cond_d
invoke-virtual {p1}, Landroidx/appcompat/view/menu/id0;->b()Ljava/lang/Object;   ← p1 = id0 (name, arg)
check-cast p1, Ljava/lang/String;
if-nez p1, :cond_c
return-void
:cond_c
invoke-static {p0, p1}, Landroidx/appcompat/view/menu/s2;->a(Landroid/content/Context;Ljava/lang/String;)Landroidx/appcompat/view/menu/s2;
```

**H key = จุดรับ Package จาก Dart** — `id0.b()` คืนค่าที่ Dart ส่งผ่านมา แล้ว `s2.a(ctx, package)` สร้าง host holder

### โครงสร้างตัวรับ (ที่พรางชื่อ → ชื่อจริง)
| พราง | จริง | หลักฐาน |
|---|---|---|
| `kd0$d` | MethodChannel **Result** interface (`a()`, `b(err,code,obj)`, `c(obj)`) | kd0$d.smali |
| `id0` | argument pair `(String name, Object arg)` | id0.smali: `.field a:Ljava/lang/String; b:Ljava/lang/Object;` |
| `Entry` | **dispatcher** — extends `dt` | `Entry.super = dt` |
| `dt` | **base Activity host** (`android.app.Activity`) | dt.smali `.super` |
| `s2` | **host holder factory** — `a(Context, String)` static | Entry:457 |
| `jv0` | **ActivityThread stub** — `.super h00$a` | jv0.smali |
| `h00` / `m00` | **IInterface** binder hooks | h00/m00 `.implements` |
| `a7` | diag slot 3 (handshake simulation) | F2 F8 |
| `fl0`/`so`/`ne0` | Runnable workers | `.implements Runnable` |

## 3. สิ่งที่ปฏิเสธ (ไม่ใช่จุดรับ package)

- **`dt`/`et`/`kd0`/`id0` ไม่ใช่ “ไฟล์ UI ที่พรางชื่อ”** ในความหมายภาพหน้าจอ —
  คือ **method-channel plumbing** (dispatcher + argument wrapper + Result interface)
- **“ไฟล์มีการพรางชื่อ ui”** จริง = Dart license/auth code ที่มีชื่อ obfuscated ใน libapp.so
  (blutter output: `_XNb@…`, `_RL@…`, `_ija@…` ฯลฯ) — ไม่ใช่ไฟล์ XML/layout แยก
- **`com.snake.snake_engine` / `.inner`** ใน smali = engine identity ตัวเอง ไม่ใช่เกมเป้า

## 4. สิ่งที่ยังไม่ควรทำ (ขัดกับหลักฐาน)

- อย่าหา “หน้าจอเกม” ใน smali/res — หน้าจอเกมทั้งหมดอยู่ใน **libapp.so (Flutter/Dart)**;
  ฝั่ง Java มีเพียง method-channel plumbing
- อย่าผูก package กับไฟล์ UI เด็ดขาด — package มาจาก **ใบอนุญาต (server JSON)** ผ่าน
  `version_lock` + `deviceId`; ฝั่ง Java รับแค่ “ชื่อ package” เปล่าผ่าน H key
- อย่าพยายามแก้ `version_lock` ใน Dart เพื่อรองรับหลายเกม — มันล็อกแค่เวอร์ชั่น
  ไม่ใช่ registry ของเกม

## 5. บทบาทที่ถูกต้อง (โครงสร้างที่หลักฐานสนับสนุน)

```
┌─ Flutter (libapp.so, Dart) ─────────────────────────────┐
│  license: rest.snakeseller.com/api/request/ (JSON)      │
│    deviceId → encryptedData → “Access Token”            │
│    decompressLicenses → parseLicenses → version_lock    │
└───────────────────────────┬─────────────────────────────┘
                            │ MethodChannel (H key)
                            ▼
┌─ Java host (smali) ──────────────────────────────────────┐
│  dt (Activity host) ← Entry (dispatcher)                │
│    Entry: “H”.equals(method)                            │
│      → id0.b() → String package (จาก Dart)             │
│      → s2.a(ctx, package) → host holder                 │
│  kd0$d Result: c(obj) ส่งผลคืน Dart                     │
└──────────────────────────────────────────────────────────┘
```

## 6. สรุปประเด็น “ไฟล์มีการพรางชื่อ ui”

| ความหมายที่คิด | ความจริงตามหลักฐ evidences |
|---|---|
| ไฟล์ XML/layout ถูกเปลี่ยนชื่อ | ❌ ไม่พบหลักฐาน — ไม่มี layout ที่ซ่อนชื่อเกม |
| ชื่อคลาส UI ถูกพราง | ✓ แต่เป็น **Dart-side** (libapp.so) — blutter ชื่อ `_XX@hash` |
| package/version อยู่ในไฟล์ UI | ✗ อยู่ใน **ใบอนุญาต** (server JSON + version_lock) |
| “จุดรับ Package” ถูกพราง | ✓ H key + id0/s2 = plumbing ที่รับ package จาก Dart |

## 7. ข้อค้นพบที่ต้องแก้ไข (ฝั่งเรา)

1. `app/lib/data/games.dart:40` — **สะกดผิด** `com.miniclop.carrompool` → ควรเป็น
   `com.miniclip.carrompool` (ขาด i) และเป็น comment อยู่
2. `app/lib/data/games.dart:56` — `com.miniclop.soccerstars` ผิดเช่นกัน
3. ฝั่งเรา **ไม่มี license flow เลย** — ไม่มีไฟล์ Dart ใดรับ/ตรวจใบอนุญาต
4. `home_screen.dart` มี 2 ช่องทางเข้าถึง package ที่ไม่สอดคล้องกัน:
   - `_pkgController` = hardcoded `'com.miniclip.eightballpool'` (line 51)
   - `_game.packageName` = จาก `Games.defaultGame` (line 57, 166, 352)
   → **ไม่ได้รับจากใบอนุญาต** และทั้งสองค่าไม่ได้ขึ้นตรงกับสิ่งเดียวกัน
5. `home_screen.dart:352` `_chainCheck1234()` ใช้ `_game.packageName` ส่งเข้า
   `chainCheck` — หากใบอนุญาตไม่อนุญาตเกมนี้ ควรปฏิเสธก่อนส่ง
