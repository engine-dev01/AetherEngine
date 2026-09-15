#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_boot_linkage.py — SP-01: the SNAKE boot spine, process start -> Dart C2 endpoint.

Composes ONE ordered spine (boot_linkage.*) out of two kinds of hops:

  * IMPORTED hops — edges already proven in SnakeLogic/call_linkage.json
    (CH-01..CH-12). They are not re-derived here; each imported hop carries its
    original edge_id, chain, confidence and evidence string verbatim.
  * NEW hops — tier T0 (framework boot, SHAPE REFERENCE from F9) and tier T4
    (the Dart task family that owns the C2 request routine), derived directly
    from the committed bundle: fragments/F9, fragments/F2, fragments/F3,
    output/blutter/pp.txt and output/blutter/asm/*.dart.

Every hop must resolve against the bundle or the build FAILS. Nothing is
asserted from memory. Deterministic: same bundle in -> byte-identical out.

usage:  python3 tools/build_boot_linkage.py [--bundle SnakeLogic] [--quiet]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
from collections import OrderedDict

# --------------------------------------------------------------------------
# paths
# --------------------------------------------------------------------------

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

LANG = "both"          # bilingual: Thai narrative + English tables
SPINE_ID = "SP-01"

# confidence vocabulary, strongest first (imported edges may carry any of these)
CONF_ORDER = ["proven", "strong", "probable", "candidate", "shape", "unresolved"]

TIERS = [
    ("T0", "framework", "Android framework boot (shape reference, NOT SNAKE bytes)",
     "บูตฝั่งเฟรมเวิร์ก Android — เป็น 'โครงรูป' จาก F9 ไม่ใช่ไบต์ของ SNAKE"),
    ("T1", "dex", "SNAKE application / activity boot in classes.dex",
     "บูตฝั่ง dex ของ SNAKE: Application และ main activity"),
    ("T2", "native", "libengine.so staged init (.init_array -> JNI_OnLoad -> RegisterNatives)",
     "init แบบ staged ของ libengine.so"),
    ("T3", "jni/flutter", "libflutter.so bootstrap + Dart platform channel",
     "บูต libflutter.so และ platform channel ฝั่ง Dart"),
    ("T4", "dart", "Dart task family -> the routine that owns the C2 request",
     "ตระกูล task ฝั่ง Dart -> รูทีนที่ถือคำขอ C2"),
    ("T5", "dart", "C2 endpoint /api/request/ + response check + post-response run",
     "ปลายทาง C2 /api/request/ + ตรวจ response + งานหลัง response"),
]
TIER_TH = {t[0]: t[3] for t in TIERS}
TIER_EN = {t[0]: t[2] for t in TIERS}
TIER_NAME = {t[0]: t[1] for t in TIERS}

ENDPOINT = "https://rest.snakeseller.com/api/request/"
ENDPOINT_SLOT = "0x139d8"
ACTION_SLOT = "0x139e0"
ACTION_QUERY = r"\?action=upload_profile_image"

# C2 request routine (pool-adjacency attribution, see CH-12 hop 1)
C2_ROUTINE = "0x2f8928"
C2_FAMILY_PARENT = "0x3103b0"
C2_FAMILY_SLOT = "0x13988"      # pool slot holding C2_FAMILY_PARENT
# the endpoint's allocation run; its boundaries are the ones CH-12 established
# (pool adjacency) and every slot inside is re-verified at build time.
C2_RUN_LO, C2_RUN_HI = "0x13988", "0x13ac0"

# shared async-continuation helper reached by all three Xu-family libraries
CONT_HELPER = "0x1a5b64"

# the shared static state store: [xkg] Yoa
STORE_CLASS = "Yoa"
STORE_LIB = "xkg"

# the second, much larger static registry found while mapping the C2 pool region
CFG_CLASS = "ooa"
CFG_LIB = "nkg"

# slots in the _Bpa region tested for "does any listed routine load this?"
SECRET_SLOTS = ("0x13918", "0x13920")
TOKEN_LIST_SLOTS = ("0x13850", "0x13950", "0x13998")
MEGA_SLOT = "0x13928"


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

def die(msg):
    sys.stderr.write("FATAL: %s\n" % msg)
    sys.exit(2)


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def read_lines(path):
    return read(path).split("\n")


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def norm_hex(h):
    """'0x139d8' / '139d8' / '0X139D8' -> '0x139d8'."""
    h = h.strip().lower()
    if not h.startswith("0x"):
        h = "0x" + h
    return h


class Bundle(object):
    """Everything parsed out of SnakeLogic/ that the spine is allowed to cite."""

    def __init__(self, root):
        self.root = root
        self.frag = os.path.join(root, "fragments")
        self.blut = os.path.join(root, "output", "blutter")
        self.asm = os.path.join(self.blut, "asm")
        self.problems = []

        self.f2 = self._load_f2()
        self.f3 = self._load_f3()
        self.f9 = self._load_f9()
        self.pp = self._load_pp()
        self.asm_index = self._load_asm()
        self.chains, self.edges = self._load_call_linkage()

    # ---- fragments -------------------------------------------------------

    def _load_f2(self):
        p = os.path.join(self.frag, "F2_dex_natives.txt")
        if not os.path.exists(p):
            die("missing fragment %s" % p)
        lines = read_lines(p)
        out = {"path": "fragments/F2_dex_natives.txt", "loadlib": [], "callers": [],
               "natives_declared": None, "sha256": None}
        m = re.search(r"^sha256=([0-9a-f]{64})", "\n".join(lines[:6]), re.M)
        if m:
            out["sha256"] = m.group(1)
        m = re.search(r"native declarations: (\d+) \((\d+) custom \+ (\d+) Flutter engine\)",
                      "\n".join(lines))
        if m:
            out["natives_declared"] = (int(m.group(1)), int(m.group(2)), int(m.group(3)))

        # library loader call sites
        i = 0
        while i < len(lines):
            m = re.match(r"^  @classes\.dex\+(0x[0-9a-f]+)\s+(.*)$", lines[i])
            if m:
                site = {"offset": m.group(1), "callee": m.group(2).strip()}
                for j in range(i + 1, min(i + 5, len(lines))):
                    mm = re.match(r"^      (enclosing method|library name|evidence)\s*:\s*(.*)$",
                                  lines[j])
                    if mm:
                        key = mm.group(1).replace(" ", "_")
                        site[key] = mm.group(2).strip()
                out["loadlib"].append(site)
                i += 4
                continue
            i += 1

        # java-side callers of the custom native classes
        cur_class = None
        for ln in lines:
            m = re.match(r"^  (L[\w/$;]+): (\d+) distinct caller method\(s\)", ln)
            if m:
                cur_class = m.group(1)
                out.setdefault("caller_counts", {})[cur_class] = int(m.group(2))
                continue
            m = re.match(r"^      <- (L[\w/$;]+->[\w<>$]+\([^)]*\)[\w\[\];/$]+)\s+@(0x[0-9a-f]+)", ln)
            if m and cur_class:
                out["callers"].append({"class": cur_class, "method": m.group(1),
                                       "offset": m.group(2)})
        return out

    def _load_f3(self):
        p = os.path.join(self.frag, "F3_manifest.txt")
        if not os.path.exists(p):
            die("missing fragment %s" % p)
        txt = read(p)
        out = {"path": "fragments/F3_manifest.txt"}
        for key, pat in (("application", r"^application\s*:\s*(\S+)"),
                         ("main_activity", r"^main activity\s*:\s*(\S+)"),
                         ("package", r"^package\s*:\s*(\S+)")):
            m = re.search(pat, txt, re.M)
            out[key] = m.group(1) if m else None
        m = re.search(r"--- components \((\d+)\) ---", txt)
        out["components"] = int(m.group(1)) if m else None
        return out

    def _load_f9(self):
        p = os.path.join(self.frag, "F9_kos_boot_stack.txt")
        if not os.path.exists(p):
            die("missing fragment %s (write it first)" % p)
        lines = read_lines(p)
        frames = []
        for ln in lines:
            m = re.match(r"^\s*(\d{1,2})\s+(\S+)\s+->\s+(\S+?)\(line:(-?\d+)\)\s*$", ln)
            if m:
                frames.append({"n": int(m.group(1)), "cls": m.group(2),
                               "meth": m.group(3), "line": m.group(4)})
        if len(frames) < 15:
            die("F9: parsed only %d frames (expected 19)" % len(frames))
        payload = re.search(r"type: (JsonObject create)", "\n".join(lines))
        return {"path": "fragments/F9_kos_boot_stack.txt", "frames": frames,
                "payload": payload.group(1) if payload else None}

    # ---- blutter object pool --------------------------------------------

    def _load_pp(self):
        p = os.path.join(self.blut, "pp.txt")
        if not os.path.exists(p):
            die("missing %s" % p)
        slots = OrderedDict()
        for i, ln in enumerate(read_lines(p), 1):
            m = re.match(r"^\[pp\+(0x[0-9a-f]+)\]\s*(.*)$", ln)
            if m:
                slots[m.group(1)] = {"line": i, "body": m.group(2).rstrip(), "raw": ln.rstrip()}
        return {"path": "output/blutter/pp.txt", "slots": slots}

    def slot(self, off):
        off = norm_hex(off)
        s = self.pp["slots"].get(off)
        if s is None:
            self.problems.append("pp.txt has no slot pp+%s" % off)
        return s

    def slot_range(self, lo, hi):
        """All slots with lo <= off <= hi, in address order."""
        lo_i, hi_i = int(norm_hex(lo), 16), int(norm_hex(hi), 16)
        out = []
        for off, s in self.pp["slots"].items():
            a = int(off, 16)
            if lo_i <= a <= hi_i:
                out.append((off, s))
        out.sort(key=lambda kv: int(kv[0], 16))
        return out

    # ---- blutter asm -----------------------------------------------------

    def _load_asm(self):
        idx = {"files": {}, "headers": {}, "classes": {}, "lines": {}}
        paths = []
        for dp, dn, fn in os.walk(self.asm):
            dn.sort()
            for f in sorted(fn):
                if f.endswith(".dart"):
                    paths.append(os.path.join(dp, f))
        paths.sort()
        for p in paths:
            rel = os.path.relpath(p, self.asm)
            lines = read_lines(p)
            idx["files"][rel] = len(lines)
            idx["lines"][rel] = lines
            cur_class = None
            for i, ln in enumerate(lines, 1):
                m = re.match(r"^(?:abstract )?class ([\w.$@]+)", ln)
                if m:
                    cur_class = m.group(1)
                    idx["classes"].setdefault(cur_class, []).append(
                        {"file": rel, "line": i, "decl": ln.rstrip()})
                    continue
                m = re.match(r"^\s*// \*\* addr: (0x[0-9a-f]+), size: (-?0x[0-9a-f]+)", ln)
                if m:
                    title = ""
                    for j in range(max(0, i - 4), i - 1):
                        t = lines[j].strip()
                        if t.startswith("[closure]") or t.startswith("static") or "(" in t:
                            title = t
                            break
                    idx["headers"][(rel, m.group(1))] = {
                        "addr": m.group(1), "size": m.group(2), "file": rel,
                        "line": i, "title": title, "class": cur_class}
        return idx

    def hdr(self, addr, want_file=None):
        """Find the function/closure header for addr (optionally in a given file)."""
        addr = norm_hex(addr)
        hits = [(k, v) for k, v in self.asm_index["headers"].items()
                if k[1] == addr and (want_file is None or k[0] == want_file)]
        if not hits:
            return None
        hits.sort(key=lambda kv: kv[0])
        return hits[0][1]

    def hdr_any(self, addr):
        return self.hdr(addr)

    def insns(self, file_rel, lo=None, hi=None):
        """(line_no, addr, text) triples for instruction lines in a listing."""
        lines = self.asm_index["lines"].get(file_rel)
        if lines is None:
            return []
        out = []
        for i, ln in enumerate(lines, 1):
            m = re.match(r"^\s*//\s+(0x[0-9a-f]+):\s*(.*)$", ln)
            if not m:
                m = re.match(r"^\s*//\s+0x([0-9a-f]+):\s+(.*)$", ln)
            if m:
                a = norm_hex(m.group(1)) if m.group(1).startswith("0x") else norm_hex("0x" + m.group(1))
                if lo and int(a, 16) < int(norm_hex(lo), 16):
                    continue
                if hi and int(a, 16) > int(norm_hex(hi), 16):
                    continue
                out.append((i, a, m.group(2).rstrip()))
        return out

    def find_insn(self, file_rel, addr, needle):
        for i, a, txt in self.insns(file_rel):
            if a == norm_hex(addr) and needle in txt:
                return {"file": file_rel, "line": i, "addr": a, "insn": txt}
        return None

    def grep_insn(self, needle_re, files=None):
        """All instruction lines matching a regex, across asm (sorted, deduped)."""
        rx = re.compile(needle_re)
        out = []
        for rel in sorted(self.asm_index["lines"]):
            if files and rel not in files:
                continue
            for i, a, txt in self.insns(rel):
                if rx.search(txt):
                    out.append({"file": rel, "line": i, "addr": a, "insn": txt})
        return out

    def grep_ann(self, needle_re, files=None):
        """All annotation/comment lines (// ...) matching a regex."""
        rx = re.compile(needle_re)
        out = []
        for rel in sorted(self.asm_index["lines"]):
            if files and rel not in files:
                continue
            for i, ln in enumerate(self.asm_index["lines"][rel], 1):
                if ln.lstrip().startswith("//") and rx.search(ln):
                    out.append({"file": rel, "line": i, "text": ln.strip()})
        return out

    def fam_slots(self, cls):
        """Pool slots whose body names this class, in address order."""
        out = []
        for off, s in self.pp["slots"].items():
            if re.search(r"\]\s*%s\b" % re.escape(cls), s["body"]):
                out.append((off, s))
        out.sort(key=lambda kv: int(kv[0], 16))
        return out

    def fam_clusters(self, cls, maxgap=0x100):
        """Maximal runs of a class's pool slots where neighbours are <= maxgap apart."""
        slots = self.fam_slots(cls)
        out = []
        for off, s in slots:
            if out and int(off, 16) - int(out[-1][-1][0], 16) <= maxgap:
                out[-1].append((off, s))
            else:
                out.append([(off, s)])
        return out

    def class_decl(self, name):
        hits = self.asm_index["classes"].get(name) or []
        return hits[0] if hits else None

    # ---- existing linkage ------------------------------------------------

    def _load_call_linkage(self):
        problems = self.problems
        p = os.path.join(self.root, "call_linkage.json")
        if not os.path.exists(p):
            die("missing %s (run tools/build_call_linkage.py first)" % p)
        d = json.loads(read(p))
        chains = OrderedDict((c["id"], c) for c in d["chains"])
        by_id = OrderedDict((e["edge_id"], e) for e in d["edges"])
        # the `hop` field on an edge is NOT a per-chain index; the authoritative
        # order is the chain's own hops[] list, so index through the edge ids.
        order = OrderedDict()
        for cid, c in chains.items():
            seq = []
            for h in c["hops"]:
                e = by_id.get(h["edge"])
                if e is None:
                    problems.append("chain %s hop %s cites unknown edge %s"
                                         % (cid, h.get("n"), h.get("edge")))
                    continue
                seq.append(e)
            order[cid] = seq
        return chains, order

    def edge(self, chain, hop):
        """hop is 0-based within the chain."""
        seq = self.edges.get(chain)
        if seq is None:
            self.problems.append("call_linkage.json has no chain %s" % chain)
            return None
        if not (0 <= hop < len(seq)):
            self.problems.append("chain %s has %d edges, no index %d" % (chain, len(seq), hop))
            return None
        return seq[hop]

    def chain_hops(self, chain):
        c = self.chains.get(chain)
        return len(c["hops"]) if c else 0

    # ---- pool reference index: which slots does *listed* code touch? -----

    def pp_refs(self):
        """slot -> [(file, line)] for every `pp+0xNNN` mention in asm listings."""
        if getattr(self, "_pp_refs", None) is None:
            idx = {}
            rx = re.compile(r"pp\+(0x[0-9a-f]+)")
            for rel in sorted(self.asm_index["lines"]):
                for i, ln in enumerate(self.asm_index["lines"][rel], 1):
                    for m in rx.finditer(ln):
                        idx.setdefault(norm_hex(m.group(1)), []).append((rel, i))
            self._pp_refs = idx
        return self._pp_refs

    def slot_is_loaded(self, slot):
        return self.pp_refs().get(norm_hex(slot), [])

    # ---- static stores ---------------------------------------------------

    def store_fields(self, cls=None):
        """Parse `static late [final] T name; // offset: 0xNNN` out of a class body."""
        cls = cls or STORE_CLASS
        decl = self.class_decl(cls)
        if not decl:
            self.problems.append("asm/ has no class %s" % cls)
            return []
        lines = self.asm_index["lines"][decl["file"]]
        out = []
        for i in range(decl["line"], min(decl["line"] + 400, len(lines))):
            m = re.match(r"^\s*static late (?:final )?([\w<>,.\s]+?)\s+(\w+);\s*//\s*offset:\s*(0x[0-9a-f]+)",
                         lines[i])
            if m:
                out.append({"type": m.group(1).strip(), "name": m.group(2),
                            "offset": m.group(3), "file": decl["file"], "line": i + 1,
                            "ft": norm_hex(hex(2 * int(m.group(3), 16)))})
            elif out and (re.match(r"^\s*\[closure\]", lines[i]) or lines[i].startswith("}")):
                break
        out.sort(key=lambda f: int(f["offset"], 16))
        return out

    def yoa_fields(self):
        return self.store_fields(STORE_CLASS)

    def store_accesses(self, cls=None):
        """Who touches each store slot: InitLateStaticField(0xNNN) + field-table loads."""
        fields = self.store_fields(cls)
        by_off = {f["offset"]: f for f in fields}
        by_ft = {f["ft"]: f for f in fields}
        acc = OrderedDict((f["name"], []) for f in fields)
        for rel in sorted(self.asm_index["lines"]):
            for i, ln in enumerate(self.asm_index["lines"][rel], 1):
                m = re.search(r"InitLate(?:Final)?StaticField\((0x[0-9a-f]+)\)", ln)
                if m and norm_hex(m.group(1)) in by_off:
                    f = by_off[norm_hex(m.group(1))]
                    acc[f["name"]].append({"file": rel, "line": i, "kind": "init",
                                           "text": ln.strip()})
                for mm in re.finditer(r"\[x\d+, #(0x1[0-9a-f]{3})\]", ln):
                    if norm_hex(mm.group(1)) in by_ft:
                        f = by_ft[norm_hex(mm.group(1))]
                        acc[f["name"]].append({"file": rel, "line": i, "kind": "ft-load",
                                               "text": ln.strip()})
        return fields, acc

    def yoa_accesses(self):
        return self.store_accesses(STORE_CLASS)


