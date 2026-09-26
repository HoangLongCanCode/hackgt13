import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fake tablet (JVM): drives the SAME PerceptionBridge as the Android app (live JPEG uplink / sim playback / watch).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":perception-bridge"))
}

application {
    mainClass.set("com.ksr.copilot.cli.MainKt")
    applicationName = "bridge-cli"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
