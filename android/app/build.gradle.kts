plugins {
    id("com.android.application")
}

android {
    namespace = "com.echobedside"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echobedside"
        // The Echo Show 5 (checkers) runs LineageOS 18.1 = Android 11 = API 30.
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
        // 32-bit only device. No native code yet; this keeps future .so files armv7-only.
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded to one device: sign release with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Keep fonts uncompressed in the APK so they're mmapped, not copied to the heap.
    androidResources { noCompress += "ttf" }

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
