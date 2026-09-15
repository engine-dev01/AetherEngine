#!/usr/bin/env python3
"""
build_call_linkage.py - derive the CALL LINKAGE (call chains anchored at
instruction offsets) of SNAKE.apk from the committed evidence bundle.

The bundle (SnakeLogic.zip / an extracted copy of it) already carries the raw
material: decoded AArch64 listings for libengine.so (F4b/F4c/F4d), the dex
call-site inventory (F2), blutter's Dart disassembly (output/blutter/asm) and
blutter's own IDA name table (output/blutter/ida_script/addNames.py).  What it
does not carry is a single table that answers

    "which instruction, at which offset, in which module, transfers control to
     what - and how do those hops line up into an end-to-end chain?"

This script builds exactly that: every edge is anchored at a *caller
instruction offset* and points at a *callee offset / symbol*, is labelled with
how the callee was resolved (symbol table, JNIEnv slot index, syscall number,
inline stub comment, ...) and is filed into one of the cross-layer chains.

Outputs (written next to this script's --out directory, repo root by default):

    call_linkage.csv    one row per hop, sorted by layer/module/offset
    call_linkage.json   the same graph plus nodes, chains, symbol tables, checks
    CALL_LINKAGE.md     the rendered document (--lang en|th|both)

Nothing here re-derives facts from the binaries: the binaries are not in this
repository.  Everything is read from the committed fragments, and every row
cites the fragment (and line) it came from, so a reader can check the hop
against the source listing.  Where a callee cannot be resolved statically the
edge is emitted anyway with resolved_by="(unresolved)" and an explicit recipe -
a missing hop is data, not a reason to drop the row.

Usage:
    python3 tools/build_call_linkage.py                 # reads SnakeLogic.zip
    python3 tools/build_call_linkage.py --src SnakeLogic --lang th
    python3 tools/build_call_linkage.py --check         # verify, do not write
"""

from __future__ import annotations

import argparse
import bisect
import csv
import hashlib
import io
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# --------------------------------------------------------------------------
# vocabularies
# --------------------------------------------------------------------------

# Modules, in the order the chains walk through them.
MOD_DEX = "classes.dex"
MOD_ENGINE = "libengine.so"
MOD_APP = "libapp.so"
MOD_FLUTTER = "libflutter.so"
MOD_RUNTIME = "android-runtime"      # ART / linker: the caller of last resort
MOD_KERNEL = "kernel"                # svc #0 targets
MOD_POOL = "libapp.so!pp"            # blutter object pool (a coordinate system of its own)
MOD_DARTVM = "dart-vm"               # dispatch tables / closure objects: resolved only at run time

# Layers, ordered.  layer_id, short name, human description.
LAYERS: List[Tuple[str, str, str]] = [
    ("L1", "dex",     "Dalvik bytecode - classes.dex invoke/loader offsets"),
    ("L2", "loader",  "ELF loader - .init_array slots and exported entry points"),
    ("L3", "native",  "libengine.so machine code - decoded AArch64 instructions"),
    ("L4", "jni",     "JNI boundary - JNIEnv function-table slots, RegisterNatives"),
    ("L5", "dart",    "Dart AOT snapshot - libapp.so instruction-level call edges"),
    ("L6", "engine",  "Flutter engine - libflutter.so symbols the snapshot binds to"),
]
LAYER_ORDER = {lid: i for i, (lid, _, _) in enumerate(LAYERS)}
LAYER_OF = {
    MOD_DEX: "L1", MOD_RUNTIME: "L2", MOD_ENGINE: "L3", MOD_APP: "L5",
    MOD_FLUTTER: "L6", MOD_POOL: "L5", MOD_KERNEL: "L3", MOD_DARTVM: "L5",
}

# JNIEnv function table (jni.h order): byte offset -> (slot index, name).
# Only the slots this sample actually touches are listed; the offsets are the
# ones the decoded instructions use, and each is cross-checked by F4/F4b/F4d.
JNIENV_SLOTS: Dict[int, Tuple[int, str]] = {
    0x30:  (6,   "FindClass"),
    0x38:  (7,   "FromReflectedMethod"),
    0x88:  (17,  "ExceptionClear"),
    0x6b8: (215, "RegisterNatives"),
}

# arm64 syscall numbers, from include/uapi/asm-generic/unistd.h (the table
# arch/arm64 uses).  Only numbers this sample stages are listed.
SYSCALLS: Dict[int, str] = {
    52: "fchmod", 53: "fchmodat", 56: "openat", 57: "close", 59: "pipe2",
    63: "read", 64: "write", 94: "exit_group", 129: "kill", 165: "getrusage",
    172: "getpid", 215: "munmap", 222: "mmap", 226: "mprotect",
}
MMAP_NR = 222
MMAP_STUB_MARKER = 0xDE          # w6 = #0xde staged before the indirect syscall

CONFIDENCE = ("proven", "strong", "probable", "candidate")

# Edge kinds, grouped by what the hop *is*.
KINDS = {
    # L1 dex
    "invokes_loader":       "dex invoke-static of java.lang.System.loadLibrary",
    "loads_library":        "the loader maps a .so named by that invoke",
    "calls_entry_point":    "dlopen hands control to an exported entry point",
    "invokes_native_class": "dex invoke-* into a class that declares ACC_NATIVE methods",
    "declares_native":      "the class declares a native method (no Java body)",
    # L2 loader
    "loader_init_call":     "the dynamic loader calls a .init_array constructor",
    # L3/L4 native + JNI
    "calls_direct":         "bl to a fixed offset inside the same module",
    "calls_plt":            "bl to a PLT stub, i.e. an imported function",
    "calls_jni_slot":       "blr through a JNIEnv function-table slot",
    "calls_vtable0":        "blr through *obj, i.e. a virtual entry resolved at run time",
    "calls_syscall_stub":   "blr to an indirect-syscall stub (syscall args staged in x0-x5, nr in w6)",
    "calls_syscall":        "svc #0 - a direct syscall, number staged in w8/x8",
    "jumps_into_generated": "blr/br into code that the function itself wrote at run time",
    "computes_branch":      "br to a target computed from a relative-offset table",
    "writes_generated_code": "store of a synthesised AArch64 opcode into a fresh RWX page",
    "stages_fnptr":         "store of a function pointer into a JNINativeMethod[] entry",
    "registers_natives":    "the RegisterNatives call itself (nMethods known, fnPtrs runtime)",
    "finds_class":          "the FindClass call that produces the jclass argument",
    "binds_fnptr_to_dex":   "a recovered fnPtr attributed to a declared dex native",
    "rejected_slot_load":   "a 0x6b8-pattern load that is NOT a JNIEnv call (audit trail)",
    # L5/L6 dart + engine
    "dart_call":            "bl inside the Dart AOT snapshot",
    "dart_tail_call":       "b (tail branch) to a Dart runtime stub",
    "dart_instantiates_closure": "ldr xN,[PP,#slot] of an AnonymousClosure - allocates a handler",
    "loads_pool_slot":      "ldr/add of an object-pool slot - the constant this instruction loads",
    "calls_unlinked_slot":  "blr through an UnlinkedCall pool slot - the miss handler it dispatches to",
    "dart_gdt_dispatch":    "blr through GDT[cid+delta] - a virtual/interface call resolved at run time",
    "dart_closure_call":    "blr through *closure+0x1f - calling a closure object",
    "dart_indirect_call":   "blr through a register this listing filled earlier",
    "uses_pool_string":     "a routine attributed to a pool String by slot adjacency (no committed instruction)",
    "branch_on_check":      "the conditional branch that decides pass/fail of a check",
    "stores_response_field": "StoreField of a decoded response value into a lazily-initialised field",
    "binds_engine_symbol":  "a snapshot name that libflutter.so must export/hold",
    "registers_native_via_engine": "FlutterJNI natives bound by the engine's JNI_OnLoad",
}


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

def hx(v: Optional[int]) -> str:
    return "" if v is None else (v if isinstance(v, str) else "0x%x" % v)


def parse_int(tok: str) -> Optional[int]:
    tok = tok.strip().rstrip(",")
    try:
        return int(tok, 16) if tok.lower().startswith("0x") else int(tok, 0)
    except ValueError:
        return None


def provenance_line(F: dict, module: str) -> str:
    for p in F["apk"]["provenance"]:
        if p["apk_entry"].endswith(module):
            return (f"APK member {p['apk_entry']} == repo copy {p['repo_path']} "
                    f"(sha256 {p['apk_sha256'][:16]}..., {p['apk_size']:,} bytes, match={p['match']})")
    return f"APK member lib/arm64-v8a/{module} (F1)"


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


# --------------------------------------------------------------------------
# the evidence bundle: read from the zip, or from an extracted directory
# --------------------------------------------------------------------------

class Bundle:
    """The evidence bundle, read from the zip or from its extracted directory.

    The files this script (and its companion `build_boot_linkage.py`) write are
    excluded from the listing, so the fingerprint covers *inputs only*: reading
    `SnakeLogic/` and reading `SnakeLogic.zip` must produce the same one, and
    re-running the build cannot change it.

    `OPERATOR_INPUTS` are fragments that were supplied by the operator during
    analysis instead of being extracted from the APK, so they exist in the tree
    but not in the uploaded zip. They are excluded from the fingerprint for the
    same reason the generated artifacts are, but they are *reported* by the
    tree-vs-zip check so the divergence stays visible instead of silent.
    """

    OWN_OUTPUTS = ("CALL_LINKAGE.md", "call_linkage.csv", "call_linkage.json",
                   "BOOT_LINKAGE.md", "boot_linkage.csv", "boot_linkage.json")
    OPERATOR_INPUTS = ("fragments/F9_kos_boot_stack.txt",)

    def __init__(self, path: str):
        self.path = path
        self.zf: Optional[zipfile.ZipFile] = None
        self.root: Optional[str] = None
        if os.path.isdir(path):
            self.root = path.rstrip("/")
            names = sorted(
                os.path.relpath(os.path.join(d, f), self.root).replace(os.sep, "/")
                for d, _, fs in os.walk(self.root) for f in fs
            )
        else:
            self.zf = zipfile.ZipFile(path)
            names = sorted(self.zf.namelist())
        self._names = [n for n in names
                       if n not in self.OWN_OUTPUTS
                       and n not in self.OPERATOR_INPUTS
                       and not n.endswith("/")]
        self.operator_inputs = [n for n in names if n in self.OPERATOR_INPUTS]
        self.own_outputs = [n for n in names if n in self.OWN_OUTPUTS]
        self._cache: Dict[str, str] = {}

    def names(self) -> List[str]:
        return self._names

    def has(self, name: str) -> bool:
        return name in self._names

    def text(self, name: str) -> str:
        if name not in self._cache:
            if self.zf is not None:
                raw = self.zf.read(name)
            else:
                with open(os.path.join(self.root or "", name), "rb") as fh:
                    raw = fh.read()
            self._cache[name] = raw.decode("utf-8", "replace")
        return self._cache[name]

    def fingerprint(self) -> str:
        h = hashlib.sha256()
        for n in self._names:
            h.update(n.encode())
            h.update(str(len(self.text(n))).encode())
        return h.hexdigest()[:32]


# --------------------------------------------------------------------------
# graph model
# --------------------------------------------------------------------------

@dataclass
class Node:
    id: str
    layer: str
    module: str
    kind: str
    name: str
    offset: Optional[int] = None
    section: str = ""
    attrs: Dict[str, object] = field(default_factory=dict)


@dataclass
class Edge:
    id: str
    kind: str
    layer: str
    src_module: str
    src_offset: Optional[int]
    src_name: str
    src_insn: str
    dst_module: str
    dst_offset: Optional[int]
    dst_name: str
    resolved_by: str
    confidence: str
    evidence: str
    source: str
    chain: str = ""
    hop: int = 0
    note: str = ""

    def sort_key(self):
        return (
            LAYER_ORDER.get(self.layer, 99),
            self.src_module,
            self.src_offset if self.src_offset is not None else -1,
            self.kind,
            self.dst_offset if self.dst_offset is not None else -1,
            self.dst_name,
        )


class Graph:
    def __init__(self) -> None:
        self.nodes: Dict[str, Node] = {}
        self.edges: List[Edge] = []
        self._n = 0

    def node(self, module: str, kind: str, name: str, offset: Optional[int] = None,
             section: str = "", **attrs) -> Node:
        nid = f"{module}!{hx(offset)}" if offset is not None else f"{module}#{name}"
        if nid not in self.nodes:
            self.nodes[nid] = Node(id=nid, layer=LAYER_OF.get(module, "L3"),
                                   module=module, kind=kind, name=name,
                                   offset=offset, section=section, attrs=dict(attrs))
        else:
            self.nodes[nid].attrs.update({k: v for k, v in attrs.items() if v is not None})
        return self.nodes[nid]

    def edge(self, kind: str, *, src_module: str, src_offset: Optional[int], src_name: str,
             src_insn: str = "", dst_module: str, dst_offset: Optional[int], dst_name: str,
             resolved_by: str, confidence: str, evidence: str, source: str,
             chain: str = "", note: str = "",
             src_kind: str = "instruction", dst_kind: str = "function") -> Edge:
        if kind not in KINDS:
            raise SystemExit(f"unknown edge kind: {kind}")
        if confidence not in CONFIDENCE:
            raise SystemExit(f"unknown confidence: {confidence}")
        self.node(src_module, src_kind, src_name or hx(src_offset), src_offset)
        self.node(dst_module, dst_kind, dst_name or hx(dst_offset), dst_offset)
        self._n += 1
        layer = LAYER_OF.get(src_module, "L3")
        if kind in ("calls_jni_slot", "registers_natives", "finds_class",
                    "binds_fnptr_to_dex", "rejected_slot_load", "stages_fnptr"):
            layer = "L4"
        if kind in ("loader_init_call", "loads_library"):
            layer = "L2"
        if kind in ("binds_engine_symbol", "registers_native_via_engine") \
                or (kind == "calls_entry_point" and dst_module == MOD_FLUTTER):
            layer = "L6"
        if kind == "calls_entry_point" and dst_module == MOD_ENGINE:
            layer = "L2"
        e = Edge(id=f"E{self._n:04d}", kind=kind, layer=layer,
                 src_module=src_module, src_offset=src_offset, src_name=src_name,
                 src_insn=src_insn, dst_module=dst_module, dst_offset=dst_offset,
                 dst_name=dst_name, resolved_by=resolved_by, confidence=confidence,
                 evidence=evidence, source=source, chain=chain, note=note)
        self.edges.append(e)
        return e


# --------------------------------------------------------------------------
# AArch64 listing parser + a deliberately small register tracker
# --------------------------------------------------------------------------

INSN_RE = re.compile(r"^\s*(0x[0-9a-f]+):\s*(\S+)\s*(.*?)\s*$")
# ldr/ldur/ldrsw  xD, [xB]   |   xD, [xB, #imm]   (imm may be negative)
LOAD_RE = re.compile(r"([wx]\d+),\s*\[\s*(?:\[)?([wx]\d+|sp|fp|lr)"
                    r"(?:,\s*#(-?0x[0-9a-f]+|\d+))?\s*\]$")


@dataclass
class Insn:
    addr: int
    mnem: str
    ops: str
    raw: str
    src: str
    line: int

    @property
    def text(self) -> str:
        return f"{self.mnem} {self.ops}".strip()


def parse_listing(text: str, src: str) -> List[Insn]:
    out: List[Insn] = []
    for i, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith(("===", "---", "#####")):
            continue
        m = INSN_RE.match(line)
        if not m:
            continue
        out.append(Insn(addr=int(m.group(1), 16), mnem=m.group(2),
                        ops=m.group(3).strip(), raw=line.strip(), src=src, line=i))
    return out


@dataclass
class Window:
    """A decoded instruction window with a known purpose."""
    name: str
    start: int
    insns: List[Insn]
    src: str
    site: Optional[int] = None      # RegisterNatives site offset, if any
    attrs: Dict[str, object] = field(default_factory=dict)


def split_windows(text: str, src: str) -> List[Window]:
    """Split an F4d-style listing on its '##### site @0x... #####' headers."""
    hdr = re.compile(r"^#####\s*site\s*@(0x[0-9a-f]+)\s+nMethods=(\d+)\s+table=(\S+)\s*#####")
    windows: List[Window] = []
    cur: List[str] = []
    meta: Optional[Tuple[int, int, str]] = None
    for line in text.splitlines():
        m = hdr.match(line.strip())
        if m:
            if meta and cur:
                windows.append(_mk_window(meta, cur, src))
            meta = (int(m.group(1), 16), int(m.group(2)), m.group(3))
            cur = []
        else:
            cur.append(line)
    if meta and cur:
        windows.append(_mk_window(meta, cur, src))
    return windows


def _mk_window(meta: Tuple[int, int, str], lines: Sequence[str], src: str) -> Window:
    va, n, table = meta
    insns = parse_listing("\n".join(lines), src)
    return Window(name=f"register-natives window @0x{n:x}", start=insns[0].addr if insns else va,
                  insns=insns, src=src, site=va,
                  attrs={"nMethods": n, "table": table})


class Tracker:
    """
    Tracks just enough register state to say where a blr's target came from.
    It is intentionally not an emulator: it understands adrp/add/mov/movz/movk,
    bare and offset loads, and immediate staging for svc.  Everything it cannot
    follow stays 'unknown', and the caller reports the hop as unresolved rather
    than guessing.
    """

    def __init__(self) -> None:
        self.imm: Dict[str, int] = {}          # reg -> immediate value
        self.load: Dict[str, Tuple[str, int]] = {}   # reg -> (base reg, slot)
        self.copy: Dict[str, str] = {}         # reg -> source reg
        self.staged_syscall: Optional[int] = None

    def feed(self, ins: Insn) -> None:
        m, ops = ins.mnem, ins.ops
        parts = [p.strip() for p in ops.split(",")]
        if m in ("mov", "movz") and len(parts) == 2 and parts[1].startswith("#"):
            v = parse_int(parts[1][1:])
            if v is not None:
                self.imm[parts[0]] = v & 0xFFFFFFFFFFFFFFFF
                self.load.pop(parts[0], None)
                self.copy.pop(parts[0], None)
        elif m == "movk" and len(parts) >= 2 and parts[1].startswith("#"):
            v = parse_int(parts[1][1:])
            shift = 0
            if len(parts) >= 3:
                sm = re.search(r"lsl\s*#(\d+)", parts[2])
                if sm:
                    shift = int(sm.group(1))
            if v is not None:
                cur = self.imm.get(parts[0], 0)
                cur &= ~(0xFFFF << shift)
                cur |= (v & 0xFFFF) << shift
                self.imm[parts[0]] = cur & 0xFFFFFFFFFFFFFFFF
        elif m == "adrp" and len(parts) == 2 and parts[1].startswith("#"):
            v = parse_int(parts[1][1:])
            if v is not None:
                self.imm[parts[0]] = v
                self.load.pop(parts[0], None)
        elif m == "add" and len(parts) >= 3 and parts[1] in self.imm and parts[2].startswith("#"):
            v = parse_int(parts[2][1:])
            if v is not None:
                self.imm[parts[0]] = self.imm[parts[1]] + v
        elif m in ("ldr", "ldur", "ldrsw"):
            mm = LOAD_RE.match(ops)
            if mm:
                dst, base = mm.group(1), mm.group(2)
                slot = parse_int(mm.group(3)) if mm.group(3) else 0
                self.load[dst] = (base, slot or 0)
                self.imm.pop(dst, None)
                self.copy.pop(dst, None)
        elif m == "svc":
            nr = self.imm.get("w8", self.imm.get("x8"))
            self.staged_syscall = nr
        else:
            # anything that writes a register invalidates what we knew about it
            if parts and re.fullmatch(r"[wx]\d+|sp|fp|lr", parts[0]):
                self.imm.pop(parts[0], None)
                self.load.pop(parts[0], None)

    def syscall_number_before(self, insns: List[Insn], idx: int, lookback: int = 12) -> Optional[int]:
        """The syscall number staged in w8/x8 (or w6 for the stub ABI) before idx."""
        for j in range(idx - 1, max(-1, idx - lookback - 1), -1):
            ins = insns[j]
            if ins.mnem in ("mov", "movz") and re.match(r"[wx]8,\s*#", ins.ops):
                return parse_int(ins.ops.split("#", 1)[1])
            if ins.mnem in ("mov", "movz") and re.match(r"[wx]6,\s*#", ins.ops):
                return parse_int(ins.ops.split("#", 1)[1])
            if ins.mnem == "svc":
                break
        return None

    def mmap_stub_before(self, insns: List[Insn], idx: int, lookback: int = 10) -> Optional[int]:
        """w6 = #0xde staged before an indirect blr -> indirect-syscall stub."""
        for j in range(idx - 1, max(-1, idx - lookback - 1), -1):
            ins = insns[j]
            if ins.mnem in ("mov", "movz") and re.match(r"[wx]6,\s*#0x%x$" % MMAP_STUB_MARKER, ins.ops):
                return MMAP_NR
            if ins.mnem in ("bl", "blr", "ret", "svc"):
                break
        return None


def reg_of(tok: str) -> str:
    return tok.strip().rstrip("]").strip()


# --------------------------------------------------------------------------
# native edge scanner
# --------------------------------------------------------------------------

@dataclass
class NativeCtx:
    graph: Graph
    module: str
    plt: Dict[int, str]              # stub offset -> import name
    known: Dict[int, str]            # any other offset -> name (exports, .mytext)
    annotations: Dict[int, dict]     # curated per-instruction readings
    chain: str
    fn_name: str

    def resolve_direct(self, target: int) -> Tuple[str, str, str]:
        """(name, resolved_by, confidence) for a bl target."""
        if target in self.plt:
            return (self.plt[target], "PLT stub named by Ghidra's callgraph (F7, rebased -0x100000)",
                    "strong")
        if target in self.known:
            return (self.known[target], "symbol table of the committed fragments", "proven")
        return (f"sub_{target:x}", "(unresolved) no symbol covers this offset in the fragments",
                "probable")


def syscall_args(tr: "Tracker", nr: Optional[int] = None) -> str:
    """Read the mmap/fchmodat argument registers the tracker still holds."""
    def val(*regs) -> Optional[int]:
        for r in regs:
            if r in tr.imm:
                return tr.imm[r]
        return None
    if nr in (None, MMAP_NR):
        length, prot, flags = val("x1", "w1"), val("x2", "w2"), val("x3", "w3")
        fd, off = val("x4"), val("x5")
        bits = []
        if length is not None:
            bits.append(f"length={hx(length)}")
        if prot is not None:
            bits.append(f"prot={hx(prot)}" + (" (R|W|X)" if prot == 7 else ""))
        if flags is not None:
            bits.append(f"flags={hx(flags)}" + (" (MAP_PRIVATE|MAP_ANONYMOUS)" if flags == 0x22 else ""))
        if fd is not None:
            bits.append(f"fd={fd if fd == -1 else hx(fd)}")
        if off is not None:
            bits.append(f"offset={hx(off)}")
        return ", ".join(bits) if bits else "none staged inside this window"
    return "only x0 is staged inside this window" if val("x0") is not None \
        else "no argument register is staged inside this window"


def call_shape(insns: List[Insn], idx: int, lookback: int = 10) -> str:
    """Which argument registers were staged before this call - a cheap ABI hint."""
    staged: List[str] = []
    for j in range(idx - 1, max(-1, idx - lookback - 1), -1):
        m = re.match(r"(mov|movz|ldr|add|adrp)\s+([wx]\d+),", insns[j].ops + " ")
        if m and m.group(2) in ("x0", "x1", "x2", "x3", "x4", "x5", "w0", "w1", "w2", "w3"):
            r = "x" + m.group(2)[1:]
            if r not in staged:
                staged.append(r)
        if insns[j].mnem in ("bl", "blr", "ret"):
            break
    staged.sort(key=lambda r: int(r[1:]))
    return ",".join(staged) if staged else "-"


