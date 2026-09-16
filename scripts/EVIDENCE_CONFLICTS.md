# EVIDENCE CONFLICTS — consolidated (audit 2026-09-16, read-only)

Goal: purge contradictory evidence/claims before porting snake call structure.
Status: **findings only** — nothing edited/deleted yet.

## Ground truth (T1, committed under `reference/snake/`)

| fact | actual value | file:line |
|---|---|---|
| custom natives (snake) | **13** (F2 table) | reference/snake/F2_dex_natives.txt |
| Flutter engine natives | 41 | F2 header: `54 (13 custom + 41 Flutter engine)` |
| manifest components | **51** | reference/snake/F3_manifest.txt:195 `--- components (51) ---` |
| permissions | 184 | F3:9 |
| gcuid in F2 table | **NO** (0 hits) | grep F2 = 0 |
| gcuid in raw T1 binary | **YES** | classes.dex / roundtrip_unaligned.apk / smali Native.smali:53 |
| engine `const val SERVICE_*` | **40** | ServiceBinderProxy.kt |

## COMMITTED-TREE MATTERS

### C1 — Two T1 trees, one untracked
- `reference/snake/F1..F8` + `F4b/c/d asm` = **committed** (`git ls-files` confirms 16 files).
- `reference/SNAKE_extract/` = **untracked** (`git status` shows `?? reference/SNAKE_extract/`),
  contains raw `classes.dex`, `roundtrip_unaligned.apk`, `apktool/…smali`, `manifest_inventory.json`.
- Both are being cited in different docs → **decide**: keep both w/ explicit purpose split, or remove the untracked one after extracting what's unique.

### C2 — Manifest component count: 36 vs 51 (contradictory)
- F3 T1 (canonical) = **51**.
- `reference/README.md:6` = **51** ✅ correct.
- `scripts/SNAKE_ALIGNMENT.md` (uncommitted, mine) = **36** ❌ wrong source (I took it from `/tmp/snake_manifest_table.md`, a *subset* for parity-plan, not F3).
- → Correct number is **51**. "36" must never be reused as a manifest count. If we need "the subset that needs parity porting" that subset has its own name — NOT "manifest components".

### C3 — Deleted file still cited
- `reference/packageconf.8bp-56.23.2.json` is **staged deleted** (`git status: D reference/…`).
- Still cited in `reference/README.md:8`.
- → Update README (mark as removed-with-reason) OR restore the file. Pick one.

### C4 — Deleted doc still cited
- `docs/PROVISIONING_8BP_56.23.2.md` was removed in commit `2a8f738`.
- Still cited at `README.md:134`.
- → Same fix as C3 (drop the link or restore).

## DOC-VS-CODE CONTRADICTIONS

### D1 — ServiceBinderProxy count: 8 / 11 / 40
- Real (`grep -c '^    const val SERVICE_'`): **40**.
- `DEV.md` (HEAD) line ~70: "8 system service hook" ❌
- `DEV.md` (staged edit): "11 system service proxies (40 constants declared)" ⚠ half-right
- `docs/ARCHITECTURE.md` (HEAD): "8 system service hook" ❌
- `docs/ARCHITECTURE.md` (staged edit): "11 system service proxies" ❌
- Blueprint note (2026-09-13 memory): "P5: 11→48 services" — 48 = snake's, ours is 40.
- → Reconcile to **40 constants** and state explicitly how many are *actually wired as proxies* (that number may differ; needs one grep against the wiring path — separate task).

### D2 — gcuid: memory says "T2-only, do not use" — reality says T1
- Daily log 2026-09-14 recorded: "gcuid ไม่มีใน bundle ทั้งหมด (T2-only claim — ห้ามใช้ออกแบบ)".
- F2 = 0 hits ✅ (only the 13-row table was searched).
- **But `reference/SNAKE_extract/apktool/smali/com/snake/helper/Native.smali:53` is real T1 (decompiled from committed-adjacent artifact)**, and `classes.dex` contains the string.
- → **Reversal:** gcuid IS T1-backed. The earlier prohibition is wrong. No cleanup removes the finding, but the prohibition must be lifted (or the smali marked CUT if that is the actual decision).

### D3 — DEV.md / ARCHITECTURE.md staged edits mid-flight
- Both files have **uncommitted edits** changing "8 → 11".
- 11 is still wrong (real=40) → commit would bake in a wrong number.
- → Do NOT commit those 2 hunks as-is; fix to 40 first, or drop the hunks.

## SCANNER SELF-BUGS (my checker, not the repo)

### S1 — evidence_conflict_scan.py crashed on section 2
- Cause: `FACTS["T1 files"]` pattern `F\d+[a-z]?_` has no capture group but code calls `m.group(1)`.
- → Fix locally; not a repo problem.

### S2 — scanner flags CUTS.md mentions as "missing files"
- CUTS.md documents *removed* TUs (`env_check.cpp`, `manifest_snapshot.cpp`, `hide_module.cpp`).
- Scanner treats them as expected-on-disk → false positive.
- → Add whitelist for files referenced **inside CUTS.md** (they are intentionally absent).

## PROPOSED CLEANUP ORDER (nothing done yet)

1. **Fix scanner** (S1, S2) — cheap, unblocks reliable re-scan.
2. **Correct SNAKE_ALIGNMENT.md** 36→51 (C2) and lift the gcuid prohibition (D2).
3. **Fix DEV.md + ARCHITECTURE.md** count 8/11→40 (D1). Freeze other pending edits; do NOT commit 11.
4. **Resolve two T1 trees** (C1): rename `reference/SNAKE_extract/` → `reference/snake_raw/` with a one-line README explaining it complements `reference/snake/`. This makes purpose explicit without deleting anything.
5. **Fix dangling cites** (C3, C4): update `reference/README.md` + `README.md` line refs.

No deletion of any committed artifact is proposed — only relabeling, correcting numbers, and clarifying the two trees.

Awaiting go/no-go on items 1–5 before editing.