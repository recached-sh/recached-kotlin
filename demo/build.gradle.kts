// The checklist demo: a small app that shows what the SDK promises —
// local reads, durable queued writes, reconnect recovery and live updates.
// scripts/acceptance.py drives it on a device to check all four.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.recached.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.recached.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The server the app connects to unless launched with `--es server <url>`:
        // the emulator's alias for the host machine. On a phone, use
        // `adb reverse tcp:6380 tcp:6380` and launch with ws://127.0.0.1:6380.
        val url = providers.gradleProperty("recached.demoUrl").getOrElse("ws://10.0.2.2:6380")
        buildConfigField("String", "SERVER_URL", "\"$url\"")
        // The ABIs the core ships; see the README.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // Minified, so the demo also checks that the consumer ProGuard rules
        // the SDK ships keep JNA and the bindings working under R8.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":recached"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
}
