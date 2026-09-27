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

rootProject.name = "SpatialCopilot"
include(":app")

// Pure-JVM bridge to the laptop (PROTOCOL_v2 client, WorldModel, Driving Context) and its fake-tablet CLI.
// They live next to the perception engine they talk to; the app and the CLI share the same code.
include(":perception-bridge")
project(":perception-bridge").projectDir = file("../perception_engine/android/perception-bridge")
include(":bridge-cli")
project(":bridge-cli").projectDir = file("../perception_engine/android/bridge-cli")

// Desktop viewer (JVM, Swing): the tablet's SIM mode on the laptop, built from the app's Android-free sources.
include(":desktop")
