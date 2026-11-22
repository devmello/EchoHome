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
    // Claude (Anthropic Messages API, streaming SSE).
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
}
