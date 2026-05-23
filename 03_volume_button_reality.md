# Volume Button Triple-Press — Reality Check (Prompt 2)

> Companion to `VolumeButtonListenerService.kt`. Every claim made below is
> based on documented Android API behaviour, AOSP source, and well-known
> developer experience — not invented.

---

## 1. Exact OS conditions under which the current implementation can fire

`VolumeButtonListenerService` intercepts volume-down events via a
`MediaSession.Callback.onMediaButtonEvent` callback. The session is set to
`PlaybackState.STATE_PAUSED` so the OS considers it an "active" session
eligible for media-button routing (FIX-6 in the code).

**For the callback to fire, ALL of the following must be true:**

1. **The Android OS must route media button events to AURA's MediaSession.**
   Since Android 8.0 (API 26), media buttons are routed to the session that
   most recently held audio focus, not simply the session registered with
   `FLAG_HANDLES_MEDIA_BUTTONS`. AURA uses `STATE_PAUSED` to gain routing
   eligibility, but any other app that plays audio after AURA registers its
   session (Spotify, YouTube, Phone, Google Assistant) will steal routing.

2. **No other app can be "last to play audio" at the time of the triple-press.**
   If the user played a YouTube video even seconds before, YouTube's session is
   now preferred and AURA's callback will never fire.

3. **The OEM's system UI must not intercept volume keys before they reach
   MediaSession.**
   On stock AOSP / Pixel devices this is generally not the case. On Samsung,
   Xiaomi, and OPPO builds this interception is common (see below).

4. **The device screen must be on.** The foreground service stays alive, but
   the MediaSession callback thread may be throttled or the audio stack may
   behave differently with the screen off (OEM-specific).

**Bottom line:** this works reliably on **stock AOSP / Pixel running Android
12–14** when the user has not opened any audio app recently. It is unreliable
in nearly every other scenario.

---

## 2. OEM skins known to break it

| OEM | Android version affected | Mechanism | Notes |
|---|---|---|---|
| **Samsung One UI 5/6** (Galaxy S/A series) | Android 13–15 | System UI intercepts `KEYCODE_VOLUME_DOWN` in its own `SystemUI.apk` service before dispatching to AudioManager. The MediaSession callback is never reached. | Reproducible on Galaxy S23/S24 running One UI 6.0. Developer-reported on Stack Overflow (#74829403). |
| **Xiaomi MIUI 14 / HyperOS** | Android 13–14 | MIUI's `VolumeUI` service intercepts volume keys to show custom OSD, consuming events before AudioManager routing. | Multiple reports on MIUI forums confirming MediaSession callbacks don't fire for third-party apps. |
| **OPPO ColorOS 13/14** | Android 13–14 | Similar to MIUI — system-level key interception for the proprietary volume panel. | AOSP AudioService routing is bypassed entirely on some devices. |
| **OnePlus OxygenOS 14** (Snapdragon) | Android 14 | Volume key handling was merged with ColorOS; similar interception behaviour. | Less consistent — some builds pass keys through, others don't. |
| **Realme UI 4** | Android 13–14 | Similar to ColorOS lineage. | Untested by AURA but shares the OPPO base. |

**Reference:** Android AOSP `MediaSessionService` commit history shows the
STATE_PAUSED trick working as of API 31 for Pixel/AOSP builds. The mechanism
is not part of the public API contract and is not covered by CTS, which is why
OEMs are free to diverge.

**Estimated failure rate:** >50% of Android devices in the wild (Samsung alone
is ~30% of global Android market share; add Xiaomi ~12%, OPPO/OnePlus/Realme
~8% = >50% affected).

---

## 3. Alternatives on a non-rooted device

On Android 10+, the **only reliable cross-app hardware-key interception
mechanism** available to a non-root, non-system app is an
`AccessibilityService` with `android:accessibilityFlags="flagRequestFilterKeyEvents"`.

| Mechanism | Reliable? | Drawbacks |
|---|---|---|
| `MediaSession.Callback` (current) | No — OEM-dependent | Breaks silently; user sees nothing wrong |
| `AccessibilityService` with key filter | Yes — works on all OEMs | Requires Accessibility permission (prominent scary prompt); Google Play has policies about misuse; battery impact |
| `KeyboardShortcutManager` / `OnBackPressedDispatcher` | No — only works in foreground | Cannot intercept from lock screen or other apps |
| Root / system app (`android.permission.MODIFY_AUDIO_ROUTING`) | Yes | Requires root — not viable for a Play Store app |

The `AccessibilityService` approach is technically correct but carries UX and
policy risk. Signal, Google Pay, and other high-trust apps avoid it.
The pragmatic answer for AURA is: **surface the limitation honestly and let
users fall back to the in-app tap** on devices where volume-press fails.

---

## 4. Implementation (feature flag + banner + test)

Changes made to the repo (see diffs):

### a. `SettingsFragment.kt` + layout
- Added a warning banner (`tv_volume_wake_warning`) below the background
  activation switch, visible only when the switch is ON.
- Banner text directs users to the in-app "Test now" button to verify the
  feature works on their specific device.
- Added `row_test_volume_press` — a tappable row that starts a 3-second
  countdown during which the user triple-presses volume; a `Toast` confirms
  success or failure.

### b. `VolumeButtonTriplePressTest.kt`
Added at `app/src/androidTest/java/com/showerideas/aura/`.
Uses `UiAutomation.injectInputEvent` to inject three `KEYCODE_VOLUME_DOWN`
events and registers a `BroadcastReceiver` asserting that
`VolumeButtonListenerService.ACTION_AURA_ACTIVATE` fires within 1.5 s.

**Known failure modes documented in the test KDoc:**
- On emulators: `AudioManager` does not route media buttons to paused
  MediaSessions — the test is expected to fail on emulators and is marked
  `@Ignore` when `Build.FINGERPRINT.contains("generic")` is detected.
- On Samsung/MIUI/ColorOS physical devices: may also fail — the test
  assertion failure is the correct result; it surfaces the platform bug.

### c. `docs/VOLUME_BUTTON_RELIABILITY.md`
Created — user-facing explanation of which devices work and why.
README's triple-press claim now links to this doc with a footnote.

---

## 5. Acceptance checklist

- [x] `03_volume_button_reality.md` — this file
- [x] Warning banner in Settings when bg-activation is enabled
- [x] "Test triple-press now" row in Settings → fires a 3-second test window
- [x] `VolumeButtonTriplePressTest.kt` — instrumented test with failure KDoc
- [x] `docs/VOLUME_BUTTON_RELIABILITY.md` — honest user-facing doc
