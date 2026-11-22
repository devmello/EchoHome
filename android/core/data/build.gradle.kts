import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}
apply(from = rootProject.file("gradle/echo-library.gradle"))

/**
 * Secrets come from android/secrets.properties (git-ignored), e.g.
 *   ha.url=http://homeassistant.local:8123
 *   ha.token=...
 * Missing keys become "" and the app falls back to demo data (debug builds) or empty states.
 */
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String) = "\"" + (secrets.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.echobedside.core.data"
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "HA_URL", secret("ha.url"))
        buildConfigField("String", "HA_TOKEN", secret("ha.token"))
        buildConfigField("String", "IMMICH_URL", secret("immich.url"))
        buildConfigField("String", "IMMICH_API_KEY", secret("immich.apiKey"))
        buildConfigField("String", "ANTHROPIC_API_KEY", secret("anthropic.apiKey"))
        buildConfigField("String", "STOCKS_API_KEY", secret("stocks.apiKey"))
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
