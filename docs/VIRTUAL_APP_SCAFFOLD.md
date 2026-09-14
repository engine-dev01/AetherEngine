# Virtual-App Scaffold — Design (guest run-to-launch)

> เป้าหมาย: ให้ host `com.aether` รัน guest (8BP `com.miniclip.eightballpool`
> v56.23.2) **จนเข้าเกมได้จริง** โดยไม่เด้งออก — ต่อจาก commit `8fb5f35b`
> ที่ปิด crash loop ด้วยการ **defer `onCreate`** + firewall guest thread.
>
> เอกสารนี้เป็น blueprint ก่อนแตะ ART/ActivityThread internals (stop-before-code).
> อ้างอิงต้นแบบ `jv0.O2` (REPORT.md §7.2) + provisioning
> [`PROVISIONING_8BP_56.23.2.md`](PROVISIONING_8BP_56.23.2.md).

## 1. สถานะปัจจุบัน (หลัง 8fb5f35b)

flow เมื่อกดปุ่ม Sandbox → `ProxyActivity.P0.onCreate` (process `:p0`):
```
1. VirtualAppContainer.init(this, targetPkg) + setup()   ✅ path/class-map redirect
2. AetherOrchestrator self-attach + startEngine()         ✅ engine running
3. VirtualAppLoader.load(callOnCreate=false)              ⏸️ attach guest, onCreate deferred
4. finish()                                               ⚠️ ปิด activity ทันที
```
ผล: **ไม่เด้งออกแล้ว แต่ไม่เข้าเกม** — guest ถูก attach เฉยๆ ไม่ init และไม่มี UI ถูก start.

## 2. ทำไมเปิด onCreate ตรงๆ ไม่ได้ (root cause ที่พิสูจน์แล้ว)

crash 2 ครั้ง (frame `prFuM5...` = R8 guest, NPE `getSystemService(null)` บน
background thread ~3-7s): guest `Application.onCreate` → SDK initializers
(FB/Firebase/Unity/AppLovin) → spawn thread → อ่าน context/`currentApplication`
ที่ยัง**ชี้ host** หรือ **null** → NPE. firewall กันแอปตายได้ แต่เกมก็ไม่รัน.

→ ต้องเตรียม **virtualization scaffold** ให้ context ของ guest "จริง" ก่อน onCreate.

## 3. Scaffold 3 ชิ้น (เรียงตาม dependency)

### Scaffold-1 — currentApplication redirect
**ปัญหา:** guest SDK เรียก `ActivityThread.currentApplication()` /
`AppGlobals.getInitialApplication()` → ได้ host `com.aether` → context ผิด.

**ทำ:** reflection set field ใน singleton `ActivityThread`:
- `mInitialApplication` → guest Application
- `mAllApplications` (ArrayList) → add guest
- `AppGlobals`/`ActivityThread.sCurrentActivityThread.mInitialApplication`

**ไฟล์:** `VirtualAppLoader` (เพิ่ม `bindCurrentApplication(app)` — เรียกหลัง
`attachBaseContext`, ก่อน onCreate).

**ยืดหยุ่น:** guarded reflection + fallback ถ้า field ไม่มี (Android version ต่าง);
คืน boolean สถานะ ไม่ throw.

**verify:** ต้องทดสอบบนอุปกรณ์จริง (reflection ActivityThread ไม่มีใน CI).

### Scaffold-2 — guest ContentProvider install
**ปัญหา:** guest SDK (Firebase/FB/AppLovin/BidMachine/Vungle...) init ผ่าน
`ContentProvider.onCreate` (androidx-startup `InitializationProvider`) — ถ้าไม่ถูก
install, SDK `getInstance()` คืน null → NPE ตอน onCreate ของ Application/thread.

**ทำ:** port `jv0.R2→Q2` — อ่าน providers 20 ตัวจาก package.conf
([reference JSON](../reference/packageconf.8bp-56.23.2.json) `components.Provider`),
instantiate ผ่าน guest classLoader → `provider.attachInfo(guestCtx, providerInfo)`
(เรียก onCreate ให้ SDK init). ทำ **หลัง Scaffold-1** (provider ต้องเห็น
currentApplication = guest).

**ไฟล์:** `VirtualAppLoader.installProviders(guestCtx, providers)` +
ดึง list จาก `PackageConfParser` (มี field `providers` แล้ว).

**ยืดหยุ่น:** loop ต่อ provider มี try/catch เดี่ยว — ตัวไหน fail ไม่ล้มทั้งชุด;
log count สำเร็จ/ล้ม.

