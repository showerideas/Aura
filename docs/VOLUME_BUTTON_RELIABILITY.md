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
