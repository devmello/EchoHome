plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

android {
    namespace = "com.echobedside.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    // loadSecrets(): tells "compiled in" secrets from ones set on the page.
    implementation(project(":core:data"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
