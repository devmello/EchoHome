plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.feature.assistant"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    // End-to-end tests drive the real HomeAssistantClient against a fake HA (MockWebServer).
    testImplementation(project(":core:data"))
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
