# AetherEngine

Android process-level engine for memory analysis, trajectory prediction, and phantom overlay automation. No-root. Multi-process isolated architecture with a native C++ engine (`libaether.so`) and Flutter shell.

## Quick start

```bash
# Local: verify before commit (3-layer pipeline)
bash scripts/preflight.sh          # L1: 7 gates (~5s)
bash scripts/test-local.sh         # L2: 12 checks (~30s)
bash scripts/test-ci.sh test/X      # L3: branch test (~6-8 min)

# Build: GitHub Actions
git push origin main               # auto triggers AetherEngine CI + Build APK
```

## Status

- **Build:** ✅ 26 MB APK, v2-signed (release apk-v*)
- **Runtime:** ✅ App opens without crash (verified)
- **Tests:** ✅ 15 unit tests + 7 preflight gates
- **Phase 10:** ✅ Config layer (games.dart, app_settings.dart) — extensible
- **Phase 11:** ✅ Pluggable engine (Aether/Snake opt-in via EngineLoader)

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 3-tier design (Core/Engine/UI)
- [docs/CONFIG.md](docs/CONFIG.md) — How to add game/engine/setting
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — Common build issues + fixes
- [TESTING.md](TESTING.md) — Test pipeline (L1/L2/L3)
- [DEV.md](DEV.md) — Development guide

## Features

### Native engine (libaether.so 3.6 MB)
- 35 JNI methods (1:1 Engine.kt ↔ aether_core.cpp, verified by pre-flight G3)
- JNI hook chain (4 slots: GetMethodID + 3× CallObjectMethod)
- Process memory reader via `process_vm_readv` (zero-trace)
- AOB scanner with 4MB windows + TTL cache
- LZ4 compression for payload codec
- Stealth (PR_SET_DUMPABLE + ptrace TRACEME)
- EnvCheck (linker hijack + Samsung Exynos detection)

### Android runtime (24 Kotlin files)
- 12 components (ProxyActivity P0-P3, services, providers, daemon)
- AetherOrchestrator (engine lifecycle: init, attach, sandbox)
- AetherDaemonService (FGS pidof loop)
- 8 system service hook (ServiceBinderProxy)
- CrashHandler (14-section local log)
- **Phase 11: EngineLoader** (pluggable: Aether or Snake engine)

### Flutter shell
- Home screen (banner + game card + paywall + bottom nav)
- **Phase 10: Games registry** (extensible — add 1 game by editing 1 file)
- 5 i18n languages (EN, Filipino, Malay, Indonesian, Spanish)
- Snake assets bundled (5 SVG + 4 fonts)
- MethodChannel (2 methods: launchGame, openPlayStore)

## Build optimization

- **Phase 1: SDK + NDK cache** → 21 min → 6 min (warm)
- **Phase 2: Gradle build cache** → save 1-2 min incremental builds
- **Phase 3: Concurrency** → cancel previous run on new push (save ~5 min)

## Architecture (3-tier)

```
Tier 1 (immutable): libaether.so (3.6 MB) — verified, no changes
Tier 2 (extensible): Kotlin layer (24 files) — pluggable engine
Tier 3 (pluggable): Flutter shell (6 files) — add content w/o rebuild
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

## Project layout

```
AetherEngine/
├── aether-native/    # Tier 1: C++ (36 files, 2,088 LOC)
├── aether-core/      # Tier 2: Engine.kt + RemoteConfig + SandboxManager
├── aether-android/   # Tier 2: AetherApp + Orchestrator + components
├── app/              # Tier 3: Flutter shell + 9 assets
├── docs/             # Architecture + Config + Troubleshooting
├── scripts/          # preflight + test-local + test-ci
├── .github/workflows/ # ci.yml + build-apk.yml
├── TESTING.md        # Test pipeline docs
├── DEV.md            # Development guide
└── README.md         # This file
```

## Comparison to Snake Engine 2.2.6

| Feature | Snake | Aether | Note |
|---|---|---|---|
| Native engine | `libengine.so` 8.5 MB (OLLVM) | `libaether.so` 3.6 MB | Phase 11: pluggable |
| Home screen | 16 games | 1 game (extensible) | Phase 10: add via games.dart |
| Package name | `com.snake` | `com.aether` | Different branding |
| JNI methods | 19 (Native.kt) | 35 (Engine.kt) | Aether has more features |
| Hook chain | 4 slots | 4 slots | 1:1 parity |
| Components | 51 | 31 | Aether has 17 stubs dropped |
| LZ4 | Yes | Yes | Same |
| Anti-debug | ptrace | ptrace | Same |

## License

Private — internal AetherEngine project.
