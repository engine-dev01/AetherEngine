#!/usr/bin/env python3
"""evidence_conflict_scan.py — find every place where a doc claims a fact
that contradicts another doc or the actual filesystem. Read-only."""
import os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

DOCS = []
for base in ("docs", "reference", "scripts", "."):
    for d, ds, fs in os.walk(base if base != "." else "."):
        if "/.git" in d or "/build" in d or d.startswith("./app/android"):
            continue
        for f in fs:
            if f.endswith((".md",)) and d.count("/") < 4:
                DOCS.append(os.path.join(d, f))
DOCS = sorted(set(DOCS))

print("=" * 66)
print("EVIDENCE CONFLICT SCAN — read-only")
print("=" * 66)

# ── 1. every filename mentioned in a doc that does not exist ────────────
print("\n[1] Doc → path mentions that DO NOT exist on disk")
idx = set()
for d, ds, fs in os.walk("."):
    if "/.git" in d or "/build" in d:
        ds[:] = [x for x in ds if x not in (".git", "build")]
    for f in fs:
        idx.add(f)
missing = {}
for doc in DOCS:
    for i, ln in enumerate(open(doc, errors="ignore").read().splitlines(), 1):
        for m in re.finditer(r"[A-Za-z0-9_./\\-]+\.[A-Za-z0-9]{1,6}\b", ln):
            p = m.group(0).rstrip(".,;:)`")
            if "/" not in p:
                continue
            base = os.path.basename(p)
            if base in idx:
                continue
            if p.startswith(("http", "com.", "android.", "java.", "dalvik.")):
                continue
            missing.setdefault(p, []).append(f"{doc}:{i}")
for p, hits in sorted(missing.items()):
    print(f"  MISSING  {p}")
    for h in hits[:3]:
        print(f"             <- {h}")

# ── 2. conflicting numeric claims about the same fact ────────────────────
print("\n[2] Numeric claims on SAME subject (potential contradiction)")
FACTS = {
    "manifest components":   r"(\d+)\s*(?:components?|แถว)",
    "native count":          r"(\d+)\s*(?:natives?|native methods|externals)",
    "T1 files":              r"(F\d+[a-z]?_)",
}
for subj, rx in FACTS.items():
    seen = {}
    for doc in DOCS:
        for i, ln in enumerate(open(doc, errors="ignore").read().splitlines(), 1):
            for m in re.finditer(rx, ln, re.I):
                seen.setdefault(m.group(1), []).append((doc, i))
    if len(seen) > 1:
        print(f"  {subj}: " + ", ".join(
            f"{n}×({len(v)})" for n, v in sorted(seen.items())))

# ── 3. gcuid / T1-only claims — grep the actual T1 files ────────────────
print("\n[3] 'T2-only, do not use' claims vs T1 reality")
for name in ("gcuid", "na", "nb", "flagger"):
    hits = []
    for d, ds, fs in os.walk("reference/SNAKE_extract"):
        for f in fs:
            if f.endswith((".txt", ".smali", ".json", ".xml")):
                p = os.path.join(d, f)
                try:
                    t = open(p, errors="ignore").read()
                except OSError:
                    continue
                if name in t:
                    hits.append(p)
    print(f"  {name:8s} T1-file hits: {len(hits)}  {hits[:3]}")

# ── 4. what's ACTUALLY in reference/SNAKE_extract/ ─────────────────────
print("\n[4] Actual contents of reference/SNAKE_extract/")
for d, ds, fs in os.walk("reference/SNAKE_extract"):
    depth = d.count("/")
    if depth > 5:
        ds[:] = []
        continue
    for f in sorted(fs):
        p = os.path.join(d, f)
        try:
            sz = os.path.getsize(p)
        except OSError:
            continue
        if sz > 20_000_000:
            print(f"  {p}  ({sz:,} B  — TOO BIG to read)")
        else:
            print(f"  {p}  ({sz:,} B)")

# ── 5. deleted-but-still-referenced ──────────────────────────────────────
print("\n[5] Files staged deleted but still referenced in docs")
r = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
for ln in r.stdout.splitlines():
    if ln.startswith(" D ") or ln.startswith("D "):
        p = ln[3:].strip()
        base = os.path.basename(p)
        refs = []
        for doc in DOCS:
            for i, dl in enumerate(open(doc, errors="ignore").read().splitlines(), 1):
                if base in dl or p in dl:
                    refs.append(f"{doc}:{i}")
        print(f"  DELETED  {p}")
        for rr in refs[:4]:
            print(f"             still cited at {rr}")

print("\n" + "=" * 66)
print("done — nothing modified")