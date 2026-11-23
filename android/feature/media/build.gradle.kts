plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.media"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // LRCLIB client (already in the app via :feature:home / :feature:photos).
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json for JVM tests (Android's copy is stubbed there).
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
