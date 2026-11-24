plugins {
    alias(libs.plugins.android.application)
}

/**
 * Versions (docs/status/update.md). versionCode only grows: it is the commit count of HEAD, so a
 * build of a later commit on main is newer, and the updater on the Echo compares it with the
 * release's. `scripts/echo-release.sh` passes both values; `scripts/echo-system-install.sh` builds
 * the /system fallback copy with `-PvesperVersionCode=1` so every update outranks it. No git: 1.
 */
val versionBase = "0.2"
val vesperVersionCode: Int = (findProperty("vesperVersionCode") as String?)?.toInt()
    ?: runCatching {
        providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)
val vesperVersionName: String = (findProperty("vesperVersionName") as String?) ?: "$versionBase.$vesperVersionCode-dev"

android {
    namespace = "app.vesper"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vesper"
        // The Echo Show 5 (checkers) runs LineageOS 18.1 = Android 11 = API 30.
        minSdk = 30
        targetSdk = 30
        versionCode = vesperVersionCode
        versionName = vesperVersionName
        // 32-bit only device. Native libraries (AirPlay) are built for armeabi-v7a only.
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded to one device: sign release with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Keep fonts and TFLite models uncompressed in the APK so they're mmapped, not copied to the heap.
    androidResources { noCompress += listOf("ttf", "tflite") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 30 is deliberate (it's the device's API level), not a Play Store app.
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":core:data"))
    listOf("clock", "system", "phone", "airplay", "spotify", "media", "alarm", "home", "assistant", "camera", "photos", "settings", "lists", "sleep", "radio", "update")
        .forEach { implementation(project(":feature:$it")) }
}
