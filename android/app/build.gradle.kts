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

    signingConfigs {
        // Shared debug key (standard "android" passwords, not a secret) so CI and local builds
        // install over each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    implementation("androidx.compose.material:material-icons-extended")
}

// Slicer resources liborca_core reads at runtime (system profiles, printer info), zipped from the
// repo's resources/ tree; AppState unzips it once per install.
val zipOrcaResources by tasks.registering(Zip::class) {
    from(rootProject.file("../resources")) { include("info/**", "profiles/**", "handy_models/**", "calib/**", "data/hints.ini") }
    // The desktop's translations, read by I18n.kt.
    from(rootProject.file("../localization/i18n")) { include("*/OrcaSlicer_*.po"); into("i18n") }
    archiveFileName.set("orca-resources.zip")
    destinationDirectory.set(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(zipOrcaResources) }
