# Provisioning Spec — 8 Ball Pool v56.23.2 (guest/target)

> Reference blueprint สำหรับให้ AetherEngine (host `com.aether`) รัน
> **8 Ball Pool `com.miniclip.eightballpool` v56.23.2** เป็น virtual app.
>
> ที่มา (source of truth): data-dir dump ของ Snake Engine ต้นแบบ
> `com.snakeOld.v56.23.2.zip` (2,723 entries) — snapshot ของ
> `/data/user/0/com.snake/` ขณะ bind กับ 8BP 56.23.2.
>
> **สำคัญ:** `Snake Engine_2.2.6.apk` = engine host (v2.2.6);
> `56.23.2` = version ของ **target เกม** ไม่ใช่ของ engine. artifact
> ทั้งหมดในเอกสารนี้ **ผูกเฉพาะ 8BP build 56.23.2** — เปลี่ยน version
> เกมเมื่อไหร่ ต้อง regenerate ทุกช่อง.

ทุกค่าในเอกสารนี้ตรวจจาก byte จริงในไฟล์ (ไม่ใช่จาก REPORT(transcript สูญ-UNVERIFIED, 2026-09-09)/DATA_DUMP(transcript สูญ-UNVERIFIED)
ซึ่งพบว่าคลาดเคลื่อนบางจุด — ดู §7).

---

## 1. Identity

| field | value |
|---|---|
| target package | `com.miniclip.eightballpool` |
| version name | `56.23.2` |
| application class | `com.miniclip.eightballpool.EightBallPoolApplication` |
| appComponentFactory | `androidx.core.app.CoreComponentFactory` |
| launcher activity | `com.miniclip.eightballpool.EightBallPoolActivity` |
| `/proc/0/cmdline` | `com.miniclip.eightballpool\0` (26 bytes, null-terminated) |

## 2. Sandbox path binding (hard-coded ต่อ build)

APK path จริงที่ engine mount (จาก `ApplicationInfo.sourceDir` ใน package.conf):

```
/data/app/~~F1DDjUIyN03WylgoiysJig==/com.miniclip.eightballpool-P6GpedHMfcxqtbfW_VQrNQ==/base.apk
```

path-hash 2 ชั้น (`~~F1DDjUIyN03WylgoiysJig==` + `com...-P6GpedHMfcxqtbfW_VQrNQ==`)
เป็น install-token ของ build 56.23.2 — regenerate เมื่อ version เปลี่ยน.

data dirs ที่ต้อง redirect (fake → real sandbox):

```
/data/data/com.miniclip.eightballpool       → <sandboxRoot>/data/user/0/com.miniclip.eightballpool
/data/user/0/com.miniclip.eightballpool      → (เดียวกัน)
/data/user_de/0/com.miniclip.eightballpool   → <sandboxRoot>/data/user_de/0/com.miniclip.eightballpool
/sdcard, /storage/emulated/0                  → engine external dir
```

hash-named subdirs ภายใน data dir ของ target (ต้องคงชื่อเดิม):

| dir | ถอด/บทบาท |
|---|---|
| `a0rjgdfbjd8fhfglkew6/90d8aa15a2de2cb4/arm64-v8a/` | native module store |
| `706d494674354b747939547a3839354b4e43626776773d3d/` | keystore — base64 = `pmIFt5Kty9Tz895KNCbgvw==` |
| `j9g29zqf0cfqd3vvu2bw/1E636546DA1546F6BAA99F1E4F4E448C/` | secondary store |

## 3. package.conf (Android Parcel, 173,904 B)

serialized manifest ของ target. **encoding ผสม**: header เป็น UTF-16LE
(repackaged classloader names `androidx.appcompat.view.menu.*`) + body เป็น
UTF-8 length-prefixed (component/manifest names). ต้อง parse ทั้งสอง.

parser: [`tools/parse_packageconf.py`](../tools/parse_packageconf.py) →
ผลลัพธ์ deterministic: [`reference/packageconf.8bp-56.23.2.json`](../reference/packageconf.8bp-56.23.2.json)

component counts (จาก structured parse):

| ชนิด | จำนวน |
|---|---|
| Application | 1 |
| Activity | 95 |
| Service | 18 |
| Provider | 20 |
| Receiver | 14 |

metadata สำคัญ (สำหรับ SDK init ของ target):

