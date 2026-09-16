# PORT PLAN — Flutter UI (Snake → AetherEngine)

วันที่สร้าง: 2026-09-16
สถานะ: **หลักฐาน (evidence) — ยังไม่ได้เขียนโค้ด**
แหล่งหลักฐาน: `reference/SNAKE_extract/` (T1 = smali/res/java_out), `reference/snake/call_linkage.csv` (T1), `fragments/` (T1/T2)

---

## 1. โครงสร้าง channel ของ snake (ตรวจครบทุก hop จาก smali)

```
com.Entry (extends dt = FlutterActivity)
  ├─ onCreate
  │    ├─ c0()              → ขอ POST_NOTIFICATIONS (SDK 33+), requestCode 0x3ea
  │    ├─ a0()              → Firebase RemoteConfig fetch → Z() (version i)
  │    └─ b0()              → Handler.postDelayed(so, 1000ms) — permission loop
  │         └─ so.run()     → d0() ? g0() : h0()
  │              ├─ h0()    → ปุ่มเปิด NotificationListener → startActivityForResult(0x3e9)
  │              └─ g0()    → kd0(messenger, Entry.h) + setMethodCallHandler(this)
  │                   └─ C(method, args, result)   ← เมธอดเดียวรับ 11 keys
  ├─ onResume              → b0() รอบใหม่
  ├─ onActivityResult(0x3e8, 0x3e9) → เลือกรูป → crop → 128×128 → JPEG ≤ 0x2800
  ├─ onRequestPermissionsResult(0x3e9, 0x3ea) → เปิดเลือกรูป / เริ่ม VPN
  └─ onStop                → channel.invokeMethod(Entry.h, null)
```

Channel names (T1):
- `Entry.h` = **ช่อง UI shell** (11 คีย์ 1 ตัวอักษร) — ค่าเดิม `com.snake`
- `DaemonService.a()` = `"com.snake.snake_engine"` — channel ของ daemon
- `ne0.run` + `vx.f` = native heartbeat (POST)

---

## 2. 11 channel keys — พฤติกรรมเต็ม (จาก `com/Entry.smali` method C)

| Key | สิ่งที่ทำ (snake) | args | return | engine ตอนนี้ |
|-----|-------------------|------|--------|----------------|
| `"0"` | `Native.djp(I)[B` — ดึง payload ตาม **index** | Int | byte[] | ⛔ ไม่มี JNI `(I)[B` |
| `"A"` | `Toast.makeText(msg).show()` | String | — | ⛔ |
| `"B"` | `SharedPreferences.putAll(map).commit()` | Map<String,*> | — | ⛔ ไม่มี prefs |
| `"C"` | ปิดหน้าต่าง + `i0()` เปิดเลือกรูป (0x3e8) | — | — | ⛔ |
| `"D"` | `SharedPreferences.getString(key, "")` | String | String | ⛔ |
| `"E"` | `Intent(VIEW, Uri.parse(url))` | String | — | ⛔ (มีแค่ launchApp) |
| `"F"` | คืน `Entry.i` (version) | — | String | ⛔ |
| `"G"` | `yu0.A(pkg, mode)` / `yu0.C(pkg, mode)` — per-app VPN start/stop | String, Int | — | ⛔ ไม่ได้เชื่อม channel |
| `"H"` | `s2.b()[B` — handshake token | — | byte[] | ⛔ |
| `"I"` | ขนาดหน้าจอ `[x, y]` | — | List<Int> | ⛔ |
| `"J"` | `j0()` zip + FileProvider + createChooser แชร์ | — | — | ⛔ |

---

## 3. s2.b() — wire format ของ `"H"` (พิสูจน์แล้วจาก `s2.smali`)

