# สกัด Native Call-Sites + Chain Install&Launch (libengine.so)
สร้างอัตโนมัติ: extract_native_callsites.py · sources: /var/minis/workspace/snake_apk_extract/jadx_out/sources · /var/minis/shared/analysis/ghidra-extractjni-2026-09-12/output_extracted/output

## 1. Native methods (Native.java)
| method | args | call sites |
|---|---|---|
| `ac` | java.lang.Object obj, java.lang.Object obj2 | 1 |
| `aior` | java.lang.String str, java.lang.String str2 | 1 |
| `awl` | java.lang.String str | 1 |
| `chl` | byte[] bArr | 1 |
| `djp` | int i | 1 |
| `eio` |  | 1 |
| `i` | int i | 1 |
| `ic` | android.content.Context context | 2 |
| `ilil` | int i | 7 |
| `pjowqpxe` | java.lang.Object obj, java.lang.Object obj2, java.lang.Object obj3 | 0 |
| `update` | java.lang.Object obj, java.lang.reflect.Method method | 0 |

## 2. Call sites (file:line → role)
| native | class.fn | file:line | role |
|---|---|---|---|
| `ac` | b8.callActivityOnResume | androidx/appcompat/view/menu/b8.java:79 | Main(+Child shared) |
| `aior` | z10.b | androidx/appcompat/view/menu/z10.java:31 | Main(+Child shared) |
| `awl` | z10.a | androidx/appcompat/view/menu/z10.java:19 | Main(+Child shared) |
| `chl` | p60.uncaughtException | androidx/appcompat/view/menu/p60.java:28 | Main(+Child shared) |
| `djp` | Entry.C | com/Entry.java:20 | Main(+Child shared) |
| `eio` | z10.c | androidx/appcompat/view/menu/z10.java:86 | Main(+Child shared) |
| `i` | c.O2 | androidx/appcompat/view/menu/jv0.java:245 | ChildAppClient(:pN) |
| `ic` | yu0.f | androidx/appcompat/view/menu/yu0.java:150 | Main(+Child shared) |
| `ic` | yu0.f | androidx/appcompat/view/menu/yu0.java:155 | Main(+Child shared) |
| `ilil` | a.b | androidx/appcompat/view/menu/vx.java:48 | Main(+Child shared) |
| `ilil` | a.c | androidx/appcompat/view/menu/vx.java:76 | Main(+Child shared) |
| `ilil` | a.c | androidx/appcompat/view/menu/vx.java:77 | Main(+Child shared) |
| `ilil` | a.c | androidx/appcompat/view/menu/vx.java:87 | Main(+Child shared) |
| `ilil` | a.c | androidx/appcompat/view/menu/vx.java:91 | Main(+Child shared) |
| `ilil` | a.f | androidx/appcompat/view/menu/vx.java:137 | Main(+Child shared) |
| `ilil` | a.f | androidx/appcompat/view/menu/vx.java:137 | Main(+Child shared) |