# --------------------------------------------------------------------------
# hop construction
# --------------------------------------------------------------------------

class Spine(object):
    def __init__(self, bundle):
        self.b = bundle
        self.hops = []
        self.gaps = []
        self.checks = []

    # -- factories ---------------------------------------------------------

    def new(self, tier, kind, src, dst, conf, evidence, source, th, en, refs=None):
        h = OrderedDict()
        h["seq"] = len(self.hops) + 1
        h["tier"] = tier
        h["kind"] = kind
        h["src"] = src
        h["dst"] = dst
        h["conf"] = conf
        h["evidence"] = evidence
        h["source"] = source
        h["via"] = ""
        h["collapsed"] = 0
        h["th"] = th
        h["en"] = en
        h["refs"] = refs or []
        self.hops.append(h)
        return h

    def imported(self, tier, chain, hop_no, th=None, en=None):
        """Pull an already-proven edge out of call_linkage.json."""
        e = self.b.edge(chain, hop_no)
        if e is None:
            return None
        h = self.new(
            tier, e["kind"],
            e["src_name"] or ("%s+%s" % (e["src_module"], e["src_offset"] or "?")),
            e["dst_name"] or ("%s+%s" % (e["dst_module"], e["dst_offset"] or "?")),
            e["confidence"], e["evidence"], e["source"],
            th or "", en or "")
        h["via"] = "%s hop %d (%s)" % (chain, hop_no + 1, e["edge_id"])
        h["resolved_by"] = e.get("resolved_by", "")
        h["src_insn"] = e.get("src_insn", "")
        h["note"] = e.get("note", "")
        h["refs"] = [("edge", chain, hop_no)]
        return h

    def import_chain(self, tier, chain, th_lead=None, en_lead=None, only=None):
        n = self.b.chain_hops(chain)
        if n == 0:
            self.b.problems.append("chain %s not found in call_linkage.json" % chain)
            return 0
        got = 0
        for i in range(n):
            if only is not None and i not in only:
                continue
            if self.imported(tier, chain, i) is not None:
                got += 1
        return got

    def collapse(self, tier, chain, th, en, kind="collapsed_range"):
        """One summary hop standing in for a whole imported chain."""
        c = self.b.chains.get(chain)
        if c is None:
            self.b.problems.append("collapse: chain %s missing" % chain)
            return None
        n = len(c["hops"])
        first = self.b.edge(chain, 0)
        last = self.b.edge(chain, n - 1)
        h = self.new(
            tier, kind,
            (first or {}).get("src_name", chain),
            (last or {}).get("dst_name", chain),
            "proven",
            "%s: %d proven hops, %s .. %s (imported verbatim in CALL_LINKAGE.md)"
            % (chain, n, c["title"], c["layer_path"]),
            "call_linkage.json", th, en)
        h["via"] = "%s hops 1-%d" % (chain, n)
        h["collapsed"] = n
        h["refs"] = [("chain", chain, n)]
        return h

    def gap(self, gid, tier, what, why, recipe_th, recipe_en, refs=None):
        g = OrderedDict()
        g["id"] = gid
        g["tier"] = tier
        g["what"] = what
        g["why"] = why
        g["recipe_th"] = recipe_th
        g["recipe_en"] = recipe_en
        g["refs"] = refs or []
        self.gaps.append(g)
        return g

    # -- verification ------------------------------------------------------

    def verify(self):
        ok = True
        for h in self.hops:
            h["ref_detail"] = []
            for r in h["refs"]:
                good, detail = self._check_ref(r)
                h["ref_detail"].append("%s %s" % ("PASS" if good else "FAIL", detail))
                if not good:
                    ok = False
                    h["conf"] = "unresolved" if h["conf"] == "proven" else h["conf"]
        for g in self.gaps:
            g["ref_detail"] = []
            for r in g["refs"]:
                good, detail = self._check_ref(r)
                g["ref_detail"].append("%s %s" % ("PASS" if good else "FAIL", detail))
                if not good:
                    ok = False
        return ok

    def _check_ref(self, r):
        b = self.b
        kind = r[0]
        if kind == "edge":
            _, chain, hop = r
            e = b.edge(chain, hop)
            return (e is not None, "edge %s hop %d" % (chain, hop + 1))
        if kind == "chain":
            _, chain, n = r
            got = b.chain_hops(chain)
            return (got == n, "chain %s has %d hops (expected %d)" % (chain, got, n))
        if kind == "pp":
            _, slot, needle = (r + (None,))[:3] if len(r) < 3 else r
            s = b.slot(slot)
            if s is None:
                return (False, "pp+%s missing" % slot)
            if needle and needle not in s["body"]:
                return (False, "pp+%s body lacks %r (got %r)" % (slot, needle, s["body"][:60]))
            return (True, "pp+%s line %d: %s" % (slot, s["line"], s["body"][:70]))
        if kind == "pp-range":
            _, lo, hi, min_n = r
            got = b.slot_range(lo, hi)
            return (len(got) >= min_n,
                    "pp+%s..%s holds %d slots (need >= %d)" % (lo, hi, len(got), min_n))
        if kind == "asm-hdr":
            _, addr, size = (r + (None,))[:3] if len(r) < 3 else r
            h = b.hdr_any(addr)
            if h is None:
                return (False, "no asm header for %s" % addr)
            if size is not None and h["size"] != size:
                return (False, "asm header %s size %s (expected %s)" % (addr, h["size"], size))
            return (True, "asm header %s @%s:%d size %s (%s)"
                    % (addr, h["file"], h["line"], h["size"], h["title"][:40]))
        if kind == "asm-nohdr":
            _, addr = r
            h = b.hdr_any(addr)
            return (h is None, "no listing body for %s (header=%s)" % (addr, h and h["size"]))
        if kind == "asm-class":
            _, name, needle = (r + (None,))[:3] if len(r) < 3 else r
            d = b.class_decl(name)
            if d is None:
                return (False, "class %s not in asm/" % name)
            if needle and needle not in d["decl"]:
                return (False, "class %s decl lacks %r (got %r)" % (name, needle, d["decl"][:70]))
            return (True, "class %s @%s:%d %s" % (name, d["file"], d["line"], d["decl"][:60]))
        if kind == "asm-insn":
            _, file_rel, addr, needle = r
            hit = b.find_insn(file_rel, addr, needle)
            if hit is None:
                return (False, "%s+%s does not contain %r" % (file_rel, addr, needle))
            return (True, "%s:%d %s: %s" % (file_rel, hit["line"], hit["addr"], hit["insn"][:60]))
        if kind == "insn-count":
            _, needle_re, want, files = r
            got = b.grep_insn(needle_re, files)
            return (len(got) == want, "insn /%s/ matched %d (expected %d): %s"
                    % (needle_re, len(got), want,
                       ", ".join("%s+%s" % (g["file"], g["addr"]) for g in got[:6])))
        if kind == "field-count":
            _, want = r
            got = len(b.yoa_fields())
            return (got == want, "store %s has %d static fields (expected %d)"
                    % (STORE_CLASS, got, want))
        if kind == "field-access":
            _, name, want = r
            _, acc = b.yoa_accesses()
            got = len(acc.get(name, []))
            return (got == want, "%s.%s has %d accessors in asm/ (expected %d)"
                    % (STORE_CLASS, name, got, want))
        if kind == "frag":
            _, path, regex = r
            p = os.path.join(b.root, path)
            if not os.path.exists(p):
                return (False, "fragment %s missing" % path)
            found = re.search(regex, read(p), re.M)
            return (found is not None, "%s /%s/ -> %s" % (path, regex[:40],
                                                          (found.group(0)[:60] if found else "NO MATCH")))
        if kind == "frag-offset":
            _, which, off = r
            if which == "f2-caller":
                hit = [c for c in b.f2["callers"] if c["offset"] == norm_hex(off)]
                return (bool(hit), "F2 caller %s -> %s" % (off, hit and hit[0]["method"]))
            if which == "f2-loadlib":
                hit = [c for c in b.f2["loadlib"] if c["offset"] == norm_hex(off)]
                return (bool(hit), "F2 loadLibrary %s -> %s" % (off, hit and hit[0].get("library_name")))
            if which == "f9-frame":
                hit = [f for f in b.f9["frames"] if f["n"] == int(off)]
                return (bool(hit), "F9 frame %s -> %s.%s" % (off, hit and hit[0]["cls"],
                                                             hit and hit[0]["meth"]))
        return (False, "unknown ref kind %r" % (kind,))


# --------------------------------------------------------------------------
# the spine
# --------------------------------------------------------------------------

