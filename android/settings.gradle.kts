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
rootProject.name = "EchoBedside"

include(":app")
include(":core:design", ":core:model", ":core:data")

/** One module per feature agent (see PARALLEL_BUILD_PROMPT.md). */
val features = listOf("clock", "system", "airplay", "media", "alarm", "home", "assistant", "camera", "photos")
features.forEach { include(":feature:$it") }
