import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sonorapulse.app"
    compileSdk = 36
    // NDK r28+ aligns native libraries to 16 KB pages by default (required by Google Play).
    // Install it in SDK Manager > SDK Tools > NDK, then set the exact version here, e.g.:
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.sonorapulse.app"
        minSdk = 29          // MediaStore.Downloads needs API 29 (Android 10)
        targetSdk = 36       // Required by Google Play since Aug 31, 2026
        // Each cloud build gets a higher versionCode, which Google Play requires for every upload.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // 1.1.x = free app with the one-time Pro unlock.
        versionName = "1.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // Upload key for Google Play. The cloud build decrypts it into a temp file and passes
    // its path and password through environment variables; nothing secret lives in this file.
    val uploadKeystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (uploadKeystore != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Check for newer versions of the BOM and Oboe before release.
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("com.google.oboe:oboe:1.11.0")
    // Google Play Billing for the one-time "pro_unlock" in-app product.
    implementation("com.android.billingclient:billing:8.3.0")
}