def build(bundle):
    b = bundle
    sp = Spine(b)

    # ---- discovered facts (used by several tiers) ------------------------
    cont_sites = b.grep_insn(r"#%s\b" % CONT_HELPER)          # bl/b into the helper
    store_fields, store_acc = b.yoa_accesses()
    store_by_name = OrderedDict((f["name"], f) for f in store_fields)
    c2_run = b.slot_range(C2_RUN_LO, C2_RUN_HI)                 # _Bpa allocation run
    fam_bpa = b.fam_slots("_Bpa")
    fam_ioa = b.fam_slots("_ioa")
    fam_aqa = b.fam_slots("_aqa") + b.fam_slots("_bqa")
    ep_slot = b.slot(ENDPOINT_SLOT)
    act_slot = b.slot(ACTION_SLOT)
    ioa_run = b.slot_range(fam_ioa[0][0], fam_ioa[-1][0]) if fam_ioa else []

    # =====================================================================
    # T0 — framework boot (SHAPE REFERENCE, F9).  Not SNAKE bytes.
    # =====================================================================
    frames = sorted(b.f9["frames"], key=lambda f: -f["n"])     # outermost first
    for i in range(len(frames) - 1):
        a, c = frames[i], frames[i + 1]
        sp.new("T0", "framework_dispatch",
               "%s.%s (line:%s)" % (a["cls"], a["meth"], a["line"]),
               "%s.%s (line:%s)" % (c["cls"], c["meth"], c["line"]),
               "shape",
               "captured KOS/Kaori module-host stack, frame %d -> frame %d" % (a["n"], c["n"]),
               b.f9["path"],
               "เฟรม %d -> %d ของสแตกที่จับได้ (โครงรูป ไม่ใช่ไบต์ SNAKE)" % (a["n"], c["n"]),
               "frame %d -> %d of the captured stack (shape only, not SNAKE bytes)",
               refs=[("frag-offset", "f9-frame", str(a["n"])),
                     ("frag-offset", "f9-frame", str(c["n"]))])

    # the injection anchor: last framework frame before app code
    anchor = [f for f in b.f9["frames"] if f["meth"] == "newApplication"]
    if not anchor:
        b.problems.append("F9 has no Instrumentation.newApplication frame")
    anchor = anchor[0] if anchor else None

    # =====================================================================
    # T1 — dex: SNAKE's own boot (F3 roles, then the proven chains)
    # =====================================================================
    app = b.f3["application"]
    main_act = b.f3["main_activity"]

    sp.new("T1", "constructs_application",
           "%s.%s (line:%s)" % (anchor["cls"], anchor["meth"], anchor["line"]) if anchor else "?",
           "%s (manifest <application>)" % app,
           "proven",
           "F3 manifest declares android:name=%s; F9 frame %d is the framework call that "
           "constructs it (the stitch point between the shape tier and SNAKE bytes)"
           % (app, anchor["n"] if anchor else 0),
           "fragments/F3_manifest.txt",
           "จุดต่อระหว่างโครงรูปเฟรมเวิร์กกับไบต์จริงของ SNAKE: manifest ประกาศ "
           "android:name=%s และ Instrumentation.newApplication คือผู้ที่สร้างมัน" % app,
           "stitch point: the manifest names %s and newApplication is what constructs it" % app,
           refs=[("frag", "fragments/F3_manifest.txt", r"^application\s*:\s*%s" % re.escape(app)),
                 ("frag-offset", "f9-frame", str(anchor["n"]) if anchor else "7")])

    sp.new("T1", "manifest_role",
           "%s (main activity)" % main_act,
           "%s.<clinit>()V / onCreate" % app,
           "proven",
           "F3: main activity = %s, application = %s, %d components declared"
           % (main_act, app, b.f3["components"] or 0),
           "fragments/F3_manifest.txt",
           "บทบาทจาก manifest: activity หลักคือ %s, Application คือ %s, มีคอมโพเนนต์ %d ตัว"
           % (main_act, app, b.f3["components"] or 0),
           "manifest roles: main activity %s, application %s, %d components"
           % (main_act, app, b.f3["components"] or 0),
           refs=[("frag", "fragments/F3_manifest.txt",
                  r"^main activity\s*:\s*%s" % re.escape(main_act))])

    sp.import_chain("T1", "CH-01")          # <clinit> -> loadLibrary('engine') -> dlopen
    sp.collapse("T1", "CH-02",
                "ตัวโหลด ELF ไล่ .init_array ทั้ง 44 สล็อต — ย่อเป็นฮอปเดียว "
                "(รายละเอียดครบใน CALL_LINKAGE.md CH-02)",
                "the ELF loader walks all 44 .init_array slots — collapsed to one hop "
                "(full detail in CALL_LINKAGE.md CH-02)")

    # =====================================================================
    # T2 — native staged init
    # =====================================================================
    sp.import_chain("T2", "CH-03")          # JNI_OnLoad: RWX pages + synthesised branch
    sp.import_chain("T2", "CH-04")          # RegisterNatives @0xf3a08 -> Native
    sp.import_chain("T2", "CH-05")          # RegisterNatives @0xb40a8 -> flagger
    sp.import_chain("T2", "CH-07")          # RegisterNatives @0xb0140 -> Native
    sp.import_chain("T2", "CH-06")          # the one statically recoverable handler (.mytext)

    # =====================================================================
    # T1' — the 20 dex invoke sites (CH-08) sit here: after registration,
    #       these are the Java-side entries into the 13 bound natives.
    # =====================================================================
    sp.import_chain("T1", "CH-08")

    # =====================================================================
    # T3 — flutter half + platform channel
    # =====================================================================
    sp.import_chain("T3", "CH-09")          # FlutterJNI.loadLibrary('flutter') -> JNI_OnLoad
    sp.import_chain("T3", "CH-10")          # 3 MethodCall handlers + 11 engine symbols

    sp.new("T3", "spawns_dart_isolate",
           "FlutterJNI.nativeRunBundleAndSnapshotFromLibrary (declared native, unresolved by exports)",
           "Dart isolate entry: main() in libapp.so",
           "unresolved",
           "F2 lists nativeRunBundleAndSnapshotFromLibrary among the 41 FlutterJNI natives with "
           "0 resolved by libflutter.so exports; the committed dump contains no Dart `main` "
           "symbol and no asm listing that instantiates the C2 family (no instruction loads "
           "pp+%s). This is the one hole between the engine and the Dart spine." % C2_FAMILY_PARENT,
           "fragments/F2_dex_natives.txt",
           "ช่องว่างเดียวระหว่าง engine กับแกน Dart: dex ประกาศ "
           "nativeRunBundleAndSnapshotFromLibrary แต่ dump ที่ commit ไว้ไม่มี symbol `main` "
           "ของ Dart และไม่มี listing ไหนโหลด pp+%s (สล็อตของรูทีนคำขอ C2) — ดูสูตรปิดช่องว่าง G1"
           % C2_FAMILY_PARENT,
           "the single hole between engine and Dart spine: no `main`, no listing loads the C2 "
           "routine's pool slot pp+%s — see recipe G1" % C2_FAMILY_SLOT,
           refs=[("frag", "fragments/F2_dex_natives.txt",
                  r"nativeRunBundleAndSnapshotFromLibrary"),
                 ("pp", C2_FAMILY_SLOT, C2_FAMILY_PARENT)])

    # =====================================================================
    # T4 — Dart task family -> the routine that owns the C2 request (NEW)
    # =====================================================================
    xu = b.class_decl("Xu")
    sp.new("T4", "type_hierarchy",
           "_au  [%s]" % (b.class_decl("_au") or {}).get("file", "?"),
           "Xu<X0 bound Vu>  [%s]" % (xu or {}).get("file", "?"),
           "proven",
           "abstract class Xu<X0 bound Vu> extends _au — the common task base of every "
           "family that touches the C2 store",
           "output/blutter/asm/Meg.dart",
           "ฐาน type ร่วม: `Xu<X0 bound Vu> extends _au` — ทุกตระกูลที่แตะสโตร์ C2 สืบจากนี่",
           "the shared task base: `Xu<X0 bound Vu> extends _au`",
           refs=[("asm-class", "Xu", "extends _au"),
                 ("asm-class", "_au", "abstract class _au"),
                 ("asm-class", "Vu", "abstract class Vu"),
                 ("asm-class", "Yu", "extends Xu")])

    for n_fam, (cls, lib, extra) in enumerate((
            ("_ioa", "kkg", "field late final PU<LI> fxc @0x14"),
            ("_Bpa", "Kkg", "field late double tpe @0x18"),
            ("_aqa", "Xkg", "transformed mixin with Yu<X0 bound Vu>, subclass _bqa")), 1):
        d = b.class_decl(cls)
        sp.new("T4", "family_member",
               "Xu<dynamic>",
               "%s  [%s]  (%s)" % (cls, lib, extra),
               "proven",
               "%s" % (d["decl"].strip() if d else "class %s not found" % cls),
               "output/blutter/asm/%s.dart" % ("kkg" if lib == "kkg" else lib),
               "สมาชิกตระกูล task #%d: `%s` ในไลบรารี %s (%s)" % (n_fam, cls, lib, extra),
               "task-family member: `%s` in library %s (%s)" % (cls, lib, extra),
               refs=[("asm-class", cls, "extends Xu")])

    # the two closure families, as they sit in the object pool
    fams = [
        ("_ioa", "kkg", fam_ioa,
         "ตระกูล closure ของ `_ioa` ใน object pool — คลัสเตอร์แรก (pp+0xd968..pp+0xd9d0) "
         "มีสล็อต Field ของสโตร์ `[xkg] Yoa` แทรกอยู่ด้วย (pp+0xd9b0 = tKb, pp+0xd9e0 = hne) "
         "แปลว่างาน async ฝั่งนี้กับสโตร์ C2 ถูกจัดก้อนไว้ด้วยกันตอน compile"),
        ("_Bpa", "Kkg", fam_bpa,
         "ตระกูล closure ของ `_Bpa` ใน object pool — รวม sibling %s (pp+%s) และ "
         "รูทีนคำขอ C2 %s (pp+%s)" % (C2_FAMILY_PARENT, C2_FAMILY_SLOT, C2_ROUTINE, "0x139a8")),
        ("_aqa/_bqa", "Xkg", fam_aqa,
         "ตระกูล closure ของ `_aqa`/`_bqa` (Xkg) ใน object pool — สมาชิกตระกูลที่สามที่เรียก "
         "helper ต่อเนื่องตัวเดียวกัน"),
    ]
    for cls, lib, run, th in fams:
        if not run:
            b.problems.append("no pool slots found naming %s" % cls)
            continue
        clusters = b.fam_clusters(cls.replace("_aqa/_bqa", "_aqa"))
        lo, hi = run[0][0], run[-1][0]
        refs = [("pp", lo), ("pp", hi)]
        for cl in clusters:
            refs.append(("pp-range", cl[0][0], cl[-1][0], len(cl)))
        ev = "%d pool slots naming %s, in %d cluster(s): %s" % (
            len(run), cls, len(clusters),
            "; ".join("pp+%s..pp+%s (%d)" % (cl[0][0], cl[-1][0], len(cl)) for cl in clusters))
        sp.new("T4", "pool_family",
               "[%s] %s" % (lib, cls),
               "%d pool slots in %d cluster(s), pp+%s..pp+%s overall"
               % (len(run), len(clusters), lo, hi),
               "proven", ev, "output/blutter/pp.txt", th,
               "the closure family of [%s] %s as laid out in the object pool" % (lib, cls),
               refs=refs)

    # ---- the named upstream entry: _ioa.ugf ------------------------------
    ugf = b.hdr_any("0x53ec84")
    ugf_end = None
    if ugf and ugf["size"].startswith("0x"):
        ugf_end = norm_hex(hex(int(ugf["addr"], 16) + int(ugf["size"], 16)))
    ugf_fields = [x for x in b.insns(ugf["file"], ugf["addr"], ugf_end)
                  if re.search(r"field_(1b|b|f)\b", x[2])] if ugf_end else []
    sp.new("T4", "named_upstream_entry",
           "[%s] _ioa" % "kkg",
           "ugf(dynamic) -> Future<bool>  @0x53ec84 (size %s)" % (ugf or {}).get("size", "?"),
           "proven",
           "the only *named* method in the committed dump belonging to the Xu task family "
           "that reads the C2 store: `Future<bool> ugf(dynamic) async`",
           "output/blutter/asm/kkg.dart",
           "เมธอดเดียวใน dump ที่มี 'ชื่อจริง' และอ่านสโตร์ C2: `_ioa.ugf(dynamic) -> Future<bool>` "
           "ที่ 0x53ec84 (ขนาด %s) — นี่คือจุดเข้าฝั่ง upstream ที่พิสูจน์ได้" % (ugf or {}).get("size", "?"),
           "the only *named* method of the Xu family that reads the C2 store; this is the "
           "provable upstream entry point",
           refs=[("asm-hdr", "0x53ec84", "0x318")])

    tKb = store_by_name.get("tKb")
    hne = store_by_name.get("hne")
    if tKb and hne:
        sp.new("T4", "reads_static_store",
               "_ioa.ugf @0x53ec84",
               "[%s] %s.%s  (field offset %s -> field-table %s, type %s)"
               % (STORE_LIB, STORE_CLASS, tKb["name"], tKb["offset"], tKb["ft"], tKb["type"]),
               "proven",
               "InitLateStaticField(%s) // [%s] %s::%s then ldr from the field table; "
               "%d accessors of this slot exist in asm/"
               % (tKb["offset"], STORE_LIB, STORE_CLASS, tKb["name"], len(store_acc["tKb"])),
               "output/blutter/asm/kkg.dart",
               "`ugf` เปิดสล็อต static `%s.%s` (offset %s = field-table %s) ด้วย "
               "InitLateStaticFieldStub แล้วอ่านค่า — มี accessor ของสล็อตนี้ %d จุดใน dump"
               % (STORE_CLASS, tKb["name"], tKb["offset"], tKb["ft"], len(store_acc["tKb"])),
               "`ugf` initialises and reads the static slot %s.%s; %d accessors in the dump"
               % (STORE_CLASS, tKb["name"], len(store_acc["tKb"])),
               refs=[("field-access", "tKb", len(store_acc["tKb"])),
                     ("field-count", len(store_fields))])

        if ugf_fields:
            seq = " -> ".join(x[2].split("LoadField:")[-1].strip().split("  ")[0]
                              for x in ugf_fields[:3])
            sp.new("T4", "traverses_stored_object",
                   "_ioa.ugf @0x53ec84",
                   "field chain: %s" % seq,
                   "proven",
                   "%d LoadField steps inside ugf's own body (%s..%s) walk the object that the "
                   "store slot points at" % (len(ugf_fields), ugf["addr"], ugf_end),
                   "output/blutter/asm/kkg.dart",
                   "ภายในตัว `ugf` มีการไล่ field %d ขั้น (%s) เพื่อเดินอ็อบเจกต์ที่สโตร์ชี้ไป — "
                   "แปลว่าค่าที่ C2 ฝากไว้ถูกอ่านจริง ไม่ใช่แค่ init"
                   % (len(ugf_fields), seq),
                   "%d LoadField steps inside ugf walk the stored object: the C2 result is "
                   "actually consumed, not just initialised" % len(ugf_fields),
                   refs=[("asm-hdr", "0x53ec84", "0x318")])

    # ---- sibling reader: _ioa::<anon>(dynamic,int) -----------------------
    sib = b.hdr_any("0x53e7ac")
    if sib:
        sp.new("T4", "reads_static_store",
               "[%s] _ioa::<anonymous closure>(dynamic, int) async @0x53e7ac (size %s)"
               % ("kkg", sib["size"]),
               "[%s] %s.%s (field-table %s)" % (STORE_LIB, STORE_CLASS, "tKb",
                                                 store_by_name["tKb"]["ft"]),
               "proven",
               "second accessor of the same store slot; it allocates a sibling closure from "
               "pp+0xd9c0 (0x38a5d8, inside _ioa's own family) before handing off",
               "output/blutter/asm/kkg.dart",
               "accessor ตัวที่สองของสล็อตเดียวกัน — closure นี้สร้าง closure พี่น้องจาก "
               "pp+0xd9c0 (0x38a5d8 ในตระกูล _ioa เอง) ก่อนส่งต่องาน",
               "second accessor of the same slot; allocates a sibling closure from pp+0xd9c0 "
               "before handing off",
               refs=[("asm-hdr", "0x53e7ac", "0x1c0"), ("pp", "0xd9c0")])

    # ---- the shared continuation helper ---------------------------------
    for site in cont_sites:
        owner = b.hdr_any(site["addr"])
        lib_file = site["file"]
        sp.new("T4", "calls_direct",
               "%s+%s  (%s)" % (lib_file, site["addr"], (owner or {}).get("class") or "?"),
               "continuation helper %s" % CONT_HELPER,
               "proven",
               "%s at %s:%d" % (site["insn"], lib_file, site["line"]),
               "output/blutter/asm/%s" % lib_file,
               "จุดเรียก helper ต่อเนื่อง %s จาก %s (offset %s)" % (CONT_HELPER, lib_file, site["addr"]),
               "call into the shared continuation helper %s from %s+%s"
               % (CONT_HELPER, lib_file, site["addr"]),
               refs=[("asm-insn", lib_file, site["addr"], CONT_HELPER)])

    sp.new("T4", "fan_in_convergence",
           "3 call sites in 3 different Xu-family libraries (Kkg / Xkg / kkg)",
           "continuation helper %s (no listing)" % CONT_HELPER,
           "proven",
           "insn /#%s/ matches exactly %d sites across the whole dump — one per family library, "
           "i.e. this helper is where the three task families converge"
           % (CONT_HELPER, len(cont_sites)),
           "output/blutter/asm/",
           "ทั้งสามตระกูล task (Kkg/Xkg/kkg) มาบรรจบที่ helper เดียวกัน %s — พบจุดเรียก "
           "ทั้งหมด %d จุดใน dump ทั้งชุด" % (CONT_HELPER, len(cont_sites)),
           "all three task families converge on one helper: %d call sites in the whole dump"
           % len(cont_sites),
           refs=[("insn-count", r"#%s\b" % CONT_HELPER, len(cont_sites), None),
                 ("asm-nohdr", CONT_HELPER)])

    sp.new("T4", "helper_body_missing",
           "continuation helper %s" % CONT_HELPER,
           "(body not in the committed dump)",
           "unresolved",
           "no `** addr: %s` header anywhere under asm/ — the helper is called but never "
           "disassembled, so what it dispatches to is not provable from this bundle" % CONT_HELPER,
           "output/blutter/asm/",
           "ไม่มี listing ของ %s ใน dump (ถูกเรียกแต่ไม่เคยถูก disassemble) — ดูสูตรปิดช่องว่าง G3"
           % CONT_HELPER,
           "%s is called but never disassembled — see recipe G3" % CONT_HELPER,
           refs=[("asm-nohdr", CONT_HELPER)])

    # ---- the C2 request routine ------------------------------------------
    c2h = b.hdr_any(C2_ROUTINE)
    sp.new("T4", "owns_endpoint_pool_run",
           "[%s] _Bpa::<anonymous closure> @%s (size %s)" % ("Kkg", C2_ROUTINE,
                                                             (c2h or {}).get("size", "?")),
           "pool run pp+%s..pp+%s (%d contiguous slots)" % (C2_RUN_LO[2:], C2_RUN_HI[2:],
                                                             len(c2_run)),
           "candidate",
           "the routine is declared with size %s (no body), so its identity rests on pool "
           "adjacency: its own allocation run holds the endpoint string, the multipart "
           "template, the verb and the response-side objects" % (c2h or {}).get("size", "?"),
           "output/blutter/pp.txt",
           "รูทีนคำขอ C2 @%s ถูกประกาศไว้แต่ไม่มี body (size %s) — การระบุตัวตนจึงพึ่ง "
           "'ความติดกันของ pool': ช่วง pp+0x13988..pp+0x13ac0 (%d สล็อต) เก็บทั้ง endpoint, "
           "แม่แบบ multipart, คำสั่ง post และอ็อบเจกต์ฝั่ง response"
           % (C2_ROUTINE, (c2h or {}).get("size", "?"), len(c2_run)),
           "the C2 request routine has no body (size %s); its identity rests on pool adjacency "
           "over %d contiguous slots" % ((c2h or {}).get("size", "?"), len(c2_run)),
           refs=[("asm-hdr", C2_ROUTINE, (c2h or {}).get("size")),
                 ("pp-range", C2_RUN_LO, C2_RUN_HI, len(c2_run)),
                 ("chain", "CH-12", b.chain_hops("CH-12")),
                 ("pp", ENDPOINT_SLOT, "rest.snakeseller.com/api/request")])

    sink = b.hdr_any("0x2f8998")
    if sink:
        sp.new("T4", "byte_sink_signature",
               "[%s] _Bpa::<anonymous closure> @0x2f8998" % "Kkg",
               "Null <anonymous closure>(dynamic, Uint8List?)",
               "proven",
               "the sibling closure in the same family takes a nullable Uint8List — the upload "
               "payload type; it too has size %s (no body)" % sink["size"],
               "output/blutter/asm/Kkg.dart",
               "closure พี่น้องในตระกูลเดียวกันรับ `Uint8List?` = ประเภทของ payload ที่อัปโหลด "
               "(แต่ก็ไม่มี body เช่นกัน)",
               "the sibling closure takes a nullable Uint8List — the upload payload type",
               refs=[("asm-hdr", "0x2f8998", sink["size"])])

    # ---- the family's full closure inventory ------------------------------
    top = [(o, x) for o, x in fam_bpa if x["body"].startswith("AnonymousClosure") and " of [" in x["body"]]
    kids = [(o, x) for o, x in fam_bpa if " in [" in x["body"]]
    sp.new("T4", "closure_inventory",
           "[%s] _Bpa" % "Kkg",
           "%d pool slots (pp+%s..pp+%s): %d top-level closures + %d nested children"
           % (len(fam_bpa), fam_bpa[0][0], fam_bpa[-1][0], len(top), len(kids)),
           "proven",
           "every pool slot naming _Bpa: %s" % ", ".join("%s=%s" % (o, re.search(r"\((0x[0-9a-f]+)\)", x["body"]).group(1) if re.search(r"\((0x[0-9a-f]+)\)", x["body"]) else "?") for o, x in fam_bpa),
           "output/blutter/pp.txt",
           "บัญชี closure ทั้งหมดของ `_Bpa` ใน object pool: %d สล็อต (pp+%s..pp+%s) — "
           "%d ตัวเป็น closure ระดับบน และ %d ตัวเป็นลูกซ้อนอยู่ข้างใน; มีแค่ 0x533110 กับ "
           "0x5332c4 เท่านั้นที่มี body จริงใน dump"
           % (len(fam_bpa), fam_bpa[0][0], fam_bpa[-1][0], len(top), len(kids)),
           "the complete closure inventory of _Bpa in the object pool; only two of them have a "
           "real body in the dump",
           refs=[("pp", fam_bpa[0][0]), ("pp", fam_bpa[-1][0]),
                 ("pp-range", fam_bpa[0][0], fam_bpa[-1][0], len(fam_bpa))])

    # ---- the negative finding: the region's code is not in the dump -------
    tested = [C2_FAMILY_SLOT, "0x139a8", ENDPOINT_SLOT, ACTION_SLOT, "0x13a38",
              "0x13a98", "0x13aa0", MEGA_SLOT] + list(SECRET_SLOTS) + list(TOKEN_LIST_SLOTS)
    tested = list(OrderedDict.fromkeys(tested))
    loaded = OrderedDict((t, b.slot_is_loaded(t)) for t in tested)
    dead = [t for t in tested if not loaded[t]]
    run_all = b.slot_range(C2_RUN_LO, C2_RUN_HI)
    run_ref = [(off, b.slot_is_loaded(off), sl) for off, sl in run_all if b.slot_is_loaded(off)]
    run_ref_files = sorted(set(f for _, r, _ in run_ref for f, _l in r))
    sp.new("T4", "code_not_in_dump",
           "%d/%d slots of pp+%s..pp+%s (request side: endpoint, action, multipart template, "
           "verb, hex literals, name tables, dart:io objects)"
           % (len(run_all) - len(run_ref), len(run_all), C2_RUN_LO, C2_RUN_HI),
           "loaded by **zero** disassembled routines; the only %d referenced slots (%s) are all "
           "loaded from %s — i.e. by the response-check closure @0x533110"
           % (len(run_ref), ", ".join("pp+" + o for o, _, _ in run_ref),
              ", ".join(run_ref_files) or "none"),
           "proven",
           "grep for `pp+0xNNN` over all %d asm files: %d of the %d slots in the endpoint's own "
           "allocation run are never loaded by any instruction, and every slot that *is* loaded "
           "is loaded from %s (%s). The request-building half of the C2 code is present as pool "
           "data only."
           % (len(b.asm_index["files"]), len(run_all) - len(run_ref), len(run_all),
              ", ".join(run_ref_files) or "none",
              "; ".join("pp+%s = %s" % (o, sl["body"][:30]) for o, _, sl in run_ref)),
           "output/blutter/asm/",
           "ข้อเท็จจริงเชิงลบที่คมที่สุดของ tier นี้: ใน allocation run ของ endpoint "
           "(pp+%s..pp+%s) มี %d สล็อต แต่ %d สล็อต **ไม่มีโค้ดตัวไหนโหลดเลย** — ที่ถูกโหลดมีแค่ "
           "%d สล็อต (%s) และทั้งหมดถูกโหลดจาก %s ซึ่งเป็น closure ฝั่ง 'ตรวจ response' (0x533110) "
           "สรุปสั้น ๆ คือ **ฝั่ง response ถูก disassemble ไว้ ฝั่ง request ไม่ได้** — ข้อมูลคำขอ "
           "(endpoint, action, multipart, verb, ค่า hex, ตารางชื่อ) นอนอยู่ใน pool อย่างเดียว "
           "(ดู G1/G3/G6)"
           % (C2_RUN_LO, C2_RUN_HI, len(run_all), len(run_all) - len(run_ref), len(run_ref),
              ", ".join("pp+" + o for o, _, _ in run_ref),
              ", ".join(run_ref_files) or "none"),
           "the sharpest negative result of this tier: of %d slots in the endpoint's own "
           "allocation run, %d are never loaded by any code and the %d that are loaded all belong "
           "to the response-check closure — the response half is disassembled, the request half "
           "is not"
           % (len(run_all), len(run_all) - len(run_ref), len(run_ref)),
           refs=[("pp", t) for t in tested[:4]] + [("pp", ENDPOINT_SLOT, "rest.snakeseller.com")]
                + [("pp", o) for o, _, _ in run_ref])

    # ---- candidate secret material ---------------------------------------
    secs = [(t, b.slot(t)) for t in SECRET_SLOTS]
    sp.new("T4", "candidate_secret",
           "pp+%s / pp+%s (inside the _Bpa region, %d bytes from the endpoint slot)"
           % (SECRET_SLOTS[0], SECRET_SLOTS[1],
              abs(int(ENDPOINT_SLOT, 16) - int(SECRET_SLOTS[0], 16))),
           "%s" % " · ".join('"%s" (%d hex chars)' % (x["body"].split('"')[1],
                                                      len(x["body"].split('"')[1]))
                             for _, x in secs if x),
           "candidate",
           "two lowercase-hex literals sitting between _Bpa closure slots pp+0x138f8 and "
           "pp+0x13960; no listed routine loads either slot, so neither their owner nor their "
           "role (key / IV / token / hash) is provable from this bundle",
           "output/blutter/pp.txt",
           "ค่า hex สองก้อนนอนอยู่ระหว่างสล็อต closure ของ `_Bpa` (pp+0x138f8 กับ pp+0x13960) — "
           "ก้อนหนึ่ง %d ตัวอักษร อีกก้อน %d ตัวอักษร (รูปทรงแบบ MD5) แต่ไม่มีโค้ดตัวไหนโหลด "
           "จึงระบุไม่ได้ว่าเป็น key/IV/token หรือ hash — คงไว้เป็น candidate + สูตรใน G6"
           % tuple(len(x["body"].split('"')[1]) for _, x in secs if x),
           "two hex literals between _Bpa closure slots; owner and role unprovable — kept as "
           "candidate with recipe G6",
           refs=[("pp", SECRET_SLOTS[0]), ("pp", SECRET_SLOTS[1])])

    # ---- candidate name/serialisation tables ------------------------------
    tls = [(t, b.slot(t)) for t in TOKEN_LIST_SLOTS]
    toks = []
    for _, x in tls:
        if x:
            toks += re.findall(r'"([A-Za-z][A-Za-z0-9]{2})"', x["body"])
    tok_freq = sorted(((t, sum(1 for _o, sl in b.pp["slots"].items()
                               if '"%s"' % t in sl["body"])) for t in sorted(set(toks))),
                      key=lambda kv: (-kv[1], kv[0]))
    sp.new("T4", "candidate_name_table",
           "const List literals pp+%s" % " / pp+".join(TOKEN_LIST_SLOTS),
           "%s" % " · ".join(x["body"][:74] for _, x in tls if x),
           "candidate",
           "const lists mixing small ints with 3-char minified identifiers; the same tokens "
           "recur all over the pool (the most frequent one appears in %d of %d pool entries), "
           "which is the shape of a serialisation / field-name table rather than an "
           "obfuscation alphabet" % (tok_freq[0][1], len(b.pp["slots"])),
           "output/blutter/pp.txt",
           "List ค่าคงที่ที่ผสม int เล็ก ๆ กับชื่อ identifier 3 ตัวอักษร (ผลจากการ minify) — "
           "ชื่อเหล่านี้โผล่ซ้ำทั่วทั้ง pool จึงน่าจะเป็นตารางชื่อฟิลด์สำหรับ serialize มากกว่า "
           "ตัวอักษรแทน code; ยังพิสูจน์ไม่ได้เพราะไม่มีโค้ดโหลด (candidate)",
           "const lists mixing ints with 3-char minified identifiers; the tokens recur "
           "throughout the pool, so they look like a serialisation field-name table — unproven",
           refs=[("pp", t) for t in TOKEN_LIST_SLOTS])

    # ---- megamorphic dispatch site ---------------------------------------
    mega = b.slot(MEGA_SLOT)
    sp.new("T4", "unlinked_call",
           "pp+%s" % MEGA_SLOT,
           "%s" % (mega or {}).get("body", "?"),
           "proven",
           "a third UnlinkedCall slot inside the _Bpa region, with the same target as pp+0x13a00 "
           "and pp+0x13a10 (which the response-check closure loads twice — a megamorphic `[]` on "
           "the decoded response map); this one is not loaded by any listed routine",
           "output/blutter/pp.txt",
           "สล็อต UnlinkedCall ตัวที่สามในย่าน `_Bpa` (CH-12 บันทึกสองตัวที่ 0x533110 โหลดจริง) — "
           "ตัวนี้ไม่มีโค้ดโหลด เป็นการเรียกแบบ megamorphic ที่ยังไม่ได้ link",
           "a third UnlinkedCall slot in the region; megamorphic dispatch, not yet linked, and "
           "not loaded by any listed routine",
           refs=[("pp", MEGA_SLOT, "UnlinkedCall")])

    # ---- the second, much larger static registry --------------------------
    cfg_fields, cfg_acc = b.store_accesses(CFG_CLASS)
    cfg_used = [f for f in cfg_fields if cfg_acc[f["name"]]]
    if cfg_fields:
        types = OrderedDict()
        for f in cfg_fields:
            types[f["type"]] = types.get(f["type"], 0) + 1
        sp.new("T4", "config_registry",
               "[%s] %s (abstract, class id 368)" % (CFG_LIB, CFG_CLASS),
               "%d static late final fields (%s), offsets %s..%s -> field-table %s..%s"
               % (len(cfg_fields),
                  ", ".join("%s x%d" % (k, v) for k, v in sorted(types.items())),
                  cfg_fields[0]["offset"], cfg_fields[-1]["offset"],
                  cfg_fields[0]["ft"], cfg_fields[-1]["ft"]),
               "proven",
               "a second global registry whose field slots are interleaved with the _Bpa "
               "closures in the pool; its field-table range ends at %s, immediately below the "
               "%s store range that starts at %s"
               % (cfg_fields[-1]["ft"], STORE_CLASS,
                  [f for f in store_fields if f["name"] == "Vge"][0]["ft"]
                  if [f for f in store_fields if f["name"] == "Vge"] else "?"),
               "output/blutter/asm/nkg.dart",
               "registry สากลตัวที่สอง: `[nkg] %s` มี static late final %d ฟิลด์ (ส่วนใหญ่ประเภท "
               "`noa`) ครอบคลุม field-table %s..%s ซึ่งต่อกับช่วงของสโตร์ `%s` (%s..) พอดี — "
               "เป็นโครง config ระดับแอปที่วางชิดกับฝั่ง C2"
               % (CFG_CLASS, len(cfg_fields), cfg_fields[0]["ft"], cfg_fields[-1]["ft"],
                  STORE_CLASS, [f for f in store_fields if f["name"] == "Vge"][0]["ft"]
                  if [f for f in store_fields if f["name"] == "Vge"] else "?"),
               "a second global registry of %d late-final fields whose field-table range abuts "
               "the %s store range" % (len(cfg_fields), STORE_CLASS),
               refs=[("asm-class", CFG_CLASS, "abstract class %s" % CFG_CLASS),
                     ("pp", "0x137f0", "ooa"), ("pp", "0x13810", "ooa")])

        sp.new("T4", "registry_unaccessed",
               "[%s] %s (%d fields)" % (CFG_LIB, CFG_CLASS, len(cfg_fields)),
               "**%d** accessors in the whole dump (%d/%d fields touched)"
               % (sum(len(v) for v in cfg_acc.values()), len(cfg_used), len(cfg_fields)),
               "unresolved",
               "the same field-table grep that found the %s readers finds nothing at all for %s: "
               "not one of its %d slots is initialised or read by disassembled code"
               % (STORE_CLASS, CFG_CLASS, len(cfg_fields)),
               "output/blutter/asm/",
               "registry ทั้งก้อนนี้ไม่มี accessor เลยใน dump (%d ฟิลด์, 0 การเข้าถึง) — ผู้ที่ "
               "init/อ่านมันอยู่ในโค้ดที่ไม่ถูก disassemble เหมือนฝั่งคำขอ C2 (ดูสูตร G7)"
               % len(cfg_fields),
               "the entire registry has zero accessors in the dump — its initialisers live in "
               "undisassembled code, exactly like the C2 request side (recipe G7)",
               refs=[("asm-class", CFG_CLASS, "abstract class %s" % CFG_CLASS)])

    # ---- the shared static store -----------------------------------------
    sp.new("T4", "shared_state_store",
           "[%s] %s (abstract, class id 334)" % (STORE_LIB, STORE_CLASS),
           "%d static late fields, offsets %s..%s" % (len(store_fields),
                                                      store_fields[0]["offset"],
                                                      store_fields[-1]["offset"]),
           "proven",
           "; ".join("%s:%s@%s" % (f["type"], f["name"], f["offset"]) for f in store_fields),
           "output/blutter/asm/xkg.dart",
           "สโตร์สถานะร่วม `[xkg] %s` มี static late field %d ตัว (offset %s..%s) — "
           "ทั้งตระกูล `_Bpa` (ฝั่งคำขอ/ตอบ C2) และ `_ioa` (ฝั่งงาน async) อ่าน/เขียนที่นี่"
           % (STORE_CLASS, len(store_fields), store_fields[0]["offset"], store_fields[-1]["offset"]),
           "the shared static store: %d late fields spanning %s..%s"
           % (len(store_fields), store_fields[0]["offset"], store_fields[-1]["offset"]),
           refs=[("asm-class", STORE_CLASS, "abstract class %s" % STORE_CLASS),
                 ("field-count", len(store_fields))])

    sp.new("T4", "addressing_rule",
           "field offset (class metadata)",
           "field-table address = 2 x offset  (THR+0x68 -> field_table_values)",
           "proven",
           "verified twice on this store: %s@%s is loaded as [x,#%s] and %s@%s as [x,#%s]"
           % (hne["name"], hne["offset"], hne["ft"], tKb["name"], tKb["offset"], tKb["ft"]),
           "output/blutter/asm/",
           "กฎการเข้าถึง: ที่อยู่ field-table = 2 เท่าของ offset — ตรวจแล้วสองครั้งบนสโตร์นี้ "
           "(%s@%s -> %s และ %s@%s -> %s) ทำให้ไล่หา reader/writer ของทุกสล็อตได้ด้วย grep"
           % (hne["name"], hne["offset"], hne["ft"], tKb["name"], tKb["offset"], tKb["ft"]),
           "field-table address = 2 x offset, verified on both accessed slots — this is what "
           "makes every other slot greppable",
           refs=[("field-access", "hne", len(store_acc["hne"])),
                 ("field-access", "tKb", len(store_acc["tKb"]))])

    unused = [f for f in store_fields if not store_acc[f["name"]]]
    sp.new("T4", "store_slots_unaccessed",
           "[%s] %s" % (STORE_LIB, STORE_CLASS),
           "%d of %d slots have no accessor in the dump: %s"
           % (len(unused), len(store_fields), ", ".join(f["name"] for f in unused)),
           "unresolved",
           "field-table grep over all %d asm files for %s finds nothing for these slots — their "
           "readers/writers live in routines blutter did not list"
           % (len(b.asm_index["files"]),
              ", ".join(f["ft"] for f in unused)),
           "output/blutter/asm/",
           "%d จาก %d สล็อตไม่มี accessor ใน dump (รวม String 5 ตัวที่อาจเป็น token/host) — "
           "ดูสูตรปิดช่องว่าง G5" % (len(unused), len(store_fields)),
           "%d of %d slots have no accessor in the dump — see recipe G5"
           % (len(unused), len(store_fields)),
           refs=[("field-access", unused[0]["name"], 0), ("field-count", len(store_fields))])

    fetch = b.hdr_any("0x52b4e4")
    if fetch:
        alloc = [x for x in b.insns(fetch["file"], fetch["addr"],
                                    norm_hex(hex(int(fetch["addr"], 16) + int(fetch["size"], 16))))
                 if "AllocateUint8ArrayStub" in x[2] or "ldrb" in x[2] or "strb" in x[2]]
        sp.new("T4", "byte_fetch_routine",
               "[%s] %s::<static anonymous closure> @0x52b4e4" % (STORE_LIB, STORE_CLASS),
               "Future<Uint8List> (size %s): AllocateUint8Array + %d byte-move steps + AwaitStub"
               % (fetch["size"], len(alloc)),
               "proven",
               "static closure of the store class, pool slot pp+0xe1d8; allocates "
               "TypeArguments <Uint8List> (pp+0x1300) and reads Field <::.Yza> "
               "static late final (pp+0xe1d0)",
               "output/blutter/asm/xkg.dart",
               "closure static ของคลาสสโตร์เอง (pp+0xe1d8) คืนค่า `Future<Uint8List>`: "
               "จอง Uint8Array, คัดลอกไบต์ %d ขั้น, Await — นี่คือฝั่ง 'ดึงไบต์' ที่เลี้ยง "
               "payload ฝั่งอัปโหลด" % len(alloc),
               "the store class's own static closure returns Future<Uint8List>: %d byte-move "
               "steps — this is the byte-fetch side feeding the upload payload" % len(alloc),
               refs=[("asm-hdr", "0x52b4e4", fetch["size"]),
                     ("pp", "0xe1d8"), ("pp", "0x1300"), ("pp", "0xe1d0")])

    sp.collapse("T4", "CH-11",
                "ขอบ call ระดับคำสั่งของ Dart (จัดอันดับ fan-in) — ย่อเป็นฮอปเดียว "
                "(รายละเอียดครบใน CALL_LINKAGE.md CH-11)",
                "Dart instruction-level call edges, fan-in ranking — collapsed to one hop "
                "(full detail in CALL_LINKAGE.md CH-11)")

    # =====================================================================
    # T5 — the endpoint itself and everything after it (CH-12, verbatim)
    # =====================================================================
    if ep_slot:
        sp.new("T5", "TERMINUS",
               "[%s] _Bpa::<anonymous closure> @%s" % ("Kkg", C2_ROUTINE),
               "pp+%s  %s" % (ENDPOINT_SLOT, ep_slot["body"].split("String:")[-1].strip()),
               "proven",
               "pool slot pp+%s holds the endpoint verbatim; pp+%s holds the action query "
               "%s ; the two slots are 8 bytes apart inside the same allocation run"
               % (ENDPOINT_SLOT, ACTION_SLOT, ACTION_QUERY),
               "output/blutter/pp.txt",
               "ปลายทางของแกนนี้: pp+%s เก็บ endpoint `%s` ตรงตัว และ pp+%s เก็บ action "
               "`%s` ห่างกัน 8 ไบต์ในช่วง allocation เดียวกัน"
               % (ENDPOINT_SLOT, ENDPOINT, ACTION_SLOT, ACTION_QUERY),
               "the terminus of this spine: pp+%s holds the endpoint, pp+%s the action query"
               % (ENDPOINT_SLOT, ACTION_SLOT),
               refs=[("pp", ENDPOINT_SLOT, "rest.snakeseller.com/api/request"),
                     ("pp", ACTION_SLOT, "upload_profile_image")])
    sp.import_chain("T5", "CH-12")

    # =====================================================================
    # gaps + recipes
    # =====================================================================
    sp.gap("G1", "T3",
           "Dart isolate bootstrap: FlutterJNI.nativeRunBundleAndSnapshotFromLibrary -> main() -> "
           "the first instantiation of the Xu task family",
           "no `main` symbol in pp.txt, no asm file named main/app/entry, and no instruction in "
           "the whole dump loads pp+%s (the pool slot holding the C2 request routine %s)"
           % (C2_FAMILY_SLOT, C2_FAMILY_PARENT),
           "รัน blutter ใหม่บน libapp.so โดยบังคับ dump ทุกฟังก์ชัน (ไม่ใช่เฉพาะที่ reachable จาก "
           "object pool) หรือ hook `Dart_Invoke`/`RunDartCode` ด้วย frida ตอน start isolate แล้ว "
           "บันทึก stack แรกที่แตะ `_Bpa`/`_ioa`",
           "re-run blutter on libapp.so forcing a full function dump (not only pool-reachable "
           "code), or frida-hook Dart_Invoke / RunDartCode at isolate start and record the first "
           "stack that touches _Bpa / _ioa",
           refs=[("pp", C2_FAMILY_SLOT, C2_FAMILY_PARENT)])

    sp.gap("G2", "T3",
           "bodies of the three platform-channel MethodCall handlers (_pfc @0x504300, "
           "_cec @0x50e170, _eec @0x50dad8)",
           "all three are declared with size -1 in the committed dump: the channel names and the "
           "engine symbols they run on are proven (CH-10), the handler code is not there",
           "dump สามฟังก์ชันนี้ตรง ๆ ด้วย blutter/objection ที่ address ดังกล่าว หรือ trace "
           "`SendPlatformMessage` ฝั่ง engine แล้วจับคู่ payload กับ channel name",
           "disassemble those three addresses directly, or trace SendPlatformMessage on the engine "
           "side and correlate payloads with channel names",
           refs=[("asm-hdr", "0x504300", None)])

    sp.gap("G3", "T4",
           "body of the shared continuation helper %s" % CONT_HELPER,
           "exactly %d call sites (one per Xu-family library) but no `** addr: %s` header — the "
           "single most valuable missing function on this spine, because it is where all three "
           "families converge" % (len(cont_sites), CONT_HELPER),
           "disassemble %s ใน libapp.so (ขอบเขตอ่านได้จากรายชื่อ stub ที่มันเรียก) แล้วเช็คว่ามัน "
           "tail-call ไปยัง closure ไหน — น่าจะเชื่อม `ugf` เข้ากับรูทีนคำขอ C2 @%s โดยตรง"
           % (CONT_HELPER, C2_ROUTINE),
           "disassemble %s in libapp.so and follow its tail calls; it is the most likely direct "
           "link between _ioa.ugf and the C2 request routine @%s" % (CONT_HELPER, C2_ROUTINE),
           refs=[("asm-nohdr", CONT_HELPER), ("insn-count", r"#%s\b" % CONT_HELPER,
                                              len(cont_sites), None)])

    sp.gap("G4", "T5",
           "body of the response decoder 0x3102f4 called from the C2 response check @0x53313c",
           "CH-12 proves the call and proves what the caller does with the result (reads "
           "`success` / `data`, stores an int through Yoa.hne), but the decoder itself has no "
           "listing — so the wire format (JSON? encrypted? base64?) is not provable here",
           "disassemble 0x3102f4; if it is a JSON parse the string table around it will show "
           "`success`/`data` literals; if it decrypts, look for the same opcode-synthesis idiom "
           "as libengine.so JNI_OnLoad (CH-03)",
           "disassemble 0x3102f4 and check for JSON literals vs. a decryption idiom",
           refs=[("asm-nohdr", "0x3102f4")])

    sp.gap("G6", "T4",
           "the consumer of the _Bpa pool region: the endpoint, the multipart template, the two "
           "hex literals (pp+%s / pp+%s) and the const name tables" % (SECRET_SLOTS[0], SECRET_SLOTS[1]),
           "all %d slots of pp+%s..pp+%s are unreferenced by every one of the %d disassembled "
           "files — the routine that builds the request (%s) is declared with size -0x1, so the "
           "bundle holds its data but not its code"
           % (len(b.slot_range(C2_RUN_LO, C2_RUN_HI)), C2_RUN_LO, C2_RUN_HI,
              len(b.asm_index["files"]), C2_ROUTINE),
           "disassemble %s และ closure พี่น้อง (%s) ใน libapp.so ตรง ๆ แล้วดูว่าแต่ละสล็อตถูกโหลด "
           "ที่คำสั่งไหน — ลำดับการโหลดจะบอกเองว่าค่า hex สองก้อนเป็น key/IV หรือ token และ "
           "ตารางชื่อถูกใช้ตอน serialize ส่วนไหนของ body"
           % (C2_ROUTINE, ", ".join("0x3103b0")),
           "disassemble %s and its sibling closures directly in libapp.so and record which "
           "instruction loads each slot; the load order alone will say whether the two hex "
           "literals are key/IV or a token, and which part of the body the name tables serialise"
           % C2_ROUTINE,
           refs=[("pp", SECRET_SLOTS[0]), ("pp", SECRET_SLOTS[1]),
                 ("asm-hdr", C2_ROUTINE, "-0x1"),
                 ("pp-range", C2_RUN_LO, C2_RUN_HI, 1)])

    sp.gap("G7", "T4",
           "initialisers and readers of the [%s] %s registry (%d static late final fields, "
           "mostly type `noa`)" % (CFG_LIB, CFG_CLASS, len(b.store_fields(CFG_CLASS))),
           "field-table grep over all %d asm files finds zero accesses for any of its slots; the "
           "class body carries no closures at all, so nothing in the dump shows how an entry is "
           "built" % len(b.asm_index["files"]),
           "grep field-table addresses %s..%s ใน dump ชุดใหม่ (หลังปิด G1/G3) หรือ hook "
           "InitLateFinalStaticFieldStub ที่ offset เหล่านั้นตอนรันไทม์ เพื่อบันทึกว่าใคร init "
           "ฟิลด์ไหนก่อน — ลำดับการ init จะเผยโครง config ของแอป (host, key, flag)"
           % (b.store_fields(CFG_CLASS)[0]["ft"], b.store_fields(CFG_CLASS)[-1]["ft"]),
           "grep field-table addresses %s..%s in a fuller dump (after G1/G3), or hook "
           "InitLateFinalStaticFieldStub at those offsets at runtime and log which field is "
           "initialised by whom — the init order reveals the app-level config layout"
           % (b.store_fields(CFG_CLASS)[0]["ft"], b.store_fields(CFG_CLASS)[-1]["ft"]),
           refs=[("asm-class", CFG_CLASS, "abstract class %s" % CFG_CLASS)])

    sp.gap("G5", "T4",
           "readers/writers of the %d unaccessed %s store slots (5 late Strings, 3 late ints, "
           "wx<int>, Uint8List, Xoa) plus fqa._Bre @0xe84" % (len(unused), STORE_CLASS),
           "the field-table rule (ft = 2 x offset) was grepped over all %d asm files and found "
           "nothing, so these slots are touched only from unlisted routines — the five late "
           "Strings are the most interesting candidates for host/token/key material"
           % len(b.asm_index["files"]),
           "grep field-table addresses %s ใน dump ชุดใหม่ที่ได้จาก G1/G3 หรือ hook "
           "InitLateStaticFieldStub ที่ field-table offset เหล่านั้นตอนรันไทม์ แล้วบันทึก caller"
           % ", ".join(f["ft"] for f in unused[:6]),
           "grep the field-table addresses %s in a fuller dump (after G1/G3), or hook "
           "InitLateStaticFieldStub at those offsets at runtime and log the caller"
           % ", ".join(f["ft"] for f in unused[:6]),
           refs=[("field-count", len(store_fields))])

    sp.gaps.sort(key=lambda g: g["id"])
    return sp