## 3. Chain: ปุ่ม "G" → เกมรัน (ยืนยันจาก jadx แล้วทุก hop)
| # | จุด | ทำอะไร | process |
|---|---|---|---|
| 1 | Entry.java:64 | channel "G" (pkg,userId) — ปุ่มเริ่มเกม | Main |
| 2 | yu0.java:109 C() | u().l(pkg,uid) สร้าง intent MAIN+INFO→LAUNCHER | Main |
| 3 | qv0.java:78 l() | w()→m00.W0 [binder PMS เสมือน] resolve → setClassName(เกม) | Main→Server |
| 4 | yu0.java:118 F() | j().C(intent,uid) | Main |
| 5 | dv0.java:33 C() | g00.n0 → Parcel transact code=3 | Main→:engine |
| 6 | g00.java:543 | server onTransact case 3 → n0(intent,uid) | Server(:engine) |
| 7 | ev0.java:238 n0() | k(uid)→p41 → k.b.v(...) = r1.v | Server |
| 8 | r1.java:388 v() | x6.w2().n() resolve ภายใน; หา task/affinity → t()/u() | Server |
| 9 | r1.java:578 w() | a7.e().u(pkg,processName,...) จอง slot — throw "Unable to create process" ถ้ามัดไม่ได้ | Server |
| 10 | a7.java:292 u() | x6.w2().c0(pkg)=ApplicationInfo เสมือน → d7.b(uid,pkgIdx) key → reuse-slot หรือ l() เลือก 0..3 ที่ไม่ชนชื่อ process ที่รันอยู่ → new yj0; หมด → "No processes available" | Server |
| 11 | a7.java:171 m() | ★★ PROCESS-HANDSHAKE: p3 config → Bundle "SnakeEngine_client_config" → ContentResolver.call(content://com.snake.proxy_content_provider_N, "_Engine_|_init_process_") — Android spawn :pN เองเพราะ provider ถูก touch | Server→Android |
| 12 | ProxyContentProvider.java:15 | [:pN] call() → jv0.B2().P2(p3) เก็บ config+ตรวจ pkg (Reject init process) → ตอบ Bundle "_Engine_|_client_" = asBinder() ของ jv0 (child→server channel, linkToDeath คุมชีพ) | Child(:pN) |
| 13 | a7.java:333 | h(ctx,"com.snake:P"+slot) → pid จริงลง yj0.q (getRunningAppProcesses) | Server |
| 14 | r1.java:155 k() | kl0.a/d(r9) = "…helper.ProxyActivity$P%d[_L]" (หรือ Transparent เมื่อ theme ดัน) → component = com.snake/Stub | Server |
| 15 | il0.java:21 b() | putExtra _S_|_user_id_/_activity_info_/_target_/_activity_token_v_ (real intent ซ่อนใน stub) | Server |
| 16 | r1.java:361 t() | flags 134217728|524288|268435456 → yu0.m().startActivity(stub) — ★AMส จริง (message จะถูก re-queue จนกว่า bound) | Server→Android |
| 17 | yu0.f() 150/155 | App.attachBaseContext: Main→Native.ic; Child→Native.ic; Server ไม่เรียก — แล้ว iz.d() ลงทะเบียน 57 callbacks | Main+Child |
| 18 | jv0.O2:273 / my | H-callback "my" ฝังที่ mH.mCallback (ny.b.e(d(),this)) — switch t1.d.* = LAUNCH/EXECUTE_TRANSACTION → h(obj): ไม่ bound→เก็บ/re-queue; bound→rewrite ClientTransaction fields →放行 | Child(:pN) |
| 19 | jv0.java:427 x2() | main-looper bound ผ่าน ConditionVariable (U2→O2) — ทุก binder thread รอตรงนี้ | Child |
| 20 | jv0.java:211 O2() | u().m(pkg,8)→PackageInfo เสมือน → z2(appInfo)=ClassLoader เกม → ★t1.b.* เขียน AppBindData (c2=t1.f.c(AT)=mBoundApplication): b=appInfo c=info d=instrumentationName e=processName(←str2 guest) f=providers → a3 → m90.i.b=newApplication → zh.a → callApplicationOnCreate → iz.c().b(my.class) | Child |
| 21 | jv0.java:245 | Native.i(SDK_INT) ★ native จุดเดียวใน bind path | Child |
| 22 | p3 identity | jv0.E2()=pkg(m) G2/F2()=slot(o) H2()=Application(m) N2()=userId(r) J2()=q(UID?) — ทุก getter ใน child อ่าน p3 ไม่ใช่ framework → guest SDK เห็น com.miniclip ทั้งหมด | Child |
| 23 | b8.java:79 | Native.ac(activity, getDeclaredMethod("pjowqpxe",…)) — hook ใส่ activity | Child |
| 24 | Native.update(obj,Method) | มีจริงใน Native.java + MethodUtils แปลง Method→sig; แต่ call site ฝั่ง hidden-dex ยังไม่ถูกจับได้แบบ dynamic = สมมติฐานรอพิสูจน์ (vdex×5 ไม่ใช่หลักฐาน — ดู EVIDENCE_CHAIN §3.6 แก้ไข) | Child? |

## 4. Ghidra anchors (libengine.so)
- JNI_OnLoad: **@ 0x1f3fa0, 12,388 B** (export เดียวที่โผล่ใน dynsym)
- .init_array: 44 entries — ตัวใหญ่ entry_37 @ 001794ac (9.3 KB decompile, VMP jumptable)
- RegisterNatives xrefs: ไม่พบ (indirect/obfuscated — ตรงกับบันทึก)
- Java_* strings: ว่าง → ลงทะเบียน natives แบบ dynamic (ยืนยันใน EVIDENCE_CHAIN §4)
- callgraph 1-hop จาก JNI_OnLoad: ['rand', 'FUN_0091ad58', '__stack_chk_fail', 'strlen', 'sysconf']
- capability strings (syscall ที่ lib ใช้): ['__system_property_get', 'dl_iterate_phdr', 'getauxval', 'mprotect', 'process_vm_readv', 'sigaction']

## 5. ข้อสรุปเชิงโครงสร้าง
- **t1 field handles (go0 reflection cache — ยืนยันด้วย t1.java:52-57,108):**
  `t1.f` = ActivityThread.mBoundApplication · `t1.b.e` = **AppBindData.processName** ·
  `t1.b.d` = instrumentationName · `t1.b.b/c/f` = appInfo/info/providers ·
  `t1.d.c/d` = H.EXECUTE_TRANSACTION/LAUNCH_ACTIVITY · `t1.g` = mH
  → jv0.O2 เขียน processName = ชื่อ guest ลง AppBindData ก่อน newApplication —
  **นี่คือ field เดียวกับที่ patch bc55e7e ของเราเล็ง (mBoundApplication.processName)**
  หลักฐานนี้เลื่อน spoof ของเราจาก "อาการ" → "ตรงกลไก SNAKE" (แต่ sCache 48 services
  ยังขาดอยู่ — spoof แค่ชิ้น identity เดียว)
- **p3 field map** (จาก yj0.a() builder: a7 slot allocator → bundle → child):
  `m`=guest pkg (=E2() ที่ guest SDK อ่าน), `n`=processName, `o`=slot 0..3 (F2()),
  `p`=guest UID จาก x6.y2(pkg) (J2(); I2()=d7.a() แยก idx), `q`=server myUid,
  `r`=userId (N2()), `s`=caller token (`t` ของ yj0 = f(callingPid,pkg) — ตัว K2() อ่านตัวนี้) — โครง identity ทั้งหมดของ child **มาจาก bundle
  นี้ชุดเดียว** ไม่ใช่จาก framework → guest SDK ทุกตัวเห็น `com.miniclip.eightballpool`
  + virtual UID ตรงกันหมด = ไม่มี call ไหน UID-host+ชื่อ-guest ชน system จริง
- Native ที่ **จำเป็นต่อ chain รันเกม** = `ic`(ctx init ทุก process), `i`(SDK_INT ตอน O2),
  `ac`+`pjowqpxe`(method-hook proxy ที่ activity) — `update` = rอลู่พิสูจน์ — ที่เหลือ (`chl`,`djp`,
  `ilil`,`awl`,`aior`,`eio`) = license/key/protection branch → **ตัดตามคำสั่ง**
- `Native.gcuid(pid)` = virtual-UID mapper ฝั่ง binder: pid → u().r(pid) → slot uid —
  ทำหน้าที่ "แปลตัวตน" ให้ system checks (จุดที่ Aether ไม่มี =SecurityException ทั้งวง)
- ★ กลไกที่ "ทำให้เกมถูกรัน" ที่แท้จริง = **ContentProvider handshake** (a7.m →
  ProxyContentProvider.call `_Engine_|_init_process_`): Android เป็นฝ่าย spawn :pN
  เองเมื่อ provider ถูก touch — child ตอบกลับด้วย IBinder ของตัวเอง = channel คุมชีพ
  (linkToDeath) + config p3 ฝัง processName/slot/uid มาใน bundle ตั้งแต่เกิด
- AMS จริงถูกเรียก 2 ครั้ง: (1) `r1.t()` → `startActivity(stub)` (2) ระบบเปิด :pN จาก
  provider call — ครั้งที่ 2 คือจุดที่ process identity ถูกกำหนด **จริงที่ framework**
  ไม่ใช่จาก code ฝั่ง app เลย (= คำตอบว่าทำไม SNAKE ไม่ต้อง spoof mBoundApplication)
- การ slot: l() เลือก 0..3 ที่ไม่ตรงกับชื่อ process ที่กำลังรันอยู่; d7.b(uid, pkgIdx)
  = กุญแจ map ต่อ user → ซ้อนได้หลายเกม/ผู้ใช้
- `jv0.O2` (bind เกม) ถูกเรียกจาก my.h() (H-hook) หรือ b1/x3 path — ก่อน O2 สำเร็จ
  ทุก intent launch message จะถูก re-queue (sendMessageAtFrontOfQueue) จนกว่า bound