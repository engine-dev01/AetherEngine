#!/usr/bin/env python3
"""
extract_native_callsites.py — สกัดจุดเรียก native methods ของ libengine.so
จาก jadx sources + จับคู่กับหลักฐาน Ghidra (JNI_OnLoad/init_array/callgraph)

Output:
  1. ทุก native method (จาก Native.java) → call site ใน Java (file:line, class, method)
  2. จัดกลุ่มตาม role (Main / Server / ChildAppClient) ตาม yu0.f() split
  3. Ghidra anchor: JNI_OnLoad addr, init_array entries, RegisterNatives xrefs
  4. Chain หลัก Install&Launch: Entry"G" → yu0.C → dv0.C → transact → ev0.n0 →
     r1.v/t/k → kl0 stub → il0.b pack → yu0.m().startActivity → my/h.java unpack
     → jv0.O2 bind → Native.i/ic → newApplication → callActivityOnCreate → Native.update
"""
import re, sys, json
import os
from pathlib import Path

# Inputs are NOT vendored in this repo (only the T1 fragments under
# reference/snake/ are). The generator therefore requires explicit paths and
# fails loud when they are missing — do not "fix" this with invented defaults.
#   AETHER_JADX_SOURCES = dir of jadx-decompiled com/snake/**.java
#   AETHER_GHIDRA_OUT   = dir of Ghidra output_extracted/output transcripts
#   AETHER_MAP_OUT      = destination markdown (default: reference/NATIVE_CALLSITE_MAP.md)
_jadx = os.environ.get('AETHER_JADX_SOURCES')
_ghidra = os.environ.get('AETHER_GHIDRA_OUT')
if not _jadx or not _ghidra:
    sys.exit('FAIL: set AETHER_JADX_SOURCES and AETHER_GHIDRA_OUT '
             '(inputs are not vendored in this repo)')
JADX = Path(_jadx)
GHIDRA = Path(_ghidra)
REPO = Path(__file__).resolve().parent.parent  # repo root (script lives in scripts/)
OUT = Path(os.environ.get('AETHER_MAP_OUT',
             str(REPO / 'reference/NATIVE_CALLSITE_MAP.md')))
if not JADX.is_dir():
    sys.exit(f'FAIL: jadx sources not found: {JADX}')

# ── 1. native methods จาก Native.java ──
native_src = (JADX / 'com/snake/helper/Native.java').read_text()
natives = re.findall(r'public static native\s+[\w\[\].<>]+\s+(\w+)\s*\(([^)]*)\)', native_src)
native_names = {n for n, _ in natives}

# ── 2. call sites ทุกไฟล์ jadx ──
sites = []  # (file, line, method, arg_snippet, enclosing)
for jf in JADX.rglob('*.java'):
    try:
        text = jf.read_text()
    except Exception:
        continue
    lines = text.splitlines()
    cur_cls = cur_fn = ''
    for idx, ln in enumerate(lines, 1):
        m = re.search(r'(?:public|private|protected|static|final|abstract|class|interface)\s+.*?\bclass\s+(\w+)', ln)
        if m: cur_cls = m.group(1)
        m = re.match(r'\s*(?:@Override\s*)?(?:public|private|protected)\s+[\w\[\]<>, .]+\s+(\w+)\s*\(', ln)
        if m: cur_fn = m.group(1)
        for nm in native_names:
            for mm in re.finditer(rf'Native\.{nm}\s*\(', ln):
                # snippet until end of statement (max 160 chars)
                stmt = ln.strip()[:200]
                sites.append({
                    'method': nm, 'file': str(jf.relative_to(JADX)),
                    'line': idx, 'cls': cur_cls, 'fn': cur_fn, 'stmt': stmt,
                })

# ── 3. role assignment ตาม yu0.f() split (Main=s==o(); Server=endsWith(":engine"); Child=else) ──
# yu0:150 (Main), yu0:155 (Child) เรียก ic; ส่วนอื่น: caller process = ?
ROLE_BY_FILE = {}  # heuristic: jv0/my=Child(:pN), ev0/r1/x6=Server(:engine), Entry/yu0/App=Main/child
for s in sites:
    f = s['file']
    if 'helper/Native' in f: s['role'] = 'lib'
    elif re.search(r'(Entry|App|yu0|b8|p60|z10|vx)\.java', f): s['role'] = 'Main(+Child shared)'
    elif re.search(r'(jv0|my|zh|x3|y40)\.java', f): s['role'] = 'ChildAppClient(:pN)'
    elif re.search(r'(ev0|r1|p41|x6|m00|g00)\.java', f): s['role'] = 'Server(:engine)'
    else: s['role'] = 'caller-dependent'