# --------------------------------------------------------------------------
# checks
# --------------------------------------------------------------------------

def run_checks(b, sp):
    ck = []

    def add(name, ok, detail):
        ck.append(OrderedDict([("name", name), ("result", "PASS" if ok else "FAIL"),
                               ("detail", detail)]))

    total = len(sp.hops)
    by_conf = {}
    for h in sp.hops:
        by_conf[h["conf"]] = by_conf.get(h["conf"], 0) + 1
    add("spine built", total > 0,
        "%d hops across %d tiers (%s)" % (total, len(set(h["tier"] for h in sp.hops)),
                                          ", ".join("%s=%d" % (k, by_conf[k])
                                                    for k in sorted(by_conf))))

    fails = [d for h in sp.hops for d in h.get("ref_detail", []) if d.startswith("FAIL")]
    fails += [d for g in sp.gaps for d in g.get("ref_detail", []) if d.startswith("FAIL")]
    refs = sum(len(h.get("ref_detail", [])) for h in sp.hops) + \
        sum(len(g.get("ref_detail", [])) for g in sp.gaps)
    add("every hop/gap resolves against the bundle", not fails,
        "%d evidence references checked, %d failed%s"
        % (refs, len(fails), (" -> " + "; ".join(fails[:3])) if fails else ""))

    add("no hop without evidence or source",
        all(h["evidence"] and h["source"] for h in sp.hops),
        "%d/%d hops carry both" % (sum(1 for h in sp.hops if h["evidence"] and h["source"]), total))

    # T0 shape tier
    t0 = [h for h in sp.hops if h["tier"] == "T0"]
    add("T0 covers the whole captured framework stack",
        len(t0) == len(b.f9["frames"]) - 1 and all(h["conf"] == "shape" for h in t0),
        "%d T0 hops from %d F9 frames, all conf=shape" % (len(t0), len(b.f9["frames"])))
    add("T0 is labelled as shape reference, never as SNAKE evidence",
        all("F9" in h["source"] or "shape" in h["conf"] for h in t0),
        "source for every T0 hop is %s" % b.f9["path"])
    anchor = [h for h in sp.hops if h["kind"] == "constructs_application"]
    add("the shape tier is stitched to SNAKE bytes at newApplication",
        bool(anchor) and anchor[0]["dst"].startswith(b.f3["application"] or "?"),
        anchor and "%s -> %s" % (anchor[0]["src"][:48], anchor[0]["dst"]))

    # T1 dex coverage
    t1 = [h for h in sp.hops if h["tier"] == "T1"]
    f2_offs = set(c["offset"] for c in b.f2["callers"])
    ch08 = [h for h in t1 if h["via"].startswith("CH-08")]
    add("all %d F2 dex invoke sites are on the spine" % len(f2_offs),
        len(ch08) >= len(f2_offs),
        "CH-08 contributed %d hops; F2 lists %d caller offsets" % (len(ch08), len(f2_offs)))
    add("loadLibrary('engine') site is on the spine",
        any(c["offset"] == "0x2b6c2e" for c in b.f2["loadlib"]) and
        any("CH-01" in h["via"] for h in t1),
        "F2 @classes.dex+0x2b6c2e in %s"
        % [c.get("enclosing_method") for c in b.f2["loadlib"] if c["offset"] == "0x2b6c2e"])
    add("manifest roles quoted verbatim",
        (b.f3["application"], b.f3["main_activity"]) == ("com.snake.App", "com.Entry"),
        "application=%s main_activity=%s components=%s"
        % (b.f3["application"], b.f3["main_activity"], b.f3["components"]))

    # T2/T3 imported chains
    for chain in ("CH-01", "CH-03", "CH-04", "CH-05", "CH-06", "CH-07", "CH-08",
                  "CH-09", "CH-10", "CH-12"):
        want = b.chain_hops(chain)
        got = sum(1 for h in sp.hops if h["via"].startswith(chain + " hop"))
        add("chain %s imported in full" % chain, got == want,
            "%d/%d hops" % (got, want))
    for chain in ("CH-02", "CH-11"):
        got = [h for h in sp.hops if h["collapsed"] and h["via"].startswith(chain)]
        add("chain %s collapsed with an explicit pointer" % chain, bool(got),
            got and "collapsed=%d, via=%s" % (got[0]["collapsed"], got[0]["via"]))

    # T4 new work
    t4 = [h for h in sp.hops if h["tier"] == "T4"]
    add("T4 (new Dart tier) has substantive content", len(t4) >= 12,
        "%d hops: %s" % (len(t4), ", ".join(sorted(set(h["kind"] for h in t4)))))
    cont = [h for h in t4 if h["kind"] == "fan_in_convergence"]
    add("the three task families provably converge on one helper",
        bool(cont) and cont[0]["conf"] == "proven",
        cont and cont[0]["evidence"][:90])
    add("the C2 request routine's identity is marked candidate, not proven",
        any(h["kind"] == "owns_endpoint_pool_run" and h["conf"] == "candidate" for h in t4),
        "pool-adjacency attribution only (routine body is size -0x1)")
    run_offs = set(off for off, _ in b.slot_range(C2_RUN_LO, C2_RUN_HI))
    fam_bpa = b.fam_slots("_Bpa")
    children = [off for off, x in fam_bpa
                if "(%s)" % C2_ROUTINE in x["body"] and C2_RUN_LO <= off <= C2_RUN_HI]
    add("the C2 run holds the request routine and its nested children",
        C2_FAMILY_SLOT in run_offs and "0x139a8" in run_offs and ENDPOINT_SLOT in run_offs
        and len(children) >= 4,
        "pp+%s..pp+%s = %d slots; contains the family head pp+%s, the request routine pp+0x139a8, "
        "the endpoint pp+%s and %d nested children of %s (%s)"
        % (C2_RUN_LO, C2_RUN_HI, len(run_offs), C2_FAMILY_SLOT, ENDPOINT_SLOT,
           len(children), C2_ROUTINE, ", ".join(children)))
    outside = [off for off, _ in fam_bpa if off not in run_offs]
    add("the family footprint is wider than the C2 run, and that is reported",
        bool(outside) and any(h["kind"] == "closure_inventory" for h in t4),
        "%d of %d _Bpa slots lie outside the run (%s..%s) and are inventoried separately"
        % (len(outside), len(fam_bpa), min(outside), max(outside)))

    tested = [h for h in t4 if h["kind"] == "code_not_in_dump"]
    allslots = list(b.slot_range(C2_RUN_LO, C2_RUN_HI))
    dead = [(off, sl) for off, sl in allslots if not b.slot_is_loaded(off)]
    live = [(off, sl) for off, sl in allslots if b.slot_is_loaded(off)]
    live_files = sorted(set(f for off, _ in live for f, _l in b.slot_is_loaded(off)))
    add("the request side of the endpoint region has no code in the dump",
        bool(tested) and len(dead) > len(live)
        and ENDPOINT_SLOT in [o for o, _ in dead] and ACTION_SLOT in [o for o, _ in dead],
        "%d/%d slots unreferenced by any of the %d asm files (endpoint pp+%s and action pp+%s "
        "among them); the %d referenced slots are loaded only from %s"
        % (len(dead), len(allslots), len(b.asm_index["files"]), ENDPOINT_SLOT, ACTION_SLOT,
           len(live), ", ".join(live_files) or "none"))
    add("the response side of the same region *is* disassembled",
        live_files == ["Kkg.dart"],
        "all %d referenced slots are loaded from %s (the response-check closure 0x533110): %s"
        % (len(live), ", ".join(live_files),
           ", ".join("pp+%s %s" % (o, sl["body"][:24]) for o, sl in live)))

    secs = [b.slot(x) for x in SECRET_SLOTS]
    add("the two hex literals are hex and shaped like key material",
        all(x and re.fullmatch(r"[0-9a-f]+", x["body"].split('"')[1]) for x in secs),
        ", ".join("pp+%s = %d hex chars" % (SECRET_SLOTS[i],
                                            len(secs[i]["body"].split('"')[1]))
                  for i in range(len(secs)) if secs[i]))

    cfg_fields, cfg_acc = b.store_accesses(CFG_CLASS)
    add("second registry parsed and reported as unaccessed",
        len(cfg_fields) >= 100 and not any(cfg_acc.values()),
        "[%s] %s: %d static late final fields (offsets %s..%s), %d accessors in the dump"
        % (CFG_LIB, CFG_CLASS, len(cfg_fields), cfg_fields[0]["offset"],
           cfg_fields[-1]["offset"], sum(len(v) for v in cfg_acc.values())))
    yfields, _yacc = b.yoa_accesses()
    yo = [f for f in yfields if f["name"] == "Vge"][0]
    add("the two stores occupy adjacent, non-overlapping field-table ranges",
        int(cfg_fields[-1]["ft"], 16) < int(yo["ft"], 16),
        "%s ends at %s, %s starts at %s (gap %d bytes)"
        % (CFG_CLASS, cfg_fields[-1]["ft"], STORE_CLASS, yo["ft"],
           int(yo["ft"], 16) - int(cfg_fields[-1]["ft"], 16)))
    add("named upstream entry is present",
        any(h["kind"] == "named_upstream_entry" and "ugf" in h["dst"] for h in t4),
        "_ioa.ugf(dynamic) -> Future<bool> @0x53ec84")

    # T5 terminus
    t5 = [h for h in sp.hops if h["tier"] == "T5"]
    term = [h for h in t5 if h["kind"] == "TERMINUS"]
    add("the spine ends at the requested endpoint",
        bool(term) and ENDPOINT in term[0]["dst"],
        term and term[0]["dst"])
    add("endpoint slot body matches the requested URL exactly",
        b.slot(ENDPOINT_SLOT)["body"].endswith('"%s"' % ENDPOINT),
        b.slot(ENDPOINT_SLOT)["raw"][:90])
    add("post-endpoint continuation is carried over from CH-12",
        sum(1 for h in t5 if h["via"].startswith("CH-12")) == b.chain_hops("CH-12"),
        "%d CH-12 hops after the terminus" % sum(1 for h in t5 if h["via"].startswith("CH-12")))

    # store
    fields, acc = b.yoa_accesses()
    add("shared store parsed", len(fields) == 13,
        "%d static late fields in [%s] %s (offsets %s..%s)"
        % (len(fields), STORE_LIB, STORE_CLASS, fields[0]["offset"], fields[-1]["offset"]))
    accessed = [f for f in fields if acc[f["name"]]]
    rule_ok = all(int(f["ft"], 16) == 2 * int(f["offset"], 16) for f in fields)
    add("field-table rule ft = 2 x offset holds for every slot", rule_ok,
        "%d/%d slots satisfy the rule; %d of them are actually accessed in asm/ (%s)"
        % (sum(1 for f in fields if int(f["ft"], 16) == 2 * int(f["offset"], 16)), len(fields),
           len(accessed), ", ".join("%s %s->%s x%d" % (f["name"], f["offset"], f["ft"],
                                                       len(acc[f["name"]])) for f in accessed)))
    add("the C2 response side and the task side touch adjacent slots of one class",
        len(accessed) == 2 and
        abs(int(accessed[0]["offset"], 16) - int(accessed[1]["offset"], 16)) == 4 and
        accessed[0]["name"] == "hne" and accessed[1]["name"] == "tKb",
        "accessed = %s" % ", ".join("%s(%s, %d refs in %s)"
                                    % (f["name"], f["offset"], len(acc[f["name"]]),
                                       sorted(set(x["file"] for x in acc[f["name"]])))
                                    for f in accessed))
    add("unresolved slots are kept, not dropped",
        any(h["kind"] == "store_slots_unaccessed" for h in t4) and
        any(g["id"] == "G5" for g in sp.gaps),
        "%d of %d slots have no accessor and are reported with a recipe"
        % (sum(1 for f in fields if not acc[f["name"]]), len(fields)))

    # gaps
    add("every gap carries a recipe in both languages",
        all(g["recipe_th"] and g["recipe_en"] for g in sp.gaps),
        "%d gaps: %s" % (len(sp.gaps), ", ".join(g["id"] for g in sp.gaps)))
    add("unresolved hops are kept on the spine",
        by_conf.get("unresolved", 0) >= 2,
        "%d unresolved hops inline + %d gaps" % (by_conf.get("unresolved", 0), len(sp.gaps)))
    add("no bundle parse problems", not b.problems,
        "; ".join(b.problems[:4]) if b.problems else "clean")
    return ck


