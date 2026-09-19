#!/usr/bin/env python3
"""dart_static_check.py — verify Dart sources before the CI Flutter job runs.

No Dart SDK required (python3 only), so this gate runs locally and in CI
*before* `flutter analyze` / `flutter test`. It catches the failure modes that
would otherwise only show up as a red CI run:

  D1  `package:aether/...` imports must resolve to a real file under app/lib
  D2  parentheses / brackets / braces / quotes must balance (an unbalanced
      file aborts the whole `flutter test` run)
  D3  `Type.member` on a type declared in app/lib must exist (static, factory
      constructor, enum value, or instance member)
  D4  named arguments at a lib-declared constructor's call site must match the
      declared parameters (depth-1 only — nested calls are not attributed to
      the outer constructor). Catches renamed/removed params such as the
      PopScope→WillPopScope class of breakage.

Usage: python3 scripts/dart_static_check.py [repo_root]
Exit:  0 = clean, 1 = findings
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
LIB = ROOT / 'app/lib'
TESTS = ROOT / 'app/test'

FAILS = []
WARNS = []

OBJECT_MEMBERS = {'toString', 'hashCode', 'runtimeType', 'noSuchMethod'}
ENUM_MEMBERS = {'values', 'name', 'index', 'toString', 'hashCode'}


def read(p):
    try:
        return Path(p).read_text(encoding='utf-8', errors='replace')
    except OSError:
        return ''


def strip_comments(text):
    """Drop // and /* */ comments without touching string literals."""
    out, in_block = [], False
    for line in text.split('\n'):
        if in_block:
            end = line.find('*/')
            if end == -1:
                continue
            in_block, line = False, line[end + 2:]
        i, buf = 0, ''
        while i < len(line):
            c = line[i]
            if c in '"\'':
                j = i + 1
                while j < len(line):
                    if line[j] == '\\':
                        j += 2
                        continue
                    if line[j] == c:
                        break
                    j += 1
                buf += line[i:j + 1]
                i = j + 1
                continue
            if c == '/' and line[i + 1:i + 2] == '/':
                break
            if c == '/' and line[i + 1:i + 2] == '*':
                end = line.find('*/', i + 2)
                if end == -1:
                    in_block = True
                    break
                i = end + 2
                continue
            buf += c
            i += 1
        out.append(buf)
    return '\n'.join(out)


def _body(code, idx, opener='{', closer='}'):
    start = code.find(opener, idx)
    if start == -1:
        return ''
    depth = 0
    for i in range(start, len(code)):
        if code[i] == opener:
            depth += 1
        elif code[i] == closer:
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
    return code[start + 1:]


def _split_top(text):
    """Split on commas that are at brace/paren/bracket depth 0."""
    parts, depth, cur = [], 0, ''
    for ch in text:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
            continue
        cur += ch
    if cur.strip():
        parts.append(cur)
    return parts


class Decl:
    __slots__ = ('name', 'kind', 'statics', 'instance', 'params', 'values')

    def __init__(self, name, kind):
        self.name, self.kind = name, kind
        self.statics = set()
        self.instance = set()
        self.params = None      # None = no constructor seen
        self.values = set()


def collect_decls():
    decls = {}
    for f in sorted(LIB.rglob('*.dart')):
        code = strip_comments(read(f))
        for m in re.finditer(r'^(?:abstract\s+)?class\s+([A-Za-z_]\w*)', code, re.M):
            name = m.group(1)
            body = _body(code, m.end())
            d = decls.setdefault(name, Decl(name, 'class'))
            _fill_class(d, body)
        for m in re.finditer(r'^enum\s+([A-Za-z_]\w*)\s*\{', code, re.M):
            name = m.group(1)
            d = decls.setdefault(name, Decl(name, 'enum'))
            head = _body(code, m.end() - 1)
            head = strip_comments(head.split(';')[0])
            head = head.split('(')[0]
            d.values |= {v.strip() for v in head.split(',') if v.strip()}
            d.params = {'index', 'name'}
    return decls


