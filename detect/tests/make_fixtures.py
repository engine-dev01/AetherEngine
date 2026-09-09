#!/usr/bin/env python3
"""สร้าง synthetic fixtures จากหลักฐาน 4 ชั้น — โครงสร้าง/สตริงจริง แต่ไฟล์เล็กและไม่มีโค้ดจริง
ใช้ทดสอบ scanner ใน CI โดยไม่ต้องอัพโหลด sample จริงขึ้น public repo
อ้างอิง: 00_MASTER_SYNTHESIS.md ทุก section ที่ rule อ้างถึง
"""
import json
import zipfile
from pathlib import Path

OUT = Path(__file__).parent / "fixtures"
OUT.mkdir(parents=True, exist_ok=True)


def build_snake_like_apk(path: Path):
    """fixture ประเภท 1: โครง SNAKE.apk จริง (ชื่อ entry + สตริงหลักฐาน — ไม่มีโค้ด)"""
    entries = {
        # Layer 4: AndroidManifest/package
        "AndroidManifest.xml": b"pkg=com.snake minimal placeholder",
        # Layer 3: native engine (สตริงจาก Ghidra)
        "lib/arm64-v8a/libengine.so": b"\x7fELF process_vm_readv JNI_OnLoad dlsym placeholder",
        # Layer 4: Dart C2 (สตริงจาก Blutter)
        "lib/arm64-v8a/libapp.so": b"rest.snakeseller.com action=upload_profile_image Carrom Entry Key snakeengine.com/topup",
        # Layer 1: stock flutter (context — ต้องไม่ trigger อะไรเพิ่ม)
        "lib/arm64-v8a/libflutter.so": b"Dart stock engine 3.24.5",
        # Layer 4: dex (ChildAppClient enum + channel name)
        "classes.dex": b"ChildAppClient Snake Engine androidx.appcompat.view.menu",
        # Layer 2: assets (FontAwesome ที่ SNAKE และ Aether แชร์)
        "assets/flutter_assets/fonts/fa-solid-900.woff": b"font-placeholder",
        "assets/flutter_assets/assets/whatsapp.svg": b"<svg/>",
        "META-INF/MANIFEST.MF": b"placeholder",
    }
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


def build_clean_game_zip(path: Path):
    """fixture ประเภท 2: เกมแท้ (baseline จาก miniclip-56.23.2 — ต้อง CLEAN ทุก pack)"""
    entries = {
        "AndroidManifest.xml": b"pkg=com.miniclip.eightballpool genuine",
        "lib/arm64-v8a/libgame.so": b"\x7fELF genuine game module 3965",
        "assets/GameConfiguration.plist": b"bplist00 encrypted-config-placeholder",
        "classes.dex": b"genuine miniclip code",
    }
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


def build_snake_data_dump_zip(path: Path):
    """fixture ประเภท 3: data dump (โครงจาก com.snake_1.zip — sandbox artifact)"""
    entries = {
        # sandbox layout จริง (Layer 2 dump) — package.conf จริงเป็น UTF-16LE
        # component list + มี ASCII path ปน (sourceDir) → rule string หา ASCII ต้องเจอ
        "root/data/app/com.miniclip.eightballpool/package.conf": (
            "com.miniclip.eightballpool.EightBallPoolActivity".encode("utf-16-le")
            + b"/data/app/com.miniclip.eightballpool/base.apk"       # sourceDir ASCII
        ),
        "root/data/user/0/com.miniclip.eightballpool/a0rjgdfbjd8fhfglkew6/90d8aa15a2de2cb4/arm64-v8a/libpglarmor.so": b"\x7fELF pgl armor",
        "root/data/user/0/com.miniclip.eightballpool/app_webview_0:com.miniclip.eightballpool:com.miniclip.eightballpool/Default/Local Storage/leveldb/000003.log": b"leveldb",
        "root/proc/0/cmdline": b"com.miniclip.eightballpool",
        "root/system/uid.conf": b"1000",
        # cheat app data ข้าง sandbox
        "files/phenotype/shared/com.google.android.gms.measurement#com.snake.xml": b"phenotype",
        "shared_prefs/com.snake.xml": b"<map/>",
    }
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


if __name__ == "__main__":
    p1 = build_snake_like_apk(OUT / "fixture_snake_like.zip")
    p2 = build_clean_game_zip(OUT / "fixture_clean_game.zip")
    p3 = build_snake_data_dump_zip(OUT / "fixture_data_dump.zip")
    manifest = {
        "fixture_snake_like.zip": "โครง SNAKE.apk จาก synthesis 4 ชั้น — คาดหวัง: snake-8bp DETECTED, aether-family SUSPICIOUS(แชร์ FA font)",
        "fixture_clean_game.zip": "เกมแท้ baseline — คาดหวัง: CLEAN ทุก pack (anti false-positive)",
        "fixture_data_dump.zip": "โครง data dump com.snake_1.zip — คาดหวัง: snake-8bp SUSPICIOUS/DETECTED (sandbox artifact ไม่มี .so จริง)",
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    for p in (p1, p2, p3):
        print(f"built: {p.name} ({p.stat().st_size} bytes)")
    print(f"manifest: {OUT / 'manifest.json'}")
