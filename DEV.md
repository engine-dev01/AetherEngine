# AetherEngine Development Guide

How to build, test, and extend AetherEngine.

## Quick start

```bash
# 1. Pre-flight (L1) — 5 sec
bash scripts/preflight.sh

# 2. Local test (L2) — 30 sec (requires Android SDK)
bash scripts/test-local.sh

# 3. CI branch test (L3) — 6-8 min
bash scripts/test-ci.sh test/your-branch
```

## Project structure

```
aether-native/   # C++ engine (libaether.so, 3.6 MB)
  src/main/cpp/   # 14 files
    aether_core.cpp   # JNI entry (35 methods)
    core/         # 12 files: jni_hook, class_map, crypto, mem_reader,
                  #   aob_scanner, env_check, stealth, binder, etc.
    layer/        # 3 files: bindmount, packageconf, rootspoof
    third_party/  # LZ4 vendored

aether-core/     # Kotlin core (3 files)
  Engine.kt        # 35 external fun ↔ C++ JNI (1:1)
  RemoteConfig.kt  # offline-only (no remote endpoint)
  SandboxManager.kt # bootstrap, payload, ELF stub

aether-android/  # Android library (24 files)
  app/src/main/kotlin/com/aether/engine/
    app/         # AetherApp (loadLibrary, init subsystems)
    daemon/      # AetherDaemonService (pidof loop)
    ipc/         # AetherSystemCallProvider + AetherStubReceiver
    proxy/       # AetherOrchestrator, ServiceBinderProxy, VirtualAppContainer
    vpn/         # AetherVpnService

app/             # Flutter shell
  lib/            # main.dart + screens + widgets + i18n
  assets/         # 5 SVG (social) + 4 fonts (FontAwesome)
  android/        # Android host (AetherHostActivity + EngineBridge)
```

## Native engine (libaether.so)

- **Size:** 3.6 MB (stripped, no obfuscation)
- **Compiler:** NDK r26b, clang 14, arm64-v8a only
- **Build time:** ~2 min (cache hit on NDK)
- **Output:** `aether-native/build/intermediates/cmake/debug/obj/arm64-v8a/libaether.so`

### JNI contract (35 methods)
- `Engine.kt` defines 35 `external fun`
- `aether_core.cpp` exports 35 `Java_com_aether_Engine_*`
- **Pre-flight G3** verifies 1:1 parity (must match exactly)

### Hook chain (NATIVE_LOGIC(UNVERIFIED) §A)
- `GetMethodID` (index 33) → cache `loadClass` jmethodID
- `CallObjectMethod{,V,A}` (indices 34-36) → custom `loadClass` redirect
- Self-guard via magic header `0xA37E2C5F1B8D4E69`

## Kotlin layer (AetherOrchestrator)

```
init() →
  1. VirtualAppContainer.setup()  # fake context, path redirect
  2. ServiceBinderProxy.init()   # 8 system service hook
  3. (ArtHookEngine removed in V3 — was no-op)
  4. (StringObfuscator removed in V3 — was orphan)
  5. SandboxManager.init()        # bootstrap root/ (dataDir/root) + ELF stubs
  6. Flagger.init()               # tamper state bitset
  7. (Phase 3.3) RemoteConfig.fetchRemoteAsync()  # offline
  8. (Phase 3.1) Engine.nativeHydratePayloads()  # if payloads in root/files/
  9. CrashHandler.install()       # local file only
 10. AetherDaemonService.start()   # FGS pidof loop
```

## Flutter shell

- **Channel:** `com.aether/engine_bridge` (EngineBridge.kt)
- **2 methods exposed:** `launchGame` + `openPlayStore`
- **Assets:** 5 SVG (social) + 4 fonts (FontAwesome) + slick.ttf
- **i18n:** 5 langs (EN, FIL, Malay, ID, ES) — see `app/lib/i18n/strings.dart`

## Build (local, requires Android SDK + NDK)

```bash
export ANDROID_HOME=/path/to/android-sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

# Kotlin modules + native engine (root Gradle wrapper — ตัวเดียวใน repo)
./gradlew :aether-core:compileDebugKotlin \
         :aether-android:compileDebugKotlin \
         :aether-native:externalNativeBuildDebug

# Full APK — ผ่าน Flutter เท่านั้น (app/android wrapper สร้างโดย flutter tool)
cd app && flutter build apk --release
```

