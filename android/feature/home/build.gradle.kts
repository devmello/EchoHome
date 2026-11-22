plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.feature.home"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
