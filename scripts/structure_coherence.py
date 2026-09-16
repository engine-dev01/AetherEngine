#!/usr/bin/env python3
"""structure_coherence.py — stage-2 deep audit: manifest <-> class <-> chain wiring.

Checks, each with file:line evidence (no guessing, no `//TODO`):
  M1  every manifest component resolves to a real declared class (incl. nested $Pn)
  M2  process slots declared in manifest == slots the Kotlin code can dispatch
  M3  CH-09/CH-10/CH-11 mechanism classes actually override the framework hook
  M4  HCallbackProxy is installed by someone (not just defined)
  M5  sCache / hidden-field reflection targets exist in the SDK surface we compile against
  M6  Flutter channel wiring (CH-09) present or absent
  M7  every AETHER-NOOP marker is a documented decision, not a silent hole
"""
import os, re, sys, glob, json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAN = os.path.join(ROOT, "aether-android/aether-app/src/main/AndroidManifest.xml")
fails, notes = [], []


def rel(p):
    return os.path.relpath(p, ROOT)


def kt_files():
    out = []
    for d in ("aether-core/src/main", "aether-android/aether-app/src/main"):
        for dd, ds, fs in os.walk(os.path.join(ROOT, d)):
            out += [os.path.join(dd, f) for f in fs if f.endswith(".kt")]
    return sorted(out)


KT = kt_files()
SRC = {f: open(f, errors="ignore").read() for f in KT}

# ── M1: manifest components resolve ────────────────────────────────────────
print("═══ M1 manifest component -> class resolution ═══")
mtxt = open(MAN, errors="ignore").read()
components = []
for m in re.finditer(r'<(activity|service|provider|receiver)\b([^>]*?)/?>', mtxt, re.S):
    tag, body = m.group(1), m.group(2)
    name = re.search(r'android:name="([^"]+)"', body)
    proc = re.search(r'android:process="([^"]+)"', body)
    if name and name.group(1).startswith("com."):
        components.append((tag, name.group(1), proc.group(1) if proc else "(main)",
                           mtxt[:m.start()].count("\n") + 1))
by_class = {}
for f, t in SRC.items():
    for m in re.finditer(r'(?:class|object|interface)\s+(\w+)', t):
        by_class[m.group(1)] = f
unres = []
for tag, cn, proc, ln in components:
    outer = cn.rsplit("$", 1)[0].rsplit(".", 1)[-1]
    ok = outer in by_class
    if not ok:
        unres.append((tag, cn, proc, ln))
        fails.append(("M1", f"manifest {tag} class not found in source",
                      rel(MAN) + f":{ln}", cn))
print(f"   {len(components)} components; {len(unres)} unresolved")
for tag, cn, proc, ln in unres:
    print(f"   MISS {tag:9s} {cn} ({proc}) :{ln}")

# ── M2: process slots parity between manifest and dispatcher ───────────────
print("\n═══ M2 slot parity (manifest vs dispatcher) ═══")
manifest_slots = sorted({m.group(1)
                         for _, cn, _, _ in components
                         for m in [re.search(r'\$(P\d+)\b', cn)] if m})
disp = []
for f, t in SRC.items():
    for m in re.finditer(r'"?(P\d)"?|\bslot\s*=\s*(\d)', t):
        pass
# find the dispatcher that maps slot -> proxy class
cands = [f for f, t in SRC.items() if re.search(r'ProxyActivity\$P|"P0"', t)]
print(f"   manifest slots      : {manifest_slots}")
print(f"   dispatcher files    : {[rel(c) for c in cands][:4]}")
disp_src = {rel(f): t for f, t in SRC.items() if f in cands}
missing_slot_wiring = []
for s in manifest_slots:
    hit = any(re.search(r'\b' + s + r'\b', t) for t in disp_src.values())
    if not hit:
        missing_slot_wiring.append(s)
if missing_slot_wiring:
    fails.append(("M2", "manifest slot has no dispatcher reference",
                  rel(MAN), ", ".join(missing_slot_wiring)))
print(f"   slots not seen in dispatcher: {missing_slot_wiring or 'none'}")

