# Manual QA Pass

> Last updated: 2026-05-23 (Prompt-14)
>
> Run this recipe before every Play Store submission or major release.
> Estimated time: **45–60 minutes** for one QA engineer with two devices.

---

## Devices required

| Device | Why |
|---|---|
| **Pixel 8 (stock Android 14)** | Primary reference device — no OEM skin, matches Play Store review environment |
| **Samsung Galaxy A-series (Android 12 or 13)** | High-volume Samsung One UI build — tests OEM skin quirks (volume button, permissions) |

Both devices must have **Bluetooth and Wi-Fi enabled** and be within ~1 metre of each other for Nearby Connections to work reliably indoors.

---

## Build to test

```bash
# Always test a signed release build, not debug.
./gradlew assembleRelease
# Install on both devices:
adb -s <pixel-serial>   install -r app/build/outputs/apk/release/*.apk
adb -s <samsung-serial> install -r app/build/outputs/apk/release/*.apk
```

---

## Scenario matrix

### S1 — Fresh install onboarding

| Step | Expected | Pass/Fail |
|---|---|---|
| Clear app data or fresh install | Onboarding shows 3 swipeable cards | |
| Swipe through all 3 cards | Each card visible, no layout overflow | |
| Tap "Get started" | Profile setup screen opens | |
| Grant Bluetooth + Nearby permissions | Permission rationale sheet shown before system dialog; user can grant | |
| Fill profile (name, email, phone) | Fields accept input, no crash | |
| Skip avatar | Proceed without avatar works | |
| Tap "Record gesture" | Camera preview opens; countdown animation plays | |
| Perform a hand gesture | Gesture recorded; "Strength" meter visible; confirmation shown | |
| Tap "Done" | Returns to Home screen; activation tile shown | |

---

### S2 — Peer-to-peer exchange (happy path)

> Run this on both Pixel and Samsung as the initiating device.

| Step | Expected | Pass/Fail |
|---|---|---|
| Device A: tap activation tile | Pulsing animation + "Scanning…" state | |
| Device B: tap activation tile | Same animation | |
| Perform gesture on both devices | Exchange starts (CONNECTING state) | |
| Wait for completion (~5 s) | "Exchange complete" confirmation on both | |
| Check Contacts on both devices | Received contact appears; name/email/phone correct | |
| Contact detail: tap email | Implicit intent opens mail app (if installed) | |
| Contact detail: swipe away | Returns to contacts list | |

---

### S3 — Volume-button wake (reliability test)

| Step | Expected | Pass/Fail |
|---|---|---|
| Settings → "Background activation" toggle ON | Amber warning banner appears | |
| Tap "Test volume button" row | "Listening…" state; green row visible | |
| Triple-press vol ▼ within 3 seconds | **Pixel:** Success toast. **Samsung:** May show Fail toast (documented OEM limitation) | |
| Settings → toggle OFF | Banner disappears | |

> ⚠️ Samsung Fail on this test is an **expected known limitation**, not a regression.
> Document the result as KNOWN-FAIL (Samsung One UI).

---

### S4 — QR fallback exchange

| Step | Expected | Pass/Fail |
|---|---|---|
| Device A: Exchange screen → "QR" tab | QR code displayed | |
| Device B: Exchange screen → "Scan QR" | Camera opens for scan | |
| Device B scans Device A's QR | Contact imported; confirmation shown | |
| Verify no Nearby permission needed | QR import works without BT/Wi-Fi scan permission | |

---

### S5 — Room mode (host + 2 guests)

| Step | Expected | Pass/Fail |
|---|---|---|
| Device A: "Start room" → performs gesture | Room host state; "Waiting for guests…" | |
| Device B: "Join room" → performs gesture | Connects; host counter shows 1 | |
| Device A host: receives Device B card | Contact appears in host's list | |
| Add Device C (use emulator if needed): "Join room" | Host counter shows 2 | |
| Device A: tap "Close room" | Guests see "Room closed" | |

