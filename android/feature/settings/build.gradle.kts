plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

android {
    namespace = "app.vesper.feature.settings"
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
