plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

android {
    namespace = "com.aether.aethernative"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        ndkVersion = "26.1.10909125"
        ndk {
            // Match :app (Flutter) — arm64-v8a only
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
            }
        }
        debug {
            externalNativeBuild {
                cmake { arguments += "-DCMAKE_BUILD_TYPE=Debug" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ndkVersion = "26.1.10909125"
}

dependencies {
    // No aether-core dependency — engine is self-contained JNI bridge
}
