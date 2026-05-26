// Phase 7.3 — Android Auto / Automotive OS module.
// Provides an AuraCarAppService with four screens: Idle, Advertising, SAS, Completed.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.showerideas.aura.auto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.showerideas.aura.auto"
        minSdk = 26
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

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Car App Library — Android Auto + Automotive OS
    implementation("androidx.car.app:app:1.4.0")

    // Wearable Data Layer for phone state sync (same bridge pattern as :wearos)
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