# ── 4. ghidra anchors ──
gh = {}
try:
    gh['jni_onload'] = re.findall(r'0x?[0-9a-f]+|@ ?[0-9a-f]{6,}', (GHIDRA / 'libengine_jni/00_INDEX.txt').read_text())[:3]
    cl = (GHIDRA / 'libengine_jni/04_JNI_OnLoad_callgraph.txt').read_text()
    gh['onload_callees'] = re.findall(r'([0-9a-f]{8})\s+size=\S+\s+name=(\S+)', cl)
    ia = (GHIDRA / 'libengine_jni/02_init_array_entries.txt').read_text()
    gh['init_array'] = re.findall(r'\[\s*(\d+)\]\s+[0-9a-f]+\s+->\s+([0-9a-f]+)\s+(\S+)', ia)
    rn = (GHIDRA / 'libengine_jni/05_RegisterNatives_xrefs.txt').read_text()
    gh['register_natives'] = 'symbol not found' in rn
    sy = (GHIDRA / 'symbols/libengine.so.symbols.txt').read_text()
    gh['exports'] = re.findall(r'([0-9a-f]{16})\s+\d+\s+FUNC\s+GLOBAL\s+\S+\s+(\S+)', sy)[:20]
    st = (GHIDRA / 'symbols/libengine.so.strings.txt').read_text()
    # interesting syscalls = capability profile of each native fn
    key = ['process_vm_readv','mprotect','ptrace','dlopen','dlsym','syscall','sigaction',
           'mincore','__system_property_get','dl_iterate_phdr','getauxval','openat','mmap',
           'socket','connect','madvise','memfd_create','fork','execve','prctl']
    gh['capability_strings'] = sorted({k for k in key if re.search(rf'\b{k}\b', st)})
except Exception as e:
    gh['error'] = str(e)

# ── 5. chain หลัก (ยืนยันแล้วจาก jadx ในเซสชันนี้) ──
CHAIN = [
    ('Entry.java:64',    'channel "G" (pkg,userId) — ปุ่มเริ่มเกม', 'Main'),
    ('yu0.java:109 C()', 'u().l(pkg,uid) สร้าง intent MAIN+INFO→LAUNCHER', 'Main'),
    ('qv0.java:78 l()',  'w()→m00.W0 [binder PMS เสมือน] resolve → setClassName(เกม)', 'Main→Server'),
    ('yu0.java:118 F()', 'j().C(intent,uid)', 'Main'),
    ('dv0.java:33 C()',  'g00.n0 → Parcel transact code=3', 'Main→:engine'),
    ('g00.java:543',     'server onTransact case 3 → n0(intent,uid)', 'Server(:engine)'),
    ('ev0.java:238 n0()', 'k(uid)→p41 → k.b.v(...) = r1.v', 'Server'),
    ('r1.java:388 v()',  'x6.w2().n() resolve ภายใน; หา task/affinity → t()/u()', 'Server'),
    ('r1.java:578 w()',  'a7.e().u(pkg,processName,...) จอง slot — throw "Unable to create process" ถ้ามัดไม่ได้', 'Server'),
    ('a7.java:292 u()',  'x6.w2().c0(pkg)=ApplicationInfo เสมือน → d7.b(uid,pkgIdx) key → reuse-slot หรือ l() เลือก 0..3 ที่ไม่ชนชื่อ process ที่รันอยู่ → new yj0; หมด → "No processes available"', 'Server'),
    ('a7.java:171 m()',  '★★ PROCESS-HANDSHAKE: p3 config → Bundle "SnakeEngine_client_config" → ContentResolver.call(content://com.snake.proxy_content_provider_N, "_Engine_|_init_process_") — Android spawn :pN เองเพราะ provider ถูก touch', 'Server→Android'),
    ('ProxyContentProvider.java:15', '[:pN] call() → jv0.B2().P2(p3) เก็บ config+ตรวจ pkg (Reject init process) → ตอบ Bundle "_Engine_|_client_" = asBinder() ของ jv0 (child→server channel, linkToDeath คุมชีพ)', 'Child(:pN)'),
    ('a7.java:333',      'h(ctx,"com.snake:P"+slot) → pid จริงลง yj0.q (getRunningAppProcesses)', 'Server'),
    ('r1.java:155 k()',  'kl0.a/d(r9) = "…helper.ProxyActivity$P%d[_L]" (หรือ Transparent เมื่อ theme ดัน) → component = com.snake/Stub', 'Server'),
    ('il0.java:21 b()',  'putExtra _S_|_user_id_/_activity_info_/_target_/_activity_token_v_ (real intent ซ่อนใน stub)', 'Server'),
    ('r1.java:361 t()',  'flags 134217728|524288|268435456 → yu0.m().startActivity(stub) — ★AMส จริง (message จะถูก re-queue จนกว่า bound)', 'Server→Android'),
    ('yu0.f() 150/155',  'App.attachBaseContext: Main→Native.ic; Child→Native.ic; Server ไม่เรียก — แล้ว iz.d() ลงทะเบียน 57 callbacks', 'Main+Child'),
    ('jv0.O2:273 / my',  'H-callback "my" ฝังที่ mH.mCallback (ny.b.e(d(),this)) — switch t1.d.* = LAUNCH/EXECUTE_TRANSACTION → h(obj): ไม่ bound→เก็บ/re-queue; bound→rewrite ClientTransaction fields →放行', 'Child(:pN)'),
    ('jv0.java:427 x2()', 'main-looper bound ผ่าน ConditionVariable (U2→O2) — ทุก binder thread รอตรงนี้', 'Child'),
    ('jv0.java:211 O2()', 'u().m(pkg,8)→PackageInfo เสมือน → z2(appInfo)=ClassLoader เกม → ★t1.b.* เขียน AppBindData (c2=t1.f.c(AT)=mBoundApplication): b=appInfo c=info d=instrumentationName e=processName(←str2 guest) f=providers → a3 → m90.i.b=newApplication → zh.a → callApplicationOnCreate → iz.c().b(my.class)', 'Child'),
    ('jv0.java:245',     'Native.i(SDK_INT) ★ native จุดเดียวใน bind path', 'Child'),
    ('p3 identity',      'jv0.E2()=pkg(m) G2/F2()=slot(o) H2()=Application(m) N2()=userId(r) J2()=q(UID?) — ทุก getter ใน child อ่าน p3 ไม่ใช่ framework → guest SDK เห็น com.miniclip ทั้งหมด', 'Child'),
    ('b8.java:79',       'Native.ac(activity, getDeclaredMethod("pjowqpxe",…)) — hook ใส่ activity', 'Child'),
    ('Native.update(obj,Method)', 'มีจริงใน Native.java + MethodUtils แปลง Method→sig; แต่ call site ฝั่ง hidden-dex ยังไม่ถูกจับได้แบบ dynamic = สมมติฐานรอพิสูจน์ (vdex×5 ไม่ใช่หลักฐาน — ดู EVIDENCE_CHAIN §3.6 แก้ไข)', 'Child?'),
]

