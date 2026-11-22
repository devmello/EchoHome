plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.photos"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // Immich REST client (same pattern as :core:data's Home Assistant client).
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json for JVM tests (Android's copy is stubbed there).
    testImplementation(libs.json)
}