```
s2 = factory: a(context, packageName)
  ├─ field a = packageName            (String)
  ├─ field b = signatures[0] verified (boolean — เช็คใน a())
  ├─ field c = versionCode            (int, จาก PackageInfo flags 0x40 = GET_SIGNATURES)
  ├─ field d = versionName            (String)
  └─ field e = SHA-256 ของ signature  (32 byte, จาก MessageDigest.digest(toByteArray()))

b()[B  =  serialization:
  [ len(a) | a bytes ][ len(d) | d bytes ][ 1 byte: b&0xff ][ 4 byte: c LE ][ 32 byte: e ]

c([BII)V = writeInt little-endian helper
```

---

## 4. รายละเอียดสำคัญของ keys (เก็บไว้ก่อนเขียน)

- **`"0"` (djp)** — F2 evidence: `native (I)[B djp` → expected export `Java_com_snake_helper_Native_djp`
  - caller = `ne0.run()` (polling timer: `ne0.o` = interval int, `ne0.p` = delay long, `ne0.q` = bool)
  - ne0 fields: `m` Activity, `n` String, `o` int, `p` long, `q` boolean
  - ne0.run → `Native.a(...)` → `Native.b(...)` → `vx.f(activity, s, i, j, z)` (POST heartbeat)
- **`"G"` (VPN)** — `yu0` singleton (h = INSTANCE)
  - `yu0.A(String, I)Z` → `qv0.t(String, I)Z` (system `VpnService.Builder` per-app)
  - `yu0.C(String, I)Z` = stop
  - `yu0.s(context)` = bindService; `kv0` = ServiceConnection abstraction; `m00` = **IPackageManager AIDL** (`package_manager` service)
- **`"F"` (version)** — `Entry.i` ถูก set โดย `Z()` จาก Firebase RemoteConfig (`i0` = OnSuccessListener)
  - **CUT โดยตั้งใจ** (C2/telemetry) → ใช้ `BuildConfig.VERSION_NAME` แทน
- **`"J"` (share)** — `j0()`: zip ไฟล์ใน cacheDir → `FileProvider.getUriForFile` → `ACTION_SEND` + `createChooser`
- **`"H"`** — `s2.a(context, packageName)` (GET_SIGNATURES 0x40) → `b()[B`
- **`"C"`/`"I"`** — `i0()` = image picker; `"I"` = `Resources.getDisplayMetrics()` → `[width, height]`

---

## 5. ส่วน res (100% เปรียบเทียบ)

| ประเภท | snake | engine | สรุป |
|--------|-------|--------|------|
| layout | `activity_launcher.xml`, `custom_dialog.xml` (+ lib: abc_/design_/m3_/mtrl_) | ไม่มีเลย (aether-app) | **ต้องเพิ่ม 2 custom** |
| mipmap | anydpi `ic_launcher.xml` (adaptive) + hdpi..xxxhdpi | hdpi png ×2 | **ต้องเพิ่ม adaptive** |
| values | strings.xml + styles.xml (LaunchTheme/NormalTheme + Material) | พื้นฐาน | **ต้องเพิ่ม LaunchTheme/NormalTheme** |
| anim/interpolator | หลายร้อยไฟล์ (lib) | ไม่มี | lib-provided ถ้าใส่ dependency แล้วมาเอง |
| xml | `file_paths.xml` | มีแล้ว ✅ | — |

### res ID map (จาก `res_out/res/values/public.xml` + `java_out/sources/com/snake/R.java`)
- `activity_launcher` = **0x7f0b001c** (layout)
- `custom_dialog`     = **0x7f0b001d** (layout)
- `iv_icon`           = **0x7f0800d1** (id)

### ⚠️ ข้อค้นพบสำคัญ: **ไม่มี call-site ของ layout ทั้งสอง**
- grep ทั้ง smali + java_out: `0x7f0b001c`, `0x7f0b001d`, `iv_icon`, `activity_launcher`, `custom_dialog`
  → ปรากฏ **เฉพาะใน R.java/public.xml** (นิยาม) ไม่มีการ `setContentView(0x7f0b001c)` หรือ `findViewById(iv_icon)` ใน code
