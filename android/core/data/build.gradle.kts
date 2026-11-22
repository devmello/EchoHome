import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/vesper-library.gradle"))

/**
 * Secrets come from android/secrets.properties (git-ignored; see secrets.properties.example).
 * All optional. Missing keys become "" and the app falls back to demo data (debug builds) or
 * empty states.
 */
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String) = "\"" + (secrets.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.vesper.core.data"
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "HA_URL", secret("ha.url"))
        buildConfigField("String", "HA_TOKEN", secret("ha.token"))
        buildConfigField("String", "IMMICH_URL", secret("immich.url"))
        buildConfigField("String", "IMMICH_API_KEY", secret("immich.apiKey"))
        buildConfigField("String", "ANTHROPIC_API_KEY", secret("anthropic.apiKey"))
        buildConfigField("String", "STOCKS_API_KEY", secret("stocks.apiKey"))
        buildConfigField("String", "CALENDAR_ICS_URLS", secret("calendar.icsUrls"))
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
}
