# Snake call_linkage → AetherEngine  alignment (audit 2026-09-16)

เป้า: 11 chains (CH-01..CH-11, 691 edges / L1-L6) จาก `reference/CALL_LINKAGE.md` + T1 fragments `reference/snake/F1..F8` → ต้องอยู่ใน engine แบบสอดคล้อง
Ground truth counts (verified against T1): **custom natives = 13** (F2), **manifest components = 51** (F3:195), engine `SERVICE_*` const = 40.

## สรุปสังคม (T1 หลักฐานจริงทั้งหมด)

| chain | T1 หลัก | engine มีหรือไม่ | gaps |
|---|---|---|---|
| CH-01 (launch) | nativeInitContext(ctx) — `jv0.f` bind path | ✅มี Kotlin call-site (`GuestRuntime`) + native stub | nativeInitContext = **LOGD stub** (AETHER-NOOP: ic body ยังไม่ port) |
| CH-02 (register) | 13 custom natives (F2) → RegisterNatives | ✅ our 26 externals ↔ 26 table entries (jni_parity PASS) | natives cut per docs/CUTS.md — still no init_array/constructor (P1) |
| CH-03 (launcher) | trampoline redirect entry | ✅ Kotlin `AetherInstrumentation.callActivityOnResume` | trampoline ไม่มี RWX+branch-synth (jni_hook.cpp:3 บอกเป็น INTENT) |
| CH-04 (JNI_OnLoad) | JNI_OnLoad → nativeSetSeed | ✅ มี `nativeSetSeed` + Kotlin caller | - |
| CH-05 (sCache) | sCache 11/11 services | ✅ `ServiceBinderProxy.listProxiedServices()` | mActivities/mProviderMap ไม่มี (สอดคล้องกับ blueprint P2 ค้าง) |
| CH-06 (hook) | 4 slots jni_hook: GetMethodID/CallObjectMethod/VirtualCall/Static | ✅ `jni_hook.cpp`, LOGD stub bodies | LOGD stub bodies (AETHER-NOOP ac/pjowqpxe) |
| CH-07 (dexopt/vdex) | vdex baseline | ✅ `nativeOffset` consumer, VirtualFS | Flutter DEX route 0 hits (สอดคล้องกับสถาปัตยกรรมใหม่ — Kotlin reimplementation) |
| CH-08 (native) | svc/syscall mprotect | ✅ `Stealth::ptrace` + `aob_scanner` | **ไม่มี svc/syscall/mprotect ทั้งหมด** (jni_hook.cpp:3 ระบุตั้งใจ) + rootspoof orphan (CMake stale include) |
| CH-09 (content provider) | :p0..:p3 ProxyActivity$Pn | ✅ manifest **51 components** (F3 T1) / slot=4 (S2 OK) | - |
| CH-10 (dart bridge) | MethodChannel invoke | ✅ `app/lib/screens/home_screen.dart` + `EngineBridge.kt` | - |
| CH-11 (persist) | package.conf | ✅ `nativeResolvePath`, `Config`, `package.conf` consumer | - |

## native binding ครบถ้วน (26/26 descriptors match) — jni_parity PASS

```
declared external : 26  | registered JNINativeMethod : 26  | descriptor mismatch : 0
```

## native-chain parity (native_chain_parity.py) — สถานะจริง

```
snake ac sig=MATCH  our nativeProcessPair  table=OK kt=OK
snake pjowqpxe      our nativeProcessTriple table=OK kt=OK callers=0  (WARN D6 — snake caller อยู่ hidden dex)
snake update        our nativeReflectUpdate  table=OK kt=OK callers=0  (WARN D7 — B4 internal, รอตัดสินใจ)
```

GATE ปิด PASS — ทุก hop chain-required มี T1 sig ตรง + table + kt (WARN 2 ตัวเป็น NO-OP marker ไม่ใช่ silent)

