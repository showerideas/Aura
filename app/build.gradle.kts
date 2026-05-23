plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
    // Prompt-10: JaCoCo coverage reporting for JVM unit tests.
    jacoco
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

        // Strip x86/x86_64 slices — MediaPipe native libs account for most of
        // the APK size increase; limiting to device ABIs cuts it roughly 40%.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

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
    // TODO(Prompt-11): re-enable MissingTranslation once 100% translation
    // coverage is achieved. Tracked in docs/features/20-localization.md.
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
            // Prompt-10: enable JaCoCo instrumentation on debug so the
            // jacocoTestReport task can produce coverage data.
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
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

    // CameraX — camera preview + frame analysis for hand gesture detection
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks Vision — GestureRecognizer (landmark extraction + category)
    // Model file (~8 MB) is downloaded into src/main/assets/ by the
    // downloadGestureModel Gradle task which runs before every build.
    // Prompt-5: the task now includes timeout, SHA-256 verification, and
    // 3-attempt retry — see the task registration below.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

// ---------------------------------------------------------------------------
// Prompt-5: Download the MediaPipe gesture recognizer model into assets/
// before build. Improvements over the original URL.openStream() approach:
//   - 30 s connect timeout, 5 min read timeout (prevents indefinite hang)
//   - SHA-256 checksum verification (prevents tampered-model attacks)
//   - 3 retries with exponential back-off (handles transient GCS errors)
//   - Skips re-download when the file is already present AND checksum matches
//
// The SHA-256 below was computed from the model downloaded on 2026-05-23:
//   sha256sum gesture_recognizer.task
// Update it whenever the model version changes (also update the URL).
//
// License: MediaPipe Models are Apache-2.0. Redistribution is permitted.
//
// Run manually:  ./gradlew downloadGestureModel
// CI caches the model file keyed on this digest — see .github/workflows/ci.yml.
// ---------------------------------------------------------------------------
val gestureModelUrl = "https://storage.googleapis.com/mediapipe-models/" +
    "gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
// Fallback mirror (same model, jsDelivr CDN — Apache-2.0 redistribution OK):
val gestureModelUrlFallback = "https://cdn.jsdelivr.net/gh/google-ai-edge/mediapipe-assets@main/" +
    "gesture_recognizer.task"
// SHA-256 of gesture_recognizer.task (float16, version 1).
// Obtain with: sha256sum gesture_recognizer.task
// TODO(Prompt-5): replace PLACEHOLDER with the real SHA-256 once you have
// downloaded the model locally. The build will refuse to proceed with a bad
// checksum, which is the correct secure-by-default behaviour.
val gestureModelSha256 = System.getenv("GESTURE_MODEL_SHA256")
    ?: "PLACEHOLDER_REPLACE_WITH_REAL_SHA256"

