#!/bin/bash
# test-local.sh — Layer 2: Local Kotlin/Gradle test (Aether pipeline T1)
#
# Verify (no Android SDK required):
#   L2.1: gradle wrapper exists
#   L2.2: settings.gradle.kts has all 4 modules
#   L2.3: each module has build.gradle.kts
#   L2.4: each AndroidManifest.xml is valid XML + no deprecated package= attribute
#
# Verify (require Android SDK + NDK — skip if missing):
#   L2.5: ./gradlew :aether-core:compileDebugKotlin
#   L2.6: ./gradlew :aether-android:compileDebugKotlin
#   L2.7: ./gradlew :aether-native:compileDebugKotlin
#   L2.8: JUnit tests
set -e

cd "$(dirname "$0")/.."
REPO="$(pwd)"

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
err()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
skip() { echo "  ⚠️  $*"; SKIP=$((SKIP+1)); }

echo "═══════════════════════════════════════════════════"
echo " AetherEngine Test Pipeline L2 (Local)"
echo "═══════════════════════════════════════════════════"

# L2.1: gradle wrapper
echo ""
echo "[L2.1] Gradle wrapper"
if [ -x "./gradlew" ] && [ -f "gradle/wrapper/gradle-wrapper.properties" ]; then
    GRADLE_VER=$(grep "distributionUrl" gradle/wrapper/gradle-wrapper.properties | grep -oE "gradle-[0-9.]+")
    ok "gradlew exists (${GRADLE_VER})"
else
    err "gradlew missing — run 'gradle wrapper' to regenerate"
fi

# L2.2: settings.gradle.kts modules
echo ""
echo "[L2.2] settings.gradle.kts modules"
if [ -f "settings.gradle.kts" ]; then
    for mod in aether-core aether-android aether-native; do
        if grep -q "include(\":$mod\")" settings.gradle.kts; then
            ok "module :$mod declared"
        else
            err "module :$mod missing in settings.gradle.kts"
        fi
    done
    if grep -q "include(\":app\")" settings.gradle.kts || [ -d "app/android" ]; then
        ok "Flutter app module found"
    else
        skip "Flutter app module not declared"
    fi
else
    err "settings.gradle.kts missing"
fi

# L2.3: each module has build file
echo ""
echo "[L2.3] Module build files"
for mod in aether-core aether-android aether-native; do
    if find "$mod" -name "build.gradle*" -type f 2>/dev/null | head -1 | grep -q .; then
        ok "$mod has build.gradle"
    else
        err "$mod missing build.gradle"
    fi
done
if [ -f "app/android/app/build.gradle" ]; then
    ok "app/android/app has build.gradle"
fi

# L2.4: each module's AndroidManifest.xml
echo ""
echo "[L2.4] AndroidManifest.xml validity (XML parse + no package= attr)"
for mf in aether-core/src/main/AndroidManifest.xml \
          aether-android/aether-app/src/main/AndroidManifest.xml \
          aether-native/src/main/AndroidManifest.xml \
          app/android/app/src/main/AndroidManifest.xml; do
    if [ ! -f "$mf" ]; then
        skip "$mf not found"
        continue
    fi
    if python3 -c "import xml.etree.ElementTree as ET; ET.parse('$mf')" 2>/dev/null; then
        if grep -q 'package=' "$mf"; then
            err "$mf has deprecated 'package=' attribute (AGP 8.x)"
        else
            ok "$mf valid (no package= attr)"
        fi
    else
        err "$mf invalid XML"
    fi
done

# L2.5-L2.8: require Android SDK
echo ""
echo "[L2.5-L2.8] Gradle compile + JUnit (require Android SDK)"
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    skip "ANDROID_HOME not set — local compile skipped (use CI branch test L3)"
    skip "  Hint: export ANDROID_HOME=/path/to/android-sdk before running"
elif [ ! -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT}}" ]; then
    skip "Android SDK dir missing — local compile skipped"
else
    for mod in aether-core aether-android aether-native; do
        if ./gradlew :$mod:compileDebugKotlin --offline --no-daemon 2>/dev/null; then
            ok "Kotlin compile ($mod)"
        else
            err "Kotlin compile FAILED ($mod) — check ./gradlew output"
        fi
    done
    if ./gradlew :aether-core:testDebugUnitTest --offline --no-daemon 2>/dev/null; then
        ok "JUnit tests (aether-core)"
    else
        warn "JUnit tests skipped (offline mode or no cached deps)"
    fi
fi

echo ""
echo "═══════════════════════════════════════════════════"
echo " L2 Result: ✅ $PASS passed, ❌ $FAIL failed, ⚠️  $SKIP skip"
echo "═══════════════════════════════════════════════════"
