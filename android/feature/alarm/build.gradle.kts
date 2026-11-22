plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.alarm"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Android's org.json is stubbed in JVM unit tests.
    testImplementation(libs.json)
}
