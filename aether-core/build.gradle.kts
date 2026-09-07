plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aether"
    compileSdk = 35

    defaultConfig {
 // minSdk 28
        minSdk = 28
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
    // No Flutter dependency needed — pure Android library
    // Engine.kt, SandboxManager.kt use only Android SDK APIs

    testImplementation("junit:junit:4.13.2")
}
