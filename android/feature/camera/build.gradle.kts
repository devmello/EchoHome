plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.feature.camera"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // TFLite runtime only (no AndroidX, no support/metadata libs); :app keeps armeabi-v7a.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