- `dt.setContentView` รับ `View` (FlutterView) ไม่ใช่ layout res
- **สรุป**: `activity_launcher.xml` + `custom_dialog.xml` = **dead res** ใน snake เอง
  → ถ้าพอร์ตเข้ามาจะเป็น res ที่ไม่มี caller = ทำให้ call linkage ไม่เชื่อม
  → **decision: ยังไม่พอร์ต layout ทั้งสอง** จนกว่าจะเจอ call-site (หรือใช้เองเพื่อ splash ใหม่)

### layout ที่ใช้จริง
- `LaunchTheme` (styles.xml) → `@drawable/launch_background` (มีแล้วฝั่ง engine ✅)
- `NormalTheme` (styles.xml)

---

## 6. ความขัดแย้งหลัก: **engine มี 1 channel, snake มี 3**

| | snake | engine ตอนนี้ |
|---|---|---|
| UI shell channel | `entry_channel` (`Entry.h`) — 11 keys | ⛔ ไม่มี |
| diagnostic channel | — | `engine_bridge` (isTargetInstalled, getEngineStats, readMemory, scanAOB, nativeCompute, compressPayload, launchApp, launchInSandbox, readDiag, chainCheck, handshakeStatus) |
| daemon channel | `com.snake.snake_engine` | ⛔ ไม่มี |
| heartbeat | `ne0.run` → `vx.f` POST | ⛔ ไม่มี |

→ Dart ฝั่งเราเรียกแต่ channel วินิจฉัย — **UI shell ไม่มี call linkage เลย**

---

## 7. ขอบเขตการพอร์ต (ชุดเดียว, ไม่แก้ทีละจุด)

### สร้างใหม่ (4)
1. `app/android/app/src/main/kotlin/com/aether/ShellBridge.kt`
   - channel `entry_channel` — 11 keys ("0".."J") + onStop invokeMethod
   - `"0"` → `Engine.nativeDecompressByIndex(int)`
   - `"A"` → Toast
   - `"B"`/`"D"` → SharedPreferences putAll / getString
   - `"C"` → image picker (0x3e8)
   - `"E"` → Intent(VIEW, uri)
   - `"F"` → BuildConfig.VERSION_NAME (แทน Firebase RemoteConfig — CUT)
   - `"G"` → AetherVpnService start/stop (มีแล้ว แค่ต่อ)
   - `"H"` → s2.b() wire format (packageName, versionName, versionCode, sig SHA-256)
   - `"I"` → screen size [x, y]
   - `"J"` → zip + FileProvider + createChooser
2. `app/lib/services/shell_bridge.dart` — Dart wrapper สำหรับ `entry_channel`
3. `app/android/app/src/main/kotlin/com/aether/S2.kt` — คลาส handshake token (clone s2)
4. `app/android/app/src/main/res/mipmap-anydpi/ic_launcher.xml` — adaptive icon

### แก้ (3)
5. `app/android/app/src/main/kotlin/com/aether/AetherHostActivity.kt`
   - เพิ่ม `c0()` permission flow + `b0()` loop + `onActivityResult` (crop 128×128 JPEG ≤ 0x2800)
   - `onRequestPermissionsResult` (0x3e9, 0x3ea)
   - `onStop` → `channel.invokeMethod("entry_channel", null)`
6. `aether-core/src/main/kotlin/com/aether/Engine.kt` + `aether-native/src/main/cpp/aether_core.cpp`
   - `external fun nativeDecompressByIndex(index: Int): ByteArray?` — JNI `(I)[B`
7. `app/android/app/src/main/res/values/styles.xml` — เพิ่ม LaunchTheme/NormalTheme

### ตัดโดยตั้งใจ (documented deviations)
- `Z()` / `a0()` — Firebase RemoteConfig → ใช้ BuildConfig.VERSION_NAME
- `vx.f` POST heartbeat → URL ที่ถูกแทนที่เป็น `http://localhost/login_success` แล้ว
- `activity_launcher.xml` / `custom_dialog.xml` — **dead res ใน snake เอง** ไม่พอร์ต

