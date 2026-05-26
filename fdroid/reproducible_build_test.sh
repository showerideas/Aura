#!/usr/bin/env bash
# fdroid/reproducible_build_test.sh
# Phase I1 — F-Droid reproducible build verification.
#
# Builds the FOSS release APK twice from a clean state and compares SHA-256
# checksums. Identical checksums prove the build is bit-for-bit reproducible,
# as required by F-Droid's reproducible build policy.
#
# Usage:
#   ./fdroid/reproducible_build_test.sh [--skip-second-build]
#
# Requirements:
#   - JDK 17+ in PATH
#   - Android SDK with API 35 (ANDROID_HOME set)
#   - Gradle wrapper (./gradlew) executable
#   - sha256sum (coreutils) or shasum (macOS)
#   - At least 4 GB free disk space
#
# Exit codes:
#   0 — APKs are bit-for-bit identical (reproducible)
#   1 — APKs differ (non-reproducible) or build failed
#   2 — Prerequisites not met

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# Config
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${REPO_ROOT}/app/build/outputs/apk/foss/release"
APK_NAME_PATTERN="*foss*release*.apk"
BUILD1_DIR="/tmp/aura-repro-build1"
BUILD2_DIR="/tmp/aura-repro-build2"
SKIP_SECOND="${1:-}"

# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

log()   { echo "[repro-test] $*"; }
error() { echo "[repro-test] ERROR: $*" >&2; exit 1; }
warn()  { echo "[repro-test] WARNING: $*" >&2; }

sha256() {
    local file="$1"
    if command -v sha256sum &>/dev/null; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum &>/dev/null; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        error "Neither sha256sum nor shasum found in PATH"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Prerequisite checks
# ─────────────────────────────────────────────────────────────────────────────

log "Checking prerequisites..."

if ! java -version &>/dev/null 2>&1; then
    error "JDK not found in PATH. Install JDK 17+."
fi

JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [[ "$JAVA_MAJOR" -lt 17 ]]; then
    warn "JDK $JAVA_MAJOR detected; JDK 17+ recommended for reproducible builds."
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    warn "ANDROID_HOME / ANDROID_SDK_ROOT not set. Gradle may fail to locate SDK."
fi

if [[ ! -x "${REPO_ROOT}/gradlew" ]]; then
    error "gradlew not found or not executable at ${REPO_ROOT}/gradlew"
fi

log "Prerequisites OK (Java ${JAVA_MAJOR})"

# ─────────────────────────────────────────────────────────────────────────────
# Build 1
# ─────────────────────────────────────────────────────────────────────────────

log "=== Build 1 ==="
log "Cleaning previous build outputs..."
(cd "${REPO_ROOT}" && ./gradlew clean 2>&1 | tail -5)

log "Building FOSS release APK (SOURCE_DATE_EPOCH=0)..."
(
    cd "${REPO_ROOT}"
    SOURCE_DATE_EPOCH=0 \
    GRADLE_OPTS="-Dfile.encoding=UTF-8" \
    ./gradlew assembleFossRelease \
        --no-daemon \
        --no-build-cache \
        -Pandroid.injected.testOnly=false \
        2>&1 | tail -20
)

# Find APK
APK1=$(find "${BUILD_DIR}" -name "${APK_NAME_PATTERN}" | head -1)
if [[ -z "$APK1" ]]; then
    error "APK not found at ${BUILD_DIR}. Check build output above."
fi

mkdir -p "${BUILD1_DIR}"
cp "$APK1" "${BUILD1_DIR}/"
APK1_COPY="${BUILD1_DIR}/$(basename "$APK1")"
HASH1=$(sha256 "${APK1_COPY}")

log "Build 1 complete: $(basename "$APK1")"
log "SHA-256: ${HASH1}"
log "Size:    $(wc -c < "$APK1_COPY") bytes"

# ─────────────────────────────────────────────────────────────────────────────
# Build 2 (skippable for quick local smoke test)
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_SECOND" == "--skip-second-build" ]]; then
    log "Skipping second build (--skip-second-build passed)."
    log ""
    log "=== Single-build verification ==="
    log "APK:     $(basename "$APK1_COPY")"
    log "SHA-256: ${HASH1}"
    log ""
    log "Run without --skip-second-build for full reproducibility check."
    exit 0
fi

log ""
log "=== Build 2 (clean rebuild) ==="
log "Cleaning build outputs..."
(cd "${REPO_ROOT}" && ./gradlew clean 2>&1 | tail -5)

log "Rebuilding FOSS release APK..."
(
    cd "${REPO_ROOT}"
    SOURCE_DATE_EPOCH=0 \
    GRADLE_OPTS="-Dfile.encoding=UTF-8" \
    ./gradlew assembleFossRelease \
        --no-daemon \
        --no-build-cache \
        -Pandroid.injected.testOnly=false \
        2>&1 | tail -20
)

APK2=$(find "${BUILD_DIR}" -name "${APK_NAME_PATTERN}" | head -1)
if [[ -z "$APK2" ]]; then
    error "Second APK not found. Check build output above."
fi

mkdir -p "${BUILD2_DIR}"
cp "$APK2" "${BUILD2_DIR}/"
APK2_COPY="${BUILD2_DIR}/$(basename "$APK2")"
HASH2=$(sha256 "${APK2_COPY}")

log "Build 2 complete: $(basename "$APK2")"
log "SHA-256: ${HASH2}"
log "Size:    $(wc -c < "$APK2_COPY") bytes"

# ─────────────────────────────────────────────────────────────────────────────
# Compare
# ─────────────────────────────────────────────────────────────────────────────

log ""
log "=== Reproducibility check ==="
log "Build 1 SHA-256: ${HASH1}"
log "Build 2 SHA-256: ${HASH2}"
log ""

if [[ "$HASH1" == "$HASH2" ]]; then
    log "✅ REPRODUCIBLE — both builds produce identical APKs."
    log ""
    log "APK SHA-256 (for fdroid/com.showerideas.aura.yml 'Hash' field):"
    log "  ${HASH1}"
    log ""
    log "Update the 'Hash:' field in fdroid/com.showerideas.aura.yml if this is"
    log "a new release build, then commit before submitting to F-Droid data repo."
    exit 0
else
    log "❌ NON-REPRODUCIBLE — APKs differ between builds."
    log ""
    log "Common causes:"
    log "  1. Timestamps in generated files (BuildConfig, R.java)"
    log "     → Ensure SOURCE_DATE_EPOCH=0 is honoured by all Gradle tasks"
    log "  2. Non-deterministic file ordering in zip/jar/dex"
    log "     → Upgrade to AGP 8.3+ (deterministic zip ordering enabled by default)"
    log "  3. Cached intermediate files carrying old timestamps"
    log "     → Run ./gradlew clean before each build (done above)"
    log "  4. External tool version differences (NDK, aapt2)"
    log "     → Pin NDK version in build.gradle.kts: ndkVersion = 'r26d'"
    log ""
    log "Diff hint (apktool required):"
    log "  apktool d ${APK1_COPY} -o /tmp/aura-decode1"
    log "  apktool d ${APK2_COPY} -o /tmp/aura-decode2"
    log "  diff -r /tmp/aura-decode1 /tmp/aura-decode2"
    exit 1
fi
