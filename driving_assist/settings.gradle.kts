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

rootProject.name = "DrivingAssist"
include(":app")
// Laptop perception bridge (pure Kotlin/JVM, no Android APIs) and its JVM fake tablet.
// See PERCEPTION_INTEGRATION.md.
include(":perception-bridge")
include(":bridge-cli")