def scan_native(win: Window, ctx: NativeCtx, chain: str) -> List[Edge]:
    """Emit every control-transfer instruction in a decoded window as an edge."""
    out: List[Edge] = []
    emitted: set = set()
    insns = win.insns
    tr = Tracker()
    env_chain: Dict[str, str] = {}     # reg -> reg it was dereferenced from (env table)
    for idx, ins in enumerate(insns):
        prev = dict(tr.load)
        # ---- direct branch with link -----------------------------------
        if ins.mnem == "bl":
            tgt = parse_int(ins.ops.lstrip("#"))
            name, resolved_by, conf = ctx.resolve_direct(tgt) if tgt is not None else ("?", "", "candidate")
            shape = call_shape(insns, idx)
            ann = ctx.annotations.get(ins.addr, {})
            ev = ann.get("evidence", f"`{ins.raw}`; arguments staged before the call: {shape}")
            e = ctx.graph.edge(
                ann.get("kind", "calls_plt" if tgt in ctx.plt else "calls_direct"),
                src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                src_insn=ins.text, dst_module=ctx.module, dst_offset=tgt, dst_name=name,
                resolved_by=ann.get("resolved_by", resolved_by),
                confidence=ann.get("confidence", conf), evidence=ev,
                source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""))
            out.append(e)
        # ---- indirect branch with link ---------------------------------
        elif ins.mnem == "blr":
            r = reg_of(ins.ops)
            base, slot = prev.get(r, ("", -1))
            ann = ctx.annotations.get(ins.addr, {})
            nr = tr.mmap_stub_before(insns, idx)
            if slot in JNIENV_SLOTS and base and base in env_chain:
                sidx, sname = JNIENV_SLOTS[slot]
                kind = "registers_natives" if sname == "RegisterNatives" else (
                    "finds_class" if sname == "FindClass" else "calls_jni_slot")
                e = ctx.graph.edge(
                    ann.get("kind", kind),
                    src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                    src_insn=ins.text, dst_module=MOD_RUNTIME, dst_offset=slot,
                    dst_name=f"JNIEnv->{sname} (slot {sidx})",
                    resolved_by="JNIEnv function-table offset (jni.h order)",
                    confidence="proven",
                    evidence=ann.get("evidence",
                                     f"`{ins.raw}` reached through `{ins.mnem}` of the value loaded by "
                                     f"`ldr {r}, [{base}, #0x{slot:x}]`; the table pointer itself came from "
                                     f"`ldr {base}, [{env_chain[base]}]`"),
                    source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""),
                    src_kind="instruction", dst_kind="jni_slot")
                out.append(e)
            elif slot == 0 and base:
                e = ctx.graph.edge(
                    ann.get("kind", "calls_vtable0"),
                    src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                    src_insn=ins.text, dst_module=ctx.module, dst_offset=None,
                    dst_name=f"*{base} (first word of the object returned earlier)",
                    resolved_by="(unresolved) virtual dispatch - the vtable is filled at run time",
                    confidence="probable",
                    evidence=ann.get("evidence",
                                     f"`{ins.raw}` after `ldr {r}, [{base}]`; arguments staged: "
                                     f"{call_shape(insns, idx)}"),
                    source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""))
                out.append(e)
            elif nr is not None:
                args = syscall_args(tr)
                e = ctx.graph.edge(
                    "calls_syscall_stub",
                    src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                    src_insn=ins.text, dst_module=MOD_KERNEL, dst_offset=MMAP_NR,
                    dst_name=f"syscall {MMAP_NR} ({SYSCALLS[MMAP_NR]}) via function pointer in {r}",
                    resolved_by="syscall number staged in w6 = #0xde; x0-x5 hold the mmap arguments",
                    confidence="strong",
                    evidence=ann.get("evidence",
                                     f"`{ins.raw}` with `mov w6, #0xde` = {MMAP_NR} = "
                                     f"{SYSCALLS[MMAP_NR]} staged before it; arguments visible in this "
                                     f"window: {args}"),
                    source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""),
                    dst_kind="syscall")
                out.append(e)
            else:
                e = ctx.graph.edge(
                    ann.get("kind", "calls_direct"),
                    src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                    src_insn=ins.text, dst_module=ann.get("dst_module", ctx.module),
                    dst_offset=ann.get("dst_offset"),
                    dst_name=ann.get("dst_name", f"indirect through {r} (target not staged in the window)"),
                    resolved_by=ann.get("resolved_by", "(unresolved) indirect branch"),
                    confidence=ann.get("confidence", "candidate"),
                    evidence=ann.get("evidence", f"`{ins.raw}`; no immediate reaches {r} in this window"),
                    source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""))
                out.append(e)
        # ---- direct syscall --------------------------------------------
        elif ins.mnem == "svc":
            nr = tr.syscall_number_before(insns, idx)
            ann = ctx.annotations.get(ins.addr, {})
            name = SYSCALLS.get(nr or -1, f"syscall {nr}")
            args = syscall_args(tr, nr)
            e = ctx.graph.edge(
                "calls_syscall",
                src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                src_insn=ins.text, dst_module=MOD_KERNEL, dst_offset=nr, dst_name=name,
                resolved_by="syscall number staged in w8/x8 before svc (asm-generic/unistd.h, arm64)",
                confidence="proven" if nr in SYSCALLS else "probable",
                evidence=ann.get("evidence",
                                 f"`{ins.raw}` with w8 = #{hex(nr) if nr is not None else '?'} = {nr} "
                                 f"= {name}; arguments visible in this window: {args}"),
                source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""),
                dst_kind="syscall")
            out.append(e)
        # ---- computed branch -------------------------------------------
        elif ins.mnem == "br":
            ann = ctx.annotations.get(ins.addr, {})
            e = ctx.graph.edge(
                "computes_branch",
                src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                src_insn=ins.text, dst_module=ctx.module, dst_offset=ann.get("dst_offset"),
                dst_name=ann.get("dst_name", f"computed target from {reg_of(ins.ops)}"),
                resolved_by=ann.get("resolved_by", "(unresolved) target = PC + table entry"),
                confidence=ann.get("confidence", "probable"),
                evidence=ann.get("evidence", f"`{ins.raw}`"),
                source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""))
            out.append(e)
        # ---- annotated non-branch hops (stores that build code / tables) --
        elif (ins.addr in ctx.annotations and "kind" in ctx.annotations[ins.addr]
              and ins.addr not in emitted):
            ann = ctx.annotations[ins.addr]
            e = ctx.graph.edge(
                ann["kind"],
                src_module=ctx.module, src_offset=ins.addr, src_name=ctx.fn_name,
                src_insn=ins.text, dst_module=ann.get("dst_module", ctx.module),
                dst_offset=ann.get("dst_offset"), dst_name=ann.get("dst_name", ""),
                resolved_by=ann.get("resolved_by", "instruction-level reading of the window"),
                confidence=ann.get("confidence", "strong"), evidence=ann.get("evidence", ins.raw),
                source=f"{ins.src}:{ins.line}", chain=chain, note=ann.get("note", ""),
                dst_kind=ann.get("dst_kind", "page"))
            out.append(e)
            emitted.add(ins.addr)
        # ---- everything else: keep the tracker honest -------------------
        tr.feed(ins)
        if ins.mnem in ("ldr", "ldur"):
            m = re.match(r"([wx]\d+),\s*\[\s*([wx]\d+)\s*\]$", ins.ops)
            if m:
                env_chain.setdefault(m.group(1), m.group(2))
    return out


# --------------------------------------------------------------------------
# curated readings
# --------------------------------------------------------------------------
# A handful of hops cannot be read off a single instruction: the target is
# produced by a short computation, or it is a page the function allocated
# itself.  Each annotation below is keyed by the *instruction offset* it
# explains, quotes the offsets that justify it, and is validated at run time -
# if the instruction it describes is not in the listing, the build fails loudly
# instead of silently publishing a stale reading.

ANNOTATIONS: Dict[int, dict] = {
    # --- JNI_OnLoad: the trampoline builder ------------------------------
    0xF4054: dict(
        dst_name="dispatch table at 0x125d4 (adrp 0x12000 + 0x5d4), 4 relative offsets",
        dst_offset=None,
        resolved_by="adrp/add at 0xf3fe0+0xf3ff8 build x26 = 0x125d4; `ldrsw x11,[x26,x9,lsl#2]` "
                    "with x9 = x27 & 3 selects one of 4 signed offsets added to the PC at 0xf4048",
        confidence="strong",
        evidence="0xf4048 `adr x10, #0xf4048`; 0xf404c `ldrsw x11, [x26, x9, lsl #2]`; "
                 "0xf4050 `add x10, x10, x11`; 0xf4054 `br x10` - the four table words at "
                 "0x125d4..0x125e0 are file bytes this bundle does not contain, so the four "
                 "targets stay open (recipe: read 4 little-endian int32 at libengine.so+0x125d4)",
        note="switch-shaped dispatcher inside the loop that writes the generated page",
    ),
    0xF4078: dict(
        kind="writes_generated_code",
        dst_name="RWX page from the mmap at 0xf4018 (x21 = page, x27 = word index)",
        dst_module=MOD_ENGINE,
        resolved_by="`mov w28, #0x14000000` at 0xf4040 is the AArch64 unconditional-branch opcode; "
                    "0xf406c-0xf4074 blend it with rand() and mask 0x3ffffff",
        confidence="proven",
        evidence="0xf4040 `mov w28, #0x14000000`; 0xf4044 `mov w19, #0x3ffffff`; "
                 "0xf4070 `and w8, w0, w19`; 0xf4074 `orr w8, w8, w28`; "
                 "0xf4078 `str w8, [x21, x27, lsl #2]` - a `B <imm26>` is written word by word "
                 "into the page mmap returned",
        note="this is why no static tool sees the registration code: it is emitted at load time",
    ),
    0xF40A0: dict(
        kind="writes_generated_code",
        dst_name="previous RWX page (x8 = pages[n-1]); the branch is aimed at x21 = pages[n]",
        dst_module=MOD_ENGINE,
        resolved_by="0xf4090 stages the opcode, 0xf4098 computes the delta, 0xf409c packs it",
        confidence="proven",
        evidence="0xf4090 `mov w10, #0x14000000`; 0xf4094 `ldr x8, [x25, w8, uxtw #3]` (previous page); "
                 "0xf4098 `sub w9, w21, w8`; 0xf409c `bfxil w10, w9, #2, #0x1a`; 0xf40a0 `str w10, [x8]` "
                 "- page n-1 is patched with `B (page_n - page_{n-1}) >> 2`, chaining the pages",
        note="the pages form a linked trampoline chain, each one branching to the next",
    ),
    0xF40E0: dict(
        kind="jumps_into_generated",
        dst_name="generated code in the first RWX page (the mmap result stored at sp+0x10)",
        dst_module=MOD_ENGINE,
        dst_offset=None,
        resolved_by="x8 = [sp,#0x10] = pages[0], written by `str x0, [x25, x22, lsl #3]` at 0xf4020 "
                    "with x25 = sp+0x10 (0xf3ff4) and x0 = the mmap return at 0xf4018",
        confidence="proven",
        evidence="0xf3ff4 `add x25, sp, #0x10`; 0xf4018 `svc #0` (mmap, prot=RWX); "
                 "0xf4020 `str x0, [x25, x22, lsl #3]`; 0xf40c4 `ldr x8, [sp, #0x10]`; "
                 "0xf40e0 `blr x8` - control leaves the static image",
        note="first of the 2 blr in JNI_OnLoad (F4: blr_count = 2)",
    ),
    0xF43F4: dict(
        kind="jumps_into_generated",
        dst_name="generated table in the second RWX page (x8 = mmap result of 0xf411c)",
        dst_module=MOD_ENGINE,
        dst_offset=None,
        resolved_by="x8 = x0 of the second mmap (0xf4120 `mov x8, x0`); 0xf41f8-0xf43c8 fill "
                    "[x8+0x550 .. x8+0x5e8] with values built by shifting immediates through "
                    "counts loaded from .bss 0x828048..0x828088",
        confidence="proven",
        evidence="0xf411c `svc #0` (mmap, prot=RWX); 0xf4120 `mov x8, x0`; "
                 "0xf4190 `ldr x9, [x9, #0x48]` / 0xf41a0 `ldr x10, [x10, #0x50]` (.bss shift counts); "
                 "0xf41f8 `str x9, [x8, #0x550]` ... 0xf43c8 `str x9, [x8, #0x5e8]`; "
                 "0xf43f0 `mov x6, x8`; 0xf43f4 `blr x8`; 0xf43f8 `cbz x0, #0xf4430`",
        note="the callee returns the value that selects the 0xf4430 / 0xf442c path - a runtime check "
             "whose result is consumed but whose code is not in the image",
    ),
    # --- JNI_OnLoad: the two fchmodat sites ------------------------------
    0xF4428: dict(
        evidence="0xf4418 `str x26, [sp, #0x10]`; 0xf441c `ldr x8, [sp, #0x10]`; "
                 "0xf4420 `ldr x0, [x8]`; 0xf4424 `mov x8, #0x35`; 0xf4428 `svc #0` - the syscall "
                 "number is proven (53 = fchmodat) but only x0 is staged, and it is a word read back "
                 "out of the generated page, so the arguments are NOT recoverable from the image",
        note="reached when the second generated call returned non-zero (0xf43f8 `cbz x0, #0xf4430`)",
    ),
    0xF4458: dict(
        evidence="0xf4448 `str w8, [sp, #0x10]`; 0xf444c `ldr x8, [sp, #0x10]`; "
                 "0xf4450 `ldr x0, [x8]`; 0xf4454 `mov x8, #0x35`; 0xf4458 `svc #0` - same shape as "
                 "0xf4428 on the other branch of the `cbz`",
        note="the pair brackets the runtime check, i.e. both outcomes end in the same syscall",
    ),
    # --- registration window 1 (nMethods = 1) ---------------------------
    0xB018C: dict(
        evidence="0xb0180 `ldr x8, [x19]`; 0xb0184 `ldr x8, [x8, #0x88]`; 0xb0188 `mov x0, x19`; "
                 "0xb018c `blr x8` - slot 17 = ExceptionClear(env), reached on the failure path "
                 "`tbnz w0, #0x1f, #0xb0190` after RegisterNatives returned < 0",
        note="error path of the registration: clear the pending JNI exception",
    ),
    0xB00E4: dict(
        resolved_by="(unresolved) PLT stub - not among the 5 stubs Ghidra's callgraph names",
        confidence="probable",
        evidence="0xb00d8 `ldr x0, [sp, #0x30]`; 0xb00dc `mov x1, x24`; 0xb00e0 `mov w2, #8`; "
                 "0xb00e4 `bl #0x81f250`; 0xb00e8 `cbnz w0, #0xb0120` - a (ptr, ptr, 8) -> int call "
                 "whose result is tested, i.e. memcmp-shaped: the 8 decoded bytes are compared "
                 "before the registration proceeds",
        note="shape-identified only; the import name needs .rela.plt, which the bundle does not carry",
    ),
    # --- registration window 2 (nMethods = 2) ---------------------------
    0xB3FE4: dict(
        evidence="0xb3fd4 `stur x8, [x29, #-0x40]`; 0xb3fd8 `ldur x8, [x29, #-0x40]`; "
                 "0xb3fdc `ldr x0, [x8]`; 0xb3fe0 `mov x8, #0x35`; 0xb3fe4 `svc #0` - syscall 53 "
                 "(fchmodat) with only x0 staged, the value having been round-tripped through the "
                 "stack from an obfuscated immediate (0xb3fc8-0xb3fd0 build 0x33804ef2)",
        note="first instruction of the window: the site opens with a syscall, not with a call",
    ),
    # --- registration window 3 (nMethods = 10) --------------------------
    0xF39E8: dict(
        kind="finds_class",
        resolved_by="JNIEnv function-table offset +0x30 = slot 6",
        confidence="proven",
        evidence="0xf39d8 `ldr x8, [x24]`; 0xf39dc `mov x0, x24`; 0xf39e0 `mov x1, x20`; "
                 "0xf39e4 `ldr x8, [x8, #0x30]`; 0xf39e8 `blr x8` - FindClass(env, x20) where x20 is "
                 "the buffer the 23-iteration decode loop at 0xf39b8-0xf39d4 just filled, so the class "
                 "name is 23 characters: 'com/snake/helper/Native'",
        note="the only FindClass among the 3 registration windows; the other two reuse a class "
             "handle they did not materialise here",
    ),
    # --- .mytext --------------------------------------------------------
    0x81EEE4: dict(
        resolved_by="(unresolved) .text offset - 0x84 bytes past the RegisterNatives site at 0xb0140",
        confidence="probable",
        evidence="0x81eed8 `mov x2, x0` (x0 = FromReflectedMethod result); 0x81eedc `mov x0, x20` "
                 "(x20 = the JNIEnv* saved at 0x81eecc); 0x81eee0 `mov x1, x19` (x19 = incoming x2, "
                 "saved at 0x81eec8); 0x81eee4 `bl #0xb01c4` - env, the original second argument and "
                 "the reflected java.lang.reflect.Method are forwarded into .text",
        note="the only hop out of .mytext into the protected body; its target has no symbol",
    ),
    0x81EF40: dict(
        resolved_by="(unresolved) .text offset",
        confidence="probable",
        evidence="0x81ef34 function entry (`stp x29, x30, [sp, #-0x10]!`); 0x81ef3c `mov x1, x3`; "
                 "0x81ef40 `bl #0xb134c` - the second .mytext routine forwards its 4th argument "
                 "as x1 and does no FromReflectedMethod, so it is not the Method-shaped handler",
        note="second routine in .mytext; not reachable from the recovered fnPtr 0x81eeb0",
    ),
}


def validate_annotations(windows: Iterable[Window]) -> List[str]:
    """Every annotation must point at an instruction that really is in a listing."""
    present = {ins.addr: ins for w in windows for ins in w.insns}
    bad = []
    for addr in ANNOTATIONS:
        if addr not in present:
            bad.append(hex(addr))
    return bad


# --------------------------------------------------------------------------
# Dart layer
# --------------------------------------------------------------------------

DART_ADDR_RE = re.compile(r"//\s+\*\*\s+addr:\s+(0x[0-9a-f]+),\s+size:\s+(-?0x[0-9a-f]+)")
DART_BR_RE = re.compile(r"//\s+(0x[0-9a-f]+):\s+(bl|b)\s+#(0x[0-9a-f]+)\s*(?:;\s*(.*))?$")
DART_CALL_RE = re.compile(r"//\s+(0x[0-9a-f]+):\s+r\d+\s+=\s+call\s+(0x[0-9a-f]+)")
DART_PP_RE = re.compile(r"\[pp\+(0x[0-9a-f]+)\]\s+(.*)$")
DART_BLR_RE = re.compile(r"//\s+(0x[0-9a-f]+):\s+blr\s+(\S+)\s*$")
DART_GDT_IR_RE = re.compile(r"//\s+0x[0-9a-f]+:\s+r\d+\s+=\s+GDT\[cid_(x\d+)\s+\+\s+"
                            r"(-?0x[0-9a-f]+)\]")
DART_FIELD_LOAD_RE = re.compile(r"LoadField:\s+r(\d+)\s+=\s+r\d+->field_([0-9a-f]+)")
DART_GDT_LOAD_RE = re.compile(r"ldr\s+lr,\s*\[x21,")
DART_CLOSURE_LOAD_RE = re.compile(r"ldur\s+x\d+,\s*\[x\d+,\s*#0x1f\]")
UNLINKED_RE = re.compile(r"UnlinkedCall:\s+(0x[0-9a-f]+)\s+-\s+(.+?)\s*$")
INSN_OFFSET_RE = re.compile(r"(0x[0-9a-f]+):\s+(.*?)(?:\s+;\s+.*)?$")
BRANCH_AFTER_CHECK_RE = re.compile(r"^\s*//\s+(0x[0-9a-f]+):\s+(tbnz|tbz|cbz|cbnz|b\.\w+)\s+(.*)$")
STORE_FIELD_RE = re.compile(r"^\s*//\s+(0x[0-9a-f]+):\s+StoreField:\s+(.*)$")
LOAD_FIELD_IR_RE = re.compile(r"^\s*//\s+(0x[0-9a-f]+):\s+LoadField:\s+(.*)$")
BRANCH_TARGET_RE = re.compile(r"#(0x[0-9a-f]+)\s*$")
FN_AT_RE = re.compile(r"function at (0x[0-9a-f]+)")
POOL_OWNER_RE = re.compile(r"\[(\w+)\] (\w+)::<anonymous closure> \((0x[0-9a-f]+)\)")


def norm_dart_name(text: str) -> str:
    """Reduce a blutter label to comparable letters+digits.

    The asm listing and the IDA script label the same address differently:
    'Allocate_eMStub -> _eM (size=0x34)' vs 'Allocate_eMStub_536af4',
    '[dart:_internal] LateError::_throwLocalNotInitialized' vs
    'dart__internal_LateError::_throwLocalNotInitialized_197214'.  The
    decoration is dropped, the rest is lowercased and squeezed.
    """
    t = re.sub(r"\s*->.*$", "", text.strip())          # '-> Class (size=..)'
    t = re.sub(r"\s*\(size=[^)]*\)\s*$", "", t)
    t = re.sub(r"[_]?0x[0-9a-f]{4,}$", "", t)           # trailing address suffix
    t = re.sub(r"[_]?[0-9a-f]{5,}$", "", t)
    return re.sub(r"[^0-9a-z]+", "", t.lower())


def common_suffix_len(a: str, b: str) -> int:
    n = 0
    for x, y in zip(reversed(a), reversed(b)):
        if x != y:
            break
        n += 1
    return n


def names_agree(inline: str, table: str) -> str:
    """'exact' | 'variant' | 'conflict' for two labels of one address."""
    a, b = norm_dart_name(inline), norm_dart_name(table)
    if not a or not b:
        return "conflict"
    if a == b or a in b or b in a:
        return "exact"
    return "variant" if common_suffix_len(a, b) >= 6 else "conflict"
CLASS_RE = re.compile(r"^\s*(?:abstract\s+)?class\s+(\S+)")
IDA_NAME_RE = re.compile(r'idaapi\.set_name\((0x[0-9a-f]+),\s*"(.*)"\)')
IDA_FUNC_RE = re.compile(r"ida_funcs\.add_func\((0x[0-9a-f]+),\s*(0x[0-9a-f]+)\)")
ANON_CLOSURE_RE = re.compile(r"AnonymousClosure:\s*(.*?)\s*\((0x[0-9a-f]+)\)"
                             r"(?:,\s*(?:in|of)\s*(.*?)\s*\((0x[0-9a-f]+)\))?")
STUB_RE = re.compile(r"Stub:\s*(.*?)\s*\((0x[0-9a-f]+)\)")
FUNC_RE = re.compile(r"Function:\s*(.*?)\s*\((0x[0-9a-f]+)\)")


