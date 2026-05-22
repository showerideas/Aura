plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

android {
    namespace = "com.showerideas.aura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.showerideas.aura"
        minSdk = 26           // BLE + Nearby Connections baseline
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // PR-04: Export Room schemas so future migrations can be tested
        // against the historical schema files. The schemas directory is
        // committed to source control.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    // Make the schemas directory available to androidTest for MigrationTestHelper.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    // PR-38 (translation stubs): we ship curated values-XX/ bundles that
    // intentionally translate only the highest-impact UI surface; all
    // other keys fall back to values/ (English) via Android's normal
    // resource resolution. This is a *deliberate* coverage decision —
    // disable the MissingTranslation lint check so CI doesn't reject
    // every key that isn't yet translated in all 7 bundles. ExtraTranslation
    // and InvalidTranslation are kept ON to catch real mistakes.
    lint {
        disable += setOf("MissingTranslation")
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    // PR-22: release signing. The keystore itself is never committed —
    // both its file path and the three secret values are read from
    // environment variables. CI (.github/workflows/ci.yml) leaves them
    // blank, so assembleRelease still runs (the resulting APK is
    // unsigned and used only for compile-time validation). Real release
    // builds set these in the Play publishing pipeline.
    signingConfigs {
        create("release") {
            // Read each value defensively. System.getenv returns the literal
            // empty string when an env var is set-but-empty (which is exactly
            // what CI does); the Kotlin '?:' operator only fires on null, so
            // a naive `env ?: ""` would still feed file("") to Gradle and
            // throw 'path may not be null or empty string'.
            // takeIf { it.isNotBlank() } collapses null AND empty into null,
            // which then becomes the fallback path or null storeFile.
            val storePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            if (storePath != null) {
                storeFile = file(storePath)
            }
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD").orEmpty()
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS").orEmpty()
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD").orEmpty()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
            // Wire the env-driven signing config only when KEYSTORE_PATH was
            // actually provided. In CI the env var is intentionally empty
            // (we just want to validate that assembleRelease runs through
            // R8 + resource shrinking); leaving signingConfig unset produces
            // an unsigned release APK, which is what we want for validation.
            // Real publishing builds set KEYSTORE_PATH to the real path and
            // pick up the signing config automatically.
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Google Nearby Connections
    implementation(libs.play.services.nearby)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // ViewPager2 for onboarding (PR-05)
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // ZXing for QR fallback exchange (PR-08)
    implementation(libs.zxing.android.embedded)

    // Biometric
    implementation(libs.androidx.biometric)

    // Security / Crypto
    implementation(libs.androidx.security.crypto)

    // Gson
    implementation(libs.gson)

    // Timber
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
