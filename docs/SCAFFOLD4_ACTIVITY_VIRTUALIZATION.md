# Scaffold-4 — Activity Virtualization (Instrumentation hook)

> **ทำไมเราวนลูป**: S1-S3 โหลด guest *Application* เข้า `:p0` สำเร็จ แต่ตอน
> เปิด UI ใช้ `Intent().setClassName(targetPkg, launcher)` + `startActivity`
> → Android AMS route ออกไปเปิด **8BP process จริงข้างนอก** (เพราะ activity
> ของ 8BP อยู่ใน manifest ของ 8BP ที่ติดตั้งจริง ไม่ใช่ของเรา).
>
> = ทุกครั้งที่ "startActivity ด้วย target package/class ตรงๆ" AMS จะเปิดแอปจริง.
> นี่คือคอขวดที่ทำให้ virtual ไม่เกิด.

## 1. กลไกต้นแบบ (จาก AetherMind .md — verified, ไม่เดา)

- `classes_dex_analysis.md §6`: `ProxyActivity$P0–$P3` (+`$Pn_L`) = **stub component
  ที่สวมเป็น component ของแอปเป้าหมาย** — guest activity ไม่ได้ถูก start ตรง
- `classes_dex_analysis.md §3`: `Native.update(Object, Method)` = **ART method hook
  bridge** (คู่ `MethodUtils`) — swap method ฝั่ง native
- `libengine_analysis.md §3 + เพดาน static`: ART hook body = **RWX runtime-generated,
  resolve static ไม่ได้** → เรามีแค่ "กลไก" ไม่มี byte-level

→ กลไกหลัก = **stub activity ใน manifest ของ host + swap activity class ตอน launch**.
ต้นแบบทำ swap ผ่าน native ART hook (resolve ไม่ได้). เราทำแบบ **equivalent ด้วย
public reflection** (Instrumentation hook) — ผลเดียวกัน ไม่ต้องเดา native internals.

## 2. รูปแบบมาตรฐาน (VirtualApp / DroidPlugin pattern)

Activity virtualization ทำ 2 hook บน `ActivityThread.mInstrumentation`:

```
launch flow (guest activity G, stub activity S=ProxyActivity$P0 ที่อยู่ใน manifest เรา):

[1] startActivity(intent→G)
      │
[2] Instrumentation.execStartActivity (hook A):
      - เก็บ intent จริง (→G) ไว้ใน extra
      - แทน component เป็น S (stub ที่ AMS รู้จัก)   ← AMS เห็น S เลยไม่ route ออก
      │
[3] AMS launch S ใน process :p0 (S ประกาศ android:process=":p0")
      │
[4] Instrumentation.newActivity (hook B):
      - ถ้า className == S และมี extra intent→G:
          → สร้าง instance ของ G จริง (guest classloader) แทน S
      - guest Activity G ถูก instantiate + รัน lifecycle ใน :p0
```

- hook A กัน AMS route ออก (AMS เห็นแต่ stub ที่ registered)
- hook B swap กลับเป็น guest activity ตอน ActivityThread สร้าง instance
- **ทั้งคู่เป็น method ของ `android.app.Instrumentation`** — replace ผ่าน
  `ActivityThread.mInstrumentation` (reflection)

## 3. Reflection surface (ระบุ public/hidden ชัด — ไม่เดา)

| target | signature | ชนิด | เสถียร |
|---|---|---|---|
| `Instrumentation.newActivity` | `(ClassLoader, String, Intent): Activity` | **public API** (API 1+) | ✅ override ตรง |
| `Instrumentation.callActivityOnCreate` | `(Activity, Bundle): void` | **public API** | ✅ |
| `Instrumentation.execStartActivity` | `(Context, IBinder, IBinder, Activity, Intent, int, Bundle): ActivityResult` | **hidden, stable** ตั้งแต่ API 16 | ⚠️ reflection + guard |
| `ActivityThread.currentActivityThread()` | `(): ActivityThread` | hidden static | ✅ (มีใน VirtualAppLoader แล้ว) |
| `ActivityThread.mInstrumentation` | field | hidden | ✅ (findField มีแล้ว) |

**หลักการกัน version drift**: `AetherInstrumentation` เป็น subclass ของ
`Instrumentation` → override เฉพาะ 2 method public (`newActivity`,
`callActivityOnCreate`); `execStartActivity` เรียก **ผ่าน reflection ของ base**
(ไม่ override signature ตรง แต่ intercept ผ่าน delegate) — ถ้า signature ต่างตาม
version, guard จับแล้ว fallback = ไม่ intercept (ไม่ crash).

## 4. Component ที่มีอยู่แล้ว (ใช้ต่อ — ไม่สร้างใหม่)

- `ProxyActivity$P0..P3` — มี, `android:process=":p0..:p3"`, exported (manifest:151-154)
  → ใช้เป็น **stub target** ของ hook A
- `VirtualAppLoader` — โหลด guest Application + S1(currentApplication) + S2(providers)
  → ยังใช้; ต่อ instance guest activity เข้า Application นี้
- `PackageConfParser` — ให้ launcher activity + component list

## 5. สิ่งที่ต้องสร้าง / แก้ (scope S4)

| ไฟล์ | งาน | commit |
|---|---|---|
| `AetherInstrumentation.kt` (ใหม่) | subclass Instrumentation: hook A (execStartActivity) + hook B (newActivity) + install() replace mInstrumentation | S4.1 |
| `ProxyActivity.kt` | เลิกใช้ `setClassName(targetPkg)`; แทนด้วย start stub + carry guest intent; ให้ AetherInstrumentation swap | S4.2 |
| `VirtualAppLoader.kt` | expose guest classLoader + LoadedApk ให้ newActivity ใช้สร้าง guest activity | S4.2 |

## 6. ข้อจำกัด / verify (ตรงไปตรงมา — ไม่เดา)

1. `execStartActivity` เป็น hidden API — reflection + guard; ถ้า Android 16
   เปลี่ยน signature → fallback ไม่ intercept (log + ไม่ crash). **verify: device logcat.**
2. guest activity ที่ไม่ได้อยู่ใน host manifest → ต้องพึ่ง hook B ล้วน;
   theme/window ใช้ของ stub (`ProxyActivity$P0`). guest ที่ต้อง theme เฉพาะอาจเพี้ยน.
3. resources/assets ของ guest — activity ใช้ `getResources()` ต้องชี้ guest APK;
   S4 นี้ครอบ activity instantiation + lifecycle; **resource redirect = scope
   ถัดไป (S5)** ถ้า guest แสดงผลผิด.
4. ยืนยัน runtime บนอุปกรณ์เท่านั้น (CI = compile+static).

## 7. ลำดับ

- S4.1: `AetherInstrumentation` + install (commit + preflight)
- S4.2: rewire launch (ProxyActivity เลิก route ออก) (commit + preflight)
- ทดสอบ device: กด Sandbox → guest activity ต้องขึ้น **ใน task ของ com.aether**
  (ไม่ใช่ 8BP process จริง). ถ้า resource เพี้ยน → S5 (resource redirect).

---
*Design by System Architect. กลไกจาก AetherMind .md (stub+swap); implementation =
public Instrumentation hook (ไม่แตะ native ART ที่ resolve static ไม่ได้ = ไม่เดา).*