@dataclass
class DartSymbols:
    names: Dict[int, str] = field(default_factory=dict)        # from addNames.py
    ranges: List[Tuple[int, int]] = field(default_factory=list)
    starts: List[int] = field(default_factory=list)
    asm_addrs: Dict[int, str] = field(default_factory=dict)    # from '** addr:' + signature
    pool: Dict[int, str] = field(default_factory=dict)         # pool slot -> closure code addr
    pool_name: Dict[int, str] = field(default_factory=dict)    # code addr -> owner '[lib] Class'
    pool_desc: Dict[int, str] = field(default_factory=dict)    # pool slot -> pp.txt description
    pool_line: Dict[int, int] = field(default_factory=dict)    # pool slot -> pp.txt line number

    def resolve(self, target: int) -> Tuple[Optional[str], str]:
        if target in self.names:
            return self.names[target], "blutter's own IDA name table (ida_script/addNames.py)"
        i = bisect.bisect_right(self.starts, target) - 1
        if i >= 0 and self.ranges[i][0] <= target < self.ranges[i][1]:
            s = self.ranges[i][0]
            if s in self.names:
                return self.names[s], f"inside the function blutter names at {hx(s)} (add_func range)"
        if target in self.asm_addrs:
            return self.asm_addrs[target], "blutter asm '** addr:' marker"
        if target in self.pool_name:
            return self.pool_name[target], "blutter object pool (pp.txt)"
        return None, "(unresolved) no symbol in the committed dump covers this code address"


def load_dart_symbols(b: Bundle) -> Tuple[DartSymbols, int]:
    sym = DartSymbols()
    addnames = "output/blutter/ida_script/addNames.py"
    stub_addr = 0x160080     # blutter reuses this address for the '*_check' names
    if b.has(addnames):
        for line in b.text(addnames).splitlines():
            m = IDA_NAME_RE.search(line)
            if m:
                a = int(m.group(1), 16)
                if a != stub_addr:
                    sym.names[a] = m.group(2)
                continue
            m = IDA_FUNC_RE.search(line)
            if m:
                sym.ranges.append((int(m.group(1), 16), int(m.group(2), 16)))
        sym.ranges.sort()
        sym.starts = [r[0] for r in sym.ranges]
    if b.has("output/blutter/pp.txt"):
        for lineno, line in enumerate(b.text("output/blutter/pp.txt").splitlines(), 1):
            m = DART_PP_RE.match(line.strip())
            if not m:
                continue
            slot, body = int(m.group(1), 16), m.group(2)
            sym.pool_desc.setdefault(slot, body.strip())
            sym.pool_line.setdefault(slot, lineno)
            am = ANON_CLOSURE_RE.match(body)
            if am:
                code = int(am.group(2), 16)
                sym.pool[slot] = code
                if am.group(3):
                    sym.pool_name[code] = am.group(3).strip()
                continue
            sm = STUB_RE.match(body)
            if sm:
                sym.names.setdefault(int(sm.group(2), 16), sm.group(1).strip())
                continue
            fm = FUNC_RE.match(body)
            if fm:
                sym.names.setdefault(int(fm.group(2), 16), fm.group(1).strip())
    n_asm = 0
    for name in b.names():
        if not name.startswith("output/blutter/asm/") or not name.endswith(".dart"):
            continue
        pending = None
        cur_class = None
        for line in b.text(name).splitlines():
            cm = CLASS_RE.match(line)
            if cm:
                cur_class = cm.group(1)
            s = line.strip()
            if s.endswith("{") and not s.startswith("//"):
                pending = s
            am = DART_ADDR_RE.search(line)
            if am:
                if pending:
                    a = int(am.group(1), 16)
                    label = pending[:-1].strip()
                    owner = f"{cur_class}::" if cur_class else ""
                    sym.asm_addrs.setdefault(a, f"{owner}{label}")
                    n_asm += 1
                pending = None
    return sym, n_asm


@dataclass
class DartFn:
    sig: str
    cls: str
    addr: Optional[int]
    file: str
    line: int


def dart_edges(b: Bundle, g: Graph, sym: DartSymbols, chain: str) -> Dict[str, object]:
    """Instruction-level Dart call edges + closure-instantiation edges."""
    stats = {
        "files": 0, "files_with_disasm": 0, "branch_edges": 0, "ir_call_lines": 0,
        "ir_calls_matched_by_bl": 0, "inline_named": 0, "resolved_by_table": 0,
        "unresolved": 0, "distinct_targets": set(), "inline_vs_table_exact": 0,
        "inline_vs_table_variant": 0, "inline_vs_table_conflict": 0,
        "inline_vs_table_disagree": [], "closure_slots": 0, "fan_in": {},
        "pool_comment_lines": 0, "pool_slot_loads": 0, "blr_lines": 0,
        "blr_unlinked": 0, "blr_gdt": 0, "blr_closure": 0, "blr_other": 0,
    }
    for name in sorted(b.names()):
        if not name.startswith("output/blutter/asm/") or not name.endswith(".dart"):
            continue
        stats["files"] += 1
        text = b.text(name)
        lines = text.splitlines()
        cur: Optional[DartFn] = None
        pending = None
        cur_class = None
        pending_unlinked = None      # (target, stub name, slot) of an UnlinkedCall load
        pending_gdt = None           # 'cid_x0 + 0x7a9' from blutter's GDT IR line
        pending_gdt_load = False     # saw `ldr lr, [x21, ...]` (the dispatch-table read)
        pending_closure = False      # saw `ClosureCall` / `ldur xN, [x0, #0x1f]`
        recent: List[str] = []       # last raw listing lines, for evidence
        seen_disasm = False
        ir_calls: Dict[int, int] = {}
        for i, line in enumerate(lines, 1):
            cm = CLASS_RE.match(line)
            if cm:
                cur_class = cm.group(1)
            s = line.strip()
            if s.endswith("{") and not s.startswith("//"):
                pending = s
            am = DART_ADDR_RE.search(line)
            if am:
                cur = DartFn(sig=(pending[:-1].strip() if pending else "?"),
                             cls=cur_class or "", addr=int(am.group(1), 16), file=name, line=i)
                pending = None
                pending_unlinked = None
                pending_gdt = None
                pending_gdt_load = False
                pending_closure = False
                recent = []
                continue
            im = DART_CALL_RE.search(line)
            if im:
                stats["ir_call_lines"] += 1
                ir_calls.setdefault(int(im.group(1), 16), int(im.group(2), 16))
            bm = DART_BR_RE.search(line)
            if bm and cur is not None:
                seen_disasm = True
                src_off = int(bm.group(1), 16)
                mnem = bm.group(2)
                tgt = int(bm.group(3), 16)
                inline = (bm.group(4) or "").strip()
                if src_off in ir_calls and ir_calls[src_off] == tgt:
                    stats["ir_calls_matched_by_bl"] += 1
                named, how = sym.resolve(tgt)
                dst_name = inline or named or hx(tgt)
                if inline:
                    stats["inline_named"] += 1
                    if named:
                        verdict = names_agree(inline, named)
                        stats["inline_vs_table_" + verdict] += 1
                        if verdict == "conflict":
                            stats["inline_vs_table_disagree"].append((hx(src_off), inline, named))
                    resolved_by = "blutter's inline stub comment in the same listing"
                    conf = "proven"
                elif named:
                    stats["resolved_by_table"] += 1
                    resolved_by = how
                    conf = "strong"
                else:
                    stats["unresolved"] += 1
                    resolved_by = how
                    conf = "candidate"
                stats["distinct_targets"].add(tgt)
                stats["fan_in"][tgt] = stats["fan_in"].get(tgt, 0) + 1
                kind = "dart_call" if mnem == "bl" else "dart_tail_call"
                g.edge(kind,
                       src_module=MOD_APP, src_offset=src_off,
                       src_name=f"{cur.cls}::{cur.sig}" if cur.cls else cur.sig,
                       src_insn=f"{mnem} #{hx(tgt)}" + (f"  ; {inline}" if inline else ""),
                       dst_module=MOD_APP, dst_offset=tgt, dst_name=dst_name,
                       resolved_by=resolved_by, confidence=conf,
                       evidence=f"{name}:{i} inside `{cur.sig}` "
                                f"(function entry {hx(cur.addr)}), instruction `{bm.group(0).strip()}`",
                       source=f"{name}:{i}", chain=chain,
                       note=f"file {name}, class {cur.cls}, function at {hx(cur.addr)}")
                stats["branch_edges"] += 1
            # --- IR annotations that classify the next `blr` ---------------
            if "ClosureCall" in line:
                pending_closure = True
            gm = DART_GDT_IR_RE.search(line)
            if gm:
                pending_gdt = f"cid_{gm.group(1)} + {gm.group(2)}"
            if DART_GDT_LOAD_RE.search(line):
                pending_gdt_load = True
            if DART_CLOSURE_LOAD_RE.search(line):
                pending_closure = True

            # --- object-pool loads: what this instruction pulls in ---------
            pm = DART_PP_RE.search(line)
            if pm and cur is not None:
                slot = int(pm.group(1), 16)
                desc = pm.group(2).strip()
                im = INSN_OFFSET_RE.search(line)
                off = int(im.group(1), 16) if im else None
                insn = " ".join(im.group(2).split()) if im else ""
                fn_label = f"{cur.cls}::{cur.sig}" if cur.cls else cur.sig
                if off is not None:
                    stats["pool_comment_lines"] += 1
                cm2 = ANON_CLOSURE_RE.search(desc)
                if "AnonymousClosure" in line and cm2:
                    code = int(cm2.group(2), 16)
                    owner = (cm2.group(3) or "").strip()
                    g.edge("dart_instantiates_closure",
                           src_module=MOD_APP, src_offset=off,
                           src_name=fn_label,
                           src_insn=f"ldr xN, [PP, #0x{slot:x}]",
                           dst_module=MOD_APP, dst_offset=code,
                           dst_name=f"{owner} closure @ {hx(code)} (pool slot pp+{hx(slot)})",
                           resolved_by="pool slot named in the same disassembly line",
                           confidence="proven",
                           evidence=f"{name}:{i} `{line.strip()}`",
                           source=f"{name}:{i}", chain=chain,
                           note=f"closure allocated here runs at {hx(code)}")
                    stats["closure_slots"] += 1
                elif off is not None:
                    pline = sym.pool_line.get(slot)
                    g.edge("loads_pool_slot",
                           src_module=MOD_APP, src_offset=off,
                           src_name=fn_label, src_insn=insn,
                           dst_module=MOD_POOL, dst_offset=slot, dst_name=desc,
                           resolved_by="the slot is named in the same disassembly line; its "
                                       "content comes from the pool dump"
                                       + (f" (pp.txt:{pline})" if pline else ""),
                           confidence="proven",
                           evidence=f"{name}:{i} inside `{cur.sig}` (function entry "
                                    f"{hx(cur.addr)}), instruction `{line.strip()}`",
                           source=f"{name}:{i}", chain=chain, dst_kind="pool_slot",
                           note=f"file {name}, class {cur.cls}, function at {hx(cur.addr)}")
                    stats["pool_slot_loads"] += 1
                    um = UNLINKED_RE.match(desc)
                    if um:
                        pending_unlinked = (int(um.group(1), 16), um.group(2).strip(), slot)

            # --- `blr`: the indirect calls the Dart half actually makes ----
            blr = DART_BLR_RE.search(line)
            if blr and cur is not None:
                off, reg = int(blr.group(1), 16), blr.group(2)
                fn_label = f"{cur.cls}::{cur.sig}" if cur.cls else cur.sig
                stats["blr_lines"] += 1
                ctx = " / ".join(recent[-3:])
                ev = (f"{name}:{i} inside `{cur.sig}` (function entry {hx(cur.addr)}), "
                      f"instruction `{line.strip()}`; preceding: {ctx}")
                note = f"file {name}, class {cur.cls}, function at {hx(cur.addr)}"
                if pending_unlinked:
                    tgt, stub, slot = pending_unlinked
                    stats["blr_unlinked"] += 1
                    g.edge("calls_unlinked_slot",
                           src_module=MOD_APP, src_offset=off, src_name=fn_label,
                           src_insn=f"blr {reg}",
                           dst_module=MOD_APP, dst_offset=tgt, dst_name=stub,
                           resolved_by=f"the UnlinkedCall entry at pool slot pp+{hx(slot)} holds the "
                                       f"miss handler's entry point, and the `ldp x5, lr, [x16]` "
                                       f"before this blr is what loads it",
                           confidence="strong", evidence=ev, source=f"{name}:{i}",
                           chain=chain, note=note)
                elif pending_gdt or pending_gdt_load:
                    stats["blr_gdt"] += 1
                    if pending_gdt:
                        delta = pending_gdt
                    elif any(re.search(r"mov\s+lr,\s*x\d+\s*$", r) for r in recent):
                        delta = "cid + 0"
                    else:
                        delta = "cid, no delta annotated at this site"
                    g.edge("dart_gdt_dispatch",
                           src_module=MOD_APP, src_offset=off, src_name=fn_label,
                           src_insn=f"blr {reg}",
                           dst_module=MOD_DARTVM, dst_offset=None,
                           dst_name=f"GDT[{delta}] - the Dart dispatch table, indexed by the "
                                    f"receiver's class id",
                           resolved_by="`ldr lr, [x21, lr, lsl #3]` before the blr is the dispatch-"
                                       "table read"
                                       + (" and blutter's IR line names the delta"
                                          if pending_gdt else
                                          "; the index is the class id itself, no delta added"),
                           confidence="probable", evidence=ev, source=f"{name}:{i}",
                           chain=chain, dst_kind="runtime", note=note)
                elif pending_closure:
                    stats["blr_closure"] += 1
                    g.edge("dart_closure_call",
                           src_module=MOD_APP, src_offset=off, src_name=fn_label,
                           src_insn=f"blr {reg}",
                           dst_module=MOD_DARTVM, dst_offset=None,
                           dst_name="*closure+0x1f - the closure object's own entry point",
                           resolved_by="blutter's `ClosureCall` IR line plus the "
                                       "`ldur xN, [x0, #0x1f]` that loads the entry point",
                           confidence="probable", evidence=ev, source=f"{name}:{i}",
                           chain=chain, dst_kind="runtime", note=note)
                else:
                    stats["blr_other"] += 1
                    shape, how, conf = None, "", "candidate"
                    if any("THR::vm_tag" in r for r in recent):
                        shape = (f"*{reg} - the continuation address the caller handed in "
                                 f"(stored to THR::vm_tag by the instruction before)")
                        how = ("the listing stores the same register to THR::vm_tag immediately "
                               "before the blr, which is the async-body handoff shape")
                        conf = "probable"
                    else:
                        fld = next((m for r in recent
                                    for m in [DART_FIELD_LOAD_RE.search(r)]
                                    if m and f"x{m.group(1)}" == reg), None)
                        if fld:
                            shape = (f"*{reg} - a function pointer read from object field_"
                                     f"{fld.group(2)}")
                            how = (f"blutter's `LoadField: r{fld.group(1)} = r?->field_"
                                   f"{fld.group(2)}` line two instructions above the blr")
                            conf = "probable"
                    if shape is None:
                        shape = f"*{reg} - target filled earlier in this listing"
                        how = ("(unresolved) the register is not a dispatch-table, closure, "
                               "UnlinkedCall or object-field load in the committed listing")
                    g.edge("dart_indirect_call",
                           src_module=MOD_APP, src_offset=off, src_name=fn_label,
                           src_insn=f"blr {reg}",
                           dst_module=MOD_DARTVM, dst_offset=None, dst_name=shape,
                           resolved_by=how, confidence=conf, evidence=ev,
                           source=f"{name}:{i}", chain=chain, dst_kind="runtime", note=note)
                pending_unlinked = None
                pending_gdt = None
                pending_gdt_load = False
                pending_closure = False

            recent.append(" ".join(line.split())[3:])
            if len(recent) > 6:
                recent.pop(0)
        if seen_disasm:
            stats["files_with_disasm"] += 1
    return stats


# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------

