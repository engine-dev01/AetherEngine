#!/bin/sh
# Aether unit test runner (one-shot, no gradle daemon, no android.jar)
#
# Pipeline:
#   1. Build stub jar (10 Android classes, AOSP-accurate signatures)
#   2. Compile AetherInstrumentation.kt against stubs
#   3. Compile AetherInstrumentationTest.kt against stubs + main
#   4. Run JUnit with classpath: main + test + stubs + junit
#
# Portable: repo-relative paths; kotlinc/junit resolved from env or /tmp.
# Verification gate: stub mismatch -> kotlinc fails (same as android.jar).
#
# Usage:
#   sh aether-android/test-stubs/run-aether-test.sh [project-root]
#   # default project-root = parent of this script's directory

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STUBS_DIR="$SCRIPT_DIR"                      # this dir IS test-stubs/
# repo root = two levels up (aether-android/test-stubs -> repo root)
PROJECT_DIR="${1:-$(dirname "$(dirname "$SCRIPT_DIR")")}"
BUILD_DIR="${AETHER_TEST_BUILD_DIR:-/tmp/aether-build}"

# --- toolchain resolution (portable: local box + CI) ---
if [ -n "$KOTLINC_HOME" ]; then
    KOTLINC="$KOTLINC_HOME/bin/kotlinc"
elif [ -x /tmp/kotlinc/bin/kotlinc ]; then
    KOTLINC=/tmp/kotlinc/bin/kotlinc
elif command -v kotlinc >/dev/null 2>&1; then
    KOTLINC=kotlinc
else
    echo "FAIL: kotlinc not found (set KOTLINC_HOME or add to PATH)"
    exit 1
fi
KOTLIN_STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"

if [ -n "$JUNIT_HOME" ]; then
    JUNIT_DIR="$JUNIT_HOME"
elif [ -d /tmp/junit-libs ]; then
    JUNIT_DIR=/tmp/junit-libs
else
    echo "FAIL: JUnit not found (set JUNIT_HOME or create /tmp/junit-libs with junit.jar + hamcrest.jar)"
    exit 1
fi

cd "$PROJECT_DIR"

echo "=== STEP 1: Build AOSP stubs ==="
sh "$STUBS_DIR/build.sh" > /tmp/stubs-build.log 2>&1 || {
    echo "FAIL: stub build"; cat /tmp/stubs-build.log; exit 1
}
echo "  stubs.jar: $(ls -la "$STUBS_DIR/stubs.jar" | awk '{print $5}') bytes"

echo "=== STEP 2: Compile AetherInstrumentation.kt ==="
rm -rf "$BUILD_DIR" && mkdir -p "$BUILD_DIR/main"
"$KOTLINC" -classpath "$STUBS_DIR/stubs.jar" \
    -d "$BUILD_DIR/main" \
    aether-android/aether-app/src/main/kotlin/com/aether/engine/proxy/AetherInstrumentation.kt \
    > /tmp/main-build.log 2>&1 || {
    echo "FAIL: main compile"; cat /tmp/main-build.log; exit 1
}
echo "  classes: $(ls "$BUILD_DIR/main/com/aether/engine/proxy/" | tr '\n' ' ')"

echo "=== STEP 3: Compile AetherInstrumentationTest.kt ==="
mkdir -p "$BUILD_DIR/test"
"$KOTLINC" -classpath "$STUBS_DIR/stubs.jar:$BUILD_DIR/main:$JUNIT_DIR/junit.jar" \
    -d "$BUILD_DIR/test" \
    aether-android/aether-app/src/test/kotlin/com/aether/engine/proxy/AetherInstrumentationTest.kt \
    > /tmp/test-build.log 2>&1 || {
    echo "FAIL: test compile"; cat /tmp/test-build.log; exit 1
}
echo "  AetherInstrumentationTest.class compiled"

echo "=== STEP 4: Run JUnit ==="
java -cp "$BUILD_DIR/main:$BUILD_DIR/test:$JUNIT_DIR/junit.jar:$JUNIT_DIR/hamcrest.jar:$STUBS_DIR/stubs.jar:$KOTLIN_STDLIB" \
    org.junit.runner.JUnitCore com.aether.engine.proxy.AetherInstrumentationTest
