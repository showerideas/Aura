#!/usr/bin/env bash
# T24 — Locale screenshot automation via Screengrab (fastlane / Screengrab gem).
#
# Captures screenshots for all 8 supported locales across the key screens:
#   - Home (R.id.homeFragment)
#   - Exchange (R.id.exchangeFragment)
#   - Profile (R.id.profileFragment)
#   - Settings (R.id.settingsFragment)
#
# Output: fastlane/metadata/android/<locale>/images/phoneScreenshots/
#
# Prerequisites:
#   gem install screengrab
#   adb device connected (emulator or real device with "Disable animations" in Developer Options)
#
# Usage:
#   ./scripts/screengrab_locales.sh
#   ./scripts/screengrab_locales.sh --locale de          # single locale
#   ./scripts/screengrab_locales.sh --locale de,fr,es    # subset

set -euo pipefail

LOCALES=(en-US de es fr hi ja ko "zh-CN")
PACKAGE="com.showerideas.aura"
TEST_PACKAGE="${PACKAGE}.test"

# Parse optional --locale flag
REQUESTED_LOCALES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --locale)
      IFS=',' read -ra REQUESTED_LOCALES <<< "$2"
      shift 2
      ;;
    *) shift ;;
  esac
done

if [[ ${#REQUESTED_LOCALES[@]} -gt 0 ]]; then
  LOCALES=("${REQUESTED_LOCALES[@]}")
fi

echo "=== AURA Screengrab: ${#LOCALES[@]} locale(s): ${LOCALES[*]} ==="

# Disable animations for stable screenshots
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

for LOCALE in "${LOCALES[@]}"; do
  echo ""
  echo "--- Capturing $LOCALE ---"
  OUT_DIR="fastlane/metadata/android/${LOCALE}/images/phoneScreenshots"
  mkdir -p "$OUT_DIR"

  # Set device locale
  adb shell am start -a android.settings.LOCALE_SETTINGS 2>/dev/null || true
  # Screengrab handles locale switching internally via InstrumentationRegistry

  screengrab \
    --app_package_name "$PACKAGE" \
    --tests_package_name "$TEST_PACKAGE" \
    --locales "$LOCALE" \
    --ending_locale "en-US" \
    --output_directory "$OUT_DIR" \
    --use_tests_in_packages "com.showerideas.aura.screenshots" \
    --reinstall_app \
    --clear_previous_screenshots \
    2>&1 | tail -20

  echo "    Saved to $OUT_DIR"
done

# Re-enable animations
adb shell settings put global window_animation_scale 1 || true
adb shell settings put global transition_animation_scale 1 || true
adb shell settings put global animator_duration_scale 1 || true

echo ""
echo "=== Screengrab complete ==="