def _fill_class(d, body):
    if not body:
        return
    # ─ constructors (incl. factory and named) ──────────────────────────────
    for m in re.finditer(
            rf'^\s{{2}}(?:const\s+|factory\s+)?{re.escape(d.name)}\s*(\.\s*\w+\s*)?\(',
            body, re.M):
        named_ctor = m.group(1)
        if named_ctor:
            d.statics.add('ctor:' + named_ctor.strip().lstrip('.').strip())
        inner = _body(body, m.end() - 1, '(', ')')
        if inner.strip().startswith('{'):
            inner = _body(inner, 0)          # unwrap ({ named: ... })
        params = set()
        for part in _split_top(inner):
            p = part.strip()
            if not p:
                continue
            p = re.sub(r'^(?:@\w+(?:\([^)]*\))?\s*)+', '', p).strip()
            p = re.sub(r'^(?:required|covariant)\s+', '', p).strip()
            mm = re.match(r'super\.(\w+)', p) or re.match(r'this\.(\w+)', p)
            if mm:
                params.add(mm.group(1))
                continue
            # Type name  |  Type? name  |  List<T> name
            mm = re.match(r'[\w<>,?\s\[\].]*?\b(\w+)\s*(?:=\s*.+)?$', p)
            if mm:
                params.add(mm.group(1))
        if d.params is None:
            d.params = set()
        d.params |= params
    # ── static members ────────────────────────────────────────────────────
    for m in re.finditer(r'static\s+[\w<>,?\s\[\]]*?\b(\w+)\s*(?:=|;|\(|=>)', body):
        d.statics.add(m.group(1))
    for m in re.finditer(r'static\s+[\w<>,?\s\[\]]*?\s+get\s+(\w+)', body):
        d.statics.add(m.group(1))
    # ── instance members (fields, getters, methods) ────────────────────────
    for m in re.finditer(r'^\s{2}(?:final\s+|const\s+|late\s+)?[\w<>,?\s\[\]]*?\b(\w+)\s*(?:=|;)',
                         body, re.M):
        d.instance.add(m.group(1))
    for m in re.finditer(r'^\s{2}(?:@override\s+)?[\w<>,?\s\[\]]*?\s+get\s+(\w+)', body, re.M):
        d.instance.add(m.group(1))
    for m in re.finditer(r'^\s{2}[\w<>,?\s\[\]]*?\b(\w+)\s*\(', body, re.M):
        d.instance.add(m.group(1))


def check_imports():
    for f in sorted(list(TESTS.rglob('*.dart')) + list(LIB.rglob('*.dart'))):
        for m in re.finditer(r"import\s+'package:aether/([^']+)'", strip_comments(read(f))):
            if not (LIB / m.group(1)).is_file():
                FAILS.append(f'D1 import-unresolved {f.relative_to(ROOT)}: package:aether/{m.group(1)}')


def check_balance():
    for f in sorted(TESTS.rglob('*.dart')):
        code = strip_comments(read(f))
        for o, c in (('(', ')'), ('[', ']'), ('{', '}')):
            n = code.count(o) - code.count(c)
            if n:
                FAILS.append(f'D2 unbalanced {o}{c} ({n:+d}) {f.relative_to(ROOT)}')
        for q in ('"', "'"):
            if code.replace('\\' + q, '').count(q) % 2:
                FAILS.append(f'D2 unbalanced {q} quotes {f.relative_to(ROOT)}')


def check_members(decls):
    for f in sorted(TESTS.rglob('*.dart')):
        code = strip_comments(read(f))
        for m in re.finditer(r'(?<![\w.$])([A-Z]\w*)\.(\w+)', code):
            typ, mem = m.group(1), m.group(2)
            d = decls.get(typ)
            if d is None:
                continue                      # framework type
            if d.kind == 'enum':
                if mem not in d.values and mem not in ENUM_MEMBERS:
                    FAILS.append(f'D3 {f.relative_to(ROOT)}: {typ}.{mem} not an enum value')
                continue
            known = d.statics | d.instance | OBJECT_MEMBERS
            if f'ctor:{mem}' in known:
                continue
            if mem not in known:
                FAILS.append(
                    f'D3 {f.relative_to(ROOT)}: {typ}.{mem} not declared in app/lib')


def check_named_args(decls):
    """Depth-1 named args at constructor call sites of lib types."""
    for f in sorted(TESTS.rglob('*.dart')):
        code = strip_comments(read(f))
        for m in re.finditer(r'(?<![\w.$])([A-Z]\w*)\s*\(', code):
            typ = m.group(1)
            d = decls.get(typ)
            if d is None or d.params is None or not d.params:
                continue
            inner = _body(code, m.end() - 1, '(', ')')
            if not inner:
                continue
            for part in _split_top(inner):
                nm = re.match(r'\s*(\w+)\s*:', part)
                if not nm:
                    continue
                if nm.group(1) not in d.params:
                    FAILS.append(
                        f'D4 {f.relative_to(ROOT)}: {typ}({nm.group(1)}: ...) not a '
                        f'declared param (declared={sorted(d.params)})')


def main():
    if not LIB.is_dir():
        print(f'dart_static_check: no app/lib under {ROOT}')
        return 1
    if not TESTS.is_dir():
        WARNS.append('no app/test directory — the Flutter test step has nothing to run')
    decls = collect_decls()
    check_imports()
    check_balance()
    if TESTS.is_dir():
        check_members(decls)
        check_named_args(decls)
    n_tests = len(list(TESTS.rglob('*.dart'))) if TESTS.is_dir() else 0
    print(f'dart_static_check: root={ROOT}')
    print(f'  declared types={len(decls)}  test files={n_tests}')
    print(f'  FAIL={len(FAILS)}  WARN={len(WARNS)}')
    for x in FAILS:
        print(f'  FAIL  {x}')
    for x in WARNS:
        print(f'  WARN  {x}')
    return 1 if FAILS else 0


if __name__ == '__main__':
    sys.exit(main())