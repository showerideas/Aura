#!/usr/bin/env bash
# T26 — Custom gesture training pipeline: CI bundle validator.
#
# Validates a MediaPipe Task bundle (.task) exported from the custom gesture
# training pipeline before it is committed to the repository or deployed.
#
# Usage:
#   ./scripts/validate_gesture_bundle.sh <path-to-bundle.task>
#
# Exit codes:
#   0 — bundle passed all checks
#   1 — validation failure (see stderr for details)
#
# Checks performed:
#   1. File exists and is non-empty.
#   2. File size is within expected range (100 KB – 50 MB).
#   3. Bundle has correct MediaPipe magic bytes (ZIP local file header 'PK\x03\x04').
#   4. Bundle contains expected top-level manifest file (tflite_metadata.json or
#      MANIFEST or model.tflite — at least one must be present).
#   5. (Optional) SHA-256 checksum matches GESTURE_MODEL_SHA256 env var if set.
#
# Integration with CI (.github/workflows/ci.yml):
#   Add a step after model download:
#     - name: Validate gesture bundle
#       run: ./scripts/validate_gesture_bundle.sh app/src/main/assets/gesture_recognizer.task
#       env:
#         GESTURE_MODEL_SHA256: ${{ vars.GESTURE_MODEL_SHA256 }}

set -euo pipefail

BUNDLE_PATH="${1:-}"

# ---------------------------------------------------------------------------
# 0. Argument check
# ---------------------------------------------------------------------------
if [[ -z "$BUNDLE_PATH" ]]; then
    echo "ERROR: Usage: $0 <path-to-bundle.task>" >&2
    exit 1
fi

echo "=== Gesture bundle validator ==="
echo "Bundle: $BUNDLE_PATH"

# ---------------------------------------------------------------------------
# 1. File existence
# ---------------------------------------------------------------------------
if [[ ! -f "$BUNDLE_PATH" ]]; then
    echo "FAIL: Bundle file not found: $BUNDLE_PATH" >&2
    exit 1
fi
echo "PASS: File exists"

# ---------------------------------------------------------------------------
# 2. File size check (100 KB – 50 MB)
# ---------------------------------------------------------------------------
SIZE_BYTES=$(wc -c < "$BUNDLE_PATH")
MIN_BYTES=$((100 * 1024))        # 100 KB
MAX_BYTES=$((50 * 1024 * 1024))  # 50 MB

if [[ "$SIZE_BYTES" -lt "$MIN_BYTES" ]]; then
    echo "FAIL: Bundle too small (${SIZE_BYTES} bytes — minimum ${MIN_BYTES}). Corrupt download?" >&2
    exit 1
fi
if [[ "$SIZE_BYTES" -gt "$MAX_BYTES" ]]; then
    echo "FAIL: Bundle too large (${SIZE_BYTES} bytes — maximum ${MAX_BYTES}). Wrong file?" >&2
    exit 1
fi
SIZE_KB=$(( SIZE_BYTES / 1024 ))
echo "PASS: File size ${SIZE_KB} KB (within 100 KB – 50 MB)"

# ---------------------------------------------------------------------------
# 3. Magic bytes — ZIP local file header (MediaPipe .task is a ZIP archive)
# ---------------------------------------------------------------------------
MAGIC=$(head -c 4 "$BUNDLE_PATH" | xxd -p 2>/dev/null || od -An -tx1 -N4 "$BUNDLE_PATH" | tr -d ' \n')
# Normalise to lowercase 8-char hex
MAGIC=$(echo "$MAGIC" | tr '[:upper:]' '[:lower:]' | head -c 8)

if [[ "$MAGIC" != "504b0304" ]]; then
    echo "FAIL: Unexpected magic bytes '$MAGIC' (expected 504b0304 — ZIP). Bundle may be corrupted." >&2
    exit 1
fi
echo "PASS: Magic bytes OK (ZIP/MediaPipe task format)"

# ---------------------------------------------------------------------------
# 4. Required entries inside the ZIP
# ---------------------------------------------------------------------------
# At least one of these must be present
REQUIRED_ENTRIES=("model.tflite" "tflite_metadata.json" "MANIFEST" "metadata.json")
FOUND_ENTRY=""

for entry in "${REQUIRED_ENTRIES[@]}"; do
    if unzip -l "$BUNDLE_PATH" 2>/dev/null | grep -q "$entry"; then
        FOUND_ENTRY="$entry"
        break
    fi
done

if [[ -z "$FOUND_ENTRY" ]]; then
    echo "FAIL: Bundle does not contain any expected entry (model.tflite / tflite_metadata.json / MANIFEST)." >&2
    echo "  ZIP contents:" >&2
    unzip -l "$BUNDLE_PATH" 2>/dev/null | head -20 >&2
    exit 1
fi
echo "PASS: Required bundle entry found: $FOUND_ENTRY"

# ---------------------------------------------------------------------------
# 5. Optional SHA-256 checksum
# ---------------------------------------------------------------------------
if [[ -n "${GESTURE_MODEL_SHA256:-}" ]]; then
    if command -v sha256sum &>/dev/null; then
        ACTUAL_SHA=$(sha256sum "$BUNDLE_PATH" | awk '{print $1}')
    elif command -v shasum &>/dev/null; then
        ACTUAL_SHA=$(shasum -a 256 "$BUNDLE_PATH" | awk '{print $1}')
    else
        echo "WARN: sha256sum/shasum not available — skipping checksum verification"
        ACTUAL_SHA=""
    fi

    if [[ -n "$ACTUAL_SHA" ]]; then
        if [[ "$ACTUAL_SHA" == "$GESTURE_MODEL_SHA256" ]]; then
            echo "PASS: SHA-256 checksum matches GESTURE_MODEL_SHA256"
        else
            echo "FAIL: SHA-256 mismatch!" >&2
            echo "  Expected: $GESTURE_MODEL_SHA256" >&2
            echo "  Actual:   $ACTUAL_SHA" >&2
            exit 1
        fi
    fi
else
    echo "INFO: GESTURE_MODEL_SHA256 not set — skipping checksum check"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== All checks passed — bundle is valid ==="
exit 0
