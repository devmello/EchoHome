plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.vesper"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vesper"
        // The Echo Show 5 (checkers) runs LineageOS 18.1 = Android 11 = API 30.
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
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
    listOf("clock", "system", "phone", "airplay", "spotify", "media", "alarm", "home", "assistant", "camera", "photos", "settings", "lists", "radio")
        .forEach { implementation(project(":feature:$it")) }
}
