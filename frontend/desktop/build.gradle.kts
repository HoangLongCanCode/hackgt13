import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop viewer (JVM, Swing): the tablet's SIM mode on the laptop. It compiles the app's own Android-free sources
// (AR scene, lane arrows, route text, cue rules) straight from ../app/src/main/java, so what it draws and says is what
// the tablet would. Only the Android-bound parts (Compose drawing, ExoPlayer, VoiceBus) are re-done here in Java2D.
plugins {
    id("org.jetbrains.kotlin.jvm")
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

/** The app's files that need no Android SDK (the filter also applies to this module's own src/main/kotlin). */
val appShared = listOf(
    "com/drivingassist/spatialcopilot/ar/**",
    "com/drivingassist/spatialcopilot/nav/**",
    "com/drivingassist/spatialcopilot/voice/CueCatalog.kt",
    "com/drivingassist/spatialcopilot/voice/CuePolicy.kt",
    "com/drivingassist/spatialcopilot/voice/Earcons.kt",
    "com/drivingassist/spatialcopilot/voice/SpokenText.kt",
    "com/drivingassist/spatialcopilot/voice/VoiceArbiter.kt",
)

/** The audio catalog (one source of truth in perception_engine/docs/audio) and the OpenCV frame pipe, as resources. */
val copyDesktopResources by tasks.registering(Copy::class) {
    from(rootProject.file("../perception_engine/docs/audio/audio_cues.v1.json")) { into("audio") }
    from(file("frame_pipe.py")) { into("desktop") }
    into(layout.buildDirectory.dir("generated/desktopResources"))
}

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include(appShared + "com/drivingassist/spatialcopilot/desktop/**")
        }
        resources.srcDir(copyDesktopResources)
    }
}

dependencies {
    implementation(project(":perception-bridge"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.drivingassist.spatialcopilot.desktop.MainKt")
    applicationName = "desktop-sim"
    // A dozen decoded 1280x720 frames plus the scene need well under this; the server shares the machine's memory.
    applicationDefaultJvmArgs = listOf("-Xmx768m")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
