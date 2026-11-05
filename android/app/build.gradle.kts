plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.orgwritenotes"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.orgwritenotes"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cFlags += "-O3 -DNDEBUG -ffast-math -fno-math-errno"
                cppFlags += "-std=c++17 -O3 -DNDEBUG -ffast-math -fno-math-errno"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the debug key so it installs over debug builds; swap for a real keystore to ship.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Exclude linker script text files packaged as .so
            excludes += "**/libc++.so"
            // Onyx SDK and mmkv each bundle libc++_shared.so
            pickFirsts += "**/libc++_shared.so"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // Onyx Boox pen/EPD SDK; only touched via reflection when OnyxEInkBridge.isEInkDevice is true.
    implementation("com.onyx.android.sdk:onyxsdk-device:1.2.30")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.4.11")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // QR scanner for the Emacs pairing payload (camera + ZXing decoder).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}

