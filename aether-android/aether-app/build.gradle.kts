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
}