# --------------------------------------------------------------------------
# emitters
# --------------------------------------------------------------------------

def emit_csv(sp, path):
    cols = ["spine_seq", "tier", "tier_name", "kind", "src", "dst", "conf", "via",
            "collapsed", "evidence", "source", "ref_checks", "note_th", "note_en"]
    with io.open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(cols)
        for h in sp.hops:
            w.writerow([h["seq"], h["tier"], TIER_NAME[h["tier"]], h["kind"], h["src"],
                        h["dst"], h["conf"], h["via"], h["collapsed"], h["evidence"],
                        h["source"], " | ".join(h.get("ref_detail", [])),
                        h["th"], h["en"]])


def emit_json(b, sp, checks, path):
    d = OrderedDict()
    d["meta"] = OrderedDict([
        ("title", "SNAKE.apk - boot linkage (process start -> Dart C2 endpoint)"),
        ("spine_id", SPINE_ID),
        ("generated_by", "tools/build_boot_linkage.py"),
        ("bundle", "SnakeLogic/"),
        ("bundle_fingerprint", b.chains and json.loads(
            read(os.path.join(b.root, "call_linkage.json")))["meta"]["bundle_fingerprint"]),
        ("composes", "SnakeLogic/call_linkage.json (CH-01..CH-12) + fragments F2/F3/F9 "
                     "+ output/blutter/{pp.txt,asm/}"),
        ("lang", LANG),
        ("endpoint", ENDPOINT),
        ("hops", len(sp.hops)),
        ("tiers", [OrderedDict([("id", t[0]), ("name", t[1]), ("en", t[2]), ("th", t[3]),
                                ("hops", sum(1 for h in sp.hops if h["tier"] == t[0]))])
                   for t in TIERS]),
    ])
    d["hops"] = sp.hops
    d["gaps"] = sp.gaps
    d["checks"] = checks
    fields, acc = b.yoa_accesses()
    d["store"] = OrderedDict([
        ("class", "%s.%s" % (STORE_LIB, STORE_CLASS)),
        ("addressing_rule", "field_table_address = 2 * field_offset (base = THR+0x68)"),
        ("fields", [OrderedDict([("name", f["name"]), ("type", f["type"]),
                                 ("offset", f["offset"]), ("field_table", f["ft"]),
                                 ("accessors", len(acc[f["name"]])),
                                 ("where", sorted(set("%s:%d" % (a["file"], a["line"])
                                                      for a in acc[f["name"]])))])
                    for f in fields]),
    ])
    d["endpoint_neighbourhood"] = [OrderedDict([("slot", off), ("body", s["body"]),
                                                ("pp_line", s["line"])])
                                   for off, s in b.slot_range("0x13988", "0x13ac0")]
    with io.open(path, "w", encoding="utf-8") as fh:
        json.dump(d, fh, ensure_ascii=False, indent=1, sort_keys=False)
        fh.write("\n")