## 3 ประเภท dead code (ยืนยัน T1 + comment marker)

1. **LOG-ONLY/stub bodies** (ทิ้งค่า):
   - nativeProcessPair (`LOGD("processPair")`), nativeProcessTriple, nativeReflectUpdate, nativeInitContext
   - มีมากก่อนหน้านี้เป็น AETHER-NOOP / AETHER-NOOP(ac) / AETHER-NOOP(pjowqpxe) / AETHER-NOOP(update) / AETHER-NOOP(ic)

2. **CUT-13** (declared-but-unfed, docs/CUTS.md):
   nativeValidate, nativeExchangeKeys, nativeDeriveKey, nativeEntropy, decryptString,
   hideXposed, installNetworkHttpProbe, loadEmptyDex, nativeDecryptPayloadByHash,
   setAccessible x2 — ทุกตัว 0 caller ทั้ง Kotlin+Dart (ยืนยัน code search):

3. **nativeOffset dead-feed** (bug ซ่อน):
   Kotlin `AetherOrchestrator:103` เรียก `nativeOffset()` **แต่ทิ้งค่า** (ไม่กำหน่อยในตัวแปร)
   + `Config::setOffset/applyOffsets` มี body แต่ **0 caller** → DB ว่าง → nativeOffset() คืน 0 เสมอ
   → ความสัมพันธ์ตำแหน่งของสตีเว่น connect แต่ข้อมูลต่อเนื่องล้มเหลว (สะสมค่าใน config.cpp เท่านั้น)

## ระบบที่ทำงานแล้วจริง (สถานะจริง)

- CH-06 jni_hook: 4 slots (GetMethodID/CallObjectMethod/VA/Static) ✅มี
  - stub bodies มี LOGD (ทำให้เห็นว่า hop มาถึงแต่ไม่ได้ทำงาน)
- CH-05 sCache: 11/11 services ✅ (ServiceBinderProxy:19 listProxiedServices())
- CH-09 manifest: **51 components** (F3:195), slot=4 ตรงกับ MAX_SLOTS=4 ✅ (S2)
- Module topology: `:aether-android` → `aether-android/aether-app`, CMake มี include stale dir แต่ TU ครบ 14 ไฟล์ไม่มี orphan ✅
- Preflight ท้องถิ่ม: 10 pass / 2 fail
  - FAIL 1: structural_gates.py **CRASH** (UnicodeDecodeError อ่าน reference/**/AndroidManifest.xml เป็น AXML binary) — gate fail-open ไม่ได้ทำงาน
  - WARN 1: native_chain_parity มี 2 WARN (D6/D7) — เป็น NO-OP marker ไม่ใช่ silent

## สิ่งที่ต้องทำต่อ (ไม่ใช่อะไรที่จะ commitแล้ว)

1. **S-NATIVE-1** (CH-08): เติม `svc`/`mprotect` hook bodies — นี่คือที่มาของ `nativeFindModuleBase/nativeScanAOB` ที่มี bodyจริงแต่ไม่ถูก hook เรียกใช้
2. **S-NATIVE-2** (CH-06): เติม trampoline bodies ให้ hook_table slots แทน LOGD stub
3. **S-KOTLIN-1** (CH-02 init): เติม `init_array`/constructor ใน JNI_OnLoad พร้อม hook_table install
4. **S-OFFSET-1**: เชื่อม Config offsets → package.conf จริง แทนการเรียก + ทิ้งค่า
5. **G3c-fix**: แก้ structural_gates.py ให้ข้าม binary manifest ใน reference/ (open ที่ error-handling)

หมายเหตุ: ทุกการอ้างอิงในตารางนี้เป็นค่าจริงอ่านจากโค้ด ไม่ใช่การอ้างอิงจากบัญทึกของผม (พบข้อผิดพลาดในสรุปเช้า: nativeCompute มี callerใน app/android debug hex dump ไม่ใช่ SILENT NO-OP)
