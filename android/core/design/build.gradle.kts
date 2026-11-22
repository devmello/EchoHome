plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.core.design"
}

dependencies {
    testImplementation(libs.junit)
}
