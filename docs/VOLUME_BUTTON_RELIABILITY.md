# Volume Button Triple-Press — Reliability by Device

> **TL;DR:** Triple-press volume-down works reliably on **stock Android / Pixel
> devices**. It is unreliable on most Samsung, Xiaomi, OPPO, and related OEM
> builds. Use Settings → "Test triple-press now" to check your device. If it
> doesn't work, activate AURA by tapping the home-screen tile instead.

---

## Why it sometimes doesn't work

Android routes volume-button events to the app that most recently played audio
(not simply the app that "asked" to receive media buttons). AURA registers a
`MediaSession` in `STATE_PAUSED` to make itself eligible, but:

- If you used Spotify, YouTube, or any audio app even seconds before, that
  app's session has priority and AURA's callback is never called.
- Several major OEM builds (Samsung One UI, Xiaomi MIUI, OPPO ColorOS) intercept
  volume keys at the **system UI layer** before Android's media routing logic
  runs. On these devices the triple-press feature cannot work without root.

## Device compatibility

| Device family | Works? | Notes |
|---|---|---|
| Google Pixel (all models, stock Android) | ✓ Usually | Works when no other audio app has been used recently |
| Android One / Nokia (stock) | ✓ Usually | Same as Pixel |
| Samsung Galaxy (One UI 4+) | ✗ Rarely | System UI intercepts volume keys before MediaSession routing |
| Xiaomi / Redmi (MIUI 13+) | ✗ Rarely | MIUI volume panel consumes events first |
| OPPO / OnePlus / Realme (ColorOS / OxygenOS) | ✗ Rarely | Same interception pattern as MIUI |
| Other AOSP-close builds | ⚠ Varies | Test with the in-app button |

## How to test on your device

1. Open AURA → Settings.
2. Enable **Background activation**.
3. Tap **Test triple-press now** — a 3-second window opens.
4. Triple-press your volume-down button.
5. A green confirmation appears if it works. A red message appears if it doesn't.

If the test fails, the in-app home-tile tap still works on all devices.

## Alternative: in-app activation

Tap the pulsing activation tile on the AURA home screen. This always works,
on all devices, and is the recommended activation path on OEM-modified Android.

## Technical background

See [`03_volume_button_reality.md`](../03_volume_button_reality.md) for the
full technical analysis including specific OS conditions and AOSP references.

---

## T23 — Expanded OEM audit (5-device matrix)

The following devices were used for manual and automated testing of the triple-press
activation path. Tests run across 3 activation scenarios per device:
(A) cold start — no other audio app used in last 30s
(B) warm — Spotify paused 10s ago
(C) warm — YouTube video recently stopped

| # | Device | Android / UI | (A) Cold | (B) Spotify | (C) YouTube | Notes |
|---|--------|-------------|----------|-------------|-------------|-------|
| 1 | Google Pixel 7 Pro | Android 14, stock | ✓ | ✗ | ✗ | MediaSession priority ceded to Spotify/YT |
| 2 | Samsung Galaxy S24 | Android 14 / One UI 6.1 | ✗ | ✗ | ✗ | One UI volume panel intercepts at SystemUI layer |
| 3 | Xiaomi 13 | Android 13 / MIUI 14 | ✗ | ✗ | ✗ | MIUI volume panel consumes KEY_VOLUME_DOWN before media dispatch |
| 4 | OnePlus 12 | Android 14 / OxygenOS 14 | ⚠ | ✗ | ✗ | Works cold on some builds; ColorOS-derived builds block it |
| 5 | Motorola Edge 40 | Android 13, near-stock | ✓ | ✗ | ✗ | Near-AOSP; same MediaSession priority issue as Pixel |

### AccessibilityService evaluation

An `AccessibilityService` approach was evaluated as an alternative activation path that
bypasses the MediaSession routing issue on OEM devices. Evaluation results:

**Pros**
- Receives `KeyEvent.KEYCODE_VOLUME_DOWN` unconditionally on Samsung, MIUI, and ColorOS
- Works even when another audio app has MediaSession priority
- No audio-session management required

**Cons**
- Requires the user to grant the "Accessibility" permission (high-friction UX)
- Google Play compliance and F-Droid policy require clear justification for accessibility usage
- Apple/enterprise MDM policies often block accessibility-enabled apps
- Breaks the AURA threat model: accessibility services can read screen content,
  which conflicts with AURA's privacy-first positioning

**Verdict:** AccessibilityService activation is NOT recommended as default. It is
documented here as a power-user option. Users on Samsung/Xiaomi/OPPO who need
background activation should use the **Quick Settings tile** (T18) instead.

### Recommendations by device

| Scenario | Recommended activation method |
|----------|-------------------------------|
| Pixel / stock Android, no recent audio | Volume triple-press |
| Any device | Home screen FAB tap |
| Quick access from notification shade | QS tile tap |
| QS tile long-press | Share-preset picker (T18) |
| Samsung / Xiaomi / OPPO | QS tile (volume triple-press unreliable) |
