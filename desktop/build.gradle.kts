// T40 — Desktop companion (KMP): Compose Desktop build configuration.
//
// This module provides a standalone desktop companion app for AURA using
// Kotlin Multiplatform + Compose Desktop. The desktop app uses QR relay
// as its primary exchange transport (no BLE/NFC hardware assumed) and
// reuses crypto primitives shared from :app's commonMain-compatible code.
//
// Supported targets: macOS (arm64 + x64), Linux (x64), Windows (x64).

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.10"
}

group   = "com.showerideas.aura"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose Desktop runtime
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    // ZXing QR code generation (same library used on Android)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // HTTP client for QR relay POST/poll
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin coroutines for desktop
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // JSON
    implementation("org.json:json:20240303")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.showerideas.aura.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName    = "AURA Desktop"
            packageVersion = "1.0.0"
            description    = "AURA contact exchange companion for desktop"
            copyright      = "© 2026 showerideas"
            vendor         = "showerideas"
        }
    }
}
