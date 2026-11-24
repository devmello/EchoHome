plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.update"
}

dependencies {
    implementation(project(":core:model"))
    // The release feed and the APK download (HTTPS, redirects to githubusercontent). Already in the APK.
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    // org.json for JVM tests (Android's copy is stubbed there).
    testImplementation(libs.json)
}
