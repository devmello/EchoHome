plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.airplay"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    // libvesper_airplay.so (armeabi-v7a) is prebuilt from rust/ by rust/build.sh and committed under
    // src/main/jniLibs, so building the app needs no Rust toolchain or NDK.
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
