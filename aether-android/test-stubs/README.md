# test-stubs — AOSP-signature Android stubs for fast unit tests

ใช้แทน `android.jar` (26MB) สำหรับรัน unit test แบบ pure-Kotlin โดยไม่ต้อง Android SDK / Robolectric — **~5 วินาที** แทน `assembleDebug` 6 นาที

## ทำไมต้อง stub แบบนี้

เราเคยเจอ CI run `34224068389` พังเพราะ stub ad-hoc เขียนเร็ว ๆ (เพิ่ม field เอง เช่น `packageManager` ใน Application) ทำให้ test "pass" ใน local แต่พังใน CI เพราะ signature ไม่ตรง AOSP จริง

**กฎ**: stub ทุกตัวต้อง signature ตรง AOSP main branch (verify ผ่าน `android.googlesource.com`) — kotlinc จะ fail ทันทีถ้า production เรียก API ที่ stub ไม่มี (verification gate ในตัว เหมือน android.jar)

## วิธีใช้

```sh
# วิธี 1: ผ่าน gradle task (แนะนำ — fail build อัตโนมัติเมื่อ test fail)
./gradlew :aether-android:localTest

# วิธี 2: รัน script ตรง ๆ
sh aether-android/test-stubs/run-aether-test.sh
```

**ต้องมีบนเครื่อง**:
- `kotlinc` — ที่ `/tmp/kotlinc` หรือ `KOTLINC_HOME` env หรือ PATH
- JUnit 4 — ที่ `/tmp/junit-libs/{junit,hamcrest}.jar` หรือ `JUNIT_HOME` env

## โครงสร้าง

```
test-stubs/
├── android/app/          Activity, ActivityThread, Application, Instrumentation
├── android/content/      Intent, ComponentName, Context
├── android/content/pm/   PackageInfo, PackageManager, ActivityInfo (Java)
├── android/os/           Bundle, IBinder
├── com/aether/engine/proxy/  DiagLog no-op stub (แทน production DiagLog
│                             ที่พึ่ง android.os.Process/Log หนักเกินสำหรับ stub)
├── build.sh              สร้าง stubs.jar (javac pm/*.java → kotlinc → merge)
└── run-aether-test.sh    pipeline 4 ขั้น: stubs → main → test → JUnit
```

## เพิ่ม stub ใหม่เมื่อ production ใช้ Android API ใหม่

1. หา class ที่ production เรียก: `grep -oE "android\.[a-z]+\.[A-Z][a-zA-Z]+" <ไฟล์ .kt> | sort -u`
2. เช็ค signature จริงจาก AOSP:
   ```sh
   curl -sL 'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/<path>/<Class>.java?format=TEXT' | base64 -d
   ```
3. เขียน stub ให้ตรง signature (field/method/nullable ต้องเหมือน) — base class ที่ถูก extend ต้อง `open`
4. เพิ่มไฟล์ใน `build.sh` (Kotlin → step 2, Java → step 1)
5. `sh aether-android/test-stubs/run-aether-test.sh` — ถ้า signature ไม่ตรง kotlinc จะ fail ทันที

## ข้อจำกัด (โปรดระวัง)

- stub เป็น **compile-time only** — ถ้า test สร้าง instance จริง (เช่น `Intent()`) method ที่เรียกจะ throw/return null เพราะเป็น no-op
- ใช้ได้กับ logic test (เช่น `buildStubIntent`) ไม่ใช่ framework behavior test (นั่นต้อง Robolectric/emulator)
- `aether-native` (NDK) และ AndroidManifest ไม่เกี่ยวข้อง — pipeline นี้แตะแค่ `.kt` ไฟล์เดียว

## CI integration

GitHub Actions: แทนที่ step assemble 6 นาทีด้วย:

```yaml
- name: Fast unit test (AOSP stubs)
  run: ./gradlew :aether-android:localTest
  env:
    KOTLINC_HOME: ${{ github.workspace }}/kotlinc   # ถ้า CI ติดตั้ง kotlin-compiler ไว้ที่นี่
    JUNIT_HOME: ${{ github.workspace }}/junit-libs
```

(build.sh และ run-aether-test.sh resolve kotlinc/junit จาก env ก่อน แล้วค่อย fallback /tmp)