## Test pipeline (T1)

See [TESTING.md](TESTING.md) for details.

| Layer | Script | Time | Catches |
|---|---|---|---|
| L1 | `scripts/preflight.sh` | < 5s | YAML, brace, JNI, refs, manifest deprecation |
| L2 | `scripts/test-local.sh` | 5-30s | gradle, manifest, module structure |
| L3 | `scripts/test-ci.sh <branch>` | 6-8 min | full APK build, runtime artifacts |

## Common tasks

### Add new JNI method
1. Add `external fun` to `aether-core/.../Engine.kt`
2. Add `Java_com_aether_Engine_X` in `aether-native/.../aether_core.cpp`
3. Add to `JNINativeMethod m[]` table
4. Run L1 (preflight G3 verifies 1:1)
5. Run L3 (full build)

### Add new AetherOrchestrator subsystem
1. Add to `AetherOrchestrator.init()` in proper order
2. Add to `shutdown()` if cleanup needed
3. Test in L2 (gradle compile)

### Add new Dart UI
1. Add screen/widget under `app/lib/`
2. Use `MethodChannel('com.aether/engine_bridge')` to call Kotlin
3. Add new channel method to `EngineBridge.kt` (currently 2 — launchGame, openPlayStore)
4. Add i18n string to `app/lib/i18n/strings.dart`

## Architecture decisions

- **No obfuscation (Snake has OLLVM):** Aether chose W^X + Play Protect safety over Snake's R8 + obfuscation
- **No JIT codegen decryptor:** Aether's `StrDecrypt::decrypt` is pure C++ (Snake uses JIT mmap RWX)
- **1 game (Snake has 16):** Aether is offline-only, no API for game metadata
- **No remote config server:** Aether uses `offlineFallback()` in `RemoteConfig.kt`

## Code metrics (after V3 cleanup)

| Module | Files | LOC |
|---|---|---|
| aether-native (C++) | 36 | 2,088 |
| aether-core (Kotlin) | 6 | 1,500 |
| aether-android (Kotlin) | 24 | 5,000 |
| app/lib (Dart) | 6 | 700 |
| app/assets | 9 files | ~1 MB |
| **Total** | **81** | **9,300** |

(Removed in V3: 738 LOC of orphan code — StringObfuscator.kt 320 + ArtHookEngine.kt 418)

## Gates (local — รันก่อนขอประกอบเสมอ)
| gate | ตรวจ | fail mode |
|---|---|---|
| `scripts/preflight.sh` | รวมทั้งหมดด้านล่าง + YAML/manifest/brace | exit≠0 |
| `scripts/full_compile.sh` | compile **ทั้ง 3 โมดูล** ด้วย android.jar 35 จริง (≡ CI compileDebugKotlin) | unresolved reference |
| `scripts/jni_parity.py` | Engine.kt ↔ table 1:1 incl. descriptor + **dead-fun guard** (C3) | mismatch |
| `scripts/native_chain_parity.py` | T1 F2 ↔ sig/table/call-site placement (ic/i/ac; D6/D7=WARN) | hop ขาด |
| `scripts/structural_gates.py` | S1 orphan-TU · S2 manifest↔MAX_SLOTS · S3 NO-OP marker (C5/C7/C4) | เขียวหลอก |
| `scripts/evidence_check.sh` | T1 vendored + UNVERIFIED declared (C10/C16) | อ้างลอย |
| `scripts/wire_contract_check.py` | W1 const-only literals · W2 bundle keys single-source · W3 Dart↔Kotlin types | schema แยกร่าง |
| `aether-android/test-stubs/run-aether-test.sh` | JVM unit 4 เคส (รวม C11 seam success path) | assertion |

กติกา (audit round 2): **ห้าม push/สั่ง CI โดยไม่ได้รับอนุญาตเป็นรายครั้ง** — local gates
ต้องเขียวครบก่อน แล้วสรุป diff ให้ผู้ใช้ตรวจ → ขอ permission → push ครั้งเดียว