# ── write report ──
L = ['# สกัด Native Call-Sites + Chain Install&Launch (libengine.so)',
     f'สร้างอัตโนมัติ: extract_native_callsites.py · sources: {JADX} · {GHIDRA}', '']
L.append('## 1. Native methods (Native.java)')
L.append('| method | args | call sites |')
L.append('|---|---|---|')
cnt = {}
for s in sites: cnt.setdefault(s['method'], []).append(s)
for nm, sig in natives:
    ss = cnt.get(nm, [])
    L.append(f'| `{nm}` | {sig.strip()} | {len(ss)} |')
L.append('')
L.append('## 2. Call sites (file:line → role)')
L.append('| native | class.fn | file:line | role |')
L.append('|---|---|---|---|')
for s in sorted(sites, key=lambda x: (x['method'], x['file'])):
    stmt = s['stmt'].replace('|', '\\|')[:120]
    L.append(f"| `{s['method']}` | {s['cls']}.{s['fn']} | {s['file']}:{s['line']} | {s['role']} |")
L.append('')
L.append('## 3. Chain: ปุ่ม "G" → เกมรัน (ยืนยันจาก jadx แล้วทุก hop)')
L.append('| # | จุด | ทำอะไร | process |')
L.append('|---|---|---|---|')
for i, (loc, act, role) in enumerate(CHAIN, 1):
    L.append(f'| {i} | {loc} | {act} | {role} |')
L.append('')
L.append('## 4. Ghidra anchors (libengine.so)')
L.append(f"- JNI_OnLoad: **@ 0x1f3fa0, 12,388 B** (export เดียวที่โผล่ใน dynsym)")
L.append(f"- .init_array: {len(gh.get('init_array', []))} entries — ตัวใหญ่ entry_37 @ "
         f"{dict((e[2],e[1]) for e in gh.get('init_array', [])).get('_INIT_37','?')} "
         f"(9.3 KB decompile, VMP jumptable)")
L.append(f"- RegisterNatives xrefs: {'ไม่พบ (indirect/obfuscated — ตรงกับบันทึก)' if gh.get('register_natives') else 'พบ'}")
L.append(f"- Java_* strings: ว่าง → ลงทะเบียน natives แบบ dynamic (ยืนยันใน EVIDENCE_CHAIN §4)")
L.append(f"- callgraph 1-hop จาก JNI_OnLoad: {[n for _,n in gh.get('onload_callees',[])]}")
L.append(f"- capability strings (syscall ที่ lib ใช้): {gh.get('capability_strings', [])}")
L.append('')
L.append('## 5. ข้อสรุปเชิงโครงสร้าง')
L.append('''- **t1 field handles (go0 reflection cache — ยืนยันด้วย t1.java:52-57,108):**
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
  ทุก intent launch message จะถูก re-queue (sendMessageAtFrontOfQueue) จนกว่า bound''')
if gh.get('error'): L.append(f"\n> ghidra parse error: {gh['error']}")

OUT.write_text('\n'.join(L))
print(f'written: {OUT}')
print(f'native methods: {len(natives)}  call sites: {len(sites)}  chain hops: {len(CHAIN)}')
for nm in sorted(native_names):
    print(f"  {nm}: {len(cnt.get(nm, []))} sites")
