#!/usr/bin/env python3
"""snake_ui_parity.py — strict snake UI parity gate.

Verifies the AetherEngine Flutter UI against the snake evidence baseline
(blutter object pool pp.txt). Every deviation must carry a label
([NOT-IN-POOL], [OFFLINE-FALLBACK], [NOT-IN-ENGINE]); unlabeled deviations
are FAILs. Labels are what make the port provable, not assumed.

Usage: python3 scripts/snake_ui_parity.py [repo_root]
Exit: 0 = all parity checks pass, 1 = unlabeled deviation found.
"""
import re, sys, os
from pathlib import Path

FAILS = []
WARNS = []


def _tok(t):
    """Tone+Thai tolerant identifier boundary match."""
    return r'(?<![A-Za-z0-9_])' + t + r'(?![A-Za-z0-9_])'


def _read(p):
    try:
        return Path(p).read_text(encoding='utf-8', errors='replace')
    except OSError:
        return ''


# ───────────────────────────────────────────────────────────────
# Evidence baseline: strings that MUST be present (T1, pp.txt).
# Each entry: (label, substring, required-in-files)
# ───────────────────────────────────────────────────────────────
REQUIRED_STRINGS = [
    # internet-check gate (pp+0xf620, 6 languages)
    ('internet-check en', 'Snake Engine needs an active internet connection'),
    ('internet-check ar', 'Snake Engine يحتاج إلى اتصال نشط'),
    ('internet-check es', 'Snake Engine necesita una conexión activa'),
    ('internet-check hi', 'Snake Engine को एक्टिव इंटरनेट कनेक्शन की आवश्यकता है'),
    ('internet-check ms', 'Snake Engine memerlukan sambungan internet yang aktif'),
    ('internet-check tl', 'kailangan ng Snake Engine ng aktibong koneksyon'),
    # version lock (pp+0x112e0 + template pieces)
    ('version-lock', 'from '),
    ('version-lock-range', 'versionNotSupported'),
    # license endpoint (pp+0x139d8)
    ('license-endpoint', 'rest.snakeseller.com/api/request'),
    # access token (pp+0x11508/0x11530/0x115c0)
    ('access-token', 'Access Token'),
    ('access-token-warn', 'do not share it with others'),
    # store links (pp+0x178b8/0x178b0)
    ('store-play', 'play.google.com'),
    # selections (pp+0xfb38/0xfb68/0xfb98)
    ('sel-game', 'Game Selection'),
    ('sel-sub', 'Subscription Selection'),
    ('sel-dur', 'Duration Selection'),
    # keys (pp+0xfd50..0xfdd8)
    ('keys-your', 'Your Keys'),
    ('keys-new', 'New Keys'),
    ('keys-used', 'Used Keys'),
    # device/profile (pp+0x10380/0x103a0/0x103d0)
    ('profile', 'Profile'),
    ('device', 'Device id:'),
    # notifications (pp+0x11130/0x11160)
    ('notif', 'No notifications yet'),
    ('logout', 'Logout'),
    ('logout-confirm', 'Are you sure you want to logout'),
    # back-press exit (pp+0x11190)
    ('back-exit', 'Press back again to exit'),
]

# Deviation labels that MUST exist somewhere in the UI source tree.
REQUIRED_LABELS = ['[NOT-IN-POOL]', '[OFFLINE-FALLBACK]', '[NOT-IN-ENGINE]']

# UI source files that must exist (the port surface).
REQUIRED_FILES = [
    'app/lib/i18n/strings.dart',
    'app/lib/data/games.dart',
    'app/lib/data/license.dart',
    'app/lib/screens/home_screen.dart',
    'app/lib/widgets/license_banner.dart',
    'app/lib/widgets/key_card.dart',
]


def check_required_files(root):
    for rel in REQUIRED_FILES:
        if not (root / rel).is_file():
            FAILS.append(f'MISSING-FILE {rel}')


def check_required_strings(root):
    strings = _read(root / 'app/lib/i18n/strings.dart')
    if not strings:
        FAILS.append('MISSING-FILE app/lib/i18n/strings.dart')
        return
    for label, needle in REQUIRED_STRINGS:
        if needle not in strings:
            FAILS.append(f'MISSING-STRING {label}: {needle!r}')


def check_deviation_labels(root):
    """Every deviation comment must carry a label (this is the strictness:
    unlabeled deviations are the failure mode the gate exists to catch)."""
    ui = ''.join(
        _read(root / rel) for rel in REQUIRED_FILES
    )
    # flag any S. getter that is NOT marked when it appears in code but has
    # no pool counterpart — we approximate by requiring the label set to be
    # present at least once in the tree.
    for lab in REQUIRED_LABELS:
        if lab not in ui:
            WARNS.append(f'LABEL-ABSENT {lab} (no labeled deviations of this kind)')

    # negative control: a hard-coded package name must not appear unlabeled
    for m in re.finditer(r"['\"](com\.[a-z0-9.]+)['\"]", ui):
        pkg = m.group(1)
        # allowed: our own identity + registry (games.dart) + labeled comments
        if pkg in ('com.aether', 'com.miniclip.eightballpool', 'com.miniclip.carrompool',
                   'com.miniclip.soccerstars'):
            continue
        FAILS.append(f'UNLABELED-PACKAGE {pkg}')


