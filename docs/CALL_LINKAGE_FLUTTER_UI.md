# CALL LINKAGE — Flutter UI (snake 11 keys, T1-backed)

วันที่: 2026-09-16
แหล่ง: `reference/SNAKE_extract/smali/` (T1), `reference/snake/F2_dex_natives.txt`, `reference/snake/call_linkage.csv`
ไฟล์หลักฐานหลัก: [PORT_PLAN_FLUTTER_UI.md](../docs/PORT_PLAN_FLUTTER_UI.md)

---

## 0. คำจำกัดความ "call linkage ที่เชื่อมจริง"

```
caller (Kotlin/Dart)  →  channel key  →  handler  →  native/JNI  →  body จริง
```

ต้องครบทุก hop. ถ้ามี hop ที่เป็น **stub / LOGD / ไม่มี caller** = ไม่เชื่อม (dead segment).

---

## 1. Hop แรก: Activity → channel (snake)

| hop | สิ่งที่ทำ | smali ที่ |
|-----|----------|-----------|
| `Entry.onCreate` | `c0()` (perm 0x3ea) + `a0()` (RemoteConfig) + `b0()` | `com/Entry.smali` |
| `b0()` | `Handler.postDelayed(so, 0x3e8)` | |
| `so.run()` | `d0()` ? → `g0()` : `h0()` | `androidx/appcompat/view/menu/so.smali:26` |
| `g0()` | `new kd0(messenger, Entry.h)` + `setMethodCallHandler(this)` | `com/Entry.smali` |
| `C(...)` | `method.equals("0")` … `"J"` → dispatch | `com/Entry.smali` |

**engine ปัจจุบัน**: `AetherHostActivity` สร้าง `EngineBridge` (channel `engine_bridge`) —
เป็น **diagnostic channel** (isTargetInstalled/getEngineStats/readMemory/...) **ไม่ใช่ shell channel**
→ 11 keys ของ snake ไม่มี hop แรกใน engine เลย

---

## 2. Hop ที่ 2: channel key → native (snake)

| key | callee (smali ref) | native? | engine มี callee? |
|-----|---------------------|---------|-------------------|
| `"0"` | `Lcom/snake/helper/Native;->djp(I)[B` | ✅ native | ⛔ ไม่มี `(I)[B` (มีแค่ `nativeDecompressPayload(ByteArray)`) |
| `"A"` | `Toast.makeText` | — | ⛔ |
| `"B"` | `SharedPreferences.putAll` | — | ⛔ |
| `"C"` | `i0()` → `startActivityForResult(0x3e8)` | — | ⛔ |
| `"D"` | `SharedPreferences.getString` | — | ⛔ |
| `"E"` | `Intent(VIEW, uri)` | — | ⛔ |
| `"F"` | `Entry.i` (RemoteConfig) | — | ⛔ |
| `"G"` | `yu0.A/C` → `qv0.t` → `VpnService.Builder` | — | ✅ class มี (`AetherVpnService`) ⛔ ไม่ได้ต่อ channel |
| `"H"` | `s2.b()[B` | — | ⛔ |
| `"I"` | `Resources.getDisplayMetrics` | — | ⛔ |
| `"J"` | `j0()` → zip + FileProvider + createChooser | — | ⛔ |

---

## 3. Hop ที่ 3: native caller chain (snake, `"0"`)

```
ne0.run()                        (polling timer, fields m/n/o/p/q)
  → Native.a(Activity, String, I, J, Z)
  → Native.b(...)
  → vx.f(...)                     ← POST heartbeat (URL ถูกแทนเป็น localhost แล้ว)
  + ระหว่างทางเรียก Native.djp(I)[B   ← payload ตาม index
```

**engine ปัจจุบัน**: `ne0`/`vx` ไม่มี analog. `djp` ไม่มี JNI.
→ hop 3 ขาดทั้งหมด

---

## 4. ตาราง linkage ที่ต้องสร้า (target state)

| key | engine handler | ไปไหนต่อ | สถานะหลังพอร์ต |
|-----|----------------|-----------|-----------------|
| `"0"` | `ShellBridge` | `Engine.nativeDecompressByIndex(I)` → JNI → PayloadStore | ✅ เชื่อมใหม่ |
| `"A"` | `ShellBridge` | Toast | ✅ ใหม่ |
| `"B"` | `ShellBridge` | SharedPreferences.putAll | ✅ ใหม่ |
| `"C"` | `ShellBridge` → `AetherHostActivity` | image picker (0x3e8) | ✅ ใหม่ |
| `"D"` | `ShellBridge` | SharedPreferences.getString | ✅ ใหม่ |
| `"E"` | `ShellBridge` | Intent(VIEW) | ✅ ใหม่ |
| `"F"` | `ShellBridge` | BuildConfig.VERSION_NAME (แทน RemoteConfig) | ✅ ใหม่ |
| `"G"` | `ShellBridge` | AetherVpnService.start/stop | ✅ เชื่อมกับของมีอยู่ |
| `"H"` | `ShellBridge` | S2.b() handshake | ✅ ใหม่ |
| `"I"` | `ShellBridge` | DisplayMetrics | ✅ ใหม่ |
| `"J"` | `ShellBridge` | j0() zip+share | ✅ ใหม่ |

---

## 5. สิ่งที่ตัดออก (documented, ไม่ใช่ missing)

| ส่วน | เหตุผล |
|------|--------|
| `Z()` / `a0()` RemoteConfig | C2/telemetry — CUT |
| `vx.f` POST ออกเน็ต | URL → `http://localhost/login_success` แล้ว |
| `activity_launcher.xml` / `custom_dialog.xml` | **dead res ใน snake เอง** (ไม่มี call-site) |
| heartbeat loop `ne0` | แทนด้วย channel invokeMethod ตามที่ต้องการในอนาคต |

---

## 6. reproduce

```bash
# hop 1
awk '/^\.method public final synthetic g0/,/^\.end method/' reference/SNAKE_extract/smali/com/Entry.smali

# hop 2 — ทุกคีย์
awk '/^\.method public C/,/^\.end method/' reference/SNAKE_extract/smali/com/Entry.smali | grep 'const-string [vp][0-9], "[0-9A-Z]"'

# hop 3 — djp
sed -n '42,70p' reference/SNAKE_extract/smali/androidx/appcompat/view/menu/ne0.smali
grep -n "djp" reference/snake/F2_dex_natives.txt
```
