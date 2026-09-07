#!/usr/bin/env python3
"""
parse_packageconf.py — structured reader for Snake-engine package.conf
(Android Parcel serialization of a package manifest, UTF-16LE strings).

Reads the parcel as a SEQUENTIAL stream of length-prefixed strings
(not a byte-by-byte scan) so component ordering / grouping is preserved.

Parcel string layout:
  int32 len   (character count; -1 => null string)
  UTF-16LE chars (len * 2 bytes)
  UTF-16LE null terminator (2 bytes)   [present when len >= 0]
  zero padding up to 4-byte alignment

Usage: python3 parse_packageconf.py <package.conf> [--json out.json]
"""
import struct, sys, json

def align4(n): return (n + 3) & ~3

def _try_utf16(data, i, n):
    """int32 len(chars) + UTF-16LE + u16 null, 4-byte aligned."""
    ln = struct.unpack_from("<i", data, i)[0]
    if not (0 <= ln < 4000) or i + 4 + ln*2 + 2 > n:
        return None
    if data[i+4+ln*2 : i+4+ln*2+2] != b"\x00\x00":
        return None
    try:
        s = data[i+4 : i+4+ln*2].decode("utf-16-le")
    except UnicodeDecodeError:
        return None
    if ln and not all(32 <= ord(c) < 127 for c in s):
        return None
    return s, align4(4 + ln*2 + 2)

def _try_utf8(data, i, n):
    """int32 len(bytes) + UTF-8 + u8 null, 4-byte aligned."""
    ln = struct.unpack_from("<i", data, i)[0]
    if not (0 < ln < 4000) or i + 4 + ln + 1 > n:
        return None
    if data[i+4+ln] != 0x00:
        return None
    seg = data[i+4 : i+4+ln]
    if not all(32 <= b < 127 for b in seg):
        return None
    try:
        s = seg.decode("utf-8")
    except UnicodeDecodeError:
        return None
    return s, align4(4 + ln + 1)

def read_strings(data):
    """Sequentially walk the parcel. package.conf mixes encodings:
    a UTF-16LE header (repackaged classloader names) + UTF-8
    length-prefixed body (component/manifest names). Try UTF-8 first
    (body dominates), then UTF-16LE; 1-byte skip over typed/int blocks."""
    out = []
    i = 0
    n = len(data)
    while i <= n - 4:
        if struct.unpack_from("<i", data, i)[0] == -1:
            out.append((i, None)); i += 4; continue
        r = _try_utf8(data, i, n) or _try_utf16(data, i, n)
        if r:
            s, consumed = r
            out.append((i, s)); i += consumed; continue
        i += 1
    return out

CATEGORY = {
    "Activity":  lambda s: s.endswith("Activity") or "Activity" in s,
    "Service":   lambda s: s.endswith("Service"),
    "Provider":  lambda s: "Provider" in s or s.endswith("ContentProvider"),
    "Receiver":  lambda s: s.endswith("Receiver"),
    "Application": lambda s: s.endswith("Application"),
}

def classify(strings):
    vals = [s for _, s in strings if s]
    comps = {k: [] for k in CATEGORY}
    for s in vals:
        # only fully-qualified class names (has a dot, not a marker/perm)
        if "." in s and not s.startswith("android.content.pm") and not s.startswith("android.permission"):
            for cat, fn in CATEGORY.items():
                if fn(s):
                    if s not in comps[cat]:
                        comps[cat].append(s)
    return vals, comps

def find_meta(vals):
    """Pair well-known metadata keys with the following value string."""
    keys = {
        "com.facebook.sdk.ApplicationId","com.facebook.sdk.ClientToken",
        "com.google.android.gms.ads.APPLICATION_ID","com.google.android.gms.games.APP_ID",
        "com.google.android.gms.version","com.google.android.gms.games.version",
        "com.google.android.play.billingclient.version","com.bytedance.sdk.pangle.version",
        "com.android.stamp.type","APP_NAME",
    }
    meta = {}
    for i, s in enumerate(vals):
        if s in keys and i + 1 < len(vals):
            meta[s] = vals[i+1]
    return meta

def main():
    if len(sys.argv) < 2:
        print("usage: parse_packageconf.py <package.conf> [--json out]"); sys.exit(2)
    path = sys.argv[1]
    data = open(path, "rb").read()
    strings = read_strings(data)
    vals, comps = classify(strings)
    meta = find_meta(vals)

    pkg = "com.miniclip.eightballpool"
    version = next((s for s in vals if s == "56.23.2"), None)
    apk_path = next((s for s in vals if s.endswith("base.apk")), None)
    launcher = next((s for s in comps["Activity"] if "EightBallPool" in s), None)
    app_class = next((s for s in vals if s.endswith("Application") and s.startswith("com.miniclip")), None)
    app_factory = next((s for s in vals if s.endswith("ComponentFactory")), None)

    report = {
        "file": __import__("os").path.basename(path),
        "size_bytes": len(data),
        "parsed_strings": len([s for _, s in strings if s]),
        "package": pkg,
        "version": version,
        "application_class": app_class,
        "app_component_factory": app_factory,
        "apk_path": apk_path,
        "launcher_activity": launcher,
        "counts": {k: len(v) for k, v in comps.items()},
        "components": comps,
        "metadata": meta,
    }

    print("=" * 60)
    print(f"package.conf structured parse — {path}")
    print("=" * 60)
    print(f"size            : {len(data):,} B")
    print(f"parsed strings  : {report['parsed_strings']}")
    print(f"package         : {pkg}")
    print(f"version         : {version}")
    print(f"application     : {app_class}")
    print(f"appFactory      : {app_factory}")
    print(f"launcher        : {launcher}")
    print(f"apk_path        : {apk_path}")
    print("-" * 60)
    for k in ("Application","Activity","Service","Provider","Receiver"):
        print(f"{k:12s}: {len(comps[k])}")
    print("-" * 60)
    print("metadata:")
    for k, v in meta.items():
        print(f"  {k} = {v}")

    if "--json" in sys.argv:
        out = sys.argv[sys.argv.index("--json") + 1]
        json.dump(report, open(out, "w"), indent=2, ensure_ascii=False)
        print(f"\nJSON written: {out}")

if __name__ == "__main__":
    main()