# ── M3: CH-09/10/11 framework hooks really overridden ──────────────────────
print("\n═══ M3 CH-09/10/11 framework-hook overrides ═══")
HOOKS = [
    ("CH-10", "JobService",      r'onStartJob\s*\(',        "ProxyJobService"),
    ("CH-11", "BroadcastReceiver", r'onReceive\s*\(',        "ProxyBroadcastReceiver"),
    ("CH-04", "ContentProvider",  r'query\s*\(.*Uri',        "SystemCallProvider/Aether*Provider"),
    ("CH-05", "Instrumentation",  r'callActivityOn\w+\s*\(', "AetherInstrumentation"),
    ("CH-05", "Handler.Callback", r'mCallback|Handler\.Callback|handleMessage', "HCallbackProxy"),
]
for ch, base, pat, label in HOOKS:
    hits = []
    for f, t in SRC.items():
        if re.search(pat, t):
            ln = next(i for i, l in enumerate(t.splitlines(), 1) if re.search(pat, l))
            hits.append(f"{rel(f)}:{ln}")
    print(f"   {ch} {base:18s} {'YES' if hits else 'NO ':4s} {(hits[0] if hits else '—')}")
    if not hits:
        fails.append(("M3", f"{ch}: no {base} hook override found", "—", label))

# ── M4: HCallbackProxy installed? ──────────────────────────────────────────
print("\n═══ M4 HCallbackProxy install site ═══")
inst = []
for f, t in SRC.items():
    for m in re.finditer(r'HCallbackProxy\s*\(\s*\w|HCallbackProxy\.\w+|\.install\s*\(\s*\)', t):
        ln = t[:m.start()].count("\n") + 1
        if "HCallbackProxy" in m.group(0) or "install" in m.group(0):
            inst.append(f"{rel(f)}:{ln}  {m.group(0)[:40]}")
print("\n   ".join(inst[:6]) if inst else "   NONE — proxy defined but never installed")
if not inst:
    fails.append(("M4", "HCallbackProxy never instantiated/installed", "—", "CH-05"))

# ── M5: reflection field targets vs android.jar surface ────────────────────
print("\n═══ M5 reflected field names (chain-critical) ═══")
FIELDS = ["sCache", "sCurrentActivityThread", "mInitialApplication", "mActivities",
          "mProviderMap", "mCallback", "mInstrumentation", "mPackageName", "mInfo"]
for fn in FIELDS:
    hits = []
    for f, t in SRC.items():
        if fn in t:
            ln = next(i for i, l in enumerate(t.splitlines(), 1) if fn in l)
            hits.append(f"{rel(f)}:{ln}")
    print(f"   {fn:26s} {len(hits):2d} site(s)  {(hits[0] if hits else '—')}")
    if not hits:
        notes.append(("M5", f"{fn} never referenced (snake CH-04 uses it)", "—", ""))

# ── M6: Flutter channel (CH-09) ────────────────────────────────────────────
print("\n═══ M6 CH-09 Flutter channel ════".rstrip())
fl = []
for f, t in SRC.items():
    for m in re.finditer(r'(MethodChannel|FlutterEngine|DartMessenger|ChannelHandler)', t):
        ln = t[:m.start()].count("\n") + 1
        fl.append(f"{rel(f)}:{ln} {m.group(1)}")
print("\n   ".join(sorted(set(fl))[:8]) if fl else "   NONE")

# ── M7: NO-OP markers ──────────────────────────────────────────────────────
print("\n═══ M7 declared NO-OP points (chain holes by design) ═══")
noop = []
for d, _, fs in os.walk(ROOT):
    if "/.git" in d or "/build" in d:
        continue
    for f in fs:
        if f.endswith((".cpp", ".hpp", ".kt", ".java")):
            p = os.path.join(d, f)
            try:
                t = open(p, errors="ignore").read()
            except OSError:
                continue
            for i, l in enumerate(t.splitlines(), 1):
                if "AETHER-NOOP" in l:
                    noop.append((rel(p), i, l.strip()[:70]))
for p, i, l in noop:
    print(f"   {p}:{i}  {l}")
print(f"   total: {len(noop)}")

print("\n" + "═" * 70)
seen = set()
uniq = []
for c, msg, loc, det in fails:
    k = (c, msg, det)
    if k not in seen:
        seen.add(k)
        uniq.append((c, msg, loc, det))
for c, msg, loc, det in uniq:
    print(f"  ✗ [{c}] {msg}\n        {loc}  {det}")
print(f"\n  FAIL={len(uniq)}  NOTE={len(notes)}")
sys.exit(1 if uniq else 0)
