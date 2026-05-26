#!/usr/bin/env bash
# Phase 7.5 — Reproducible build verification script.
#
# Builds the FOSS release APK twice from a clean state and compares SHA-256
# checksums. A reproducible build produces bit-for-bit identical output.
#
# Prerequisites:
#   - Android SDK installed (ANDROID_HOME set)
#   - JDK 17 on PATH
#   - SOURCE_DATE_EPOCH=0 (set below for deterministic timestamps)
#
# Usage:
#   ./reproducible_build_test.sh [--apksigner /path/to/apksigner]
#
# Exit codes:
#   0 — builds are identical (reproducible)
#   1 — builds differ or one/both failed

set -euo pipefail

APKSIGNER="${1:-}"
BUILD_CMD="./gradlew clean assembleFossRelease"
APK_PATTERN="app/build/outputs/apk/foss/release/*.apk"
BUILD_DIR_1="/tmp/aura_repro_build_1"
BUILD_DIR_2="/tmp/aura_repro_build_2"

export SOURCE_DATE_EPOCH=0

echo "=== AURA Reproducible Build Test ==="
echo "SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
echo ""

# Ensure we are at the repo root
if [[ ! -f "settings.gradle.kts" ]]; then
    echo "ERROR: Run this script from the repository root." >&2
    exit 1
fi

build_and_copy() {
    local out_dir="$1"
    local build_num="$2"
    echo "--- Build $build_num ---"
    $BUILD_CMD
    mkdir -p "$out_dir"
    cp $APK_PATTERN "$out_dir/"
    echo "APK saved to $out_dir"
}

# First build
build_and_copy "$BUILD_DIR_1" 1

# Second build (clean slate again)
build_and_copy "$BUILD_DIR_2" 2

echo ""
echo "=== Comparing APKs ==="

APK_1=$(ls "$BUILD_DIR_1"/*.apk | head -1)
APK_2=$(ls "$BUILD_DIR_2"/*.apk | head -1)

SHA1=$(sha256sum "$APK_1" | awk '{print $1}')
SHA2=$(sha256sum "$APK_2" | awk '{print $1}')

echo "Build 1: $SHA1  $APK_1"
echo "Build 2: $SHA2  $APK_2"

if [[ "$SHA1" == "$SHA2" ]]; then
    echo ""
    echo "✓ REPRODUCIBLE: both builds produced identical APKs."
    echo "  SHA-256: $SHA1"
    exit 0
else
    echo ""
    echo "✗ NOT REPRODUCIBLE: APK SHA-256 checksums differ."
    echo ""
    echo "Diffoscope output (install with: pip install diffoscope):"
    diffoscope "$APK_1" "$APK_2" || true
    exit 1
fi
