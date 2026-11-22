plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.core.design"
}

dependencies {
    testImplementation(libs.junit)
}
