plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.radio"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // Radio Browser API, station icons and the ICY metadata proxy. Already in the APK (:core:data).
    // Playback is the platform MediaPlayer: no new dependency.
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json for JVM tests (Android's copy is stubbed there).
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
