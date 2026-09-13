#!/bin/sh
# Build AOSP-accurate Android stubs jar for localTest
#
# Portable version: works on local Alpine (PRoot) and GitHub Actions CI.
#   - kotlinc: taken from $KOTLINC_HOME if set, else /tmp/kotlinc (dev box),
#              else PATH lookup (CI installs kotlin-compiler via sdkman/choco)
#   - output:  stubs.jar in this directory
#
# Verification gate: a stub field/method mismatch makes kotlinc fail
# immediately (same as real android.jar). Catches "stub typo that the
# real API doesn't have" — the failure mode that bit CI run 34224068389.

set -e
cd "$(dirname "$0")"

# Locate kotlinc
if [ -n "$KOTLINC_HOME" ]; then
    KOTLINC="$KOTLINC_HOME/bin/kotlinc"
elif [ -x /tmp/kotlinc/bin/kotlinc ]; then
    KOTLINC=/tmp/kotlinc/bin/kotlinc
elif command -v kotlinc >/dev/null 2>&1; then
    KOTLINC=kotlinc
else
    echo "FAIL: kotlinc not found (set KOTLINC_HOME or install kotlin-compiler)"
    exit 1
fi

rm -rf kotlin-out java-out stubs.jar
mkdir -p kotlin-out java-out

# Step 1: Java stubs first (PackageManager family needs javac)
javac -d java-out \
    android/content/pm/PackageInfo.java \
    android/content/pm/ActivityInfo.java \
    android/content/pm/PackageManager.java \
    android/content/pm/ApplicationInfo.java
echo "STEP1 OK (javac)"

# Step 2: Kotlin stubs, with Java classes on classpath
"$KOTLINC" -classpath "java-out" \
    android/content/Intent.kt \
    android/content/Context.kt \
    android/content/ComponentName.kt \
    android/content/res/AssetManager.kt \
    android/content/res/Resources.kt \
    android/content/res/Configuration.kt \
    android/util/DisplayMetrics.kt \
    android/app/Activity.kt \
    android/app/ActivityThread.kt \
    android/app/Application.kt \
    android/app/Instrumentation.kt \
    android/os/IBinder.kt \
    android/os/Bundle.kt \
    com/aether/engine/proxy/DiagLog.kt \
    com/aether/Engine.kt \
    -d kotlin-out
echo "STEP2 OK (kotlinc)"

# Step 3: merge all classes into one jar
cd kotlin-out && jar cf ../stubs.jar . && cd ..
cd java-out && jar uf ../stubs.jar . && cd ..

echo "DONE: stubs.jar"
ls -la stubs.jar
