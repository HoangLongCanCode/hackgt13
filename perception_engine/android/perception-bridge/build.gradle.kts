import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no Android APIs, so the same PerceptionBridge runs in :app, :bridge-cli and the tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}

description = "PerceptionBridge (PROTOCOL_v2): WebSocket to the laptop, live uplink with credits, sim pts sync, " +
    "WorldModel + DrivingContext, phase1 navigation packets."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compile against the JDK 17 API even when Gradle runs on JDK 21 (keeps the jar Android-safe).
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    // Golden samples written by the Python engine: perception_engine/contracts/samples[/v2] (this module is
    // perception_engine/android/perception-bridge, so ../../contracts). The tests read contracts/schemas too.
    val contracts = file("../../contracts")
    val contractSamples = File(contracts, "samples")
    systemProperty("contracts.samples.dir", contractSamples.absolutePath)
    inputs.dir(contracts).withPropertyName("contracts").withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        // A wrong path would silently turn the sample-decoding tests into assumeTrue skips: fail instead.
        check(File(contractSamples, "v2").isDirectory) { "contract samples not found: $contractSamples/v2" }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
