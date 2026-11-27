plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livecaption.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.livecaption.app"
        // 29 = Android 10, the first release that lets apps capture other apps' audio.
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        // Phone ABIs only; add "x86_64" to run on an emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Optional: directory holding the NLLB model files, enables NllbModelTest.
            it.systemProperty("nllb.dir", System.getenv("NLLB_DIR") ?: "")
            // Optional: voiceprints exported by the speaker calibration, enables a data test.
            it.systemProperty("speakers.data", System.getenv("SPEAKERS_DATA") ?: "")
            it.systemProperty("voice.data", System.getenv("VOICE_DATA") ?: "")
            it.systemProperty("speakers.sweep", System.getenv("SPEAKERS_SWEEP") ?: "")
            it.maxHeapSize = "3g"
            // Screenshots of the subtitle UI need Robolectric's Android runtime (a large
            // download), so they only run with -Pscreenshots.
            if (project.hasProperty("screenshots")) {
                it.systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.path)
            } else {
                it.exclude("**/*ScreenshotTest*")
            }
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Offline streaming speech recognition.
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Offline translation.
    implementation("com.google.mlkit:translate:17.0.3")

    // Offline Khmer translation (NLLB-200 on ONNX Runtime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    implementation("com.google.code.gson:gson:2.13.2")

    // WebSocket client for Gemini Live Translate.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop ONNX Runtime, for running the real model in NllbModelTest.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.32.0")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}