def build(b: Bundle, lang: str) -> dict:
    F = json.loads(b.text("fragments/fragments.json"))
    g = Graph()
    le = F["libengine"]
    checks: List[dict] = []

    def check(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "result": "PASS" if ok else "FAIL", "detail": detail})

    # ---------------- symbol tables ----------------
    delta = int(F["tools"]["ghidra"]["image_base_delta"], 16)
    plt: Dict[int, str] = {}
    for c in F["tools"]["ghidra"]["callgraph"]:
        plt[int(c["va"], 16) - delta] = c["name"]
    # the addresses of the defined dynamic exports live only in the F4 listing
    known: Dict[int, str] = {}
    export_re = re.compile(r"^\s+(0x[0-9a-f]{8,})\s+(STT_\w+)\s+(\S+)\s*$")
    for line in b.text("fragments/F4_libengine_jni.txt").splitlines():
        m = export_re.match(line)
        if m:
            known[int(m.group(1), 16)] = m.group(3)
    check("JNI_OnLoad is in the parsed export table", 0xF3FA0 in known,
          f"{len(known)} defined dynamic exports parsed from F4 "
          f"(F4 reports {len(le['exports'])} names); JNI_OnLoad -> {hx(known.get(0xF3FA0))}")
    mytext = le["mytext"]
    mytext_sec = next((s for s in le["nonstandard_sections"] if s["name"] == ".mytext"),
                      {"executable": True, "writable": False})
    mytext_lo = int(mytext["addr"], 16)
    mytext_hi = mytext_lo + mytext["size"]
    for fnptr in (0x81EEB0,):
        known.setdefault(fnptr, f".mytext registered fnPtr ({hx(fnptr)})")

    # ---------------- L1: dex ----------------
    CH1, CH8, CH9 = "CH-01", "CH-08", "CH-09"
    for site in F["dex"]["loader_call_sites"]:
        off = site["site_off"]
        lib = {"engine": MOD_ENGINE, "flutter": MOD_FLUTTER}[site["literal"]]
        g.edge("invokes_loader",
               src_module=MOD_DEX, src_offset=off, src_name=site["inside"],
               src_insn=f"invoke-static {{...}}, {site['loader']}",
               dst_module=MOD_RUNTIME, dst_offset=None,
               dst_name=f"java.lang.System.loadLibrary(\"{site['literal']}\")",
               resolved_by=f"library name from {site['how']}",
               confidence="proven", evidence=site["evidence"],
               source="fragments/F2_dex_natives.txt + fragments.json:dex.loader_call_sites",
               chain=CH1 if site["literal"] == "engine" else CH9)
        g.edge("loads_library",
               src_module=MOD_RUNTIME, src_offset=None,
               src_name=f"System.loadLibrary(\"{site['literal']}\")",
               src_insn="dlopen",
               dst_module=lib, dst_offset=None, dst_name=f"lib/arm64-v8a/{lib}",
               resolved_by="SONAME of the committed library", confidence="proven",
               evidence=provenance_line(F, lib),
               source="fragments/F1_apk_inventory.txt",
               chain=CH1 if site["literal"] == "engine" else CH9,
               src_kind="runtime", dst_kind="module")
        payload = site.get("payload_off")
        if payload:
            g.edge("invokes_loader",
                   src_module=MOD_DEX, src_offset=payload, src_name=site["inside"],
                   src_insn=f"fill-array-data payload = {site['payload_hex']}",
                   dst_module=MOD_DEX, dst_offset=None,
                   dst_name=f"string literal \"{site['literal']}\" built at run time",
                   resolved_by="payload bytes decoded as ASCII", confidence="proven",
                   evidence=site["evidence"],
                   source="fragments.json:dex.loader_call_sites",
                   chain=CH1 if site["literal"] == "engine" else CH9,
                   note="the name never appears in the dex string pool")

    # native declarations
    for m in F["dex"]["native_methods"]:
        if not m.get("custom"):
            continue
        g.edge("declares_native",
               src_module=MOD_DEX, src_offset=None, src_name=m["class"],
               src_insn="ACC_NATIVE method declaration",
               dst_module=MOD_DEX, dst_offset=None,
               dst_name=f"{m['class']}->{m['name']}{m['sig']}",
               resolved_by="dex method_ids access flags", confidence="proven",
               evidence=f"expected static export {m['jni_long_name']} is exported by "
                        f"{', '.join(m['exported_by']) or 'NOBODY'} -> must be bound via RegisterNatives",
               source="fragments/F2_dex_natives.txt", chain=CH8,
               src_kind="class", dst_kind="dex_method")

    # java callers of the native classes (offsets live only in the F2 text)
    f2 = b.text("fragments/F2_dex_natives.txt")
    caller_block = re.compile(r"^\s+<-\s+(\S+)\s+@(0x[0-9a-f]+)\s*$")
    cur_class = None
    caller_count = 0
    for line in f2.splitlines():
        cm = re.match(r"^\s{2}(L[\w/$]+;):\s+(\d+)\s+distinct caller method", line)
        if cm:
            cur_class, _ = cm.group(1), int(cm.group(2))
            continue
        m = caller_block.match(line)
        if m and cur_class:
            caller_count += 1
            g.edge("invokes_native_class",
                   src_module=MOD_DEX, src_offset=int(m.group(2), 16), src_name=m.group(1),
                   src_insn="invoke-* (the invoked member is not recorded per site in F2)",
                   dst_module=MOD_DEX, dst_offset=None, dst_name=cur_class,
                   resolved_by="dex code-item scan (F2)", confidence="proven",
                   evidence=f"F2 lists this invoke offset under '{cur_class}: N distinct caller method(s)'; "
                            f"the class names are obfuscated into androidx.appcompat.view.menu.*, so the "
                            f"offset is the reliable half of the evidence",
                   source="fragments/F2_dex_natives.txt", chain=CH8,
                   src_kind="dex_method", dst_kind="class")
    check("every F2 caller offset became an edge",
          caller_count == 20, f"{caller_count} invoke offsets extracted from F2 (F2 says 20 for "
                              f"Lcom/snake/helper/Native; and 0 for Lcom/snake/helper/flagger;)")

    # ---------------- L2: loader ----------------
    CH2, CH3 = "CH-02", "CH-03"
    for entry in le["init_array"]:
        g.edge("loader_init_call",
               src_module=MOD_ENGINE, src_offset=int(entry["slot"], 16),
               src_name=f".init_array[{entry['index']}]",
               src_insn="R_AARCH64_RELATIVE addend (the slot holds no file bytes)",
               dst_module=MOD_ENGINE, dst_offset=int(entry["target"], 16),
               dst_name=f"_INIT_{entry['index']} ({entry['section']}, prologue {entry['prologue'][:16]}...)",
               resolved_by=".rela.dyn addend; Ghidra's 02_init_array_entries.txt agrees on all 44 "
                           "after rebasing -0x100000",
               confidence="proven",
               evidence=f"slot {entry['slot']} -> {entry['target']} in {entry['section']}, "
                        f"value_from={entry['value_from']}",
               source="fragments.json:libengine.init_array", chain=CH2)
    onload_va = next((a for a, n in known.items() if n == "JNI_OnLoad"), None)
    g.edge("calls_entry_point",
           src_module=MOD_RUNTIME, src_offset=None, src_name="linker (dlopen of libengine.so)",
           src_insn="call DT_INIT/JNI_OnLoad after the .init_array runs",
           dst_module=MOD_ENGINE, dst_offset=onload_va,
           dst_name="JNI_OnLoad",
           resolved_by=".dynsym STT_FUNC export", confidence="proven",
           evidence=f"F4: exports 17 defined dynamic symbols, 0 of them Java_*; JNI_OnLoad @0xf3fa0. "
                    f"Ghidra independently places it at 0x1f3fa0 = 0xf3fa0 + {hx(delta)}",
           source="fragments/F4_libengine_jni.txt", chain=CH3,
           src_kind="runtime")
    g.edge("calls_entry_point",
           src_module=MOD_RUNTIME, src_offset=None, src_name="linker (dlopen of libflutter.so)",
           src_insn="call JNI_OnLoad",
           dst_module=MOD_FLUTTER, dst_offset=None, dst_name="JNI_OnLoad",
           resolved_by=".dynsym export present (offset not recorded in F6)", confidence="proven",
           evidence="F6: exports 46 defined dynamic symbols, 0 Java_*, JNI_OnLoad present - so the "
                    "engine binds FlutterJNI's 41 natives with RegisterNatives at load time, the same "
                    "mechanism libengine.so uses for the 13 custom natives",
           source="fragments/F6_libflutter_version.txt", chain=CH9, src_kind="runtime")

    # ---------------- L3/L4: native windows ----------------
    f4c = b.text("fragments/F4c_libengine_JNI_OnLoad.asm")
    f4b = b.text("fragments/F4b_libengine_mytext.asm")
    f4d = b.text("fragments/F4d_libengine_regnatives_windows.asm")

    onload_insns = parse_listing(f4c, "fragments/F4c_libengine_JNI_OnLoad.asm")
    mytext_insns = parse_listing(f4b, "fragments/F4b_libengine_mytext.asm")
    reg_windows = split_windows(f4d, "fragments/F4d_libengine_regnatives_windows.asm")

    bad = validate_annotations([Window("jni_onload", 0xF3FA0, onload_insns,
                                       "fragments/F4c_libengine_JNI_OnLoad.asm"),
                                Window("mytext", mytext_lo, mytext_insns,
                                       "fragments/F4b_libengine_mytext.asm")] + reg_windows)
    check("every curated annotation points at a real instruction", not bad,
          "annotations checked against the decoded listings" + (f"; missing: {bad}" if bad else ""))

    win_onload = Window("JNI_OnLoad", 0xF3FA0, onload_insns,
                        "fragments/F4c_libengine_JNI_OnLoad.asm")
    ctx_onload = NativeCtx(g, MOD_ENGINE, plt, known, ANNOTATIONS, CH3, "JNI_OnLoad")
    scan_native(win_onload, ctx_onload, CH3)

    win_mytext = Window(".mytext", mytext_lo, mytext_insns, "fragments/F4b_libengine_mytext.asm")
    ctx_mytext = NativeCtx(g, MOD_ENGINE, plt, known, ANNOTATIONS, "CH-06",
                           ".mytext registered handler (0x81eeb4)")
    scan_native(win_mytext, ctx_mytext, "CH-06")

    CH_BY_SITE = {0xB0140: "CH-07", 0xB40A8: "CH-05", 0xF3A08: "CH-04"}
    site_meta = {int(s["va"], 16): s for s in le["register_natives_sites"]}
    for w in reg_windows:
        site = w.site or 0
        chain = CH_BY_SITE.get(site, "CH-04")
        meta = site_meta.get(site, {})
        ctx = NativeCtx(g, MOD_ENGINE, plt, known, ANNOTATIONS, chain,
                        f"registration site {hx(site)} (nMethods={w.attrs.get('nMethods')})")
        scan_native(w, ctx, chain)
        # the fnPtr recovered from the stack frame -> dex declaration
        for fp in meta.get("fnptrs", []):
            if fp.get("value"):
                g.edge("stages_fnptr",
                       src_module=MOD_ENGINE, src_offset=int(fp["store_va"], 16),
                       src_name=f"registration site {hx(site)}",
                       src_insn=f"stp/str {fp['reg']} -> {fp['offset']} "
                                f"(JNINativeMethod[{fp['entry']}].{fp['field']})",
                       dst_module=MOD_ENGINE, dst_offset=int(fp["value"], 16),
                       dst_name=(f"{fp['section']} {fp['value']} (exec={fp['executable']}) - the only "
                                 f"fnPtr of the 13 that survives static analysis"),
                       resolved_by="absolute value staged by adrp+add inside the same window",
                       confidence="proven",
                       evidence=f"{meta.get('table_evidence', '')}; the value written is "
                                f"{fp['value']}, which lies inside {fp['section']} "
                                f"({mytext['addr']} + {mytext['size']} bytes, "
                                f"exec={mytext_sec['executable']}, write={mytext_sec['writable']})",
                       source="fragments.json:libengine.register_natives_sites", chain=chain)
        # the class attribution, so the chain ends at a dex name
        g.edge("binds_fnptr_to_dex",
               src_module=MOD_ENGINE, src_offset=site,
               src_name=f"RegisterNatives site {hx(site)}",
               src_insn=f"nMethods = {meta.get('count')}",
               dst_module=MOD_DEX, dst_offset=None,
               dst_name=("com/snake/helper/Native (10 of its 11 declarations)" if meta.get("count") == 10
                         else "com/snake/helper/flagger (na, nb)" if meta.get("count") == 2
                         else "com/snake/helper/Native (pjowqpxe, by count-elimination)"),
               resolved_by="LINKAGE.md attribution: FindClass name length + decode-loop bounds + "
                           "count-elimination; consumes all 13 declarations exactly",
               confidence="strong" if meta.get("find_class_va") else "probable",
               evidence=meta.get("class_name_evidence", ""),
               source="fragments.json:libengine.register_natives_sites", chain=chain,
               dst_kind="class")

    # the registered fnPtr is the entry of the .mytext chain
    g.edge("calls_entry_point",
           src_module=MOD_RUNTIME, src_offset=None,
           src_name="ART (a Java caller of Lcom/snake/helper/Native;->update)",
           src_insn="call through the fnPtr registered by RegisterNatives",
           dst_module=MOD_ENGINE, dst_offset=0x81EEB0,
           dst_name=".mytext fnPtr 0x81eeb0 (decodes as 'ret'; the body starts at 0x81eeb4)",
           resolved_by="the only statically recovered fnPtr of the 13 registrations",
           confidence="strong",
           evidence="F4: site @0xb40a8 entry[1].fnPtr = 0x81eeb0 in .mytext; the instruction at "
                    "0x81eeb0 decodes as `ret` and 0x81eeb4 opens a real prologue "
                    "(`stp x29, x30, [sp, #-0x20]!`), so the pointer as published returns "
                    "immediately - see the gap list",
           source="fragments/F4b_libengine_mytext.asm", chain="CH-06", src_kind="runtime")

    # rejected 0x6b8 loads (audit trail)
    for s in le["slot_0x6b8_loads"]:
        if s["base"] == "env":
            continue
        g.edge("rejected_slot_load",
               src_module=MOD_ENGINE, src_offset=int(s["va"], 16),
               src_name="0x6b8-pattern load", src_insn=s["evidence"].split(" — ")[0],
               dst_module=MOD_ENGINE, dst_offset=None,
               dst_name=f"not a JNIEnv call (base = {s['base']})",
               resolved_by="the base register points at .bss/.got, not at *JNIEnv",
               confidence="proven", evidence=s["evidence"],
               source="fragments.json:libengine.slot_0x6b8_loads", chain="")

    # ---------------- L5: dart ----------------
    CH10, CH11 = "CH-10", "CH-11"
    sym, n_asm_addr = load_dart_symbols(b)
    dstats = dart_edges(b, g, sym, CH11)

    for h in F["tools"]["blutter"]["methodcall_handlers"]:
        sig = h["signature"]
        m = re.search(r"(\w+)\(", sig)
        fname = m.group(1) if m else "?"
        code = None
        for a, nm in sym.names.items():
            if nm.endswith(f"::{fname}_{a:x}"):
                code = a
                break
        if code is None:
            for line in b.text(h["file"]).splitlines():
                if fname in line:
                    am = DART_ADDR_RE.search(line)
                    if am:
                        code = int(am.group(1), 16)
        pool_slot = next((s for s, c in sym.pool.items() if c == code), None)
        g.edge("dart_instantiates_closure",
               src_module=MOD_POOL, src_offset=pool_slot,
               src_name=f"object pool slot pp+{hx(pool_slot)}",
               src_insn="AnonymousClosure entry",
               dst_module=MOD_APP, dst_offset=code,
               dst_name=f"MethodCall handler {fname} ({h['class']}) @ {hx(code)}",
               resolved_by="blutter asm '** addr:' + addNames.py + pp.txt agree on the address",
               confidence="proven" if code else "candidate",
               evidence=f"{h['file']}:{h['line']} `{sig}`; IDA name "
                        f"`{sym.names.get(code or -1, '?')}`; pool slot {hx(pool_slot)}"
                        + ("" if pool_slot else " (no pool slot found)"),
               source=h["file"], chain=CH10, src_kind="pool_slot")

    # ---------------- L5b: the C2 request path (CH-12) ---------------------
    # The endpoint is a pool String; the routine that uses it has no committed
    # disassembly, so the request side is attributed by pool-slot adjacency (the
    # method F8 §4 documents, with its limit stated).  The *response* side is
    # disassembled, and every step of it is anchored at a real instruction.
    CH12 = "CH-12"
    pj = F["tools"]["blutter"]["pool_join"]
    ep = next(x for x in F["libapp"]["indicators"]
              if x["category"] == "c2_endpoint" and "api/request" in x["value"])
    verdict, slot_txt = pj.get(ep["value"], ("pool-absent", ""))
    ep_slot = int(slot_txt.split("+")[1], 16) if slot_txt.startswith("pp+") else None
    check("the C2 endpoint is an exact object-pool slot",
          verdict == "pool-exact" and ep_slot is not None
          and ep["value"] in sym.pool_desc.get(ep_slot, ""),
          f"{ep['value']} = {slot_txt} ({verdict}) at output/blutter/pp.txt:"
          f"{sym.pool_line.get(ep_slot, '?')}; the same string is a raw byte run at "
          f"libapp.so file offset {hx(ep['file_offset'])} per F5 - one string, two "
          f"coordinate systems")
    check("every blr in the committed Dart listings is classified",
          dstats["blr_unlinked"] + dstats["blr_gdt"] + dstats["blr_closure"]
          + dstats["blr_other"] == dstats["blr_lines"],
          f"{dstats['blr_lines']} blr instructions: {dstats['blr_unlinked']} through an "
          f"UnlinkedCall pool slot, {dstats['blr_gdt']} through the dispatch table (GDT), "
          f"{dstats['blr_closure']} through a closure object, {dstats['blr_other']} other")
    check("every object-pool reference in the listings became an edge",
          dstats["pool_slot_loads"] + dstats["closure_slots"] == dstats["pool_comment_lines"],
          f"{dstats['pool_comment_lines']} listing lines carry a [pp+..] comment: "
          f"{dstats['pool_slot_loads']} loads_pool_slot + {dstats['closure_slots']} "
          f"dart_instantiates_closure")

    slots_sorted = sorted(sym.pool_desc)

    def find_slot(substr: str, lo: int = 0) -> Optional[int]:
        return next((sl for sl in slots_sorted
                     if sl >= lo and substr in sym.pool_desc[sl]), None)

    act_slot = find_slot("action=upload_profile_image")
    mp_lo = find_slot("----WebKitFormBoundary")
    mp_hi = find_slot('String: "post"', mp_lo or 0)
    pr_lo = find_slot("[dart:io] _ip", (mp_hi or 0) + 8)
    pr_hi = find_slot('String: ".jpg', pr_lo or 0)

    owners: Dict[tuple, int] = {}
    for sl in range((ep_slot or 0) - 0x40, (ep_slot or 0) + 0x48, 8):
        m = POOL_OWNER_RE.search(sym.pool_desc.get(sl, ""))
        if m:
            key = (m.group(1), m.group(2), int(m.group(3), 16))
            owners[key] = owners.get(key, 0) + 1
    owner = max(sorted(owners), key=lambda k: owners[k]) if owners else None
    check("the endpoint sits inside one closure family's pool run",
          owner is not None and owners.get(owner, 0) >= 3,
          (f"the slots around pp+{hx(ep_slot)} that name an owner all name "
           f"[{owner[0]}] {owner[1]}::<anonymous closure> ({hx(owner[2])}), "
           f"{owners.get(owner, 0)} of them within +-0x40 bytes" if owner else
           "no owner found in the neighbouring slots"))

    if owner:
        o_lib, o_cls, o_addr = owner
        o_file = f"output/blutter/asm/{o_lib}.dart"
        o_size = None
        if b.has(o_file):
            om = re.search(r"\*\* addr: " + hx(o_addr) + r", size: (-?0x[0-9a-f]+)",
                           b.text(o_file))
            o_size = om.group(1) if om else None
        o_label = f"[{o_lib}] {o_cls}::<anonymous closure> @ {hx(o_addr)}"
        o_insn = (f"(no committed listing: blutter gives size {o_size})"
                  if o_size in (None, "-0x1") else f"(listing at {hx(o_addr)}, size {o_size})")

        def run_note(lo: Optional[int], hi: Optional[int]) -> str:
            if lo is None or hi is None:
                return ""
            return "; ".join(f"pp+{hx(sl)} {sym.pool_desc[sl]}"
                             for sl in range(lo, hi + 8, 8) if sl in sym.pool_desc)

        def run_count(lo: Optional[int], hi: Optional[int]) -> int:
            return 0 if lo is None or hi is None else (hi - lo) // 8 + 1

        def adjacent(dst_slot, dst_name, what, note, first=None, last=None):
            if dst_slot is None:
                return
            # a pool String is shown as the value it holds, not as 'String: "…"'
            if dst_name.startswith('String: '):
                dst_name = dst_name[len('String: '):]
            lo = first if first is not None else dst_slot
            hi = last if last is not None else dst_slot
            pline = sym.pool_line.get(lo)
            g.edge("uses_pool_string",
                   src_module=MOD_APP, src_offset=o_addr, src_name=o_label, src_insn=o_insn,
                   dst_module=MOD_POOL, dst_offset=lo, dst_name=dst_name,
                   resolved_by=f"object-pool slot adjacency: pp+{hx(lo)}"
                               + (f"..pp+{hx(hi)}" if hi != lo else "")
                               + f" sit in the same allocation run as the {o_cls} closure slots "
                                 f"that name {hx(o_addr)} as their owner (F8 §4 records this method "
                                 f"and its limit: adjacency is not a call graph)",
                   confidence="probable",
                   evidence=what + (f"; output/blutter/pp.txt:{pline}" if pline else ""),
                   source=f"output/blutter/pp.txt:{pline}" if pline else "output/blutter/pp.txt",
                   chain=CH12, src_kind="function", dst_kind="pool_slot", note=note)

        adjacent(ep_slot, f'{sym.pool_desc.get(ep_slot, "")}',
                 f"the same string F5 found as a raw byte run at libapp.so file offset "
                 f"{hx(ep['file_offset'])} and F8 confirmed pool-exact at {slot_txt}",
                 f"endpoint of the request; second coordinate: libapp.so file "
                 f"{hx(ep['file_offset'])}")
        adjacent(act_slot, f'{sym.pool_desc.get(act_slot, "")}',
                 "the slot right after the endpoint, in the same run",
                 "the `?` is regex-escaped, i.e. Dart holds this as a pattern, exactly like the "
                 "2 store links in F8 §2")
        adjacent(mp_lo, f"multipart POST template ({run_count(mp_lo, mp_hi)} slots "
                        f"pp+{hx(mp_lo)}..{hx(mp_hi)})",
                 "consecutive slots between the endpoint run and the next unrelated entry",
                 run_note(mp_lo, mp_hi))
        adjacent(pr_lo, f"post-response run ({run_count(pr_lo, pr_hi)} slots "
                        f"pp+{hx(pr_lo)}..{hx(pr_hi)})",
                 "consecutive slots after the POST template, same run",
                 run_note(pr_lo, pr_hi))

    # ---- the response handler: located through the graph, not hard-coded ----
    succ = next((e for e in g.edges if e.kind == "loads_pool_slot"
                 and e.dst_name == '"success"'), None)
    if succ is not None:
        fn_file = succ.source.split(":")[0]
        fm = FN_AT_RE.search(succ.note or "")
        fn_entry = int(fm.group(1), 16) if fm else None
        fn_note = succ.note
        fn_label = succ.src_name
        text = b.text(fn_file)
        sm = re.search(r"\*\* addr: " + hx(fn_entry) + r", size: (0x[0-9a-f]+)", text)
        fn_end = fn_entry + int(sm.group(1), 16) if sm else None
        gdt = next((e for e in g.edges if e.kind == "dart_gdt_dispatch"
                    and e.note == fn_note), None)
        lines = text.splitlines()
        br = next(((int(m.group(1), 16), m.group(2), m.group(3), ln)
                   for ln, l in enumerate(lines, 1)
                   for m in [BRANCH_AFTER_CHECK_RE.match(l)]
                   if m and gdt is not None and fn_end is not None
                   and gdt.src_offset < int(m.group(1), 16) <= fn_end), None)
        sf = next(((int(m.group(1), 16), m.group(2), ln)
                   for ln, l in enumerate(lines, 1)
                   for m in [STORE_FIELD_RE.match(l)]
                   if m and br is not None and fn_end is not None
                   and br[0] < int(m.group(1), 16) <= fn_end), None)
        lf = next(((int(m.group(1), 16), m.group(2))
                   for ln, l in enumerate(lines, 1)
                   for m in [LOAD_FIELD_IR_RE.match(l)]
                   if m and sf is not None and br is not None
                   and br[0] < int(m.group(1), 16) < sf[0]), None)
        fld = next((e for e in g.edges if e.kind == "loads_pool_slot"
                    and e.dst_name.startswith("Field <") and e.note == fn_note), None)
        check("the C2 response handler's decision branch and store were located",
              br is not None and sf is not None,
              (f"in {fn_file}, function {hx(fn_entry)} size {hx(fn_end - fn_entry)}: the branch "
               f"after the dispatch is `{br[1]} {br[2]}` at {hx(br[0])} and the store is "
               f"`{sf[1]}` at {hx(sf[0])}" if br and sf else
               f"branch={br}, store={sf} in {fn_file} function {hx(fn_entry or 0)}"))

        def insn_at(off: int) -> str:
            for l in lines:
                m = INSN_OFFSET_RE.search(l)
                if m and int(m.group(1), 16) == off and not l.strip().startswith("// 0x"):
                    return " ".join(m.group(2).split())
            return ""

        if br:
            tgt = BRANCH_TARGET_RE.search(br[2])
            g.edge("branch_on_check",
                   src_module=MOD_APP, src_offset=br[0], src_name=fn_label,
                   src_insn=f"{br[1]} {' '.join(br[2].split())}",
                   dst_module=MOD_APP,
                   dst_offset=int(tgt.group(1), 16) if tgt else None,
                   dst_name=(f"the merge point at {tgt.group(1)} of the same function - where the "
                             f"handler continues when the check does NOT hold" if tgt
                             else "the merge point of the same function"),
                   resolved_by="the decoded conditional branch inside the response handler; the bit "
                               "it tests is the result of the dispatch two instructions earlier",
                   confidence="proven",
                   evidence=f"{fn_file}:{br[3]} inside `{fn_label.split('::')[-1]}` "
                            f"(function entry {hx(fn_entry)}), instruction "
                            f"`{lines[br[3] - 1].strip()}`",
                   source=f"{fn_file}:{br[3]}", chain=CH12, note=fn_note)
        if sf:
            dst_slot = fld.dst_offset if fld else None
            g.edge("stores_response_field",
                   src_module=MOD_APP, src_offset=sf[0], src_name=fn_label,
                   src_insn=insn_at(sf[0]) or "StoreField",
                   dst_module=MOD_POOL if fld else MOD_APP, dst_offset=dst_slot,
                   dst_name=((f"{fld.dst_name}" + (f"; the value lands in ({lf[1].split('=')[-1].strip()})->{sf[1].split('=')[0].strip()}" if lf else f"; {sf[1]}"))
                             if fld else sf[1]),
                   resolved_by="blutter's own StoreField IR line for this instruction, plus the pool "
                               "slot that names the field being initialised",
                   confidence="proven",
                   evidence=f"{fn_file}:{sf[2]} inside `{fn_label.split('::')[-1]}` "
                            f"(function entry {hx(fn_entry)}), IR line `{lines[sf[2] - 1].strip()}` "
                            f"/ instruction `{insn_at(sf[0])}`",
                   source=f"{fn_file}:{sf[2]}", chain=CH12,
                   dst_kind="pool_slot" if fld else "field", note=fn_note)

    # ---------------- L6: engine ----------------
    for name, offs in F["libapp"]["engine_api_names"].items():
        eoffs = F["libflutter"]["engine_api_names"].get(name)
        g.edge("binds_engine_symbol",
               src_module=MOD_APP, src_offset=int(offs[0], 16), src_name=name,
               src_insn="snapshot string referencing an engine C++ API",
               dst_module=MOD_FLUTTER, dst_offset=int(eoffs[0], 16) if eoffs else None,
               dst_name=name, resolved_by="exact byte match of the API name in both images",
               confidence="proven" if eoffs else "candidate",
               evidence=f"libapp.so+{offs[0]} <-> libflutter.so+{eoffs[0] if eoffs else '?'}; "
                        f"11 of the 11 names the snapshot uses exist in the shipped engine",
               source="fragments/F6_libflutter_version.txt", chain=CH10,
               src_kind="string", dst_kind="string")

    # ---------------- de-duplicate --------------------------------------
    # A hop can be discovered twice (once by the instruction scan, once by the
    # structured fragment record).  Keep the first, merge the chain labels.
    seen: Dict[tuple, Edge] = {}
    dupes = 0
    for e in list(g.edges):
        key = (e.kind, e.src_module, e.src_offset, e.dst_module, e.dst_offset, e.dst_name)
        if key in seen:
            keep = seen[key]
            if e.chain and not keep.chain:
                keep.chain, keep.hop = e.chain, e.hop
            g.edges.remove(e)
            dupes += 1
        else:
            seen[key] = e

    # ---------------- chains ----------------
    chains = define_chains(g, F, le, dstats, sym, plt)

    # ---------------- checks ----------------
    n_by_conf = {c: sum(1 for e in g.edges if e.confidence == c) for c in CONFIDENCE}
    reg_total = sum(s["count"] for s in le["register_natives_sites"])
    declared = sum(1 for m in F["dex"]["native_methods"] if m.get("custom"))
    check("RegisterNatives nMethods total == custom native declarations",
          reg_total == declared, f"{reg_total} registered across 3 sites vs {declared} declared in the dex")
    check("the recovered fnPtr lies inside .mytext",
          mytext_lo <= 0x81EEB0 < mytext_hi,
          f"0x81eeb0 in {mytext['addr']}..{hx(mytext_hi)}")
    check("JNI_OnLoad edges start at the exported entry point",
          any(e.src_module == MOD_ENGINE and e.src_offset and 0xF3FA0 <= e.src_offset < 0xF6000
              for e in g.edges), "F4c decodes 420 instructions from 0xf3fa0")
    reg_blr = {}
    for s in le["register_natives_sites"]:
        va = int(s["va"], 16)
        hits = [e.src_offset for e in g.edges
                if e.kind == "registers_natives" and e.src_offset and va < e.src_offset <= va + 0x20]
        reg_blr[s["va"]] = hits
    check("every RegisterNatives site produced exactly one slot-215 blr edge",
          all(len(v) == 1 for v in reg_blr.values()),
          "; ".join(f"ldr @ {k} -> blr at {', '.join(hx(x) for x in v) or 'NONE'}"
                    for k, v in reg_blr.items()))
    check("no edge points outside its module's address space",
          all((e.src_offset or 0) < 0x900000 and (e.dst_offset or 0) < 0x900000
              for e in g.edges if e.src_module == MOD_ENGINE or e.dst_module == MOD_ENGINE),
          "libengine.so is 8,544,568 bytes; every offset used is below that")
    dart_targets_in_text = all(
        0x160000 <= (e.dst_offset or 0) < 0x160000 + 4178912
        for e in g.edges if e.dst_module == MOD_APP and e.dst_offset is not None)
    check("every Dart call target lands inside libapp.so .text",
          dart_targets_in_text, ".text addr=0x160000 size=4,178,912 (F5)")
    check("blutter's inline stub names agree with its own IDA name table",
          dstats["inline_vs_table_conflict"] == 0,
          f"of the {dstats['inline_named']} hops that carry an inline name, "
          f"{dstats['inline_vs_table_exact']} match addNames.py exactly after decoration is stripped, "
          f"{dstats['inline_vs_table_variant']} are the same address under a different label form "
          f"(e.g. '[dart:core] Map::Map._fromLiteral' vs 'dart_core_Map::factory_ctor__fromLiteral'), "
          f"{dstats['inline_vs_table_conflict']} conflict; the remaining "
          f"{dstats['inline_named'] - dstats['inline_vs_table_exact'] - dstats['inline_vs_table_variant']}"
          f" have no addNames.py entry at all, so the inline comment is the only name they get")
    check("every IR-level 'r0 = call' line has the matching bl instruction",
          dstats["ir_call_lines"] == dstats["ir_calls_matched_by_bl"],
          f"{dstats['ir_calls_matched_by_bl']}/{dstats['ir_call_lines']} IR call lines are the same "
          f"hop as a decoded bl at the same offset")
    check("no hop is published twice", dupes == 0,
          f"{dupes} duplicate (kind, caller, callee) rows removed during the build"
          if dupes else "the instruction scan and the structured fragment records agree; no "
                       "duplicate hops were produced")
    check("no dangling node reference",
          all(e.src_module and e.dst_module for e in g.edges), f"{len(g.edges)} edges, "
          f"{len(g.nodes)} nodes")
    check("chain hops all exist in the edge set",
          all(h["edge"] for c in chains for h in c["hops"]),
          f"{sum(len(c['hops']) for c in chains)} hops across {len(chains)} chains")

    meta = {
        "title": "SNAKE.apk - call linkage",
        "generated_by": "tools/build_call_linkage.py",
        "bundle": getattr(b, "label", b.path),
        "bundle_fingerprint": b.fingerprint(),
        "bundle_files": len(b.names()),
        "lang": lang,
        "layers": [{"id": l, "name": n, "description": d} for l, n, d in LAYERS],
        "kinds": KINDS,
        "jnienv_slots": {hx(k): {"index": v[0], "name": v[1]} for k, v in sorted(JNIENV_SLOTS.items())},
        "syscalls": {str(k): v for k, v in sorted(SYSCALLS.items())},
        "plt_names": {hx(k): v for k, v in sorted(plt.items())},
        "ghidra_image_base_delta": hx(delta),
        "counts": {
            "nodes": len(g.nodes),
            "edges": len(g.edges),
            "by_confidence": n_by_conf,
            "by_layer": {l: sum(1 for e in g.edges if e.layer == l) for l, _, _ in LAYERS},
            "by_kind": dict(sorted(
                ((k, sum(1 for e in g.edges if e.kind == k)) for k in KINDS),
                key=lambda kv: (-kv[1], kv[0]))),
            "dart": {k: (len(v) if isinstance(v, (set, list)) else v)
                     for k, v in dstats.items() if k != "fan_in"},
        },
        "dart_symbol_tables": {
            "ida_names": len(sym.names), "ida_func_ranges": len(sym.ranges),
            "asm_addr_markers": len(sym.asm_addrs), "pool_closure_slots": len(sym.pool),
        },
    }
    return {"meta": meta, "nodes": list(g.nodes.values()), "edges": g.edges,
            "chains": chains, "checks": checks,
            "dart_fan_in": dstats["fan_in"], "dart_symbols": sym}


