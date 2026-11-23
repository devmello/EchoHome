plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.spotify"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    // libvesper_spotify.so (armeabi-v7a) is prebuilt from rust/ by rust/build.sh and committed under
    // src/main/jniLibs (like :feature:airplay), so building the app needs no Rust toolchain or NDK.
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