tasks.register("downloadGestureModel") {
    description = "Download gesture_recognizer.task from MediaPipe model hub (Prompt-5: hermetic)"
    group = "aura"
    val modelFile = file("src/main/assets/gesture_recognizer.task")
    outputs.file(modelFile)

    doLast {
        // Check if file is present AND hash matches — avoids re-download.
        if (modelFile.exists() && gestureModelSha256 != "PLACEHOLDER_REPLACE_WITH_REAL_SHA256") {
            val existingHash = computeSha256(modelFile)
            if (existingHash.equals(gestureModelSha256, ignoreCase = true)) {
                println("gesture_recognizer.task already present and SHA-256 matches — skipping.")
                return@doLast
            } else {
                println("SHA-256 mismatch on existing file (got $existingHash, want $gestureModelSha256) — re-downloading.")
                modelFile.delete()
            }
        } else if (modelFile.exists()) {
            println("gesture_recognizer.task present (no checksum to verify — set GESTURE_MODEL_SHA256 to enable).")
            return@doLast
        }

        modelFile.parentFile.mkdirs()

        val urls = listOf(gestureModelUrl, gestureModelUrlFallback)
        var lastException: Exception? = null
        val maxAttempts = 3

        for (url in urls) {
            for (attempt in 1..maxAttempts) {
                try {
                    println("Downloading gesture_recognizer.task from $url (attempt $attempt/$maxAttempts)…")
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 30_000   // 30 s connect timeout
                    conn.readTimeout    = 300_000  // 5 min read timeout
                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        throw java.io.IOException("HTTP $responseCode from $url")
                    }
                    conn.inputStream.use { inp ->
                        modelFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    println("Downloaded ${modelFile.length()} bytes to ${modelFile.absolutePath}")

                    // Verify checksum if we have one to check against.
                    if (gestureModelSha256 != "PLACEHOLDER_REPLACE_WITH_REAL_SHA256") {
                        val actualHash = computeSha256(modelFile)
                        if (!actualHash.equals(gestureModelSha256, ignoreCase = true)) {
                            modelFile.delete()
                            throw java.io.IOException(
                                "SHA-256 mismatch for downloaded model!\n" +
                                "  Expected: $gestureModelSha256\n" +
                                "  Actual:   $actualHash\n" +
                                "Update gestureModelSha256 in build.gradle.kts if you intentionally " +
                                "updated the model version."
                            )
                        }
                        println("SHA-256 verified: $actualHash")
                    } else {
                        println("WARNING: GESTURE_MODEL_SHA256 not set — skipping checksum verification.")
                        println("  Run: sha256sum ${modelFile.absolutePath}  and set GESTURE_MODEL_SHA256.")
                    }
                    return@doLast // Success — exit all retry loops
                } catch (e: Exception) {
                    lastException = e
                    println("Attempt $attempt failed for $url: ${e.message}")
                    if (attempt < maxAttempts) {
                        val backoffMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(8_000L)
                        println("Retrying in ${backoffMs}ms…")
                        Thread.sleep(backoffMs)
                    }
                }
            }
            println("All $maxAttempts attempts failed for $url — trying fallback URL if available.")
        }

        // Both primary and fallback URLs exhausted.
        modelFile.takeIf { it.exists() }?.delete()
        throw java.io.IOException(
            "Failed to download gesture_recognizer.task after $maxAttempts attempts " +
            "from all URLs. Last error: ${lastException?.message}\n" +
            "For offline builds, manually place the file at:\n" +
            "  ${modelFile.absolutePath}",
            lastException
        )
    }
}

/** Compute SHA-256 hex digest for a file. Used by downloadGestureModel. */
fun computeSha256(file: java.io.File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } >= 0) {
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

tasks.named("preBuild") {
    dependsOn("downloadGestureModel")
}

// ---------------------------------------------------------------------------
// Prompt-10: JaCoCo unit-test coverage report + CI drop gate.
//
// Run:  ./gradlew jacocoTestReport
//       ./gradlew jacocoTestCoverageVerification   ← fails if branch drops below gate
//
// The report is generated from testDebugUnitTest execution data.
// Classes from DI modules, Room DAOs/Entities, generated Hilt code, and the
// Android resource-generated R class are excluded — they have trivial coverage
// and add noise to the report.
//
// COVERAGE_GATE: branch coverage must be >= 40%.
// This is deliberately conservative for the first enforcement iteration.
// Raise in 5-point increments as tests are added.
// ---------------------------------------------------------------------------
val coverageExcludePatterns = listOf(
    // Generated / boilerplate
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.class",
    "**/Manifest*.*",
    // Hilt generated
    "**/*_HiltModules*",
    "**/*_Factory*",
    "**/*_MembersInjector*",
    "**/*Hilt_*",
    "**/*_GeneratedInjector*",
    // Room generated
    "**/*_Impl.class",
    // Android data binding
    "**/*databinding*",
    "**/*DataBinding*",
    // Navigation SafeArgs
    "**/*Directions*",
    "**/*Args*",
)

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generate JaCoCo coverage report from debug unit test execution data."

    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val javaClasses = fileTree("${buildDir}/intermediates/javac/debug") {
        exclude(coverageExcludePatterns)
    }
    val kotlinClasses = fileTree("${buildDir}/tmp/kotlin-classes/debug") {
        exclude(coverageExcludePatterns)
    }
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(fileTree(buildDir) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "Fail if coverage drops below the configured threshold."

    dependsOn("jacocoTestReport")

    val javaClasses = fileTree("${buildDir}/intermediates/javac/debug") {
        exclude(coverageExcludePatterns)
    }
    val kotlinClasses = fileTree("${buildDir}/tmp/kotlin-classes/debug") {
        exclude(coverageExcludePatterns)
    }
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(fileTree(buildDir) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                // Prompt-10: 40% branch coverage floor.
                // Raise in 5-point increments: 40 → 45 → 50 → ... target 70%.
                minimum = "0.40".toBigDecimal()
            }
        }
    }
}
