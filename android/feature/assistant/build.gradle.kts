plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.assistant"
    // sherpa-onnx JNI reads its Kotlin classes, fields and callbacks by name: keep them in release.
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
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
