plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aether"
    compileSdk = 35

    defaultConfig {
 // minSdk 28
        // process_vm_readv ต้องการ API 26+; Shizuku ต้องการ 23+)
        minSdk = 28
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // aether-core = Engine + SandboxManager + RemoteConfig
    implementation(project(":aether-core"))
    // native engine (libaether.so) — zero-trace memory + physics
    implementation(project(":aether-native"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("junit:junit:4.13.2")
}

// =====================================================================
// localTest — fast local unit test that uses AOSP-signature stubs
// (skips Android SDK + Robolectric, runs in < 5s vs 6min assembleDebug)
//
// Stub jar location: /tmp/aether-stubs/stubs.jar
// Stub sources:     /tmp/aether-stubs/{android,com}/
// Stub rebuild:     sh /tmp/aether-stubs/build.sh
//
// CI workflow can replace `./gradlew :aether-app:testDebugUnitTest`
// with `./gradlew :aether-app:localTest` for pure-Kotlin unit tests.
// =====================================================================
tasks.register<Exec>("localTest") {
    description = "Run pure-Kotlin unit tests with AOSP-signature stubs (no Android SDK)"
    group = "verification"

    // Run the script that wires stubs + main + test + JUnit
    commandLine = listOf("sh", "/tmp/run-aether-test.sh")

    // Fail the build if any test fails (script exits non-zero)
    isIgnoreExitValue = false
}

