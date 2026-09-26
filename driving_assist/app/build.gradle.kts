plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.drivingassist.glass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drivingassist.glass"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Perception bridge defaults (PERCEPTION_INTEGRATION.md). Override per build with
        // -Pksr.source=LIVE -Pksr.url=ws://... or per launch with intent extras (ksr.source, ksr.url, ...).
        fun ksr(name: String, default: String) = (project.findProperty(name) as String?) ?: default
        buildConfigField("String", "KSR_SOURCE", "\"${ksr("ksr.source", "MOCK")}\"")
        buildConfigField("String", "KSR_SERVER_URL", "\"${ksr("ksr.url", "ws://127.0.0.1:8765/perception")}\"")
        buildConfigField("String", "KSR_SIM_VIDEO_ID", "\"${ksr("ksr.simVideoId", "b1ff4656-0435391e")}\"")
        buildConfigField("double", "KSR_MOUNT_HEIGHT_M", ksr("ksr.mountHeight", "1.25"))
        buildConfigField("boolean", "KSR_NAV_ENABLED", ksr("ksr.nav", "true"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Perception bridge to the laptop (pure-JVM module) + SIM mode video player.
    implementation(project(":perception-bridge"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
}
