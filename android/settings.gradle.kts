pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Vesper"

include(":app")
include(":core:design", ":core:model", ":core:data")

/** One module per feature agent (see PARALLEL_BUILD_PROMPT.md). */
val features = listOf("clock", "system", "phone", "airplay", "spotify", "media", "alarm", "home", "assistant", "camera", "photos", "settings", "lists", "sleep")
features.forEach { include(":feature:$it") }