# --------------------------------------------------------------------------
# chains: ordered walks through the edge set
# --------------------------------------------------------------------------

# Thai renderings of the chain title / goal / note.  None keeps the generated
# note (the per-site ones are built from live counts inside define_chains).
CHAIN_TH = {
    "CH-01": ("เริ่มแอป -> libengine.so ถูกโหลด",
              "แสดงว่าตัวอย่างนี้ไปถึงโค้ด native ที่ถูกปกป้องได้อย่างไร: ชื่อ 'engine' ไม่เคยอยู่ใน "
              "dex string pool เลย แต่ถูกประกอบขึ้นทีละไบต์ด้วย `fill-array-data`", None),
    "CH-02": ("dynamic loader -> constructor ที่วางไว้ 44 ตัว",
              "constructor ทุกตัวที่ loader จะเรียก พร้อม slot ที่เก็บมันและ offset ปลายทาง — "
              "รายการอิสระของ Ghidra ตรงกันทั้ง 44/44 หลัง rebase",
              "37 จาก 44 ตัวใช้ prologue 16 ไบต์เดียวกัน (e80f19fcfd7b01a9fd430091fc6f02a9) "
              "และกระจายครอบคลุม .text ถึง 6,477,316 ไบต์; Ghidra decompile ไม่ผ่านสักตัว "
              "(7 'no function', 37 timeout)"),
    "CH-03": ("JNI_OnLoad: หน้า RWX 2 หน้า, opcode branch ที่สังเคราะห์เอง, และทางออก 2 ครั้ง",
              "JNI_OnLoad ไม่ได้ register อะไรเลย (0 JNIEnv slot load ใน decompiled C 12,280 ไบต์ของ "
              "Ghidra) สิ่งที่มันทำแทนคือ *ประกอบโค้ด*: ลำดับ hop นี้อธิบายว่าทำไม registration site "
              "จริงจึงอยู่ที่อื่น",
              "blr ทั้ง 2 ครั้งถูกอธิบายครบ (F4: blr_count = 2) และ svc ทั้ง 4 คือ syscall ทั้งหมดใน "
              "420 คำสั่งที่ถอดรหัสได้"),
    "CH-04": ("registration site 0xf3a08 -> com/snake/helper/Native",
              "เดินตามคำสั่งทีละตัวตั้งแต่ call แรกใน window จนถึง blr ของ RegisterNatives "
              "และจบที่ชื่อคลาสฝั่ง dex ที่มันถูกโยงไป", None),
    "CH-05": ("registration site 0xb40a8 -> com/snake/helper/flagger",
              "เดินตามคำสั่งทีละตัวตั้งแต่ svc แรกใน window จนถึง blr ของ RegisterNatives — "
              "window นี้คือแหล่งของ fnPtr ตัวเดียวที่กู้คืนได้จาก static", None),
    "CH-06": ("native handler ตัวเดียวที่กู้คืนได้จาก static (.mytext)",
              "ตาม fnPtr เพียงตัวเดียวที่รอดจากการวิเคราะห์แบบ static: ถูก register โดย site 0xb40a8, "
              "อยู่ใน section ที่ตั้งชื่อเองว่า .mytext, เรียก FromReflectedMethod แล้วส่งต่อเข้า .text "
              "ที่ 0xb01c4",
              "Lcom/snake/helper/Native;->update(Ljava/lang/Object;Ljava/lang/reflect/Method;)V เป็น "
              "1 ใน 13 declaration เดียวที่พารามิเตอร์ Java ตัวที่สองเป็น java.lang.reflect.Method "
              "รูปทรงจึงตรงกัน — นี่คือข้อความระดับ static ที่แรงที่สุดที่พูดได้เกี่ยวกับ pointer ตัวนี้"),
    "CH-07": ("registration site 0xb0140 -> com/snake/helper/Native",
              "window ที่เล็กที่สุด (nMethods = 1) แต่มี hop ครบทุกรูปแบบที่เอกสารนี้ใช้: syscall stub, "
              "virtual call ใน decode loop, call รูปทรง memcmp, RegisterNatives และ ExceptionClear "
              "บนเส้นทางล้มเหลว", None),
    "CH-08": ("invoke site ฝั่ง Java 20 จุด -> native 13 ตัวที่ถูก register",
              "ฝั่ง Java ของสะพาน: ทุก offset ใน dex ที่ invoke เข้าคลาสซึ่งเมธอดของมันถูกผูกด้วย "
              "RegisterNatives เรียงตาม offset เพื่อไล่ caller ที่ถูก obfuscate ไปอยู่ใต้ "
              "androidx.appcompat.view.menu.* ได้ตามลำดับไฟล์",
              "F2 บันทึก offset ของ invoke แต่ไม่ได้บันทึกว่าเป็นเมธอดใด hop เหล่านี้จึงจบที่ระดับคลาส; "
              "ส่วน Lcom/snake/helper/flagger; มี caller 0 ตัว — เป็น dead code หรือถูกเรียกผ่าน "
              "reflection / จาก Dart"),
    "CH-09": ("ฝั่ง Flutter ใช้กลไกเดียวกัน",
              "libflutter.so ก็ไม่ export Java_* เลย แต่ dex ประกาศ FlutterJNI native ไว้ 41 ตัว — "
              "engine จึงต้องใช้ RegisterNatives เหมือนกัน กลไกเดียวกันคนละไลบรารี "
              "ซึ่งคือเหตุผลว่าทำไม native ทั้ง 13 ตัวของแอปจึงไม่ใช่เรื่องพิเศษ", None),
    "CH-10": ("handler ของ Dart platform channel และสัญลักษณ์ engine ที่มันวิ่งอยู่",
              "ปลายฝั่ง Dart ของสะพาน: closure ที่รับ MethodCall 3 ตัว (_pfc, _cec, _eec) ซึ่ง blutter "
              "พบในไลบรารี C2 — แต่ละตัวมีทั้ง code offset และ pool slot — พร้อมชื่อ "
              "PlatformConfigurationNativeApi 11 ตัวที่ snapshot ผูกกับ libflutter.so "
              "(ระบุ offset ทั้งสองฝั่ง)",
              "ไม่มี disassembly ของ handler ทั้ง 3 ตัวใน dump ('** addr' มาคู่กับ size -1) "
              "จึงไล่ hop ขาออกจากชุดหลักฐานนี้ไม่ได้; pool slot (pp+…) คือจุดที่ควร hook เมื่อรันจริง"),
    "CH-12": ("endpoint C2 /api/request/: อะไรทำงานต่อเมื่อเช็ค response ผ่านแล้ว",
              "ตอบจากหลักฐานที่ commit ไว้ว่า endpoint ที่ hard-code ไว้ถูกใช้ทำอะไร และเกิดอะไรขึ้น "
              "หลังเช็ค response สำเร็จ — ฝั่งคำขอผูกด้วย object-pool adjacency เพราะ blutter ระบุ "
              "routine นั้นไว้ด้วย size -0x1 ส่วนฝั่ง response ไล่ระดับทีละคำสั่งตั้งแต่ call ที่ "
              "decode ไปจนถึงคำสั่งที่เก็บค่า",
              "endpoint ตัวที่สอง (https://www.snakeengine.com/topup/, pp+0x17790 = libapp.so file "
              "0x3d50e) ไม่ได้ถูกไล่ในที่นี้: ไม่มี listing ที่ commit ไว้ใดอ้าง slot ของมัน และ "
              "เพื่อนบ้านของมันเป็น allocation run คนละชุด"),
    "CH-11": ("call edge ระดับ instruction ของ Dart (จัดอันดับตาม fan-in)",
              "disassembly ของ blutter ให้ offset จริงสำหรับฝั่ง Dart ตารางนี้คือเป้าหมายที่ถูกเรียกบ่อย "
              "ที่สุด 12 อันดับ; edge ทั้งหมดอยู่ใน call_linkage.csv เรียงตาม offset", None),
}