---

## 8. ความเสี่ยง / สิ่งที่ต้องระวัง

1. **`djp(I)[B` semantics** — F2 บอกแค่ signature; ไม่มี F7 evidence ว่า index มาจาก payload table ฝั่งไหน
   - สมมติฐาน: index → payload entry ใน store ฝั่งเรา (PayloadStore)
   - **ถ้าผิด**: byte[] ผิด → Dart decode พัง → UI ขาว
2. **`"H"` signature verify** — s2 field `b` = signature match check (single-package mode)
   - ใน engine: verify ต้องเทียบกับ signature ของแอปเราเอง ไม่ใช่ของ guest
3. **`"G"` VPN** — `yu0` ใช้ `m00` = IPackageManager AIDL (system service)
   - ใน engine: ใช้ `AetherVpnService` ฝั่งเราที่มีอยู่ (VpnService.Builder) — แต่ต้องเช็คว่า per-app mode รองรับหรือไม่
4. **`"J"` FileProvider authority** — ต้องไม่ชนกับ authority ของ aether-app ฝั่งเดียวกัน
5. **channel name collision** — `entry_channel` vs `engine_bridge` ต้องไม่ทับซ้อน
6. **res ID ชน** — `iv_icon` 0x7f0800d1 อาจได้ ID อื่นหลัง rebuild ต้องใช้ `@id/iv_icon` เทียบชื่อ ไม่ใช่เทียบค่า

---

## 9. ลำดับดำเนินการ

1. บันทึกหลักฐานชุดนี้ (ไฟล์นี้) ✅
2. ขอ permission → เขียนชุดเดียว (สร้าง 4 + แก้ 3)
3. รัน gates ให้ครบ: preflight, full_compile, localTest, jni_parity, native_chain_parity, structural_gates, wire_contract_check, structure_coherence, snake_call_shape, evidence_conflict_scan
4. สรุป diff ให้ตรวจ
5. ขอ permission push (รอบเดียว) → CI รันเอง

---

## 10. หลักฐานที่ใช้อ้างอิง (reproduce)

```bash
# 11 keys
awk '/^\.method public C/,/^\.end method/' reference/SNAKE_extract/smali/com/Entry.smali | grep 'const-string'

# s2 wire format
sed -n '/^\.method public b()\[B/,/^\.end method/p' reference/SNAKE_extract/smali/androidx/appcompat/view/menu/s2.smali
sed -n '280,320p' reference/SNAKE_extract/smali/androidx/appcompat/view/menu/s2.smali   # c() LE int

# res ID map
grep -E '<public type="layout" name="(activity_launcher|custom_dialog)"' reference/SNAKE_extract/res_out/res/values/public.xml

# dead-res check (ต้องเจอแค่ใน R.java/public.xml)
grep -rn "0x7f0b001c\|0x7f0b001d\|iv_icon" reference/SNAKE_extract/smali reference/SNAKE_extract/java_out/sources

# channel names
grep -n "sput-object.*Lcom/Entry;->h:" reference/SNAKE_extract/smali/com/Entry.smali
grep -n "const-string p0, \"com.snake.snake_engine\"" reference/SNAKE_extract/smali/com/snake/helper/DaemonService.smali

# djp (F2)
grep -n "djp" reference/snake/F2_dex_natives.txt
grep -n "djp" reference/SNAKE_extract/java_out/sources/com/snake/R.java
```

---

## 11. สถานะ gates ก่อนพอร์ต (baseline, 2026-09-16)

| gate | rc |
|------|----|
| preflight | 0 |
| full_compile | 0 (3 modules OK) |
| localTest (test-stubs) | 4 tests OK |
| jni_parity | 0 |
| native_chain_parity | 0 |
| structural_gates | 0 |
| wire_contract_check | 0 |
| structure_coherence | 0 |
| snake_call_shape | 0 |
| evidence_conflict_scan | 0 |
