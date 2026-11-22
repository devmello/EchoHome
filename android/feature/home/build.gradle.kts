plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.home"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // HTTP for Open-Meteo, iCal, Nominatim, OSRM and Yahoo. Already in the APK through :core:data, so it adds nothing.
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    // The real HomeAssistantClient, for end-to-end tests against a MockWebServer "Home Assistant".
    testImplementation(project(":core:data"))
}
