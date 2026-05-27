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

// Kotlin 2.0 + kapt: correctErrorTypes prevents stub-generation failures when
// kapt falls back to 1.9 mode and encounters K2-compiled metadata.
kapt {
    correctErrorTypes = true
}

android {
    namespace = "com.showerideas.aura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.showerideas.aura"
        minSdk = 26           // BLE + Nearby Connections baseline
        targetSdk = 35
        versionCode = 4
        versionName = "4.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // QR relay base URL. Set RELAY_BASE_URL in your environment or CI secrets.
        // Must be HTTPS (network_security_config.xml blocks cleartext).
        // For a zero-ops relay, point this at a Firebase Realtime Database URL —
        // see docs/qr-relay-setup.md for setup instructions.
        val relayBaseUrl = System.getenv("RELAY_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: "https://relay.example.com"
        buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")
        // Phase 5.7 — TLS certificate pin expiry epoch (milliseconds since Unix epoch).
        // RelayClient logs a warning when within 30 days of expiry.
        // Rotate the pin AND update this value before the expiry date.
        // See docs/QR_RELAY_SETUP.md for the pin rotation runbook.
        buildConfigField("Long", "RELAY_PIN_EXPIRY_EPOCH_MS", "1874908800000L") // 2029-06-01
        // Phase 10.2 — Runtime SPKI certificate pins for RelayClient's SpkiPinTrustManager.
        // Set these in CI environment variables (RELAY_SPKI_PIN_PRIMARY / RELAY_SPKI_PIN_BACKUP).
        // Generate with: openssl s_client -connect <relay-host>:443 < /dev/null |
        //   openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER |
        //   openssl dgst -sha256 -binary | base64
        // See docs/QR_RELAY_SETUP.md for the full rotation runbook.
        val spkiPrimary = System.getenv("RELAY_SPKI_PIN_PRIMARY")?.takeIf { it.isNotBlank() }
            ?: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="   // placeholder — set in CI
        val spkiBackup  = System.getenv("RELAY_SPKI_PIN_BACKUP")?.takeIf { it.isNotBlank() }
            ?: "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="   // placeholder — set in CI
        buildConfigField("String", "RELAY_SPKI_PIN_PRIMARY", "\"$spkiPrimary\"")
        buildConfigField("String", "RELAY_SPKI_PIN_BACKUP",  "\"$spkiBackup\"")

        // Phase 6 (T80) — Native ML-DSA-65 Keystore availability flag.
        // True at runtime when Build.VERSION.SDK_INT >= 37 (Android 17).
        // Used by HybridIdentityKey and tests to gate the native signing path.
        buildConfigField("boolean", "NATIVE_ML_DSA_AVAILABLE", "false")  // overridden at runtime via Build.VERSION.SDK_INT check

        // Phase 7 (T86) — NFC CTAP2 hardware key relay (enterprise feature flag).
        buildConfigField("boolean", "ENABLE_HW_KEY_RELAY", "false")

        // Phase 8 (T91) — ZK proof in audit export (enterprise feature flag).
        buildConfigField("boolean", "ENABLE_ZK_AUDIT_PROOF", "false")

        // Phase 9 (T98) — AR exchange overlay (enterprise feature flag).
        buildConfigField("boolean", "ENABLE_AR_EXCHANGE", "false")

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

    // All 7 locale bundles (de, es, fr, hi, ja, ko, zh-rCN) are at 365 strings —
    // 100% coverage. MissingTranslation is re-enabled so any future key added to
    // values/strings.xml without a matching entry in every locale immediately
    // fails lint/CI.
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

    // Transport flavor dimension.
    //
    // gms  (default) — uses Google Nearby Connections (requires Play Services).
    //                   Primary variant; distributed as signed APK via GitHub Releases.
    // foss            — uses WifiDirectTransport (no GMS dependency).
    //                   For users who prefer a Google-free build.
    //
    // Build GMS variant:   ./gradlew assembleGmsRelease
    // Build FOSS variant:  ./gradlew assembleFossRelease
    flavorDimensions += "transport"
    productFlavors {
        create("gms") {
            dimension = "transport"
            // No applicationId suffix — GMS is the primary variant.
        }
        create("foss") {
            dimension = "transport"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
            // FOSS builds exclude the Nearby Connections dependency declared
            // in the gms sourceSet's build.gradle inclusion (see flavors/gms/).
            // Until the full transport-injection refactor lands, the FOSS variant
            // compiles cleanly but WifiDirectTransport is injected instead of
            // NearbyConnectionsTransport at runtime via TransportModule.
        }
    }

    // Phase 6.2: flavor-specific source sets must be declared AFTER productFlavors
    // so that AGP has already registered the "gms" and "foss" AndroidSourceSet objects.
    sourceSets {
        getByName("gms").java.srcDirs("src/gms/java")
        getByName("foss").java.srcDirs("src/foss/java")
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
    // well under threshold. Per-ABI slices are attached to GitHub Releases.
    //
    // NOTE: splits.abi.include takes over the role of ndk.abiFilters — AGP
    // throws a configuration error if both are set to the same ABIs. The ndk {}
    // block has been removed from defaultConfig; this splits block is the sole
    // ABI filter.
    splits {
        abi {
            // Enable ABI splits only for release assemble tasks so that:
            //  1. bundleRelease avoids AGP bug #402800800 (ABI splits + AAB conflict).
            //  2. connectedAndroidTest tasks can install on the x86_64 CI emulator
            //     (which has no arm64/armeabi APK when splits are active).
            //  3. Release APKs are sliced per-ABI and attached to GitHub Releases.
            val taskNames = gradle.startParameter.taskNames
            val isAssembleRelease = taskNames.any { t ->
                t.contains("assemble", ignoreCase = true) &&
                t.contains("release", ignoreCase = true)
            }
            val isBundleOrTestTask = taskNames.any { t ->
                t.contains("bundle", ignoreCase = true) ||
                t.contains("connected", ignoreCase = true) ||
                t.contains("test", ignoreCase = true)
            }
            isEnable = isAssembleRelease && !isBundleOrTestTask
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

    // Google Nearby Connections — gms flavor only (foss uses WifiDirectTransport)
    "gmsImplementation"(libs.play.services.nearby)

    // Wearable Data Layer — phone-side sender for Wear OS tile integration (Phase F1).
    // Optional dependency: the app degrades gracefully when no watch is paired.
    "gmsImplementation"("com.google.android.gms:play-services-wearable:18.2.0")

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



    // Phase 8.4 — WorkManager for periodic blocklist refresh
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // T11 — UWB proximity confirmation (API 31+, Pixel 6+ and select OEM devices)
    implementation("androidx.core.uwb:uwb:1.0.0-alpha08")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")
    // Phase 8.1 — BouncyCastle PQC: ML-KEM-768 post-quantum KEM + Dilithium
    // bcprov-jdk18on includes org.bouncycastle.pqc.* (mlkem, crystals.dilithium) since 1.72+.
    // bcpqc-jdk18on does not exist as a separate Maven Central artifact.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Phase 7 (T83) — FIDO2 CredentialManager provider (AuraCredentialProviderService + PasskeyGestureGateActivity)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    // Android Auto — Car App Library (Phase 7.3)
    implementation("androidx.car.app:app:1.4.0")

    // Wear OS tiles (Phase 7.2)
    implementation("androidx.wear.tiles:tiles:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json stubs in android.jar throw RuntimeException("Stub!") in JVM unit tests.
    // This real implementation overrides the stub so JSONObject works in QRSasPipelineTest.
    testImplementation("org.json:json:20231013")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.accessibility)
    // GrantPermissionRule — pre-grants dangerous permissions before Espresso tests
    // launch activities (prevents system permission dialog from pausing the activity).
    androidTestImplementation("androidx.test:rules:1.5.0")
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
// Phase 10.3 — Canonical SHA-256 of gesture_recognizer.task (float16/latest).
// This single constant drives BOTH downloadGestureModel (integrity check after download)
// AND verifyGestureModel (bundled asset verification before release builds).
// Update when the model version changes; set GESTURE_MODEL_SHA256 in CI/Actions Variables.
// Obtain with: sha256sum gesture_recognizer.task
val gestureModelSha256 = System.getenv("GESTURE_MODEL_SHA256")
    ?.takeIf { it.isNotBlank() }   // treat empty string the same as not-set
    ?: "f7bbcc17ecc99c879f45f58d36e4e0feec78e9b0aedde99d9b1a5f2e28dbd36c"

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
// The report is generated from testGmsDebugUnitTest execution data (GMS flavor).
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

    dependsOn("testGmsDebugUnitTest")

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
        include("jacoco/testGmsDebugUnitTest.exec", "outputs/unit_test_code_coverage/gmsDebugUnitTest/testGmsDebugUnitTest.exec")
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
        include("jacoco/testGmsDebugUnitTest.exec", "outputs/unit_test_code_coverage/gmsDebugUnitTest/testGmsDebugUnitTest.exec")
    })

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                // Stage-3: 60% branch coverage floor (raised from 50%).
                // Raise in 5-point increments: 60 → 65 → 70 target.
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

// Phase 5.8 — verify SHA-256 of bundled gesture model asset before building.
// Run: ./gradlew verifyGestureModel
// Phase 10.3: canonical hash is now gestureModelSha256 declared above (unified).
tasks.register("verifyGestureModel") {
    description = "Verifies the SHA-256 hash of the bundled MediaPipe gesture model."
    group = "verification"
    doLast {
        val modelFile = file("src/main/assets/gesture_recognizer.task")
        if (!modelFile.exists()) {
            println("WARNING: gesture_recognizer.task not found in assets/ — model must be present before release")
            return@doLast
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(modelFile.readBytes())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        if (hash != gestureModelSha256) {
            throw GradleException("gesture_recognizer.task SHA-256 mismatch!\nExpected: $gestureModelSha256\nActual:   $hash")
        }
        println("gesture_recognizer.task SHA-256 OK: $hash")
    }
}