| key | value |
|---|---|
| `com.facebook.sdk.ApplicationId` | `165073083517174` |
| `com.facebook.sdk.ClientToken` | `7fb62b7c48f76a66e989342af5f6e7ec` |
| `com.google.android.gms.games.APP_ID` | `697261581904` |
| `com.google.android.gms.games.version` | `21.0.0` |
| `com.android.stamp.type` | `STAMP_TYPE_DISTRIBUTION_APK` |

permissions (`android.permission.*`, 11 รายการ):
`ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION`,
`ACCESS_ADSERVICES_TOPICS`, `ACCESS_NETWORK_STATE`, `BIND_JOB_SERVICE`,
`DUMP`, `FOREGROUND_SERVICE`, `INTERNET`, `POST_NOTIFICATIONS`,
`VIBRATE`, `WAKE_LOCK`.

> รายชื่อ component เต็มอยู่ใน JSON reference — ใช้เป็น input ตอน
> `installProviders` / `installReceivers` ของ virtual-app loader.

## 4. jkl_key — native module decrypt key

จาก `shared_prefs/com.miniclip.eightballpool.xml`:

```xml
<string name="jkl_key">010100640100000000000000000100001400000000006464000000000100</string>
```

30-byte blob (little-endian dwords):

| field | hex | ค่า |
|---|---|---|
| version+flags | `01010064` | v1, flags 0x64 |
| — | `01000000` | 1 |
| — | `00000000` | 0 |
| — | `00010000` | 0x10000 |
| len? | `14000000` | 20 |
| — | `00006464` | 0x6464 |
| — | `00000000` | 0 |
| tail | `0100` | 1 |

ใช้ decrypt native module ใน §5.

## 5. Native module (encrypted, ต้อง decrypt ด้วย jkl_key)

`root/data/user/0/com.miniclip.eightballpool/a0rjgdfbjd8fhfglkew6/90d8aa15a2de2cb4/arm64-v8a/`

| ไฟล์ | ขนาด | บทบาท |
|---|---|---|
| `libgame-BPM-GooglePlay-Gold-Release-Module-3965.so` | 67,904 B | ★ ตัวเกม (encrypted, module id 3965) |
| `libbuffer_pgl.so` | 86 B | stub |
| `libpglarmor.so` | 220 B | module protector stub |

## 6. Engine-side artifacts (host `com.snake` เอง)

| item | count / value |
|---|---|
| module store `files/[64-hex]` | **85 payloads** (encrypted) |
| vdex `oat/arm64/Anonymous-DexFile@*.vdex` | 5 ไฟล์ (108 / 8748 / 156 / 300 / 156 B) |
| `shared_prefs/com.snake.xml` | `cip_pub` (ว่าง) |
| `system/uid.conf` | `01 00 00 00 00 00 00 00` |
| `system/user.conf` | `01… 01… ffffffff…` (user 0) |
| `system/shared-user.conf` | `00 00 00 00` |

## 7. เอกสารต้นแบบคลาดเคลื่อน (แก้ให้ตรงหลักฐาน)

| ที่ | เอกสารเดิมเขียน | ค่าจริง (นับ/คำนวณ) |
|---|---|---|
| `DATA_DUMP(transcript สูญ-UNVERIFIED) §4.1` | 92 payloads | **85** (`files/[64-hex]`) |
| `REPORT(transcript สูญ-UNVERIFIED, 2026-09-09) §4.1 ( เอกสารรอบก่อน ไม่ได้ commit — ค่าตรวจซ้ำจาก F4b)` | `.text` 8.18 MB | **7.8 MB** (0x52150–0x81eeac = 8,179,036 B) |
| `REPORT(transcript สูญ-UNVERIFIED, 2026-09-09) §3.2 (ไม่ได้ commit — UNVERIFIED ดู reference/README.md)` | Application class scan | ต้อง parse UTF-8 body (string-scan UTF-16 อย่างเดียวไม่เจอ) |

## 8. Provisioning checklist (ต่อ target+version)

1. [ ] identity + `/proc/0/cmdline` (§1)
2. [ ] sandbox path-hash binding (§2)
3. [ ] package.conf → parse → install components (§3, JSON reference)
4. [ ] jkl_key ลง `shared_prefs` (§4)
5. [ ] encrypted native module → decrypt → load (§5)
6. [ ] engine payload store + vdex + system conf (§6)

---
*Generated by System Architect. Source: `com.snakeOld.v56.23.2.zip`.
Regenerate JSON: `python3 tools/parse_packageconf.py <package.conf> --json reference/packageconf.<target>.json`.*