---

### S6 — Blocklist

| Step | Expected | Pass/Fail |
|---|---|---|
| Complete S2 (exchange with Device B) | Device B in contacts | |
| Open Device B's contact detail → "Block device" | Confirmation dialog; block confirmed | |
| Device B: attempt exchange with Device A | Device A instantly rejects; "Blocked" toast (or silent reject) | |
| Settings → Blocked Devices | Device B listed | |
| Unblock Device B | Removed from blocklist; exchange possible again | |

---

### S7 — Replay protection

> Simulated in unit tests (`ReplayProtectionTest`). For a manual check:

| Step | Expected | Pass/Fail |
|---|---|---|
| Complete an exchange normally | Contact saved | |
| Force-stop both apps and relaunch | Neither side shows "already received" duplicate | |
| Second exchange between same two devices | New contact entry created (new nonce); no duplicate | |

---

### S8 — Avatar sharing

| Step | Expected | Pass/Fail |
|---|---|---|
| Device A: set a profile avatar (≤ 200 KB JPEG) | Avatar shown in profile | |
| Complete a P2P exchange with Device B | Device B's contact for A shows avatar | |
| Device A: set a large avatar (> 200 KB) | UI shows "Avatar too large" warning OR avatar silently omitted in exchange | |

---

### S9 — Accessibility (TalkBack)

| Step | Expected | Pass/Fail |
|---|---|---|
| Enable TalkBack (Accessibility → TalkBack) | App navigable by swipe | |
| Navigate to Profile screen | All fields announced correctly | |
| Navigate to Exchange screen | Activation state announced | |
| Navigate to Contacts list | Contact names announced | |
| Navigate to Settings | All toggles announced with state ("on"/"off") | |
| Disable TalkBack | Normal use resumes | |

---

### S10 — Biometric unlock

| Step | Expected | Pass/Fail |
|---|---|---|
| Settings → "Use fingerprint instead of gesture" → enable | Biometric enrollment prompt if not set up | |
| Trigger exchange activation | BiometricPrompt shown instead of gesture camera | |
| Authenticate with fingerprint | Exchange proceeds normally | |
| Decline biometric (cancel) | Exchange cancelled gracefully; no crash | |

---

### S11 — Localisation smoke test

> Test at least 2 non-English locales.

| Step | Expected | Pass/Fail |
|---|---|---|
| Device settings → Language → Deutsch | Relaunch app | |
| Verify Home, Profile, Contacts screens | Navigation labels and primary buttons in German | |
| Verify settings screen | Toggle labels in German | |
| Switch to 日本語 (Japanese) | Same verification | |
| Switch back to English | All strings English | |

> Strings not yet translated (40/202) show English fallback — this is expected.

---

## Known failing tests / expected results

| Scenario | Device | Expected result | Reason |
|---|---|---|---|
| S3 volume-button triple-press | Samsung Galaxy (One UI) | FAIL | MediaSession `setActive()` does not intercept volume keys on One UI |
| S3 volume-button triple-press | Xiaomi MIUI | FAIL | MIUI intercepts volume keys at system level |
| S3 volume-button triple-press | Pixel (stock) | PASS | No OEM key interception |

---

## Sign-off checklist

```
[ ] All S1–S11 scenarios run on Pixel 8 (Android 14)
[ ] All S1–S11 scenarios run on Samsung A-series (Android 12 or 13)
[ ] Samsung S3 FAIL documented as KNOWN-FAIL (not a regression)
[ ] No crashes observed in logcat during the pass
[ ] No new ANRs in logcat
[ ] APK size within 30 MB (verified by CI or manual du -k)
[ ] Signed APK SHA-256 recorded:
    sha256: ___________________________________________
```

---

## Reporting

File any failures as GitHub issues with:
- Device + Android version
- Scenario number + step that failed
- Logcat snippet if applicable
- Screenshot if visual
