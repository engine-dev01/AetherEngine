# AetherEngine Architecture

3-tier design — pluggable, extensible, clean separation.

## Tier 1: Core (verified, immutable)

```
┌─────────────────────────────────────────────────────┐
│  Core engine (libaether.so, 3.6 MB)                 │
│  - 35 JNI methods (Engine.kt ↔ aether_core.cpp)      │
│  - JNI hook chain (4 slots)                          │
│  - process_vm_readv, AOB scanner, LZ4 codec          │
│  - Verified: build pass + runtime OK                 │
└─────────────────────────────────────────────────────┘
```

**Why immutable:** Core engine verified pass pre-flight G3 (1:1 JNI parity).
Changing requires verification on real device (not in sandbox).

## Tier 2: Engine (extensible)

```
┌─────────────────────────────────────────────────────┐
│  Kotlin layer (aether-android/, aether-core/)       │
│  - AetherApp: Application + loadLibrary              │
│  - AetherOrchestrator: lifecycle (init/attach)      │
│  - ServiceBinderProxy: 8 system service hook        │
│  - VirtualAppContainer: fake context                │
│  - CrashHandler: 14-section local log                │
│  - Phase 11: EngineLoader (pluggable)               │
└─────────────────────────────────────────────────────┘
```

**Why extensible:** EngineLoader supports swapping Aether/Snake engine
without code rewrite (Phase 11 + future).

## Tier 3: UI (pluggable)

```
┌─────────────────────────────────────────────────────┐
│  Flutter shell (app/lib/)                          │
│  - Home screen (banner + game card + paywall)       │
│  - 5 i18n langs (EN, FIL, MAL, ID, ES)              │
│  - Phase 10: Games registry (data/games.dart)       │
│  - MethodChannel com.aether/engine_bridge — 15 methods (incl. key-1234 chainCheck/handshakeStatus) │
│  - Snake assets (5 SVG + 4 fonts) bundled           │
└─────────────────────────────────────────────────────┘
```

**Why pluggable:** Add new game by editing `games.dart` (1 file).
No compile cycle for content changes.

## File layout

```
aether-native/    # Tier 1: C++ (libaether.so, 36 files, 2,088 LOC)
aether-core/      # Tier 2: Kotlin core (Engine.kt, RemoteConfig, SandboxManager)
aether-android/   # Tier 2: Android lib (24 files, 5,000 LOC)
app/              # Tier 3: Flutter shell (6 Dart files, 700 LOC + assets)
docs/             # Documentation
scripts/          # Test pipeline (preflight, test-local, test-ci)
.github/workflows/ # CI (lint, build)
```

## Boundaries

- **Tier 1 → Tier 2:** JNI contract (38 methods 1:1 Engine.kt ↔ aether_core.cpp). Verify via preflight G3 + scripts/jni_parity.py.
- **Tier 2 → Tier 3:** MethodChannel `com.aether/engine_bridge` — 15 methods
  (launchGame/isTargetInstalled/launchInSandbox/launchApp/getEngineStatus/getEngineStats/
   getVirtualAppStatus/testVirtualFS/readMemory/scanAOB/nativeCompute/compressPayload/
   readDiag/**chainCheck**/**handshakeStatus** — 2 ตัวหลัง = key-1234 chain probe)
- **External:** Snake's libengine.so (8.5 MB, OLLVM) is opt-in via EngineLoader.

## Why this design

- **T1 immutable** = core engine verified, no scope creep
- **T2 extensible** = pluggable engine swap (Phase 11+)
- **T3 pluggable** = content changes don't require rebuild cycle
- **Snake parity** = 100% UI (visual), 100% components (31/51), 0% OLLVM (intentional)
