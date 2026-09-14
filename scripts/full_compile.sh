#!/bin/sh
# full_compile.sh — compile :aether-core + :aether-android ด้วย android.jar จริง
# (เทียบเท่า ./gradlew :aether-core:compileDebugKotlin :aether-android:compileDebugKotlin
#  บน CI — แต่รันในเครื่อง ไม่ต้องเสียรอบ CI ต่อ catch ของ compiler)
#
# บทเรียน: 4888653 fail ที่ CI เพราะ 'component' (API ของ Intent) ถูกใช้ใน Activity
# — stub-based localTest จับไม่ได้เพราะ stub มีเฉพาะ method ที่ประกาศไว้
# วิธีนี้ใช้ android.jar 35 ตัวจริง => Unresolved reference = เจอแน่นอน
#
# ต้องมี: kotlinc 1.9.x, android.jar, androidx-core classes.jar
# ใช้:   KOTLINC_HOME=... sh scripts/full_compile.sh
set -e
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC_HOME:-$HOME/.cache/aether-test/kotlinc}/bin/kotlinc"
[ -x "$KOTLINC" ] || { echo "FAIL: kotlinc not found (set KOTLINC_HOME)"; exit 1; }

AJAR="${ANDROID_JAR:-/tmp/android-35.jar}"
CX="${ANDROIDX_CORE:-/tmp/androidxcore/classes.jar}"
KTX="${ANDROIDX_COREKTX:-/tmp/androidxktx/classes.jar}"
[ -f "$AJAR" ] || { echo "FAIL: android.jar missing at $AJAR"; exit 1; }
[ -f "$CX" ]   || { echo "FAIL: androidx-core classes.jar missing at $CX"; exit 1; }
[ -f "$KTX" ]  || { echo "FAIL: androidx core-ktx classes.jar missing at $KTX"; exit 1; }
CP="$AJAR:$CX:$KTX"

OUT=/tmp/full-compile; rm -rf "$OUT"; mkdir -p "$OUT/core" "$OUT/android"

echo "=== [1/2] :aether-core (kotlin, android.jar $AJAR) ==="
find aether-core/src/main/kotlin -name '*.kt' > "$OUT/core.list"
"$KOTLINC" -classpath "$CP" -d "$OUT/core" @"$OUT/core.list" 2>&1 \
  | grep -E "^.*error:" | tee "$OUT/core.err" || true
[ -s "$OUT/core.err" ] && { echo "CORE COMPILE FAILED"; exit 1; }
echo "  core OK ($(wc -l < "$OUT/core.list") files)"

echo "=== [2/2] :aether-android/aether-app (kotlin, core on cp) ==="
find aether-android/aether-app/src/main/kotlin -name '*.kt' > "$OUT/app.list"
"$KOTLINC" -classpath "$CP:$OUT/core" -d "$OUT/android" @"$OUT/app.list" 2>&1 \
  | grep -E "^.*error:" | tee "$OUT/app.err" || true
[ -s "$OUT/app.err" ] && { echo "APP COMPILE FAILED"; exit 1; }
echo "  app OK ($(wc -l < "$OUT/app.list") files)"

echo "=== [3/3] app/android — compile จริงด้วย flutter shape-stubs (javac + kotlinc) ==="
mkdir -p "$OUT/fstub"
find scripts/flutter-stub-src -name '*.java' > "$OUT/fstub.list"
javac -nowarn -classpath "$AJAR" -d "$OUT/fstub" @"$OUT/fstub.list" 2> "$OUT/fstub.err" || {
    echo "FLUTTER STUB JAVAC FAILED"; head -10 "$OUT/fstub.err"; exit 1; }
find app/android/app/src/main/kotlin -name '*.kt' > "$OUT/appf.list"
"$KOTLINC" -classpath "$CP:$OUT/core:$OUT/android:$OUT/fstub" -d "$OUT/appf" @"$OUT/appf.list" 2>&1 \
  | grep -E "error:" > "$OUT/appf.err" || true
if [ -s "$OUT/appf.err" ]; then
    echo "APP FLUTTER COMPILE FAILED"; head -15 "$OUT/appf.err"; exit 1
fi
echo "  app/flutter compiled OK ($(wc -l < "$OUT/appf.list") files; type-checked vs stubs)"

echo "PASS: 3 modules compile against real android.jar 35 + flutter stubs"
