plugins {
    id("com.android.application")
}

android {
    namespace = "org.mozkey.inputmethod"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.mozkey.inputmethod"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // 64-bit only: arm64 for devices, x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // One self-contained libmozkey_zenz.so; libmozc.so carries its
                // own C++ runtime and the two only talk through a C ABI.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so that the CI artifact can be installed
            // directly. Replace with your own signing config before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The dictionary and the Zenz model are copied out of the APK once;
        // storing them uncompressed keeps that copy fast.
        noCompress += listOf("data", "gguf")
    }

    packaging {
        jniLibs {
            // Load native libraries straight from the APK (page-aligned, 16 KB safe).
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Must match the protoc used by scripts/prepare_native.sh and Mozkey's
    // src/MODULE.bazel (protobuf 34.1).
    implementation("com.google.protobuf:protobuf-javalite:4.34.1")
}
