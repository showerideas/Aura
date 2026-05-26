// Phase 7.2 — Wear OS tile module.
// Provides an AuraTileService (Tiles 1.3 API) and WearPhoneBridge
// (WearableListenerService) that runs on the paired Wear OS device.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.showerideas.aura.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.showerideas.aura.wear"
        minSdk = 26          // Wear OS 3.0 (API 30 minimum, but 26 covers compile OK)
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {
    // Wear OS Tiles — composable tile UI
    implementation("androidx.wear.tiles:tiles:1.3.0")
    implementation("androidx.wear.tiles:tiles-material:1.3.0")

    // Wearable Data Layer — ChannelClient for phone↔watch communication
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Coroutines (Guava ListenableFuture bridge for TileService callbacks)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
