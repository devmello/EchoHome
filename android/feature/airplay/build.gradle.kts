plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.feature.airplay"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    // libecho_airplay.so (armeabi-v7a) is prebuilt from rust/ by rust/build.sh and committed under
    // src/main/jniLibs, so building the app needs no Rust toolchain or NDK.
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
