#!/usr/bin/env bash
# Phase 7.5 — Reproducible build test for AURA foss flavor.
# Builds the fossRelease APK twice and compares SHA-256 hashes.
# Usage: ./scripts/reproducible_build_test.sh
#
# Prerequisites:
#   - JAVA_HOME set to JDK 17+
#   - Android SDK with build-tools 35+
#   - KEYSTORE_* env vars or unsigned APK comparison

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/build/repro-test"
mkdir -p "$OUTPUT_DIR"

echo "=== AURA Reproducible Build Test ==="
echo "Building fossRelease APK (attempt 1)..."
cd "$PROJECT_ROOT"
./gradlew :app:assembleFossRelease --no-build-cache 2>&1 | tail -5
cp app/build/outputs/apk/foss/release/app-foss-release.apk "$OUTPUT_DIR/build1.apk"

echo "Cleaning and building fossRelease APK (attempt 2)..."
./gradlew clean :app:assembleFossRelease --no-build-cache 2>&1 | tail -5
cp app/build/outputs/apk/foss/release/app-foss-release.apk "$OUTPUT_DIR/build2.apk"

HASH1=$(sha256sum "$OUTPUT_DIR/build1.apk" | awk '{print $1}')
HASH2=$(sha256sum "$OUTPUT_DIR/build2.apk" | awk '{print $1}')

echo ""
echo "Build 1 SHA-256: $HASH1"
echo "Build 2 SHA-256: $HASH2"

if [ "$HASH1" = "$HASH2" ]; then
  echo ""
  echo "✅ REPRODUCIBLE: Both builds produce identical APKs"
  exit 0
else
  echo ""
  echo "❌ NOT REPRODUCIBLE: APK hashes differ"
  echo "Run: diffoscope $OUTPUT_DIR/build1.apk $OUTPUT_DIR/build2.apk"
  exit 1
fi
