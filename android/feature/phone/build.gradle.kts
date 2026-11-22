plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.phone"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