### Scaffold-3 — enable onCreate + start launcher activity
**หลัง 1+2 พร้อม:**
- `VirtualAppLoader.load(callOnCreate=true)` — เปิด guest Application.onCreate
- start `EightBallPoolActivity` (launcher จาก package.conf) แทน `finish()` เปล่า
  — ผ่าน guest classLoader + Instrumentation.newActivity หรือ Intent ที่ engine
  intercept.

**ไฟล์:** `ProxyActivity` (ไม่ finish ทันที; start guest activity),
`VirtualAppLoader` (flag).

**verify:** device-only. ถ้ายัง crash → เก็บ crash log ดู frame/thread ใหม่
(scaffold ไหนยังขาด).

## 4. ลำดับ commit (แต่ละอัน preflight + push แยก)

| step | scope | verify |
|---|---|---|
| S1 | `bindCurrentApplication` reflection | preflight + device |
| S2 | `installProviders` จาก package.conf | preflight + device |
| S3 | เปิด onCreate + start launcher | device (คาดว่าเข้าเกม) |

| step | scope | verify |
|---|---|---|
| S1 | `bindCurrentApplication` reflection | ✅ done (a5cb4c77) — device pending |
| S2 | `installProviders` จาก package.conf | ✅ done (a93f9ffd) — device pending |
| S3 | เปิด onCreate + start launcher | ✅ done — device (คาดว่าเข้าเกม) |

## ROOT-CAUSE REWRITE (แก้ที่ต้นเหตุ — เลิกวนลูปปลายเหตุ)

หลัง S4 พบว่า crash loop เกิดจาก **ต้นเหตุ 3 จุด** ไม่ใช่ activity hook:

1. **JNI descriptor mismatch** (nativeEntropy ()V vs ()J) → libaether.so
   โหลดไม่ได้ทั้งตัว → engine ตาย → guest ทุก scaffold รันบน engine ที่ตายแล้ว.
   แก้: Engine.kt return type + descriptor-aware G3 (ea2bdbc1).
2. **package.conf เป็น stub ปลอม** — `generatePackageConf` ใส่ `com.aether.AetherHostActivity`
   (component ของ engine) แทน manifest ของ target. ต้นแบบไม่มี MainActivity เลย
   (entry[1] = EightBallPoolActivity). แก้: **deprecate stub generator**, อ่าน
   guest manifest live จาก PackageManager (getPackageInfo GET_ACTIVITIES|
   GET_PROVIDERS + getLaunchIntentForPackage).
3. **fake Application สร้างไม่ได้** — `Proxy.newProxyInstance(Application)` throw
   "not an interface" (Application เป็น class). แก้: bind host Application ตรง;
   guest Application สร้าง+ผูก context โดย VirtualAppLoader.

## SANDBOX PATH MOVE
- sandbox root: `files/vision` → `vision` → **ปัจจุบัน `dataDir/root`**
  (P1 2026-09-14 เปลี่ยน `vision`→`root` ตาม blueprint L0 — ห้ามรื้อชื่อเก่ากลับ). ทุก caller
  (SandboxManager.init, AetherApp, AetherOrchestrator, CrashHandler) ชี้
  `context.dataDir/vision` ผ่าน SandboxManager.getSandboxRoot().

ทุก step: host/self mode (`targetPkg==com.aether`) ไม่เปลี่ยนพฤติกรรม;
firewall `:p0` คงไว้เป็น safety net; ถ้า scaffold ใด fail → fallback = defer
(สถานะเดิม ไม่เด้งออก).

## 5. ความเสี่ยง / ข้อจำกัด (ตรงไปตรงมา)

1. **ART/ActivityThread internals** ต่างตาม Android version — reflection ต้อง
   guarded + fallback. อุปกรณ์ทดสอบ = Android 16 (API 36).
2. **ยืนยันบน sandbox/CI ไม่ได้** — CI ตรวจแค่ compile + static. runtime ของ
   guest ต้องทดสอบบนเครื่องจริงที่ติดตั้ง 8BP 56.23.2.
3. guest บางตัวมี **anti-virtualization / integrity check** (8BP มี stamp
   `STAMP_TYPE_DISTRIBUTION_APK`) — อาจต้อง hook เพิ่มภายหลัง (นอก scope นี้).
4. scaffold นี้ทำให้ guest **รัน** — ยังไม่รวม native module decrypt (jkl_key →
   `libgame-BPM-...-3965.so`) ซึ่งเป็น scope แยก (PayloadStore).

---
*Design by System Architect. ทำตาม step S1→S2→S3; verify แต่ละ step บนอุปกรณ์
ก่อนไป step ถัดไป.*