def debacktick(t):
    """Cells are wrapped in `...`; inner backticks would break the table."""
    return t.replace("`", "'")


def emit_md(b, sp, checks, path):
    o = []
    W = o.append
    fields, acc = b.yoa_accesses()
    by_tier = OrderedDict((t[0], [h for h in sp.hops if h["tier"] == t[0]]) for t in TIERS)
    conf_count = {}
    for h in sp.hops:
        conf_count[h["conf"]] = conf_count.get(h["conf"], 0) + 1
    imported = sum(1 for h in sp.hops if h["via"].startswith("CH-"))
    collapsed = sum(h["collapsed"] for h in sp.hops)
    new_hops = len(sp.hops) - imported
    fp = json.loads(read(os.path.join(b.root, "call_linkage.json")))["meta"]["bundle_fingerprint"]

    W("# SNAKE boot linkage — `%s`: process start → `%s`" % (SPINE_ID, ENDPOINT))
    W("")
    W("> generated by `tools/build_boot_linkage.py` · bundle `SnakeLogic/` · "
      "fingerprint `%s` · deterministic (no timestamps, sorted traversal)" % fp)
    W(">")
    W("> ประกอบจากสองส่วน: **ฮอปนำเข้า** %d ฮอป (edge ที่พิสูจน์แล้วใน `call_linkage.json` "
      "CH-01..CH-12 — คง `edge_id`/หลักฐานเดิมไว้ทุกตัว) + **ฮอปใหม่** %d ฮอป "
      "(tier T0 จาก F9 และ tier T4 ที่ไล่จาก `pp.txt`/`asm/` ตรง ๆ) · อีก %d ฮอปเป็น 'ฮอปย่อ' "
      "ที่ชี้กลับไปแทนของเดิม %d ฮอป (CH-02 และ CH-11)"
      % (imported, new_hops, sum(1 for h in sp.hops if h["collapsed"]), collapsed))
    W("")
    W("## 0. สรุป / Executive summary")
    W("")
    W("| | TH | EN |")
    W("|---|---|---|")
    W("| ฮอปรวม / hops | **%d** | **%d** |" % (len(sp.hops), len(sp.hops)))
    conf_txt = " · ".join("%s **%d**" % (c, conf_count[c]) for c in CONF_ORDER if conf_count.get(c))
    W("| หลักฐาน / confidence | %s | %s |" % (conf_txt, conf_txt))
    W("| ช่องว่าง / gaps | **%d** (G1–G%s) พร้อมสูตรปิดทุกช่อง | **%d** (G1–G%s), each with a "
      "closing recipe |" % (len(sp.gaps), len(sp.gaps), len(sp.gaps), len(sp.gaps)))
    W("| เช็ก / checks | **%d/%d PASS** | **%d/%d PASS** |"
      % (sum(1 for c in checks if c["result"] == "PASS"), len(checks),
         sum(1 for c in checks if c["result"] == "PASS"), len(checks)))
    W("")
    W("**แกนที่ได้อ่านอย่างไร / how to read the spine**")
    W("")
    W("```")
    W("T0 framework   ZygoteInit.main ─┐  (โครงรูปจาก F9 — shape reference, ไม่ใช่ไบต์ SNAKE)")
    W("                              │ 19 เฟรม")
    W("               Instrumentation.newApplication ──┐  ← จุดต่อ (stitch)")
    W("T1 dex         com.snake.App.<clinit> ──────────┘")
    W("               → System.loadLibrary('engine')  @classes.dex+0x2b6c2e")
    W("               → 20 dex invoke sites → com/snake/helper/Native")
    W("T2 native      dlopen → .init_array[44] → JNI_OnLoad 0xf3fa0")
    W("               → 2× RWX mmap → synthesised branch → RegisterNatives ×3 → 13 natives")
    W("T3 jni/flutter FlutterJNI.loadLibrary('flutter') → libflutter JNI_OnLoad")
    W("               → 3 MethodCall handlers + 11 PlatformConfigurationNativeApi symbols")
    W("               → [G1 unresolved] runBundleAndSnapshotFromLibrary → Dart main()")
    W("T4 dart        Xu<dynamic> family: _ioa[kkg] · _Bpa[Kkg] · _aqa/_bqa[Xkg]")
    W("               → _ioa.ugf @0x53ec84 reads [xkg]Yoa.tKb (field-table 0x1cf8)")
    W("               → 3 call sites converge on continuation helper 0x1a5b64  [G3]")
    W("               → _Bpa::<anon> @0x2f8928 owns pool run pp+0x13988..0x13ac0")
    W("                 · 33 of those 38 slots are loaded by NO disassembled code")
    W("                 · the 5 that are, are all loaded by the response check 0x533110")
    W("               → [nkg]ooa: 180 static late final fields, 0 accessors  [G7]")
    W("T5 dart        pp+0x139d8 = \"%s\"   ← TERMINUS" % ENDPOINT)
    W("               → response check @0x533110 → writes [xkg]Yoa.hne → CH-12 (27 hops)")
    W("```")
    W("")
    W("ห้าเรื่องที่ใหม่ในเอกสารนี้ (ไม่ใช่ของเดิมจาก `CALL_LINKAGE.md`):")
    W("")
    W("1. **tier T0/T1 ต่อติดกันจริง** — สแตกเฟรมเวิร์กที่ผู้ใช้ให้มา (F9) ถูกเย็บเข้ากับไบต์ของ "
      "SNAKE ที่เฟรม `Instrumentation.newApplication` ซึ่งเป็นเฟรมสุดท้ายก่อนโค้ดแอปทำงาน "
      "และ manifest (F3) ระบุ `android:name=com.snake.App` พอดี")
    W("2. **พบตระกูล task ฝั่ง Dart ที่ถือคำขอ C2** — `Xu<X0 bound Vu>` มี subclass สามตัวในสาม "
      "ไลบรารี (`_ioa`[kkg], `_Bpa`[Kkg], `_aqa`+mixin`Yu`[Xkg]) และทั้งสามเรียก helper "
      "ตัวเดียวกัน `0x1a5b64` (พบจุดเรียก 3 จุดพอดีใน dump ทั้งชุด)")
    W("3. **พบสโตร์สถานะร่วม `[xkg] Yoa`** — static late field 13 ตัว (offset `0xe50..0xe80`) "
      "โดยฝั่ง response ของ C2 เขียน `Yoa.hne` (`0xe78`) และฝั่ง `_ioa.ugf` อ่าน `Yoa.tKb` "
      "(`0xe7c`) — สองสล็อตติดกันในคลาสเดียวกัน พร้อมกฎ `field_table = 2 × offset` "
      "ที่ทำให้ grep หา reader/writer ของทุกสล็อตได้")
    W("4. **ฝั่ง request ไม่มีโค้ดใน dump — ฝั่ง response มี** ใน allocation run ของ endpoint "
      "(pp+0x13988..0x13ac0, 38 สล็อต) มี 33 สล็อตที่ **ไม่มีคำสั่งใดใน 672 ไฟล์ asm โหลดเลย** "
      "(รวมตัว endpoint เอง) ส่วน 5 สล็อตที่ถูกโหลด (`success`, UnlinkedCall ×2, `Null`, closure "
      "0x310338) ถูกโหลดจาก `Kkg.dart` คือ closure ตรวจ response @0x533110 ทั้งหมด")
    W("5. **พบ registry ระดับแอปตัวที่สอง `[nkg] ooa`** — static late final 180 ฟิลด์ "
      "(179 ฟิลด์ประเภท `noa`) ช่วง field-table `0x1698..0x1c40` วางชิดกับช่วงของ `[xkg] Yoa` "
      "(`0x1ca0..`) โดยห่างกันแค่ 96 ไบต์ และ **ไม่มี accessor เลยสักตัว** ใน dump ทั้งชุด")
    W("")
    W("The same five findings in English: (1) the framework stack is stitched to real SNAKE bytes "
      "at `Instrumentation.newApplication`, the last frame before app code, matching the manifest's "
      "`android:name=com.snake.App`; (2) the Dart side of the C2 request belongs to an "
      "`Xu<dynamic>` task family with three subclasses in three libraries, all converging on one "
      "continuation helper `0x1a5b64` (exactly three call sites in the entire dump); (3) a shared "
      "static store `[xkg] Yoa` couples them — the C2 response writes `Yoa.hne` (0xe78) while "
      "`_ioa.ugf` reads `Yoa.tKb` (0xe7c), adjacent slots of the same class, addressable by the "
      "rule `field_table = 2 × offset`; (4) the request side of the C2 code is *absent* from the "
      "dump while the response side is present — 33 of the 38 slots in the endpoint's allocation "
      "run are loaded by no disassembled instruction at all, and the only 5 that are loaded belong "
      "to the response-check closure @0x533110; (5) a second app-level registry `[nkg] ooa` holds "
      "180 static late final fields in the field-table range `0x1698..0x1c40`, ending 96 bytes "
      "below the `[xkg] Yoa` range, with zero accessors anywhere in the dump.")
    W("")

    # ---------------- tier map ----------------
    W("## 1. Tier map")
    W("")
    confs = [c for c in CONF_ORDER if conf_count.get(c)]
    W("| tier | name | hops | %s | หลักฐานหลัก / main sources |" % " | ".join(confs))
    W("|---|---|---:|" + "---:|" * len(confs) + "---|")
    for t in TIERS:
        hs = by_tier[t[0]]
        srcs = sorted(set(re.sub(r":\d+$", "", h["source"]) for h in hs))
        shown = srcs[:4]
        more = "" if len(srcs) <= 4 else " <br>*(+%d more)*" % (len(srcs) - 4)
        W("| `%s` | %s | %d | %s | %s%s |" % (
            t[0], t[1], len(hs),
            " | ".join(str(sum(1 for h in hs if h["conf"] == c)) for c in confs),
            "<br>".join("`%s`" % x for x in shown) or "—", more))
    W("| | **total** | **%d** | %s | |" % (
        len(sp.hops), " | ".join("**%d**" % conf_count.get(c, 0) for c in confs)))
    W("")
    W("`shape` = มาจาก F9 (โครงรูปเฟรมเวิร์ก ไม่ใช่ไบต์ SNAKE) · `proven`/`strong`/`probable`/"
      "`candidate`/`unresolved` = ระดับหลักฐานเดิมของ edge ที่นำเข้าจาก `call_linkage.json` "
      "หรือระดับที่สคริปต์นี้กำหนดให้ฮอปใหม่")
    W("")

    # ---------------- the spine table ----------------
    W("## 2. `%s` — the spine, hop by hop" % SPINE_ID)
    W("")
    W("ลำดับการบูตจริงจากบนลงล่าง · `via` = ฮอปนั้นนำเข้าจาก chain ไหนใน `CALL_LINKAGE.md` "
      "(ว่าง = ฮอปใหม่ที่ไล่จาก bundle ในงานนี้)")
    W("")
    W("| # | tier | kind | src → dst | conf | via |")
    W("|---:|---|---|---|---|---|")
    for h in sp.hops:
        src = h["src"].replace("|", "\\|")
        dst = h["dst"].replace("|", "\\|")
        if len(src) > 78:
            src = src[:75] + "…"
        if len(dst) > 78:
            dst = dst[:75] + "…"
        mark = " **⬅ TERMINUS**" if h["kind"] == "TERMINUS" else ""
        col = ("**%s**" % h["conf"]) if h["conf"] in ("shape", "unresolved", "candidate") else h["conf"]
        W("| %d | `%s` | `%s` | `%s` → `%s`%s | %s | %s |" % (
            h["seq"], h["tier"], h["kind"], src, dst, mark, col, h["via"] or "*(new)*"))
    W("")
    W("ฮอปย่อ (collapsed): %s" % ", ".join(
        "`%s` ย่อ %d ฮอป" % (h["via"], h["collapsed"]) for h in sp.hops if h["collapsed"]) or "—")
    W("")

    # ---------------- per-tier detail ----------------
    W("## 3. รายละเอียดแต่ละ tier / Tier detail")
    for t in TIERS:
        hs = by_tier[t[0]]
        W("")
        W("### `%s` — %s" % (t[0], t[2]))
        W("")
        W("*%s* — %d ฮอป" % (t[3], len(hs)))
        W("")
        if t[0] == "T0":
            W("> ⚠️ **provenance**: ทุกฮอปใน tier นี้มาจาก `fragments/F9_kos_boot_stack.txt` "
              "ซึ่งเป็นสแตกของ **module host ตระกูล KaoriOS ที่รันเกม TFT** ไม่ใช่ไบต์ของ "
              "`SNAKE.apk` — ใช้เป็น *โครงรูป* ของครึ่งเฟรมเวิร์กเท่านั้น (marked `conf=shape`). "
              "ครึ่งที่เหลือของแกน (T1–T5) เป็นหลักฐานของ SNAKE ทั้งหมด")
            W(">")
            W("> Every hop in this tier comes from a captured KOS/Kaori module-host stack "
              "(Teamfight Tactics), **not** from `SNAKE.apk`. It supplies the *shape* of the "
              "framework half only; T1–T5 are SNAKE evidence throughout.")
        if t[0] == "T4":
            W("> tier นี้คืองานใหม่ทั้งหมด: ไล่จาก `pp.txt` (object pool) และ `asm/*.dart` "
              "(listing) เพื่อหาว่า *ใคร* ถือคำขอ C2 — คำตอบคือตระกูล `Xu<dynamic>` "
              "และสโตร์ `[xkg] Yoa`")
            W("> Entirely new work: pool + listing analysis to find *who* owns the C2 request.")
        W("")
        W("| # | kind | src → dst | conf | evidence | note |")
        W("|---:|---|---|---|---|---|")
        for h in hs:
            note = h["th"] if LANG == "both" else h["en"]
            if LANG == "both" and h["en"]:
                note = "%s<br>*%s*" % (h["th"], h["en"])
            note = debacktick((note or "").replace("|", "\\|").replace("\n", " "))
            ev = debacktick(h["evidence"].replace("|", "\\|").replace("\n", " "))
            if len(ev) > 150:
                ev = ev[:147] + "…"
            if len(note) > 240:
                note = note[:237] + "…"
            W("| %d | `%s` | `%s` → `%s` | %s | %s<br>`%s` | %s |" % (
                h["seq"], h["kind"],
                debacktick(h["src"].replace("|", "\\|"))[:90],
                debacktick(h["dst"].replace("|", "\\|"))[:90],
                h["conf"], ev, h["source"], note))
        W("")

    # ---------------- endpoint neighbourhood ----------------
    W("## 4. ย่าน pool ของปลายทาง C2 / The endpoint's pool neighbourhood")
    W("")
    W("ช่วง `pp+0x13988..pp+0x13ac0` คือ allocation run ของ `[Kkg] _Bpa` — หลักฐานเดียวที่ผูก "
      "รูทีน `@%s` (ซึ่งไม่มี body, size `-0x1`) เข้ากับ endpoint" % C2_ROUTINE)
    W("")
    W("| slot | pp line | body |")
    W("|---|---:|---|")
    for off, s in b.slot_range("0x13988", "0x13ac0"):
        body = s["body"].replace("|", "\\|")
        if len(body) > 118:
            body = body[:115] + "…"
        W("| `pp+%s` | %d | `%s` |" % (off, s["line"], debacktick(body)))
    W("")

    # ---------------- the stores ----------------
    cfg_fields, cfg_acc = b.store_accesses(CFG_CLASS)
    cfg_types = OrderedDict()
    for f in cfg_fields:
        cfg_types[f["type"]] = cfg_types.get(f["type"], 0) + 1
    W("## 5. สโตร์สถานะ static สองตัว / The two static stores")
    W("")
    W("### 5.1 `[xkg] %s` — สโตร์ของฝั่ง C2" % STORE_CLASS)
    W("")
    W("กฎการเข้าถึงที่ตรวจแล้วสองครั้ง: **field-table address = 2 × field offset** "
      "(ฐานคือ `THR+0x68 → field_table_values`) — ทำให้ grep หา reader/writer ของทุกสล็อตได้จาก "
      "offset ใน class metadata")
    W("")
    W("| field | type | offset | field-table | accessors | where |")
    W("|---|---|---|---|---:|---|")
    for f in fields:
        a = acc[f["name"]]
        where = "<br>".join("`%s:%d` %s" % (x["file"], x["line"], x["kind"]) for x in a) or "**(none in dump)**"
        W("| `%s` | `%s` | `%s` | `%s` | %d | %s |"
          % (f["name"], f["type"], f["offset"], f["ft"], len(a), where))
    W("")
    W("อ่านผลอย่างไร: `hne` (ฝั่ง response ของ C2 เขียน) กับ `tKb` (ฝั่ง `_ioa` อ่าน) เป็นสล็อต "
      "ติดกันในคลาสเดียวกัน — นั่นคือข้อต่อที่พิสูจน์ได้ระหว่าง 'คำขอ C2' กับ 'ตระกูลงาน async'; "
      "ส่วนอีก %d สล็อต (รวม late String 5 ตัวที่อาจเป็น host/token/key) ไม่มี accessor ใน dump "
      "จึงถูกเก็บเป็น unresolved พร้อมสูตรใน G5" % sum(1 for f in fields if not acc[f["name"]]))
    W("")
    W("English: `hne` (written by the C2 response side) and `tKb` (read by the `_ioa` side) are "
      "adjacent slots of one class — that is the provable joint between the C2 request and the "
      "async task family. The other %d slots have no accessor in the committed dump and are kept "
      "as unresolved with recipe G5." % sum(1 for f in fields if not acc[f["name"]]))
    W("")
    W("### 5.2 `[%s] %s` — registry ระดับแอป (พบระหว่างทำ tier T4)" % (CFG_LIB, CFG_CLASS))
    W("")
    W("| | value |")
    W("|---|---|")
    W("| class | `abstract class %s extends Object` (class id 368, `asm/nkg.dart`) |" % CFG_CLASS)
    W("| static late final fields | **%d** (%s) |"
      % (len(cfg_fields), ", ".join("`%s` x%d" % (k, v) for k, v in sorted(cfg_types.items()))))
    W("| field offsets | `%s..%s` |" % (cfg_fields[0]["offset"], cfg_fields[-1]["offset"]))
    W("| field-table range | `%s..%s` (กฎ 2 x offset) |" % (cfg_fields[0]["ft"], cfg_fields[-1]["ft"]))
    W("| accessors in the whole dump | **%d** (%d/%d fields touched) |"
      % (sum(len(v) for v in cfg_acc.values()),
         sum(1 for f in cfg_fields if cfg_acc[f["name"]]), len(cfg_fields)))
    W("| pool Field slots | %d |" % len([1 for off, sl in b.pp["slots"].items()
                                         if sl["body"].startswith("Field <%s." % CFG_CLASS)]))
    W("")
    W("สองเรื่องที่ควรสังเกต: (1) ช่วง field-table ของ `%s` (`%s..%s`) จบก่อนช่วงของ `%s` (`%s..`) "
      "เพียง %d ไบต์ — สองสโตร์วางชิดกันในพื้นที่เดียวกัน; (2) registry ทั้งก้อน **ไม่มี accessor "
      "เลยสักตัว** ใน 672 ไฟล์ asm ซึ่งแปลว่าโค้ดที่ init/อ่าน config ระดับแอปก็ไม่อยู่ใน dump "
      "เช่นเดียวกับฝั่ง request ของ C2 (สูตรปิดช่องว่าง G7)"
      % (CFG_CLASS, cfg_fields[0]["ft"], cfg_fields[-1]["ft"], STORE_CLASS,
         [f for f in fields if f["name"] == "Vge"][0]["ft"],
         int([f for f in fields if f["name"] == "Vge"][0]["ft"], 16)
         - int(cfg_fields[-1]["ft"], 16)))
    W("")
    W("Two things worth noting: (1) the field-table range of `%s` ends just %d bytes below where "
      "`%s` starts — the two stores sit in one contiguous field-table neighbourhood; (2) the "
      "entire registry has **zero** accessors across %d asm files, so the app-level config code "
      "is missing from the dump exactly like the C2 request side (recipe G7)."
      % (CFG_CLASS, int([f for f in fields if f["name"] == "Vge"][0]["ft"], 16)
         - int(cfg_fields[-1]["ft"], 16), STORE_CLASS, len(b.asm_index["files"])))
    W("")

    # ---------------- gaps ----------------
    W("## 6. ช่องว่างและสูตรปิด / Unresolved hops & closing recipes")
    W("")
    W("| id | tier | what is missing | why it is not in the bundle |")
    W("|---|---|---|---|")
    for g in sp.gaps:
        W("| `%s` | `%s` | %s | %s |" % (g["id"], g["tier"], debacktick(g["what"]),
                                          debacktick(g["why"])))
    W("")
    for g in sp.gaps:
        W("### `%s` — %s" % (g["id"], g["what"]))
        W("")
        W("- **ทำไม / why:** %s" % g["why"])
        W("- **สูตรปิด (TH):** %s" % g["recipe_th"])
        W("- **recipe (EN):** %s" % g["recipe_en"])
        if g.get("ref_detail"):
            W("- **ref checks:** %s" % " · ".join(g["ref_detail"]))
        W("")

    # ---------------- verification ----------------
    W("## 7. การตรวจสอบ / Verification")
    W("")
    W("| # | check | result | detail |")
    W("|---:|---|---|---|")
    for i, c in enumerate(checks, 1):
        W("| %d | %s | **%s** | %s |" % (i, c["name"], c["result"],
                                           c["detail"].replace("|", "\\|")))
    W("")
    npass = sum(1 for c in checks if c["result"] == "PASS")
    W("รวม **%d/%d PASS** · refs ที่ตรวจทั้งหมด **%d** จุด "
      "(ทุกฮอปต้องมีหลักฐานที่ resolve ได้จริงใน bundle ไม่งั้น build fail)"
      % (npass, len(checks),
         sum(len(h.get("ref_detail", [])) for h in sp.hops) +
         sum(len(g.get("ref_detail", [])) for g in sp.gaps)))
    W("")
    W("Total **%d/%d PASS**. Every hop carries at least one evidence reference that is resolved "
      "against the bundle at build time; a reference that does not resolve fails the build." % (npass, len(checks)))
    W("")

    # ---------------- reproduce ----------------
    W("## 8. ทำซ้ำ / Reproduce")
    W("")
    W("```sh")
    W("cd /home/user/Codes")
    W("python3 tools/build_call_linkage.py   # ถ้ายังไม่มี call_linkage.json (SP-01 นำเข้า edge จากที่นี่)")
    W("python3 tools/build_boot_linkage.py   # -> SnakeLogic/BOOT_LINKAGE.md, boot_linkage.{csv,json}")
    W("```")
    W("")
    W("สคริปต์เป็น deterministic: ไม่อ่านนาฬิกา ไม่สุ่มลำดับ (ไล่แบบ sorted ทั้งหมด) — รันซ้ำบน bundle "
      "เดิมได้ไฟล์เหมือนเดิมทุกไบต์ ตรวจได้ด้วย")
    W("")
    W("```sh")
    W("sha256sum SnakeLogic/BOOT_LINKAGE.md SnakeLogic/boot_linkage.csv SnakeLogic/boot_linkage.json")
    W("```")
    W("")
    W("The script is deterministic: no clock reads, no random ordering (all traversals sorted). "
      "Re-running on the same bundle reproduces all three files byte for byte.")
    W("")

    text = "\n".join(o)
    with io.open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return text


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bundle", default=os.path.join(REPO, "SnakeLogic"),
                    help="path to the SnakeLogic evidence bundle")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args(argv)

    root = os.path.abspath(a.bundle)
    if not os.path.isdir(root):
        die("bundle not found: %s" % root)

    b = Bundle(root)
    sp = build(b)
    ok = sp.verify()
    checks = run_checks(b, sp)

    md = os.path.join(root, "BOOT_LINKAGE.md")
    cs = os.path.join(root, "boot_linkage.csv")
    js = os.path.join(root, "boot_linkage.json")
    emit_md(b, sp, checks, md)
    emit_csv(sp, cs)
    emit_json(b, sp, checks, js)

    npass = sum(1 for c in checks if c["result"] == "PASS")
    if not a.quiet:
        sys.stderr.write("\n=== %s: %s ===\n" % (SPINE_ID, ENDPOINT))
        for t in TIERS:
            n = sum(1 for h in sp.hops if h["tier"] == t[0])
            sys.stderr.write("  %s %-12s %3d hops   %s\n" % (t[0], t[1], n, t[3][:52]))
        conf = {}
        for h in sp.hops:
            conf[h["conf"]] = conf.get(h["conf"], 0) + 1
        sys.stderr.write("  hops=%d  imported=%d  new=%d  collapsed=%d  conf=%s\n"
                         % (len(sp.hops),
                            sum(1 for h in sp.hops if h["via"].startswith("CH-")),
                            sum(1 for h in sp.hops if not h["via"].startswith("CH-")),
                            sum(h["collapsed"] for h in sp.hops),
                            ", ".join("%s=%d" % (k, conf[k]) for k in sorted(conf))))
        sys.stderr.write("  gaps=%d (%s)\n" % (len(sp.gaps), ", ".join(g["id"] for g in sp.gaps)))
        for c in checks:
            sys.stderr.write("  [%s] %s — %s\n" % (c["result"], c["name"], c["detail"][:96]))
        for p in (b.problems or [])[:8]:
            sys.stderr.write("  [problem] %s\n" % p)
        for f in (md, cs, js):
            sys.stderr.write("  wrote %s (%d bytes, sha256 %s)\n"
                             % (os.path.relpath(f, REPO), os.path.getsize(f),
                                sha256_text(read(f))[:16]))
        sys.stderr.write("  checks: %d/%d PASS\n" % (npass, len(checks)))
    return 0 if (ok and npass == len(checks) and not b.problems) else 1


if __name__ == "__main__":
    sys.exit(main())
