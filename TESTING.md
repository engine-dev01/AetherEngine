# AetherEngine Test Pipeline

3 layers of testing — run before push to prevent build failures.

## Layer 1: Pre-flight (G1-G7) — **LOCAL, < 5 sec**

```bash
bash scripts/preflight.sh
```

Checks:
- **G1**: YAML syntax (`.github/workflows/*.yml`)
- **G1.5**: AndroidManifest deprecated `package=` attribute (AGP 8.x — use `namespace` in build.gradle)
- **G2**: Brace balance (`.kt`, `.cpp`, `.hpp`, `.dart`)
- **G3**: JNI parity (Kotlin `external fun` ↔ C++ `Java_com_aether_Engine_*`)
- **G4**: Unresolved reference (Intent/Context/Activity/Bundle/File/Application)
- **G5**: Workflow bash syntax (shellcheck warning)
- **G6**: GitHub release state (verify `apk-v*` tag exists)
- **G7**: APK integrity (`/tmp/newapk/app-release.apk`)

**No dependencies** — works in any sandbox.

## Layer 2: Local Kotlin/Gradle test — **LOCAL, ~30 sec (with Android SDK)**

```bash
bash scripts/test-local.sh
```

Checks (no SDK required):
- **L2.1**: `gradlew` exists + `gradle-wrapper.properties`
- **L2.2**: `settings.gradle.kts` has all 3 modules (`aether-core`, `aether-android`, `aether-native`)
- **L2.3**: Each module has `build.gradle.kts` (recursive scan)
- **L2.4**: Each `AndroidManifest.xml` is valid XML + no `package=` attribute

Checks (require Android SDK + NDK — skip if missing):
- **L2.5**: `./gradlew :aether-core:compileDebugKotlin`
- **L2.6**: `./gradlew :aether-android:compileDebugKotlin`
- **L2.7**: `./gradlew :aether-native:compileDebugKotlin`
- **L2.8**: `./gradlew :aether-core:testDebugUnitTest`

**Setup for L2.5-L2.8:**
```bash
export ANDROID_HOME=/path/to/android-sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

## Layer 3: CI branch test — **GITHUB, 6-8 min**

```bash
# 1. Commit + push to test branch
git add <files>
git commit -m "fix: ..."
bash scripts/test-ci.sh test/fix-typo

# 2. Script will:
#    - Run L1 + L2
#    - Push to origin/test/fix-typo
#    - Poll CI (max 10 min)
#    - If pass, offer to merge to main
#    - If fail, report CI URL

# 3. If merged to main:
#    - Final CI runs on main (6-8 min)
#    - APK released as apk-v* tag
```

## Quick reference

| Layer | Script | Time | Local? | When to use |
|---|---|---|---|---|
| L1 | `scripts/preflight.sh` | < 5s | ✅ | Before every commit |
| L2 | `scripts/test-local.sh` | 5-30s | ✅ | Before push (catches Kotlin errors) |
| L3 | `scripts/test-ci.sh <branch>` | 6-8 min | ❌ (needs GitHub) | Before merge to main |

## Common issues

### Build fail: "package= attribute deprecated"
**Cause:** AGP 8.x removed support for `package=` in AndroidManifest
**Fix:** Remove `package="..."` from `<manifest>` tag + set `namespace` in `build.gradle.kts`

### Build fail: "Symbol not found"
**Cause:** JNI symbol mismatch between Kotlin `external fun` and C++ function
**Fix:** Check G3 (preflight detects mismatch) + update C++ function name

### Build fail: "Kotlin unresolved reference"
**Cause:** Missing import or typo
**Fix:** Check G4 + use Android Studio auto-import

### Build fail: "AGP namespace used in multiple modules"
**Cause:** Two modules have same `namespace = "..."` in build.gradle
**Fix:** Give each module unique namespace

## History

- **T1 (9810791e)**: Initial pipeline (L1 + L2 + L3 scripts + G1.5 deprecated check)
- **9810791e**: Restore preflight.sh after R1 revert
- **4c1ea42b**: Last known good state (Aether's stable build)