def define_chains(g: Graph, F: dict, le: dict, dstats: dict, sym: DartSymbols,
                  plt: Dict[int, str]) -> List[dict]:
    by_src: Dict[Tuple[str, Optional[int]], List[Edge]] = {}
    for e in g.edges:
        by_src.setdefault((e.src_module, e.src_offset), []).append(e)

    def find(module: str, offset: Optional[int], kind: Optional[str] = None,
             dst_module: Optional[str] = None, dst_offset: Optional[int] = None) -> Optional[Edge]:
        for e in by_src.get((module, offset), []):
            if kind is not None and e.kind != kind:
                continue
            if dst_module is not None and e.dst_module != dst_module:
                continue
            if dst_offset is not None and e.dst_offset != dst_offset:
                continue
            return e
        return None

    def find_kind(kind: str, module: Optional[str] = None) -> List[Edge]:
        return [e for e in g.edges if e.kind == kind and (module is None or e.src_module == module)]

    def hop(e: Optional[Edge], why: str) -> dict:
        return {"edge": e.id if e else "", "text": why,
                "ok": bool(e)}

    chains: List[dict] = []

    # CH-01 application start -> the protected library is loaded
    c1 = [
        hop(find(MOD_DEX, 0x2B6C2E, "invokes_loader"),
            "Lcom/snake/App;-><clinit>()V invokes System.loadLibrary"),
        hop(find(MOD_DEX, 0x2B6C40, "invokes_loader"),
            "the library name is built by fill-array-data, 6 payload bytes = 'engine'"),
        hop(find(MOD_RUNTIME, None, "loads_library"),
            "the loader maps lib/arm64-v8a/libengine.so"),
        hop(sorted(find_kind("loader_init_call"), key=lambda e: e.src_offset or 0)[0],
            "the loader then calls the 44 .init_array constructors before JNI_OnLoad"),
        hop(find(MOD_RUNTIME, None, "calls_entry_point", dst_module=MOD_ENGINE),
            "and finally hands control to the exported JNI_OnLoad"),
    ]
    chains.append(dict(id="CH-01", layer_path="L1 -> L2 -> L3",
                       title="application start -> libengine.so is loaded",
                       goal="Show how the sample reaches its protected native code at all: the name "
                            "'engine' never appears in the dex string pool, it is built byte by byte.",
                       hops=c1))

    # CH-02 ELF init fan-out (summarised: 44 hops)
    init_edges = sorted(find_kind("loader_init_call"), key=lambda e: e.src_offset or 0)
    grouped = set(le["init_array_chains"][0]["indexes"]) if le.get("init_array_chains") else set()
    chains.append(dict(id="CH-02", layer_path="L2 -> L3", layout="index",
                       title="dynamic loader -> 44 staged constructors",
                       goal="Every constructor the loader will call, with the slot that holds it and "
                            "the target offset it points at. Ghidra's independent list agrees on 44/44.",
                       hops=[hop(e, T(
                           f"index {i}: shares the 16-byte prologue with 36 others"
                           if i in grouped else
                           f"index {i}: its own prologue, not part of the 37-entry group",
                           f"ลำดับที่ {i}: ใช้ prologue 16 ไบต์ร่วมกับอีก 36 ตัว"
                           if i in grouped else
                           f"ลำดับที่ {i}: prologue ของตัวเอง ไม่อยู่ในกลุ่ม 37 ตัว"))
                           for i, e in enumerate(init_edges)],
                       note="37 of the 44 share the identical 16-byte prologue "
                            "e80f19fcfd7b01a9fd430091fc6f02a9 and span 6,477,316 bytes of .text; "
                            "Ghidra decompiled 0 of them (7 'no function', 37 timeout)."))

    # CH-03 JNI_OnLoad: the trampoline builder
    c3 = [
        hop(find(MOD_ENGINE, 0xF3FD8), "sysconf - page size for the coming mmap"),
        hop(find(MOD_ENGINE, 0xF4018, "calls_syscall"), "svc #222 mmap(NULL, len, PROT_RWX, "
                                                        "MAP_PRIVATE|ANON) - the first writable+exec page"),
        hop(find(MOD_ENGINE, 0xF4054, "computes_branch"), "br through a 4-entry relative-offset table"),
        hop(find(MOD_ENGINE, 0xF406C), "rand - entropy mixed into the synthesised opcode"),
        hop(find(MOD_ENGINE, 0xF4078), "the B opcode is written word by word into the fresh page"),
        hop(find(MOD_ENGINE, 0xF40A0), "page n-1 is patched to branch to page n"),
        hop(find(MOD_ENGINE, 0xF40AC), "FUN_0091ad58 (Ghidra) over the written range"),
        hop(find(MOD_ENGINE, 0xF40E0), "blr into the generated page - control leaves the static image"),
        hop(find(MOD_ENGINE, 0xF411C, "calls_syscall"), "second RWX mmap"),
        hop(find(MOD_ENGINE, 0xF43F4), "blr into the second generated table; the return value selects "
                                       "the path"),
        hop(find(MOD_ENGINE, 0xF4428, "calls_syscall"), "svc #53 fchmodat on a value taken from the "
                                                        "generated page"),
        hop(find(MOD_ENGINE, 0xF446C), "strlen - the two length checks (11 and 10) that gate the "
                                       "byte-decode loops"),
    ]
    chains.append(dict(id="CH-03", layer_path="L3",
                       title="JNI_OnLoad: two RWX pages, a synthesised branch opcode, two jumps out",
                       goal="JNI_OnLoad registers nothing itself (0 JNIEnv slot loads in 12,280 bytes "
                            "of Ghidra decompilation). What it does instead is build code: this is the "
                            "hop sequence that explains why the registration sites live elsewhere.",
                       hops=c3,
                       note="Both blr are accounted for (F4: blr_count = 2); the 4 svc are the only "
                            "syscalls in the decoded 420 instructions."))

    # CH-04/05/07 the three registration sites
    for site, ch, cls, extra in (
        (0xF3A08, "CH-04", "com/snake/helper/Native", [
            hop(find(MOD_ENGINE, 0xF3944), "FUN_0091ad58 (Ghidra)"),
            hop(find(MOD_ENGINE, 0xF3968, "calls_syscall_stub"),
                "indirect syscall stub: mmap(0, 0x18, PROT_RWX, MAP_PRIVATE|ANON)"),
            hop(find(MOD_ENGINE, 0xF3988), "0x7778a8 - returns the decoder object"),
            hop(find(MOD_ENGINE, 0xF3994), "0x81f140 - a 12-byte record is allocated"),
            hop(find(MOD_ENGINE, 0xF39C4, "calls_vtable0"),
                "the decode loop calls *obj once per byte (23 iterations)"),
            hop(find(MOD_ENGINE, 0xF39E8, "finds_class"),
                "FindClass on the 23 decoded bytes -> 'com/snake/helper/Native'"),
            hop(find(MOD_ENGINE, 0xF3A0C, "registers_natives"),
                "RegisterNatives(env, jclass, 0x828ee8, 10)"),
        ]),
        (0xB40A8, "CH-05", "com/snake/helper/flagger", [
            hop(find(MOD_ENGINE, 0xB3FE4, "calls_syscall"), "svc #53 fchmodat before anything else"),
            hop(find(MOD_ENGINE, 0xB4018), "FUN_0091ad58 (Ghidra)"),
            hop(find(MOD_ENGINE, 0xB403C, "calls_syscall_stub"),
                "indirect syscall stub: mmap(0, 4, PROT_RWX, MAP_PRIVATE|ANON)"),
            hop(find(MOD_ENGINE, 0xB4050), "0x777fb0 - returns the decoder object"),
            hop(find(MOD_ENGINE, 0xB405C), "0x81f140 - a 12-byte record is allocated"),
            hop(find(MOD_ENGINE, 0xB407C, "calls_vtable0"),
                "the decode loop calls *obj once per byte (3 iterations)"),
            hop(find(MOD_ENGINE, 0xB40B0, "stages_fnptr"),
                "stp x21, x9, [sp, #0x40] - the fnPtr written into the table is 0x81eeb0"),
            hop(find(MOD_ENGINE, 0xB40B4, "registers_natives"),
                "RegisterNatives(env, jclass, sp+0x20, 2)"),
        ]),
        (0xB0140, "CH-07", "com/snake/helper/Native", [
            hop(find(MOD_ENGINE, 0xB0068, "calls_syscall_stub"),
                "indirect syscall stub with 6 staged arguments (mmap-shaped)"),
            hop(find(MOD_ENGINE, 0xB0088), "0x7775d8 - returns the decoder object"),
            hop(find(MOD_ENGINE, 0xB0094), "0x81f140 - a 12-byte record is allocated"),
            hop(find(MOD_ENGINE, 0xB00C4, "calls_vtable0"),
                "the decode loop calls *obj once per byte (8 iterations)"),
            hop(find(MOD_ENGINE, 0xB00E4),
                "0x81f250 - memcmp-shaped (ptr, ptr, 8) -> int, gates the registration"),
            hop(find(MOD_ENGINE, 0xB0144, "registers_natives"),
                "RegisterNatives(env, jclass, sp+0x38, 1)"),
            hop(find(MOD_ENGINE, 0xB018C, "calls_jni_slot"),
                "ExceptionClear on the failure path"),
        ]),
    ):
        chains.append(dict(id=ch, layer_path="L3 -> L4 -> L1",
                           title=f"registration site {hx(site)} -> {cls}",
                           goal=f"The full instruction walk from the first call in the window to the "
                                f"RegisterNatives blr, ending at the dex class it is attributed to.",
                           hops=extra,
                           note=f"nMethods = {site_meta_count(le, site)}. The decoded window "
                                f"contributes {sum(1 for e in g.edges if e.chain == ch)} edges to the "
                                f"graph; the {len(extra)} above are the control transfers, in address "
                                f"order.",
                           note_th=f"nMethods = {site_meta_count(le, site)} — window ที่ถอดรหัสไว้นี้ให้ "
                                   f"edge รวม {sum(1 for e in g.edges if e.chain == ch)} เส้น "
                                   f"ส่วน {len(extra)} รายการข้างบนคือ control transfer เรียงตาม address"))

    # CH-06 .mytext handler
    c6 = [
        hop(find(MOD_ENGINE, 0xB40B0, "stages_fnptr"),
            "window 2 stages the pointer: adrp 0x81e000 + add #0xeb0 -> 0x81eeb0, stored into "
            "JNINativeMethod[1].fnPtr"),
        hop(find(MOD_RUNTIME, None, "calls_entry_point", dst_offset=0x81EEB0),
            "ART later calls that fnPtr - 0x81eeb0 decodes as `ret`, the real body opens at 0x81eeb4"),
        hop(find(MOD_ENGINE, 0x81EED4, "calls_jni_slot"),
            "the body converts its 4th argument: FromReflectedMethod(env, x3)"),
        hop(find(MOD_ENGINE, 0x81EEE4),
            "and forwards (env, saved x2, reflected Method) into .text at 0xb01c4"),
    ]
    chains.append(dict(id="CH-06", layer_path="L4 -> L3",
                       title="the one statically recoverable native handler (.mytext)",
                       goal="Follow the single fnPtr that survives static analysis: registered by "
                            "site 0xb40a8, living in the hand-named .mytext section, calling "
                            "FromReflectedMethod and then forwarding into .text at 0xb01c4.",
                       hops=c6,
                       note="Lcom/snake/helper/Native;->update(Ljava/lang/Object;"
                            "Ljava/lang/reflect/Method;)V is the only one of the 13 declarations whose "
                            "second Java parameter is a java.lang.reflect.Method, so the shapes agree."))

    # CH-08 java callers -> natives
    caller_edges = sorted([e for e in g.edges if e.kind == "invokes_native_class"],
                          key=lambda e: e.src_offset or 0)
    chains.append(dict(id="CH-08", layer_path="L1 -> L4", layout="index",
                       title="20 Java invoke sites -> the 13 registered natives",
                       goal="The Java side of the bridge: every dex offset that invokes a class whose "
                            "methods are bound by RegisterNatives. Sorted by offset, so the obfuscated "
                            "androidx.appcompat.view.menu.* callers can be walked in file order.",
                       hops=[hop(e, binding_note(e.dst_name)) for e in caller_edges],
                       note="F2 records the invoke offset but not which member is invoked, so these "
                            "hops end at the class. Lcom/snake/helper/flagger; has 0 callers - dead "
                            "code, or reached reflectively / from Dart."))

    # CH-09 flutter side
    c9 = [
        hop(find(MOD_DEX, 0x2BAFDA, "invokes_loader"),
            "FlutterJNI.loadLibrary() invokes System.loadLibrary('flutter')"),
        hop([e for e in g.edges if e.kind == "loads_library" and e.dst_module == MOD_FLUTTER][0],
            "the loader maps lib/arm64-v8a/libflutter.so"),
        hop([e for e in g.edges if e.kind == "calls_entry_point"
             and e.dst_module == MOD_FLUTTER][0],
            "the engine's own JNI_OnLoad binds FlutterJNI's 41 natives the same way"),
    ]
    chains.append(dict(id="CH-09", layer_path="L1 -> L2 -> L6",
                       title="the Flutter half of the same mechanism",
                       goal="libflutter.so exports no Java_* symbol either, yet the dex declares 41 "
                            "FlutterJNI natives - so the engine uses RegisterNatives too. Same shape, "
                            "different library, which is why the 13 custom natives are not special.",
                       hops=c9))

    # CH-10 dart <-> engine
    handlers = [e for e in g.edges if e.kind == "dart_instantiates_closure" and e.src_module == MOD_POOL]
    engine_edges = sorted([e for e in g.edges if e.kind == "binds_engine_symbol"],
                          key=lambda e: e.src_offset or 0)
    chains.append(dict(id="CH-10", layer_path="L5 -> L6",
                       title="Dart platform-channel handlers and the engine symbols they run on",
                       goal="The Dart end of the bridge: the 3 MethodCall closures blutter found in "
                            "the C2 library, each with a code offset and an object-pool slot, plus the "
                            "11 PlatformConfigurationNativeApi names the snapshot resolves against "
                            "libflutter.so (both offsets given).",
                       hops=[hop(e, f"{e.dst_name} - pool slot {hx(e.src_offset)}") for e in handlers]
                            + [hop(e, f"{e.dst_name}: libapp.so+{hx(e.src_offset)} <-> "
                                      f"libflutter.so+{hx(e.dst_offset)}") for e in engine_edges],
                       note="No disassembly of the 3 handlers is in the dump ('** addr' with size -1), "
                            "so their outgoing hops are not recoverable from this bundle; the pool slots "
                            "(pp+...) are the hook points a dynamic run should watch."))

    # CH-11 dart instruction-level call edges (the hottest ones)
    dart_edges_all = [e for e in g.edges if e.kind in ("dart_call", "dart_tail_call")]
    fan = sorted(((t, c) for t, c in dstats["fan_in"].items()), key=lambda kv: (-kv[1], kv[0]))
    hot = []
    for t, c in fan[:12]:
        ex = next((e for e in dart_edges_all if e.dst_offset == t), None)
        hot.append(hop(ex, f"fan-in {c} - one of its call sites is shown at the left"))
    chains.append(dict(id="CH-11", layer_path="L5", layout="index",
                       title="Dart instruction-level call edges (fan-in ranking)",
                       goal="blutter's disassembly yields real instruction offsets for the Dart half. "
                            "The 12 hottest call targets are listed here; the complete edge set "
                            f"({len(dart_edges_all)} hops) is in call_linkage.csv, sorted by offset.",
                       hops=hot,
                       note=f"{dstats['inline_named']} hops carry blutter's own inline stub name, "
                            f"{dstats['resolved_by_table']} more resolve through addNames.py, "
                            f"{dstats['unresolved']} stay address-only "
                            f"({len(dstats['distinct_targets'])} distinct targets overall).",
                       note_th=f"{dstats['inline_named']} hop มีชื่อ stub ที่ blutter แนบมาในบรรทัดเดียว, "
                               f"อีก {dstats['resolved_by_table']} hop แกะได้ผ่าน addNames.py, "
                               f"{dstats['unresolved']} hop เหลือแค่ address "
                               f"(เป้าหมายไม่ซ้ำกันทั้งหมด {len(dstats['distinct_targets'])} ตัว)"))

    # CH-12 the C2 endpoint: what runs once its response check passes
    c2_uses = sorted(find_kind("uses_pool_string"), key=lambda e: e.dst_offset or 0)
    br = find_kind("branch_on_check")
    walk: List[Edge] = []
    if br:
        fn_file = br[0].source.split(":")[0]
        walk = sorted([e for e in g.edges
                       if e.src_name == br[0].src_name and e.src_offset is not None
                       and e.source.startswith(fn_file)],
                      key=lambda e: e.src_offset)
    post = [e for e in c2_uses if "post-response" in e.dst_name]
    pre = [e for e in c2_uses if e not in post]

    def c2_text(e: Edge, key: Optional[str] = None) -> str:
        d, k = e.dst_name, e.kind
        if k == "uses_pool_string":
            if "api/request" in d:
                return T("the endpoint the request is built from; its second coordinate is the raw "
                         "byte run at libapp.so file 0x43fe5 (F5) and F8 confirms pool-exact",
                         "endpoint ที่ใช้ประกอบคำขอ — พิกัดที่สองของมันคือ byte run ที่ libapp.so "
                         "file offset 0x43fe5 (F5) และ F8 ยืนยันว่า pool-exact")
            if "action=" in d:
                return T("the action appended to the endpoint: upload_profile_image. Its question "
                         "mark is stored regex-escaped, so Dart holds this as a pattern, exactly "
                         "like the 2 store links in F8 §2",
                         "action ที่ต่อท้าย endpoint: upload_profile_image — เครื่องหมาย question "
                         "mark ถูกเก็บแบบ escape ไว้ จึงเป็น *แพตเทิร์น* ฝั่ง Dart เหมือน store link "
                         "2 ตัวใน F8 §2")
            if "multipart" in d:
                return T("the POST body template: 12 consecutive slots hold the boundary, "
                         "multipart/form-data, Content-Type, the form-data part for the image, "
                         "image/jpeg, the -- terminators, both upload error strings and the method "
                         "name post (itemised in the CSV note column)",
                         "แม่แบบ body ของ POST: 12 slot ติดกันเก็บ boundary, multipart/form-data, "
                         "Content-Type, part ของรูป, image/jpeg, ตัวปิด --, ข้อความผิดพลาดของการ "
                         "อัปโหลด 2 เส้น และชื่อ method post (รายการเต็มอยู่ในคอลัมน์ note ของ CSV)")
            return T("what the same routine loads once the upload answer is in: a dart:io file "
                     "closure (0x30ff78), Cannot delete file, TypeArguments <String, Uint8List> and "
                     "a .jpg path with a cache-buster suffix - the uploaded image is fetched back as "
                     "bytes and kept in a Map<String, Uint8List>",
                     "สิ่งที่ routine เดิมโหลดเมื่อได้คำตอบของการอัปโหลด: closure ฝั่ง dart:io "
                     "(0x30ff78), Cannot delete file, TypeArguments <String, Uint8List> และ path "
                     ".jpg ที่ต่อท้ายด้วย cache-buster — รูปที่อัปโหลดถูกดึงกลับเป็นไบต์แล้วเก็บใน "
                     "Map<String, Uint8List>")
        if k == "loads_pool_slot":
            if d == '"success"':
                return T("loads the key success from the object pool - the word the response is "
                         "checked against",
                         "โหลดคีย์ success จาก object pool — คำที่ใช้เช็ค response")
            if d == '"data"':
                return T("loads the key data - the value the endpoint returns",
                         "โหลดคีย์ data — ค่าที่ endpoint ส่งกลับมา")
            if d.startswith("UnlinkedCall"):
                return T(f"loads the UnlinkedCall slot for the dynamic lookup of the key "
                         f"{key or '?'} - a "
                         f"call site that has never been linked, so its first word is the miss "
                         f"handler",
                         f"โหลด slot ชนิด UnlinkedCall สำหรับการอ่านคีย์ {key} แบบ dynamic — "
                         f"call site นี้ยังไม่เคยถูก link word แรกของมันจึงเป็น miss handler")
            if d == "Sentinel":
                return T("loads the Sentinel that marks a late field as still uninitialised",
                         "โหลดค่า Sentinel ที่แปลว่า field แบบ late ยังไม่ถูก initialise")
            if d.startswith("Field <"):
                return T("the destination of the response: the late static field Yoa.hne of library "
                         "xkg (type Loa, class id 347, size 0x28, field-table offset 0xe78)",
                         "ปลายทางของ response: field แบบ late static ชื่อ Yoa.hne ของ library xkg "
                         "(ชนิด Loa, class id 347, size 0x28, offset ใน field table 0xe78)")
            if d.startswith("Type:"):
                return T("loads Type: int - the type the decoded value must satisfy",
                         "โหลด Type: int — ประเภทที่ค่าซึ่ง decode ได้ต้องตรง")
            if d == "Null":
                return T("the Null argument handed to the type check",
                         "อาร์กิวเมนต์ Null ที่ส่งให้การเช็คประเภท")
            return T("loads a constant from the object pool", "โหลดค่าคงที่จาก object pool")
        if k == "calls_unlinked_slot":
            return T(f"the first execution of the dynamic lookup of {key or '?'} goes through "
                     f"SwitchableCallMissStub (0x173c2c), which resolves the selector and patches "
                     f"this slot",
                     f"การอ่านค่า {key or '?'} แบบ dynamic ครั้งแรกวิ่งผ่าน SwitchableCallMissStub "
                     f"(0x173c2c) ซึ่ง resolve selector แล้ว patch slot นี้")
        if k == "dart_gdt_dispatch":
            return T("the equality dispatch: the index is the class id loaded by LoadClassIdInstr "
                     "(or the Smi class id 59 staged at 0x533168), so GDT[cid + 0] picks the "
                     "implementation of == that decides the check",
                     "dispatch ของการเทียบเท่า: index คือ class id ที่โหลดด้วย LoadClassIdInstr "
                     "(หรือ cid 59 ของ Smi ที่ stage ไว้ที่ 0x533168) GDT[cid + 0] จึงเลือก "
                     "implementation ของ == ที่ใช้ตัดสิน")
        if k == "branch_on_check":
            return T("THE DECISION: tbnz w0, #4 tests bit 4 of the comparison result, and the "
                     "listing itself stages true as NULL+0x20 at 0x533178 - so false is NULL+0x10 "
                     "and a set bit 4 means the answer was false. Check FAILED: jump to the merge "
                     "point 0x53323c. Check PASSED: fall through to 0x533194 and store the value",
                     "จุดตัดสิน: tbnz w0, #4 ทดสอบ bit 4 ของผลเทียบ และตัว listing เอง stage ค่า true "
                     "ไว้เป็น NULL+0x20 ที่ 0x533178 — false จึงคือ NULL+0x10 และการที่ bit 4 ถูกเซ็ต "
                     "แปลว่าผลเป็น false: เช็ค *ไม่ผ่าน* -> กระโดดไปจุดรวม 0x53323c; เช็ค *ผ่าน* -> "
                     "ไหลต่อลงไปที่ 0x533194 แล้วเก็บค่า")
        if k == "stores_response_field":
            return T("WHAT IS STORED: StoreField writes the decoded int into the object that "
                     "Yoa.hne->field_1f points at - the durable result of a passed check",
                     "ค่าที่ถูกเก็บ: StoreField เขียน int ที่ decode ได้ลงอ็อบเจกต์ที่ "
                     "Yoa.hne->field_1f ชี้อยู่ — ผลลัพธ์ถาวรของการเช็คที่ผ่าน")
        if k == "dart_instantiates_closure":
            return T("allocates the continuation closure 0x310338 of the same _Bpa family",
                     "สร้าง closure 0x310338 ของครอบครัว _Bpa เดียวกันเพื่อใช้ต่อ")
        if k == "dart_call":
            if "InitLateStaticFieldStub" in d:
                return T("WHAT GETS LOADED: Yoa.hne is late, so while it still holds the Sentinel "
                         "this stub initialises the singleton and writes it to the field table "
                         "(THR+0x68 then +0x1cf0)",
                         "สิ่งที่ถูกโหลดเข้ามา: Yoa.hne เป็น late — ตราบใดที่ยังถือ Sentinel อยู่ "
                         "stub นี้จะ initialise singleton แล้วเขียนลง field table (THR+0x68 แล้ว "
                         "+0x1cf0)")
            if "IsType_int_Stub" in d:
                return T("checks and casts the decoded value to int",
                         "เช็คและ cast ค่าที่ decode ได้ให้เป็น int")
            if "AllocateClosureStub" in d:
                return T("allocates the closure object", "allocate ตัว closure object")
            if "StackOverflow" in d:
                return T("the async frame's stack guard", "stack guard ของ async frame")
            if "NullCastError" in d:
                return T("the failure path: if Yoa.hne->field_1f is null a NullCastError is thrown",
                         "เส้นทางล้มเหลว: ถ้า Yoa.hne->field_1f เป็น null จะโยน NullCastError")
            if d.startswith("0x3102f4"):
                return T("the response body is handed to an unnamed helper - no symbol in the "
                         "committed dump covers 0x3102f4 (a decoder is the shape-consistent "
                         "reading, but it stays unresolved)",
                         "body ของ response ถูกส่งให้ helper ที่ไม่มีชื่อ — ไม่มีสัญลักษณ์ใดในชุด "
                         "หลักฐานครอบคลุม 0x3102f4 (รูปทรงสอดคล้องกับตัว decode แต่ยังนับเป็น "
                         "unresolved)")
            if d.startswith("0x1a5b64"):
                return T("hands (receiver, value, closure) to the unnamed helper 0x1a5b64: 3 call "
                         "sites in the dump (0x533278, 0x535830, 0x53e950), two of them right after "
                         "AwaitStub and all three passing a freshly allocated closure - a "
                         "continuation-shaped helper",
                         "ส่ง (receiver, ค่า, closure) ให้ helper 0x1a5b64 ที่ไม่มีชื่อ: มี 3 จุดเรียก "
                         "ในชุดหลักฐาน (0x533278, 0x535830, 0x53e950) สองจุดอยู่หลัง AwaitStub "
                         "ทันทีและทั้งสามส่ง closure ที่เพิ่ง allocate — รูปทรงแบบ continuation")
            return T("a call inside the snapshot", "การเรียกภายใน snapshot")
        if k == "dart_tail_call":
            if e.dst_offset and e.dst_offset < (e.src_offset or 0):
                return T("re-enters the body after the stack is grown",
                         "กลับเข้า body หลังขยาย stack แล้ว")
            return T("the merge: whether the check held or not, both paths continue through this "
                     "same tail",
                     "จุดรวม: ไม่ว่าจะเช็คผ่านหรือไม่ ทั้งสองเส้นทางเดินต่อด้วย tail เดียวกันนี้")
        return T("see the CSV row", "ดูที่แถวใน CSV")

    c2_hops = [hop(e, c2_text(e)) for e in pre]
    key = None
    for e in walk:
        if e.kind == "loads_pool_slot" and e.dst_name.startswith('"'):
            key = e.dst_name.strip('"')
        c2_hops.append(hop(e, c2_text(e, key)))
    c2_hops += [hop(e, c2_text(e)) for e in post]
    if c2_hops:
        chains.append(dict(id="CH-12", layer_path="L5", layout="steps",
                           title="the C2 endpoint /api/request/: what runs once its response check "
                                 "passes",
                           goal="Answer from committed evidence what the hard-coded endpoint is used "
                                "for and what happens after its response check succeeds. The request "
                                "side is attributed by object-pool adjacency because blutter lists "
                                "that routine with size -0x1; the response side is walked "
                                "instruction by instruction, from the decode call to the store.",
                           hops=c2_hops,
                           note="The second endpoint (https://www.snakeengine.com/topup/, "
                                "pp+0x17790 = libapp.so file 0x3d50e) is not walked: no committed "
                                "listing references its slot and its neighbourhood is a different "
                                "allocation run."))

    for ch in chains:
        title_th, goal_th, note_th = CHAIN_TH.get(
            ch["id"], (ch["title"], ch["goal"], ch.get("note")))
        ch["title_th"], ch["goal_th"] = title_th, goal_th
        # a None here means "keep the note that was generated from live counts"
        ch["note_th"] = note_th if note_th is not None else ch.get("note")
        for i, h in enumerate(ch["hops"], 1):
            h["n"] = i
            for e in g.edges:
                if e.id == h["edge"]:
                    have = [x for x in (e.chain or "").split(";") if x]
                    if ch["id"] not in have:
                        have.append(ch["id"])
                    e.chain = ";".join(have)
                    e.hop = e.hop or i
    return chains


def binding_note(dst_name: str) -> str:
    """What the invoked class means: how many natives it has and where they are bound."""
    if "flagger" in dst_name:
        return T("2 declarations (na, nb), both bound by the RegisterNatives site at 0xb40a8; "
                 "0 Java callers invoke this class",
                 "มี 2 declaration (na, nb) ทั้งคู่ถูกผูกโดย RegisterNatives site ที่ 0xb40a8; "
                 "ไม่มี caller ฝั่ง Java เรียกคลาสนี้เลย")
    return T("11 declarations: 10 bound by the site at 0xf3a08 (FindClass name length 23) and 1 by "
             "the site at 0xb0140 (decode-loop bound 8 -> pjowqpxe)",
             "มี 11 declaration: 10 ตัวถูกผูกโดย site ที่ 0xf3a08 (ความยาวชื่อจาก FindClass = 23) "
             "และ 1 ตัวโดย site ที่ 0xb0140 (ขอบเขต decode loop = 8 -> pjowqpxe)")


def site_meta_count(le: dict, site: int) -> int:
    for s in le["register_natives_sites"]:
        if int(s["va"], 16) == site:
            return s["count"]
    return -1


# --------------------------------------------------------------------------
# writers
# --------------------------------------------------------------------------

CSV_COLUMNS = ["edge_id", "chain", "hop", "layer", "kind", "src_module", "src_offset",
               "src_name", "src_insn", "dst_module", "dst_offset", "dst_name",
               "resolved_by", "confidence", "evidence", "source", "note"]


def edges_to_rows(edges: Sequence[Edge]) -> List[dict]:
    rows = []
    for e in sorted(edges, key=lambda e: e.sort_key()):
        rows.append({
            "edge_id": e.id, "chain": e.chain, "hop": e.hop, "layer": e.layer, "kind": e.kind,
            "src_module": e.src_module, "src_offset": hx(e.src_offset), "src_name": e.src_name,
            "src_insn": e.src_insn, "dst_module": e.dst_module, "dst_offset": hx(e.dst_offset),
            "dst_name": e.dst_name, "resolved_by": e.resolved_by, "confidence": e.confidence,
            "evidence": e.evidence, "source": e.source, "note": e.note,
        })
    return rows


