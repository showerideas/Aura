import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    // Issue-51: all 7 locale bundles (de, es, fr, hi, ja, ko, zh-rCN) are now
    // at 209/209 strings — 100% coverage. MissingTranslation is re-enabled so
    // any future key added to values/strings.xml without a matching entry in
    // every locale immediately fails lint/CI.
    lint {
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

    // ABI splits: produce one APK per architecture instead of a universal APK.
    // MediaPipe native libs are the dominant size contributor (~12-14 MB each).
    // A universal APK bundles all ABI slices together, pushing the total past
    // 30 MB. Per-ABI APKs (arm64-v8a ~20 MB, armeabi-v7a ~16 MB) are each
    // well under threshold and are what Play Store delivers to devices.
    //
    // NOTE: splits.abi.include takes over the role of ndk.abiFilters — AGP
    // throws a configuration error if both are set to the same ABIs. The ndk {}
    // block has been removed from defaultConfig; this splits block is the sole
    // ABI filter.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
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
    testImplementation(libs.kotlinx.coroutines.test)
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
    "gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task"
// Fallback: versioned path (float16, version 1) in case `latest` redirects:
val gestureModelUrlFallback = "https://storage.googleapis.com/mediapipe-models/" +
    "gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
// SHA-256 of gesture_recognizer.task (float16/latest, verified 2026-05-24).
// Obtain with: sha256sum gesture_recognizer.task
// The env var is read from GESTURE_MODEL_SHA256 (set as a GitHub Actions repo
// variable — see Settings › Secrets and variables › Actions › Variables).
// If blank/unset, checksum verification is skipped with a warning (safe for
// local dev). The fallback literal here is the last known-good hash so that
// local builds with the model already present never need the env var.
val gestureModelSha256 = System.getenv("GESTURE_MODEL_SHA256")
    ?.takeIf { it.isNotBlank() }   // treat empty string the same as not-set
    ?: "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"

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
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30_000   // 30 s connect timeout
                    conn.readTimeout    = 300_000  // 5 min read timeout
                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        throw IOException("HTTP $responseCode from $url")
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
                            throw IOException(
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
        throw IOException(
            "Failed to download gesture_recognizer.task after $maxAttempts attempts " +
            "from all URLs. Last error: ${lastException?.message}\n" +
            "For offline builds, manually place the file at:\n" +
            "  ${modelFile.absolutePath}",
            lastException
        )
    }
}

/** Compute SHA-256 hex digest for a file. Used by downloadGestureModel. */
fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } >= 0) {
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

// Wire downloadGestureModel to every task that reads from src/main/assets.
// Unit-test tasks run on JVM and never load the on-device model, so we must
// NOT block them on a network download — the model CDN URL could be
// unavailable in CI without affecting correctness of the test suite.
//
// Gradle 8 strict task dependency validation requires an explicit dependsOn
// whenever a task reads a directory that another task writes to.  We satisfy
// this for the three families of tasks known to scan src/main/assets:
//   • merge*Assets           — packages assets into APK/AAB
//   • generate*LintModel     — AGP lint model writer scans assets dirs
//   • lintAnalyze*           — lint analysis tasks read merged assets
afterEvaluate {
    tasks.matching { task ->
        val n = task.name
        // Tasks that package the assets directory into APK/AAB.
        (n.startsWith("merge") && n.endsWith("Assets")) ||
        // ALL lint-related tasks: lintAnalyze*, lintVitalAnalyze*, lintReport*,
        // lintDebug, lintRelease, etc. Lint tasks scan the entire module
        // (including src/main/assets) to build their analysis model.
        // Using startsWith("lint") covers all these variants in one pattern.
        n.startsWith("lint") ||
        // AGP lint model writer tasks: generateDebugLintReportModel,
        // generateReleaseLintVitalReportModel, generateDebugAndroidTestLintModel,
        // etc. They also scan the assets directory.
        (n.startsWith("generate") && n.contains("Lint"))
    }.configureEach {
        dependsOn("downloadGestureModel")
    }
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

    val javaClasses = fileTree("${layout.buildDirectory.get().asFile}/intermediates/javac/debug") {
        exclude(coverageExcludePatterns)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
        exclude(coverageExcludePatterns)
    }
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory.get().asFile) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "Fail if coverage drops below the configured threshold."

    dependsOn("jacocoTestReport")

    val javaClasses = fileTree("${layout.buildDirectory.get().asFile}/intermediates/javac/debug") {
        exclude(coverageExcludePatterns)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
        exclude(coverageExcludePatterns)
    }
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory.get().asFile) {
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
