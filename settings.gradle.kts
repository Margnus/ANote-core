pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "NoteCore"
include(":canvas-core", ":shared-core")
if (providers.gradleProperty("enableAndroidTargets").orNull == "true") include(":android-app")

if (providers.gradleProperty("enableAndroidTargets").orNull == "true" &&
    providers.gradleProperty("enableSampleHost").orNull == "true") include(":samples:sketch-host")