def write_csv(path: str, rows: Sequence[dict]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=CSV_COLUMNS, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow(r)


def write_json(path: str, data: dict) -> None:
    payload = {
        "meta": data["meta"],
        "chains": data["chains"],
        "nodes": [n.__dict__ for n in sorted(data["nodes"], key=lambda n: (n.layer, n.module,
                                                                          n.offset or -1, n.id))],
        "edges": edges_to_rows(data["edges"]),
        "checks": data["checks"],
        "dart_hot_targets": [{"offset": hx(t), "fan_in": c,
                              "name": next((e.dst_name for e in data["edges"]
                                            if e.dst_offset == t and e.dst_module == MOD_APP), "")}
                             for t, c in sorted(data["dart_fan_in"].items(),
                                                key=lambda kv: (-kv[1], kv[0]))[:25]],
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False, sort_keys=False, default=str)
        fh.write("\n")


# --------------------------------------------------------------------------
# markdown renderer
# --------------------------------------------------------------------------

LANG_TH = False      # set from --lang; True renders the prose in Thai


def T(en: str, th: str) -> str:
    """Prose is bilingual: Thai narrative, English tables/offsets/symbols."""
    return th if LANG_TH else en


def esc(s: str) -> str:
    return str(s).replace("|", "\\|").replace("\n", " ")


def code(s: str, limit: int = 0) -> str:
    """A code span that cannot be broken by backticks inside the value."""
    t = str(s).replace("`", "'").replace("|", "/").replace("\n", " ")
    if limit and len(t) > limit:
        t = t[:limit - 1].rstrip() + "…"
    return "`" + t + "`"


def clip(text: str, limit: int) -> str:
    """Truncate with an ellipsis instead of cutting a word in half."""
    t = str(text)
    return t if len(t) <= limit else t[:limit - 1].rstrip() + "…"


def md_loc(module: str, offset) -> str:
    """`module+offset` for anything addressable; bare `module` for the runtime/kernel."""
    if module in (MOD_RUNTIME, MOD_KERNEL, MOD_DARTVM):
        return f"`{module}`"
    return f"`{module}`{('+' + hx(offset)) if offset not in (None, '') else ''}"


def render_md(data: dict) -> str:
    meta, chains, checks = data["meta"], data["chains"], data["checks"]
    edges: List[Edge] = data["edges"]
    rows = {e.id: e for e in edges}
    c = meta["counts"]
    out: List[str] = []
    A = out.append

    A(T("# SNAKE.apk — call linkage", "# SNAKE.apk — call linkage (สายการเรียก)"))
    A("")
    if LANG_TH:
        A("`LINKAGE.md` ตอบว่า *ข้อเท็จจริงใน fragment ไหนพูดถึงเรื่องเดียวกัน* เอกสารนี้ตอบคำถามถัดไป:")
        A("**instruction ที่ offset ไหน ในโมดูลไหน โอน control ไปยังอะไร** — เรียงเป็นสายการเรียกตั้งแต่")
        A("`invoke` ฝั่ง Dalvik ที่โหลด `libengine.so` ลงไปถึง `blr` ที่หลุดออกจาก image แบบ static")
        A("และข้ามไปยัง call edge ของ Dart AOT snapshot")
        A("")
        A("> **ดูเพิ่ม:** `BOOT_LINKAGE.md` (แกน `SP-01`) เอา chain ในเอกสารนี้ (CH-01..CH-12) "
          "ไปประกอบกับ tier เฟรมเวิร์ก (fragment F9) และ tier ใหม่ฝั่ง Dart (T4) "
          "เป็นแกนเดียวตั้งแต่ process boot จนถึง `https://rest.snakeseller.com/api/request/` "
          "สร้างด้วย `tools/build_boot_linkage.py`")
        A("")
        A("ทุก hop ผูกกับ *offset ของ instruction ฝั่งผู้เรียก* (`module+offset`) และอ้างบรรทัดของ fragment")
        A("ที่อ่านมาเสมอ ถ้าหาชื่อ callee จากหลักฐานที่ commit ไว้ไม่ได้ แถวนั้นจะยังอยู่ ทำเครื่องหมาย")
        A("`(unresolved)` และระบุวิธีปิดไว้ด้วย — เป้าหมายที่หายไปคือข้อมูล ไม่ใช่เหตุผลให้ทิ้งแถว")
        A("")
        A("เอกสารเขียนสองภาษา: คำอธิบายเป็นไทย ส่วนตาราง offset ชื่อ symbol และ mnemonic คงเป็นอังกฤษ")
        A("ตามต้นฉบับ เพื่อให้ grep และ CI ใช้ได้เหมือนเดิม")
    else:
        A("`LINKAGE.md` answers *which facts in which fragment describe the same thing*. This document")
        A("answers the next question: **which instruction, at which offset, transfers control to what** —")
        A("an ordered call linkage from the Dalvik `invoke` that loads `libengine.so` down to the `blr`")
        A("that leaves the static image entirely, and across to the Dart AOT snapshot's own call edges.")
        A("")
        A("> **See also:** `BOOT_LINKAGE.md` (spine `SP-01`) composes the chains below (CH-01..CH-12)")
        A("> with a framework tier (fragment F9) and a new Dart tier (T4) into one ordered spine from")
        A("> process boot to `https://rest.snakeseller.com/api/request/`; built by")
        A("> `tools/build_boot_linkage.py`.")
        A("")
        A("Every hop below is anchored at a *caller instruction offset* (`module+offset`) and cites the")
        A("fragment line it was read from. Hops whose callee cannot be resolved from the committed")
        A("evidence are kept, marked `(unresolved)`, and paired with the exact step that would resolve")
        A("them — a missing target is data, not a reason to drop the row.")
    A("")
    A(T(f"_Built by `{meta['generated_by']}` from `{meta['bundle']}` "
        f"(fingerprint `{meta['bundle_fingerprint']}`, {meta['bundle_files']} files). "
        f"Re-running the script on the same bundle reproduces this file, `call_linkage.csv` and "
        f"`call_linkage.json` byte for byte._",
        f"_สร้างโดย `{meta['generated_by']}` จาก `{meta['bundle']}` "
        f"(ลายนิ้วมือ `{meta['bundle_fingerprint']}`, {meta['bundle_files']} ไฟล์) — "
        f"รันสคริปต์ซ้ำบนชุดหลักฐานเดิมจะได้ไฟล์นี้ รวมถึง `call_linkage.csv` และ `call_linkage.json` "
        f"เหมือนเดิมทุกไบต์ (path อ้างอิงจาก root ของ repo)_"))
    A("")
    A(T("## Contents", "## สารบัญ (Contents)"))
    A("")
    if LANG_TH:
        A("1. วิธีอ่านหนึ่ง hop (How to read a hop)")
        A("2. สรุปภาพรวม (Summary)")
        A("3. ตารางสัญลักษณ์ที่ใช้แกะ indirect hop (Symbol tables)")
        A(f"4. สายการเรียก (Chains) — {chains[0]['id']} … {chains[-1]['id']}")
        A("5. edge ทั้งหมด เรียงตาม layer (Every edge, by layer)")
        A("6. hop ที่ยังปิดไม่ได้ และวิธีปิด (Unresolved hops)")
        A("7. ช่องว่างที่มุมมองนี้เผยให้เห็น (Gaps)")
        A("8. การตรวจสอบ (Verification)")
    else:
        A("1. [How to read a hop](#how-to-read-a-hop)")
        A("2. [Summary](#summary)")
        A("3. [Symbol tables used to resolve indirect hops](#symbol-tables-used-to-resolve-indirect-hops)")
        A("4. [Chains](#chains)")
        A("5. [Every edge, by layer](#every-edge-by-layer)")
        A("6. [Unresolved hops and how to close them](#unresolved-hops-and-how-to-close-them)")
        A("7. [Gaps this view exposes](#gaps-this-view-exposes)")
        A("8. [Verification](#verification)")
    A("")

    # ---------------- 1 ----------------
    A(T("## How to read a hop", "## วิธีอ่านหนึ่ง hop (How to read a hop)"))
    A("")
    A(T("A row reads `caller instruction → callee`, never the other way round:",
        "แต่ละแถวอ่านว่า `caller instruction → callee` เสมอ ไม่อ่านย้อนกลับ:"))
    A("")
    A("| column | meaning |")
    A("|---|---|")
    A("| `caller` | `module+offset` of the **instruction** that transfers control |")
    A("| `instruction` | the decoded text of that instruction (AArch64, dex invoke, or Dart `bl`) |")
    A("| `callee` | `module+offset` when the target is a fixed offset, `module#name` when it is a symbol, `libapp.so!pp+offset` when it is an object-pool slot, `android-runtime`/`dart-vm` when the target only exists at run time |")
    A("| `resolved by` | *how* the callee name was obtained — export table, JNIEnv slot index, syscall number, Ghidra's callgraph, blutter's inline stub comment, blutter's own IDA name table |")
    A("| `conf.` | `proven` (byte/offset level) · `strong` (two sources agree) · `probable` (shape-based) · `candidate` (hypothesis) |")
    A("")
    A("Edge kinds:")
    A("")
    A("| kind | meaning |")
    A("|---|---|")
    for k, v in KINDS.items():
        n = c["by_kind"].get(k, 0)
        if n:
            A(f"| `{k}` | {v} ({n}) |")
    A("")
    if LANG_TH:
        A("ข้อตกลงสองข้อที่ต้องพูดให้ชัด:")
        A("")
        A("- **indirect call ถูกแกะด้วย table ของมัน ไม่ใช่เดา** — `blr x8` ที่ตามหลัง")
        A("  `ldr x8, [x8, #0x6b8]` โดย base มาจาก `ldr x8, [env]` คือ `JNIEnv->RegisterNatives`")
        A("  (slot 215) ชื่อมาจากลำดับ JNI function table และ offset ตรงกับ F4/F4b/F4d ส่วน `blr x8`")
        A("  ที่มีแค่ `ldr x8, [x25]` นำหน้า คือ virtual call ผ่าน word แรกของอ็อบเจกต์ และถูกรายงานแบบนั้น")
        A(f"- **offset ทั้งหมดเป็น file virtual address** — artifact ของ Ghidra ใช้ image base `+"
          f"{meta['ghidra_image_base_delta']}`; ชื่อทุกตัวที่มาจาก Ghidra ถูก rebase ด้วย delta ที่วัดได้")
        A("  ก่อนใช้งานเสมอ")
    else:
        A("Two conventions worth stating explicitly:")
        A("")
        A("- **Indirect calls are resolved by their table, not guessed.** A `blr x8` preceded by")
        A("  `ldr x8, [x8, #0x6b8]` whose base came from `ldr x8, [env]` is `JNIEnv->RegisterNatives`")
        A("  (slot 215) — the name comes from the JNI function-table order, and the offsets agree with")
        A("  F4/F4b/F4d. A `blr x8` preceded only by `ldr x8, [x25]` is a virtual call through the")
        A("  object's first word, and is reported as such.")
        A("- **Offsets are file virtual addresses.** Ghidra's artifact uses an image base of")
        A(f"  `+{meta['ghidra_image_base_delta']}`; every Ghidra-derived name below is rebased by that")
        A("  measured delta before it is used.")
    A("")

    # ---------------- 2 ----------------
    A(T("## Summary", "## สรุปภาพรวม (Summary)"))
    A("")
    A(T(f"- **{c['edges']} hops** across **{len(chains)} chains**, {len(data['nodes'])} distinct nodes",
        f"- **{c['edges']} hop** กระจายอยู่ใน **{len(chains)} สายการเรียก**, "
        f"ครอบคลุม {len(data['nodes'])} node ที่ไม่ซ้ำกัน"))
    A(T("- confidence: " + ", ".join(f"{k}={v}" for k, v in c["by_confidence"].items()),
        "- ระดับความมั่นใจ: " + ", ".join(f"{k}={v}" for k, v in c["by_confidence"].items())
        + "  (`proven` = ระดับไบต์/offset · `strong` = สองแหล่งตรงกัน · `probable` = จากรูปทรง · "
          "`candidate` = สมมติฐาน)"))
    A("")
    A("| layer | what it covers | hops |")
    A("|---|---|---|")
    for lay in meta["layers"]:
        A(f"| `{lay['id']}` {lay['name']} | {lay['description']} | "
          f"{c['by_layer'].get(lay['id'], 0)} |")
    A("")
    A("| chain | title | layers | hops |")
    A("|---|---|---|---|")
    for ch in sorted(chains, key=lambda ch: ch["id"]):
        title = ch.get("title_th") if LANG_TH else ch["title"]
        A(f"| `{ch['id']}` | {esc(title or ch['title'])} | {ch['layer_path']} | "
          f"{len(ch['hops'])} |")
    A("")
    d = c["dart"]
    A(T(f"Dart detail: {d['branch_edges']} decoded branch edges in {d['files_with_disasm']} of "
        f"{d['files']} asm files; {d['inline_named']} carry blutter's inline stub name, "
        f"{d['resolved_by_table']} resolve through `addNames.py`, {d['unresolved']} stay address-only "
        f"({d['distinct_targets']} distinct targets).",
        f"รายละเอียดฝั่ง Dart: branch edge ที่ถอดรหัสได้ {d['branch_edges']} เส้น ใน "
        f"{d['files_with_disasm']} จาก {d['files']} ไฟล์ asm; {d['inline_named']} เส้นมีชื่อ stub ที่ "
        f"blutter แนบมาในบรรทัด, {d['resolved_by_table']} เส้นแกะได้ผ่าน `addNames.py`, "
        f"{d['unresolved']} เส้นเหลือแค่ address (เป้าหมายไม่ซ้ำกัน {d['distinct_targets']} ตัว)"))
    A(T(f"Pool and indirect detail: {d['pool_slot_loads']} listing lines load an object-pool slot "
        f"(plus {d['closure_slots']} that allocate a closure), and all {d['blr_lines']} `blr` "
        f"instructions are classified - {d['blr_unlinked']} through an UnlinkedCall slot (each "
        f"resolves to the miss stub named in `pp.txt`), {d['blr_gdt']} through the dispatch table, "
        f"{d['blr_closure']} through a closure object, {d['blr_other']} other.",
        f"รายละเอียด object pool และ indirect call: มี {d['pool_slot_loads']} บรรทัดที่โหลด slot "
        f"จาก object pool (บวกอีก {d['closure_slots']} บรรทัดที่ allocate closure) และ `blr` ทั้ง "
        f"{d['blr_lines']} ตัวถูกจำแนกครบ — {d['blr_unlinked']} ตัวผ่าน slot ชนิด UnlinkedCall "
        f"(แต่ละตัว resolve ไปยัง miss stub ที่มีชื่อใน `pp.txt`), {d['blr_gdt']} ตัวผ่าน dispatch "
        f"table, {d['blr_closure']} ตัวผ่าน closure object และอื่น ๆ {d['blr_other']} ตัว"))
    A("")

    # ---------------- 3 ----------------
    A(T("## Symbol tables used to resolve indirect hops", "## ตารางสัญลักษณ์ที่ใช้แกะ indirect hop (Symbol tables)"))
    A("")
    A(T("### JNIEnv function table (the slots this sample touches)", "### JNIEnv function table — slot ที่ตัวอย่างนี้แตะ"))
    A("")
    A("| offset | slot | function | where it is called |")
    A("|---|---|---|---|")
    slot_sites = {}
    for e in edges:
        if e.kind in ("calls_jni_slot", "registers_natives", "finds_class") and e.dst_module == MOD_RUNTIME:
            slot_sites.setdefault(e.dst_offset, []).append(hx(e.src_offset))
    for off, info in sorted(meta["jnienv_slots"].items(), key=lambda kv: int(kv[0], 16)):
        sites = ", ".join(f"`{s}`" for s in sorted(slot_sites.get(int(off, 16), [])))
        A(f"| `{off}` | {info['index']} | `{info['name']}` | {sites or '—'} |")
    A("")
    A(T("### Syscalls (arm64, `asm-generic/unistd.h`)", "### Syscalls (arm64, ตาม `asm-generic/unistd.h`)"))
    A("")
    A("| nr | name | sites |")
    A("|---|---|---|")
    sys_sites = {}
    for e in edges:
        if e.kind in ("calls_syscall", "calls_syscall_stub") and e.dst_offset:
            sys_sites.setdefault(e.dst_offset, []).append(hx(e.src_offset))
    for nr, sites in sorted(sys_sites.items()):
        A(f"| {nr} | `{SYSCALLS.get(nr, '?')}` | " + ", ".join(f"`{s}`" for s in sorted(sites)) + " |")
    A("")
    A(T("### PLT stubs named by Ghidra's callgraph (rebased)", "### PLT stub ที่ Ghidra's callgraph ระบุชื่อ (rebase แล้ว)"))
    A("")
    A("| offset | name | callers in this document |")
    A("|---|---|---|")
    for off, name in sorted(meta["plt_names"].items(), key=lambda kv: int(kv[0], 16)):
        callers = sorted({hx(e.src_offset) for e in edges
                          if e.dst_module == MOD_ENGINE and hx(e.dst_offset) == off})
        A(f"| `{off}` | `{name}` | " + (", ".join(f"`{c2}`" for c2 in callers) or "—") + " |")
    A("")
    A(T("Stubs that the decoded windows call but Ghidra's callgraph does not name "
        "(`0x81f140`, `0x81f250`, `0x7775d8`, `0x777fb0`, `0x7778a8`) are kept as offsets and "
        "described by their **call shape** — which argument registers are staged and how the result "
        "is used. Naming them needs `.rela.plt`, which this bundle does not carry.",
        "stub ที่ window ซึ่งถอดรหัสไว้เรียกแต่ Ghidra's callgraph ไม่ระบุชื่อ "
        "(`0x81f140`, `0x81f250`, `0x7775d8`, `0x777fb0`, `0x7778a8`) ถูกเก็บไว้เป็น offset และอธิบายด้วย "
        "**รูปทรงของ call** — argument register ใดถูกเตรียมไว้ และผลลัพธ์ถูกใช้อย่างไร "
        "การจะใส่ชื่อต้องอ่าน `.rela.plt` ซึ่งชุดหลักฐานนี้ไม่ได้แนบมา"))
    A("")

    # ---------------- 4 ----------------
    A(T("## Chains", "## สายการเรียก (Chains)"))
    A("")
    A(T("Cross-layer backbone (each numbered node is a chain; the arrows are the layer transitions):",
        "โครงหลักข้าม layer (ป้ายบนลูกศรคือหมายเลข chain; ลูกศรคือการย้าย layer):"))
    A("")
    A("```mermaid")
    A("flowchart LR")
    A("  DEX[classes.dex<br/>invoke offsets] -->|CH-01 loadLibrary 'engine'| LOAD[linker / .init_array]")
    A("  LOAD -->|CH-02 44 constructors| INIT[libengine.so .text]")
    A("  LOAD -->|CH-01| ONLOAD[JNI_OnLoad 0xf3fa0]")
    A("  ONLOAD -->|CH-03 mmap RWX + B opcode| GEN[generated pages]")
    A("  ONLOAD -->|CH-03 blr| GEN")
    A("  INIT -->|CH-04/05/07| REG[RegisterNatives x3]")
    A("  REG -->|CH-05 fnPtr 0x81eeb0| MYT[.mytext handler]")
    A("  MYT -->|CH-06 FromReflectedMethod + bl 0xb01c4| INIT")
    A("  REG -->|CH-04/05/07 attribution| NAT[com/snake/helper/*]")
    A("  DEX -->|CH-08 20 invoke sites| NAT")
    A("  NAT -.->|CH-06 ART calls fnPtr| MYT")
    A("  DEX -->|CH-09 loadLibrary 'flutter'| FL[libflutter.so JNI_OnLoad]")
    A("  DART[libapp.so Dart AOT] -->|CH-10 MethodCall handlers + 11 engine symbols| FL")
    A("  DART -->|CH-11 decoded bl/b edges| DSTUB[Dart runtime stubs<br/>AwaitStub, InitAsyncStub, ...]")
    A("```")
    A("")
    for ch in sorted(chains, key=lambda ch: ch["id"]):
        A(f"### `{ch['id']}` — {ch['title_th'] if LANG_TH else ch['title']}")
        A("")
        A(f"*{ch['layer_path']}* · {len(ch['hops'])} hops"
          + (f" · {T('', 'hop')}" if False else ""))
        A("")
        A(ch["goal_th"] if LANG_TH else ch["goal"])
        A("")
        if ch.get("layout") == "index":
            # long, homogeneous chains: one compact row per hop, the shared
            # instruction/resolution stated once above the table
            shared_insn = {rows[h["edge"]].src_insn for h in ch["hops"] if h["edge"] in rows}
            shared_res = {rows[h["edge"]].resolved_by for h in ch["hops"] if h["edge"] in rows}
            if len(shared_insn) == 1:
                insn_txt = esc(shared_insn.pop())
                A(T(f"Every hop is the same instruction shape: `{insn_txt}`.",
                    f"ทุก hop เป็น instruction รูปทรงเดียวกัน: `{insn_txt}`"))
                A("")
            if len(shared_res) == 1:
                res_txt = esc(shared_res.pop())
                A(T(f"Resolved the same way for all {len(ch['hops'])} hops: {res_txt}.",
                    f"แกะชื่อด้วยวิธีเดียวกันทั้ง {len(ch['hops'])} hop: {res_txt}"))
                A("")
            texts = {h["text"] for h in ch["hops"]}
            uniform = len(texts) == 1
            if uniform:
                shared_txt = esc(next(iter(texts)))
                A(T(f"True of every hop below: {shared_txt}",
                    f"เป็นจริงเหมือนกันทุก hop ข้างล่าง: {shared_txt}"))
                A("")
                A("| # | caller (module+offset) | callee (module+offset) | conf. |")
                A("|---|---|---|---|")
            else:
                A("| # | caller (module+offset) | callee (module+offset) | what the hop says | "
                  "conf. |")
                A("|---|---|---|---|---|")
            for h in ch["hops"]:
                e = rows.get(h["edge"])
                if e is None:
                    A(f"| {h['n']} | — | — | *{esc(h['text'])}* **MISSING** | — |")
                    continue
                tail = f" | {e.confidence} |"
                body = (f"| {h['n']} | {md_loc(e.src_module, e.src_offset)} "
                        f"{code(esc(e.src_name))} | {md_loc(e.dst_module, e.dst_offset)} "
                        f"{code(esc(e.dst_name), 60)}")
                A(body + (tail if uniform else f" | {esc(h['text'])}" + tail))
        elif ch.get("layout") == "steps":
            present = [rows[h["edge"]] for h in ch["hops"] if rows.get(h["edge"])]
            callers: List[str] = []
            for e in present:
                if e.src_name not in callers:
                    callers.append(e.src_name)
            if len(callers) > 1:
                A(T(f"{len(callers)} callers take part; the table below gives only their offsets:",
                    f"มีผู้เรียก {len(callers)} ตัวในสายนี้ ตารางข้างล่างจึงแสดงแค่ offset:"))
                A("")
                for nm in callers:
                    mine = [e for e in present if e.src_name == nm]
                    offs = sorted({e.src_offset for e in mine if e.src_offset is not None})
                    span = hx(offs[0]) if len(offs) == 1 else f"{hx(offs[0])}..{hx(offs[-1])}"
                    A(f"- {code(esc(nm), 110)} — "
                      + T(f"{len(mine)} hops at offset {span}",
                          f"{len(mine)} hop ที่ offset {span}"))
                A("")
            A(T("| # | caller (module+offset) | instruction | callee | what happens here | conf. |",
                "| # | caller (module+offset) | instruction | callee | ขั้นนี้ทำอะไร | conf. |"))
            A("|---|---|---|---|---|---|")
            for h in ch["hops"]:
                e = rows.get(h["edge"])
                if e is None:
                    A(f"| {h['n']} | — | — | — | *{esc(h['text'])}* **MISSING** | — |")
                    continue
                A(f"| {h['n']} | {md_loc(e.src_module, e.src_offset)} | "
                  f"{code(e.src_insn, 58)} | "
                  f"{md_loc(e.dst_module, e.dst_offset)} {code(esc(e.dst_name), 58)} | "
                  f"{esc(h['text'])} | {e.confidence} |")
            A("")
            A(T("_`resolved by`, the caller name and the full evidence line of every step are "
                "columns of `call_linkage.csv` (its `chain` column contains CH-12)._",
                "_คอลัมน์ `resolved by`, ชื่อผู้เรียก และบรรทัดหลักฐานเต็มของทุกขั้นอยู่ใน "
                "`call_linkage.csv` (คอลัมน์ `chain` มี CH-12 อยู่)_"))
        else:
            A("| # | caller | instruction | callee | resolved by | conf. |")
            A("|---|---|---|---|---|---|")
            for h in ch["hops"]:
                e = rows.get(h["edge"])
                if e is None:
                    A(f"| {h['n']} | — | — | *{esc(h['text'])}* | **MISSING** | — |")
                    continue
                A(f"| {h['n']} | {md_loc(e.src_module, e.src_offset)} | {code(e.src_insn)} | "
                  f"{md_loc(e.dst_module, e.dst_offset)} {code(e.dst_name)} | "
                  f"{esc(e.resolved_by)} | {e.confidence} |")
        A("")
        note = ch.get("note_th") if LANG_TH else ch.get("note")
        if note:
            A(f"> {note}")
            A("")
        # what the chain does not reach
        hop_ids = {h["edge"] for h in ch["hops"]}
        miss = [rows[h["edge"]] for h in ch["hops"]
                if h["edge"] in rows and "(unresolved)" in rows[h["edge"]].resolved_by]
        if miss:
            A(T(f"**{len(miss)} of these {len(ch['hops'])} hops do not reach a name**: ",
                f"**{len(miss)} จาก {len(ch['hops'])} hop ไปไม่ถึงชื่อ**: ") + "; ".join(
                f"`{hx(e.src_offset)}` → {code(e.dst_name, 60)}" for e in miss) +
                T(" — see the unresolved-hop section below.", " — ดูวิธีปิดในส่วน hop ที่ยังปิดไม่ได้ข้างล่าง"))
            A("")
        extra = [e for e in edges if e.chain == ch["id"] and e.id not in hop_ids]
        if extra and len(extra) <= 20:
            A(T(f"The same window contributes {len(extra)} further edge(s) that are not control "
                f"transfers of this walk ({', '.join(sorted({e.kind for e in extra}))}); they appear "
                f"in the layer tables below and in `call_linkage.csv` under chain `{ch['id']}`.",
                f"window เดียวกันนี้ให้ edge อีก {len(extra)} เส้นที่ไม่ใช่ control transfer ของการเดินนี้ "
                f"({', '.join(sorted({e.kind for e in extra}))}) — ดูได้ในตาราง layer ข้างล่าง และใน "
                f"`call_linkage.csv` โดยกรอง chain `{ch['id']}`"))
            A("")

    # ---------------- 5 ----------------
    A(T("## Every edge, by layer", "## edge ทั้งหมด เรียงตาม layer (Every edge, by layer)"))
    A("")
    A(T("Sorted by module and caller offset — the same order as `call_linkage.csv`, which holds all "
        f"{c['edges']} rows with their evidence strings.",
        f"เรียงตามโมดูลและ offset ของผู้เรียก — ลำดับเดียวกับ `call_linkage.csv` ที่เก็บทั้ง "
        f"{c['edges']} แถวพร้อม evidence string ของแต่ละแถว"))
    A("")
    for lay in meta["layers"]:
        lid, lname, desc = lay["id"], lay["name"], lay["description"]
        sel = sorted([e for e in edges if e.layer == lid], key=lambda e: e.sort_key())
        if not sel:
            continue
        A(f"### `{lid}` {lname} — {desc}")
        A("")
        cap = sel
        collapsed = ""
        if lid == "L5" and len(sel) > 40:
            # one row per distinct callee, ranked by fan-in: the whole Dart call
            # graph in 30 lines instead of 500
            groups: Dict[tuple, List[Edge]] = {}
            for e in sel:
                if e.dst_offset is not None:
                    groups.setdefault((e.dst_module, e.dst_offset), []).append(e)
            ranked = sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0][1]))
            A(T(f"_{len(sel)} Dart hops over {len(groups)} distinct callees. Grouped by callee and "
                f"ranked by fan-in; the top {min(30, len(ranked))} are shown, all {len(sel)} rows are "
                f"in `call_linkage.csv` (filter `layer=L5`)._",
                f"_Dart {len(sel)} hop กระจายไปยัง callee ไม่ซ้ำกัน {len(groups)} ตัว — จัดกลุ่มตาม "
                f"callee แล้วเรียงตาม fan-in แสดง {min(30, len(ranked))} อันดับแรก ส่วนครบทั้ง "
                f"{len(sel)} แถวอยู่ใน `call_linkage.csv` (กรอง `layer=L5`)_"))
            A("")
            A("| callee | name | resolved by | fan-in | callers (first 3) | conf. |")
            A("|---|---|---|---|---|---|")
            for (dmod, tgt), grp in ranked[:30]:
                ex = grp[0]
                callers = ", ".join(f"`{hx(g.src_offset)}`" for g in
                                    sorted(grp, key=lambda g: g.src_offset or 0)[:3])
                A(f"| {md_loc(dmod, tgt)} | {code(esc(ex.dst_name), 60)} | "
                  f"{esc(clip(ex.resolved_by, 70))} | {len(grp)} | {callers}"
                  f"{', …' if len(grp) > 3 else ''} | {ex.confidence} |")
            A("")
            continue
        if lid == "L2" and len(sel) > 46:
            cap = sel[:6] + sel[-3:]
            collapsed = T(f"First 6 and last 3 of {len(sel)}; the full list is in the CSV/JSON.",
                          f"แสดง 6 รายการแรกและ 3 รายการสุดท้ายจาก {len(sel)}; "
                          f"รายการเต็มอยู่ใน CSV/JSON")
        if collapsed:
            A(f"_{collapsed}_")
            A("")
        A("| caller | instruction | callee | kind | chain | conf. |")
        A("|---|---|---|---|---|---|")
        for e in cap:
            A(f"| {md_loc(e.src_module, e.src_offset)} | {code(esc(e.src_insn), 80)} | "
              f"{md_loc(e.dst_module, e.dst_offset)} {code(esc(e.dst_name), 70)} | `{e.kind}` | "
              f"{e.chain or '—'} | {e.confidence} |")
        A("")

    # ---------------- 6 ----------------
    A(T("## Unresolved hops and how to close them", "## hop ที่ยังปิดไม่ได้ และวิธีปิด (Unresolved hops)"))
    A("")
    runtime_only = {"calls_vtable0", "calls_syscall_stub", "jumps_into_generated",
                    "computes_branch", "writes_generated_code",
                    "dart_gdt_dispatch", "dart_closure_call", "dart_indirect_call"}
    unres = [e for e in edges
             if "(unresolved)" in e.resolved_by or e.kind in runtime_only
             or (e.kind == "calls_entry_point" and e.dst_offset is None)]
    groups: Dict[str, List[Edge]] = {}
    for e in unres:
        groups.setdefault(e.kind, []).append(e)
    A("| kind | hops | first caller offsets | what is missing | one step that closes it |")
    A("|---|---|---|---|---|")
    recipes = {
        "calls_direct": ("a `.text` offset no symbol covers",
                         "run Ghidra/IDA over `binaries/libengine.so` and name the function containing "
                         "the target, or read `.rela.plt` if the target is a stub"),
        "calls_vtable0": ("the callee is `*obj`, filled at run time",
                          "hook the decoder object's constructor (the `bl` that returns it) and dump "
                          "the first word of the returned object"),
        "calls_syscall_stub": ("the function pointer holding the syscall stub is not named",
                               "the stub is reached through a register filled earlier in the protected "
                               "body; a Frida `Interceptor` on the `blr` site prints the resolved pointer"),
        "jumps_into_generated": ("the target page exists only after `mmap`",
                                 "`analysis_scripts/frida_dump_register_natives.js` already hooks this "
                                 "family; dump the page after the `blr` and disassemble it"),
        "computes_branch": ("4 relative offsets at `libengine.so+0x125d4`",
                            "read 4 little-endian int32 at that file offset and add each to 0xf4048"),
        "writes_generated_code": ("the bytes written are computed, not stored",
                                  "breakpoint the `str` and read the destination page"),
        "dart_call": ("a Dart code address blutter could not name",
                      "load `libapp.so` in IDA and run the committed "
                      "`output/blutter/ida_script/addNames.py`, then re-resolve"),
        "dart_tail_call": ("a Dart stub address with no inline comment",
                           "same as above — `addNames.py` names the stub region"),
        "registers_native_via_engine": ("the engine's 41 fnPtrs",
                                        "hook `RegisterNatives` inside `libflutter.so`"),
        "calls_entry_point": ("the export exists but the bundle records no offset for it",
                              "`readelf --dyn-syms flutter_libs/libflutter.so | grep JNI_OnLoad`"),
        "dart_gdt_dispatch": ("the dispatch-table entry is picked from the receiver's class id at "
                              "run time",
                              "map the class id to a class with blutter's `objs.txt`, then read "
                              "`GDT[cid+delta]`; or `Interceptor.attach` the blr and print `lr`"),
        "dart_closure_call": ("the closure object's entry point is only known once the closure "
                              "exists",
                              "hook the blr and print the word at `closure+0x1f`, or xref the pool "
                              "slot that allocated the closure (`dart_instantiates_closure` rows)"),
        "dart_indirect_call": ("the register's source is not one of the recognised shapes",
                               "single-step the site in a debugger and record the target"),
    }
    for k, sel in sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        what, how = recipes.get(k, ("the callee is not named in the committed evidence",
                                    sel[0].resolved_by))
        ordered = sorted(sel, key=lambda e: (e.src_offset is None, e.src_offset or 0))
        sites = ", ".join(f"`{hx(e.src_offset)}`" if e.src_offset is not None
                          else f"`{e.src_name[:40]}`" for e in ordered[:6])
        A(f"| `{k}` | {len(sel)} | {sites}{' …' if len(sel) > 6 else ''} | {what} | {how} |")
    A("")
    if LANG_TH:
        A("สูตรแบบ dynamic ที่ปิดช่องว่างเหล่านี้ได้ครั้งละมาก ๆ ถูก commit ไว้ข้าง `LINKAGE.md` แล้ว:")
        A("`analysis_scripts/frida_dump_register_natives.js` hook ที่ JNIEnv slot แล้วพิมพ์")
        A("`class / name / signature / fnPtr / module+offset` ครบทั้ง 13 รายการ — ซึ่งก็คือคอลัมน์")
        A("`dst_module + dst_offset` ของ `call_linkage.csv` พอดี ผลการ capture จึงเติมเข้ากราฟนี้ได้")
        A("โดยไม่ต้องพิมพ์ถ่ายเอง")
    else:
        A("The dynamic recipe that closes most of them at once is already committed beside `LINKAGE.md`:")
        A("`analysis_scripts/frida_dump_register_natives.js` hooks the JNIEnv slot and prints")
        A("`class / name / signature / fnPtr / module+offset` for all 13 entries — i.e. exactly the")
        A("`dst_module + dst_offset` columns of `call_linkage.csv`, so a dynamic capture can be appended "
          "to this graph without manual transcription.")
    A("")

    # ---------------- 7 ----------------
    A(T("## Gaps this view exposes", "## ช่องว่างที่มุมมองนี้เผยให้เห็น (Gaps)"))
    A("")
    gaps_en = [
        ("**high**", "the one statically recovered fnPtr points at a `ret`",
         "`0x81eeb0` (registered by the site at `0xb40a8`, stored by `stp x21, x9, [sp, #0x40]` at "
         "`0xb40b0`) decodes as `ret`; the real prologue `stp x29, x30, [sp, #-0x20]!` starts 4 bytes "
         "later at `0x81eeb4`",
         "as registered, the handler returns immediately. Either the pointer is patched at run time "
         "before ART ever calls it, or `0x81eeb0` is a decoy and the live entry is reached another "
         "way. The chain `CH-06` is therefore drawn from `0x81eeb4` and the discrepancy is recorded "
         "rather than smoothed over."),
        ("**medium**", "the 3 MethodCall handlers have no disassembly in the bundle",
         "blutter lists `_pfc` @`0x504300`, `_cec` @`0x50e170`, `_eec` @`0x50dad8` with `size: -1`, "
         "and their pool slots (`pp+0x3910`, `pp+0x2cd8`, `pp+0x2ce8`) are not referenced by any "
         "disassembled function in `output/blutter/asm/`",
         "the Dart->Java half of the bridge cannot be walked instruction by instruction from this "
         "bundle; `CH-10` stops at the handler addresses. Disassemble those three offsets in IDA "
         "(they are inside `.text`, `0x160000`+4,178,912) or xref the pool slots."),
        ("**medium**", "`Lcom/snake/helper/flagger;` declares 2 natives that nothing calls",
         "F2 records 0 invoke sites, yet site `0xb40a8` registers exactly 2 methods for that class "
         "and supplies the only recoverable fnPtr of the 13",
         "the registration is real and the handler shape is known, but no Java caller reaches it - "
         "so it is reached reflectively, from Dart, or it is dead. This is a call-graph hole, not a "
         "missing fact."),
        ("**medium**", "the routine that issues the C2 request has no committed disassembly",
         "the 6 `[Kkg] _Bpa` closures that own the endpoint's pool run (0x2f7aac, 0x2f8928, "
         "0x2f8998, 0x310338, 0x310360, 0x3103b0) are all listed with `size: -0x1`; of that family "
         "only 0x533110 and 0x5332c4 are disassembled",
         "the request itself - URL assembly, the multipart POST, the HTTP client call - is therefore "
         "attributed by pool adjacency at `probable`, not proven instruction by instruction, and "
         "`CH-12` walks only the response side. Disassemble 0x2f8928 in IDA (it is inside `.text`, "
         "`0x160000`+4,178,912) or hook the 4 pool slots pp+0x139d8, pp+0x139e0, pp+0x13a38 and "
         "pp+0x13ac0 at run time."),
        ("**low**", "5 PLT stubs the windows call are unnamed",
         "`0x81f140` (called with `w0=#0xc`, result used as a 12-byte record), `0x81f250` "
         "(`(ptr, ptr, 8) -> int`, result tested - memcmp-shaped), `0x7775d8`, `0x777fb0`, `0x7778a8` "
         "(each returns an object whose first word is then called)",
         "shapes are recorded instead of names; `.rela.plt` or one Ghidra pass over those five "
         "offsets closes it."),
        ("**low**", "`svc #53` sites stage only `x0`",
         "`0xf4428` and `0xf4458` set `x8 = #0x35` and load `x0` from a word of the generated page; "
         "`0xb3fe4` does the same inside registration window 2. The remaining argument registers "
         "are not staged in the decoded window",
         "the syscall *number* is proven (`fchmodat`), the *arguments* are not. Treat the name as "
         "the floor of what is known and do not describe it as a file operation on a specific path."),
        ("**low**", f"{c['dart']['unresolved']} Dart branch hops have no symbol",
         f"{c['dart']['unresolved']} of {c['dart']['branch_edges']} decoded Dart branch edges target "
         f"addresses that neither `addNames.py`, the asm `** addr:` markers nor `pp.txt` name",
         "they are emitted as address-only hops with `conf.=candidate` and ranked by fan-in in "
         "`CH-11`, so the hottest unnamed helper (`0x19ac78`, 4 call sites) is visible even though "
         "its name is not."),
    ]
    gaps_th = [
        ("**high**", "fnPtr ตัวเดียวที่กู้คืนได้จาก static ชี้ไปยัง `ret`",
         "`0x81eeb0` (ถูก register โดย site `0xb40a8` และถูกเก็บด้วย `stp x21, x9, [sp, #0x40]` ที่ "
         "`0xb40b0`) ถอดรหัสได้เป็น `ret` ส่วน prologue จริง `stp x29, x30, [sp, #-0x20]!` "
         "เริ่มที่ `0x81eeb4` ซึ่งถัดไปอีก 4 ไบต์",
         "ตามที่ register ไว้ handler จะ return ทันที นั่นแปลได้สองอย่าง: pointer ถูก patch ตอนรันก่อนที่ "
         "ART จะเรียกจริง หรือ `0x81eeb0` เป็นตัวล่อแล้ว entry ที่ทำงานจริงไปถึงด้วยทางอื่น — สาย `CH-06` "
         "จึงลากจาก `0x81eeb4` และบันทึกความต่างนี้ไว้แทนที่จะปัดให้เรียบ"),
        ("**medium**", "handler ของ MethodCall ทั้ง 3 ตัวไม่มี disassembly ในชุดหลักฐาน",
         "blutter ระบุ `_pfc` @`0x504300`, `_cec` @`0x50e170`, `_eec` @`0x50dad8` พร้อม `size: -1` "
         "และ pool slot ของมัน (`pp+0x3910`, `pp+0x2cd8`, `pp+0x2ce8`) ไม่ถูกอ้างถึงจากฟังก์ชันใดเลย "
         "ที่ถูก disassemble ไว้ใน `output/blutter/asm/`",
         "ครึ่ง Dart->Java ของสะพานจึงไล่ระดับ instruction จากชุดนี้ไม่ได้ `CH-10` หยุดที่ address ของ "
         "handler — วิธีปิดคือ disassemble ทั้ง 3 offset ใน IDA (อยู่ใน `.text` ที่ "
         "`0x160000`+4,178,912) หรือทำ xref จาก pool slot"),
        ("**medium**", "`Lcom/snake/helper/flagger;` ประกาศ native 2 ตัวที่ไม่มีอะไรเรียก",
         "F2 บันทึก invoke site ไว้ 0 จุด แต่ site `0xb40a8` register เมธอดให้คลาสนี้พอดี 2 ตัว "
         "และเป็นแหล่งของ fnPtr ตัวเดียวที่กู้คืนได้จาก 13 ตัว",
         "การ register มีจริงและรู้รูปทรงของ handler แล้ว แต่ไม่มี caller ฝั่ง Java ไปถึง — จึงเป็นไปได้ว่า "
         "ถูกเรียกผ่าน reflection, จาก Dart, หรือเป็น dead code; นี่คือรูใน call graph "
         "ไม่ใช่ข้อเท็จจริงที่ขาดหาย"),
        ("**medium**", "routine ที่ส่งคำขอไปยัง C2 ไม่มี disassembly ในชุดหลักฐาน",
         "closure ทั้ง 6 ตัวของ `[Kkg] _Bpa` ที่เป็นเจ้าของ pool run ของ endpoint (0x2f7aac, "
         "0x2f8928, 0x2f8998, 0x310338, 0x310360, 0x3103b0) ถูกระบุไว้ด้วย `size: -0x1` ทั้งหมด; "
         "ในครอบครัวนี้มีแค่ 0x533110 และ 0x5332c4 ที่ถูก disassemble",
         "ตัวคำขอเอง — การประกอบ URL, การ POST แบบ multipart, การเรียก HTTP client — จึงถูกผูกด้วย "
         "pool adjacency ที่ระดับ `probable` ไม่ใช่พิสูจน์ทีละคำสั่ง และ `CH-12` ไล่เฉพาะฝั่ง "
         "response; วิธีปิดคือ disassemble 0x2f8928 ใน IDA (อยู่ใน `.text` ที่ `0x160000`+4,178,912) "
         "หรือ hook pool slot ทั้ง 4 (pp+0x139d8, pp+0x139e0, pp+0x13a38, pp+0x13ac0) ตอนรัน"),
        ("**low**", "PLT stub 5 ตัวที่ window เรียกแต่ยังไม่มีชื่อ",
         "`0x81f140` (เรียกด้วย `w0=#0xc` ผลลัพธ์ถูกใช้เป็น record 12 ไบต์), `0x81f250` "
         "(`(ptr, ptr, 8) -> int` แล้วทดสอบผลลัพธ์ — รูปทรง memcmp), `0x7775d8`, `0x777fb0`, "
         "`0x7778a8` (แต่ละตัวคืนอ็อบเจกต์ที่ word แรกถูกเรียกต่อ)",
         "บันทึกเป็นรูปทรงแทนชื่อ; ปิดได้ด้วยการอ่าน `.rela.plt` หรือให้ Ghidra ไล่ 5 offset นั้นหนึ่งรอบ"),
        ("**low**", "จุด `svc #53` เตรียมอาร์กิวเมนต์ไว้แค่ `x0`",
         "`0xf4428` และ `0xf4458` ตั้ง `x8 = #0x35` แล้วโหลด `x0` จาก word ของหน้าที่ถูกสร้างตอนรัน; "
         "`0xb3fe4` ทำแบบเดียวกันใน registration window 2 — argument register ที่เหลือไม่ถูกเตรียมไว้ "
         "ใน window ที่ถอดรหัสได้",
         "หมายเลข syscall พิสูจน์แล้ว (`fchmodat`) แต่ *อาร์กิวเมนต์* ยังไม่ — ให้ถือว่าชื่อนี้เป็นขั้นต่ำ "
         "ของสิ่งที่รู้ และอย่าอธิบายว่าเป็นปฏิบัติการไฟล์กับ path ใด path หนึ่ง"),
        ("**low**", f"Dart branch hop {c['dart']['unresolved']} ตัวไม่มีสัญลักษณ์",
         f"{c['dart']['unresolved']} จาก {c['dart']['branch_edges']} edge ของ Dart ที่ถอดรหัสได้ "
         f"ชี้ไปยัง address ที่ทั้ง `addNames.py`, marker `** addr:` ใน asm และ `pp.txt` ไม่ระบุชื่อ",
         "ถูกส่งออกเป็น hop ที่มีแค่ address พร้อม `conf.=candidate` และจัดอันดับตาม fan-in ใน `CH-11` "
         "ทำให้ยังเห็น helper ไม่มีชื่อที่ถูกเรียกบ่อยที่สุด (`0x19ac78`, 4 จุดเรียก) แม้จะไม่รู้ชื่อก็ตาม"),
    ]
    gaps = gaps_th if LANG_TH else gaps_en
    for sev, title, ev, imp in gaps:
        A(f"- {sev} {title}  ")
        A(f"  evidence: {ev}  ")
        A(f"  impact: {imp}")
    A("")

    # ---------------- 8 ----------------
    A(T("## Verification", "## การตรวจสอบ (Verification)"))
    A("")
    npass = sum(1 for k in checks if k["result"] == "PASS")
    A(T(f"{npass}/{len(checks)} checks re-run against the bundle on every build; all must pass:",
        f"ทั้ง {npass}/{len(checks)} รายการถูกตรวจซ้ำกับชุดหลักฐานทุกครั้งที่ build และต้องผ่านทั้งหมด:"))
    A("")
    A("| result | check | detail |")
    A("|---|---|---|")
    for k in checks:
        A(f"| {k['result']} | {esc(k['name'])} | {esc(k['detail'])} |")
    A("")
    if LANG_TH:
        A("การตรวจสอบระดับไบต์ 45 รายการที่อยู่เบื้องหลัง hop เหล่านี้อยู่ใน `VERIFICATION.txt` ข้าง")
        A("`LINKAGE.md`; ส่วนรายการข้างบนคือ invariant ของ *call graph* — ทุก hop ของ chain ต้องมีอยู่,")
        A("จำนวน method ที่ register ต้องรวมได้ 13 เท่าเดิม, แหล่งชื่อทั้งสองของ blutter ต้องตรงกัน,")
        A("และไม่มี offset ใดตกออกนอกโมดูลของมัน")
    else:
        A("The 45 byte-level checks behind these hops live in `VERIFICATION.txt` next to `LINKAGE.md`;")
        A("the checks above are the *call-graph* invariants — that every chain hop exists, that the")
        A("registration counts still add to 13, that both blutter name sources agree, and that no "
          "offset falls outside its module.")
    A("")
    return "\n".join(out)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main(argv: Optional[Sequence[str]] = None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    default_src = os.path.join(root, "SnakeLogic")
    if not os.path.isdir(default_src):
        default_src = os.path.join(root, "SnakeLogic.zip")
    ap.add_argument("--src", default=default_src,
                    help="evidence bundle: the extracted SnakeLogic/ directory (default when it "
                         "exists) or SnakeLogic.zip")
    ap.add_argument("--out", default=None,
                    help="where to write CALL_LINKAGE.md / call_linkage.csv / call_linkage.json "
                         "(default: next to LINKAGE.md, i.e. the bundle directory)")
    ap.add_argument("--lang", choices=("en", "th", "both"), default="both",
                    help="prose language; tables, offsets and symbol names stay English")
    ap.add_argument("--check", action="store_true",
                    help="build and verify only; do not write")
    args = ap.parse_args(argv)

    if not os.path.exists(args.src):
        print(f"error: bundle not found: {args.src}", file=sys.stderr)
        return 2
    global LANG_TH
    LANG_TH = args.lang in ("th", "both")
    out_dir = args.out or (args.src if os.path.isdir(args.src) else root)
    b = Bundle(args.src)
    for need in ("fragments/fragments.json", "fragments/F4c_libengine_JNI_OnLoad.asm",
                 "fragments/F4b_libengine_mytext.asm",
                 "fragments/F4d_libengine_regnatives_windows.asm",
                 "fragments/F2_dex_natives.txt"):
        if not b.has(need):
            print(f"error: bundle is missing {need}", file=sys.stderr)
            return 2

    b.label = os.path.relpath(os.path.abspath(args.src), root)
    if os.path.isdir(args.src):
        b.label += "/"
    data = build(b, args.lang)

    # If both forms of the bundle are present, they must describe identical
    # inputs - the extracted tree is a copy of the zip, and the fingerprint is
    # what proves it stayed one.
    sibling = (os.path.join(root, "SnakeLogic.zip") if os.path.isdir(args.src)
               else os.path.join(root, "SnakeLogic"))
    if os.path.exists(sibling) and os.path.abspath(sibling) != os.path.abspath(args.src):
        other = Bundle(sibling)
        same = other.fingerprint() == data["meta"]["bundle_fingerprint"]
        op = sorted(set(getattr(other, "operator_inputs", [])) |
                    set(b.operator_inputs if hasattr(b, "operator_inputs") else []))
        excluded = ("%d generated artifacts" % len(Bundle.OWN_OUTPUTS)) + (
            (" and %d operator-supplied fragment(s): %s" % (len(op), ", ".join(op)))
            if op else "")
        data["checks"].append({
            "name": "the extracted tree and the zip carry identical inputs",
            "result": "PASS" if same else "FAIL",
            "detail": f"{data['meta']['bundle']} fingerprint "
                      f"{data['meta']['bundle_fingerprint']} vs {other.label if hasattr(other, 'label') else os.path.relpath(sibling, root)} "
                      f"fingerprint {other.fingerprint()} over "
                      f"{len(other.names())} input files ({excluded} are excluded "
                      f"from both)",
        })

    rows = edges_to_rows(data["edges"])
    md = render_md(data)

    fails = [k for k in data["checks"] if k["result"] != "PASS"]
    missing = [h for ch in data["chains"] for h in ch["hops"] if not h["edge"]]
    for k in data["checks"]:
        print(f"{k['result']:4s} {k['name']}: {k['detail'][:160]}")
    print(f"\nedges={len(data['edges'])} nodes={len(data['nodes'])} "
          f"chains={len(data['chains'])} hops={sum(len(c['hops']) for c in data['chains'])} "
          f"missing_hops={len(missing)} checks_failed={len(fails)}")
    if fails or missing:
        for h in missing:
            print("  MISSING HOP:", h["text"], file=sys.stderr)
        return 1
    if args.check:
        return 0
    os.makedirs(out_dir, exist_ok=True)
    write_csv(os.path.join(out_dir, "call_linkage.csv"), rows)
    write_json(os.path.join(out_dir, "call_linkage.json"), data)
    with open(os.path.join(out_dir, "CALL_LINKAGE.md"), "w", encoding="utf-8") as fh:
        fh.write(md)
    print(f"wrote CALL_LINKAGE.md ({len(md)} bytes), call_linkage.csv ({len(rows)} rows), "
          f"call_linkage.json to {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