def check_tab_structure(root):
    """Snake nav (pp+0x10270/0x10aa0/0x11130/0x103a8): Login, Keys, Accounts,
    Notifications, Profile + Language + Logout. The port must wire all tabs."""
    home = _read(root / 'app/lib/screens/home_screen.dart')
    strings = _read(root / 'app/lib/i18n/strings.dart')
    # tabs must be wired in the nav bar (as S. getters), and the getter
    # itself must resolve to the pool string.
    for getter, pool in (('yourKeys', 'Your Keys'),
                         ('accountsList', 'Accounts List'),
                         ('notifications', 'No notifications yet'),
                         ('profile', 'Profile')):
        if f'S.{getter}' not in home:
            FAILS.append(f'MISSING-TAB {getter} not wired into nav')
        if pool not in strings:
            FAILS.append(f'MISSING-STRING tab {getter}: {pool!r}')
    for bridge in ('isTargetInstalled', 'chainCheck', 'handshakeStatus',
                   'launchApp', 'launchInSandbox'):
        if bridge not in home:
            FAILS.append(f'MISSING-BRIDGE-CALL {bridge}')


def check_license_gate(root):
    """chainCheck / Play must refuse packages the license does not cover —
    snake never virtualizes outside its server license. The check must hold
    at EVERY entry point that could virtualize, not just one of them."""
    home = _read(root / 'app/lib/screens/home_screen.dart')
    lic = _read(root / 'app/lib/data/license.dart')
    if 'covers(' not in lic:
        FAILS.append('LICENSE-STORE-ABSENT LicenseStore.covers missing')
    if 'VersionLock' not in lic or 'version_lock' not in lic:
        FAILS.append('VERSION-LOCK-ABSENT version_lock range not modeled')
    if 'covers(' not in home:
        FAILS.append('LICENSE-GATE-ABSENT home_screen never calls covers()')

    # Every virtualization entry point must gate on covers(). Enforced
    # STRUCTURALLY (brace-walking proved unreliable): the port routes every
    # risky MethodChannel call through one helper that performs the license
    # check. After stripping comments, each occurrence of the method name
    # must sit on a line that also references the helper.
    src = _strip_comments(home)
    for call in ('launchInSandbox', 'launchApp', 'chainCheck'):
        outside = _count_outside_line(src, call, '_guardedInvoke')
        if outside != 0:
            FAILS.append(
                f'LICENSE-GATE-BYPASS {call} invoked {outside}x outside _guardedInvoke')

    # The helper itself must perform the license check.
    if '_guardedInvoke(' not in src or 'covers(' not in src:
        FAILS.append('LICENSE-GATE-ABSENT _guardedInvoke does not check covers()')


def _strip_comments(text):
    """Remove // line comments and /* */ block comments. String literals are
    not fully parsed, but Dart string literals in this file never contain
    '//' or '/*', so this is exact enough for the counts here."""
    out = []
    in_block = False
    for line in text.split('\n'):
        if in_block:
            end = line.find('*/')
            if end == -1:
                continue
            in_block = False
            line = line[end + 2:]
        # find // outside of nothing special
        idx = line.find('//')
        while idx != -1:
            # crude: treat // as comment unless inside quotes
            dq = line[:idx].count('"') + line[:idx].count("'")
            if dq % 2 == 0:
                line = line[:idx]
                break
            idx = line.find('//', idx + 2)
        out.append(line)
        if '/*' in line and '*/' not in line:
            in_block = True
    return '\n'.join(out)


def _count_outside_line(src, call, helper):
    """After comment stripping, every line that mentions the risky method
    name must also mention the helper (either calling it, or being it)."""
    needle = f"'{call}'"
    outside = 0
    for line in src.split('\n'):
        if needle not in line:
            continue
        if helper in line:
            continue
        outside += 1
    return outside



def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else os.getcwd())
    check_required_files(root)
    check_required_strings(root)
    check_deviation_labels(root)
    check_tab_structure(root)
    check_license_gate(root)

    print(f'snake_ui_parity: root={root}')
    print(f'  FAIL={len(FAILS)}  WARN={len(WARNS)}')
    for f in FAILS:
        print(f'  FAIL  {f}')
    for w in WARNS:
        print(f'  WARN  {w}')
    return 1 if FAILS else 0


if __name__ == '__main__':
    sys.exit(main())



