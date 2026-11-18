plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.orcaslicer.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.orcaslicer.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "2.5.1-android.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    packaging {
        // Native libs are prebuilt by android/scripts/build_core.sh into src/main/jniLibs.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
