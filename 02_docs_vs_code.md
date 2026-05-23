# AURA — Docs vs. Code Delta Report (Prompt 1)

> Every documentation lie identified by comparing `docs/` and `README.md`
> against the actual source on `main`. Format: file:line → claim → reality.

---

## A. `docs/GESTURE_AUTH.md` — systematic mismatch

The entire document describes an **accelerometer + Dynamic Time Warping** pipeline
that does not exist in source. The actual implementation uses **CameraX +
MediaPipe GestureRecognizer + cosine similarity** on a 42-float hand-landmark
embedding.

### False sentences with file:line citations

| Line(s) | Quoted claim | Reality |
|---|---|---|
| Title / opening | "AURA's signature interaction is a personal motion that gates every exchange. This page explains how that motion travels from the **accelerometer** into a stored feature vector" | There is no accelerometer. The pipeline uses `CameraX` + `MediaPipe GestureRecognizer`. Source: `CameraHandEmbedder.kt:1-298` |
| 6 | "The implementation lives in `GestureAuthManager.kt` (~317 lines)." | `GestureAuthManager.kt` is 247 lines. It delegates all camera/model work to `CameraHandEmbedder.kt` which is the primary implementation file. |
| Flowchart node | `Accelerometer SensorManager SENSOR_DELAY_GAME` | No `SensorManager` exists in `auth/`. The input is `CameraX ImageAnalysis` frames. `CameraHandEmbedder.kt:165-245` |
| Flowchart node | `Ring buffer of GestureEvent(x,y,z,t)` | No `GestureEvent` class exists in the project. There is no ring buffer. |
| Flowchart node | `Magnitude per sample √(x²+y²+z²)` | No magnitude computation. The pipeline extracts 21 NormalizedLandmarks from `GestureRecognizerResult`. `CameraHandEmbedder.kt:115-128` |
| Flowchart node | `Resample to 50 points — linear interpolation` | No resampling. The output is always 42 floats (21 landmarks × x,y). `CameraHandEmbedder.EMBEDDING_SIZE = 42` |
| Flowchart node | `Z-normalise (mean=0, sd=1)` | No z-normalisation. Normalisation is wrist-centred + MCP-scaled. `CameraHandEmbedder.normalizeEmbedding():115-128` |
| Flowchart node | `Feature vector FloatArray(50)` | Feature vector is `FloatArray(42)`. `CameraHandEmbedder.EMBEDDING_SIZE = 42:104` |
| Flowchart node | `Variance ≥ threshold?` | No variance gate exists in current code. `GestureAuthManager.kt` has no `computeVariance` function. |
| Flowchart node | `EncryptedSharedPreferences aura_gesture_pattern_v1` | Prefs key is `aura_gesture_prefs` (the EncryptedSharedPreferences file name), with keys `gesture_feature_vector` and `gesture_pattern_id`. `GestureAuthManager.kt:40-41` |
| Flowchart node | `DTW vs stored — distance ≤ τ?` | No DTW. Match uses `CameraHandEmbedder.cosineSimilarity()` with threshold 0.88. `GestureAuthManager.kt:232-236` |
| 41 | "The whole pipeline is JVM-pure once the samples are captured — `computeVariance` and `dtw` are covered by `app/src/test/java/com/showerideas/aura/GestureMatchTest.kt`." | `computeVariance` and `dtw` functions do not exist. There is no `GestureMatchTest.kt` file. Tests are in `GestureAuthTest.kt` and test cosine similarity math only. |
| Section 2 heading | "Why DTW and not a hash?" | DTW is not used. The section is entirely fictional. |
| Section 2 body | "Dynamic Time Warping measures how well two sequences align under non-linear time stretching" | Irrelevant — not used. |
| Section 3 heading | "Variance gate (PR-06)" | No variance gate in code. `GestureAuthManager.kt` has no `computeVariance`. |
| Section 3 body | "we compute the population variance of the raw magnitude series" | No magnitude series, no variance computation. |
| Section 4 heading + flowchart | "The strength meter turns the same **variance** value into a 1-of-5 bar" | The strength meter is driven by `liveVariance` which maps to `CameraHandEmbedder.GestureState.Detecting.stability` (frame-count progress 0..1), not variance. `GestureAuthManager.kt:81-86` |
| Storage table | "The 50-float feature vector + variance — `EncryptedSharedPreferences` (`aura_gesture_prefs_v1`) under key `pattern_v1`" | Vector is 42 floats. Key name is `gesture_feature_vector`. File name is `aura_gesture_prefs`. No variance is stored. `GestureAuthManager.kt:40-41, 199` |
| Section 6 body | "DTW match threshold + per-recording variance check + 3-strike cap" | Match is cosine similarity ≥ 0.88. No variance check. |

**Verdict:** `docs/GESTURE_AUTH.md` describes a system that was replaced by the
CameraX/MediaPipe implementation. Zero sentences in sections 1–4 are accurate.
Section 5 (Storage) and Section 6 (Match-failure UX) are partially accurate —
the 3-strike cap and EncryptedSharedPreferences storage are real, but the
specific key names and vector size are wrong.

---

## B. `README.md` — false and misleading claims

| Line | Claim | Reality |
|---|---|---|
| 65 (feature table) | "DTW gesture matcher" | No DTW. Match is cosine similarity at 0.88. `GestureAuthManager.kt:232` |
| 207 (tech stack table) | `Gesture auth \| Accelerometer + Dynamic Time Warping` | Gesture auth is `CameraX + MediaPipe Tasks Vision GestureRecognizer`. `CameraHandEmbedder.kt:1` |
| 70 (feature table) | "32 unit + 4 instrumentation tests" | Actual: 51 unit @Test methods across 7 files + 20 instrumented @Test methods across 6 files. Verified by `grep -rc "@Test"`. |
| 238 (Roadmap) | "v1.1.0 — ship 7 translated `values-xx/` bundles" | Shipped in commit `Merge v1.1/full-translations`. Roadmap checkbox should be `[x]`. |
| 237 (Roadmap) | "v1.0.0 — ... R8-shrunk release APK" | No MediaPipe ProGuard -keep rules exist in `proguard-rules.pro`. R8 will strip `com.google.mediapipe.**`, causing a crash on first gesture attempt. |
| 24 (opening paragraph) | "a single motion is enough to push your details" | The motion is a camera-detected hand gesture, not an accelerometer motion. Wording should reflect camera requirement. |
| 31 (diagram) | `✋ gesture` implies an arbitrary physical motion | The gesture must be one of the gestures MediaPipe can recognise (`THUMBS_UP`, `OPEN_PALM`, `POINTING_UP`, etc.). `HandGesture.kt` defines the allowed set. |

---

## C. `docs/AUDIT.md` — green rows that lack proof

### H2: Triple-press volume (🟢)

**Claim:** "VolumeButtonListenerService listens to media-button events and emits
`ACTION_ACTIVATE` after three vol-down presses."

**Code evidence:** Wired (`VolumeButtonListenerService.kt`). ✓

**Missing:**
- No instrumentation test exists that injects three `KEYCODE_VOLUME_DOWN` events
  and asserts the broadcast is received.
- No documentation of the failure modes on Samsung One UI, MIUI, ColorOS.
- The `STATE_PAUSED` PlaybackState trick (FIX-6, line 114) is noted in the code
  itself as potentially unreliable. The audit grades this green with no caveat.

**Verdict:** `PARTIAL` — code exists, reliability unproven, no test.

---

### H3: Gesture performance (🟢)

**Claim:** "GestureAuthManager + accelerometer pipeline + DTW matcher;
100% JVM-testable."

**Code evidence:** Pipeline is camera-based, not accelerometer-based.
`GestureAuthTest.kt` exists but tests cosine similarity math only — it does
not import `GestureAuthManager` or `CameraHandEmbedder` because those require
Android Context and MediaPipe native libs.

**Missing:**
- The claim "100% JVM-testable" is false — the camera/MediaPipe pipeline
  requires an Android device to run.
- "accelerometer pipeline + DTW matcher" is false.
- No measurement of false-accept rate at threshold 0.88.

**Verdict:** `UNVERIFIED + FALSE DESCRIPTION`

---

### H4: Nearby Connections P2P link (🟢)

**Claim:** "`play-services-nearby:19.1.0` wired through `NearbyExchangeService`."

**Code evidence:** Correct — dependency is real. ✓

**Missing:**
- No CI test verifies two devices actually exchange data. `ExchangeFlowEspressoTest`
  has 1 @Test method and uses Espresso; it cannot exercise real Nearby Connections
  wire protocol.
- Two-device exchange has never been verified by CI.

**Verdict:** `PARTIAL` — dependency present, wire protocol unverified.

---

### H10: QR fallback (🟢)

**Claim:** "PR-08: `QRExchangeFragment` + ZXing-embedded."

**Code evidence:** Code exists. ✓

**Missing:**
- `ExchangeFlowEspressoTest` does not cover the QR flow. There is no test for
  QR encode → decode → contact save round-trip.

**Verdict:** `PARTIAL` — code present, no automated test.

---

### H11: Room mode 1:N (🟢)

**Claim:** "PR-09: `RoomExchangeFragment`, P2P_STAR strategy."

**Code evidence:** `P2P_CLUSTER` is used, not `P2P_STAR`. `NearbyExchangeService.kt:77`
— `private val STRATEGY = Strategy.P2P_CLUSTER`.

**Missing:**
- Audit claims P2P_STAR; code uses P2P_CLUSTER. P2P_CLUSTER allows arbitrary
  topology; P2P_STAR requires a hub. The strategic choice is reasonable but
  the audit is factually wrong about which strategy is used.
- No multi-guest integration test exists.

**Verdict:** `PARTIAL + FALSE DETAIL` (wrong strategy name cited)

---

### PR-21: Test-suite finisher (🟢)

**Claim:** "32 unit + 4 instrumentation tests."

**Reality:** 51 unit + 20 instrumented. This is referenced both in the audit
and in the README feature table.

---

### PR-20: Localisation (🟡)

**Claim in AUDIT.md:** "translated `values-xx/` dirs not yet committed" (marked yellow, roadmap item).

**Reality:** Translations were shipped in commit `Merge v1.1/full-translations:
162-string translations across 7 locales`. All 7 locale files now have 162
strings. The audit row was never updated to green after the translations landed.

**Verdict:** Row should be 🟢 with caveat that 35 of 197 base strings (18%)
remain English-only across all locales.

---

## D. `docs/features/` — PR labels vs. actual git history

The `docs/features/NN-*.md` files reference "PR-01" through "PR-22". Inspecting
`git log --oneline` reveals:

```
fa9bbb2 fix: comprehensive gesture-auth camera audit — 6 issue categories resolved
ea5a5a6 Merge pull request #48 from showerideas/antigrav
33e4dc4 feat: add haptic feedback, improved error handling and onboarding validation
ac96e13 docs: add ROADMAP.md
...
```

The `PR-NN` labels in feature docs are **internal tracking numbers**, not GitHub
pull request numbers. GitHub PR #48 is the highest visible merge, but the
feature docs reference up to PR-22. The numbers are a documentation convention,
not links to real GitHub PRs. There is no cross-reference between "PR-01" and
any GitHub PR URL.

**Verdict:** No action required if the labels are treated as internal sequence
numbers, but they should not be called "PRs" in docs if they don't correspond
to GitHub PR numbers. Consider renaming to "Feature-01" or linking to the
actual Git tags.

---

## E. `docs/SECURITY.md` — false threat model entries

| Section | Line | Claim | Reality |
|---|---|---|---|
| T6 | ~67 | "DTW match threshold + per-recording variance check + 3-strike cap" | Match is cosine similarity ≥ 0.88. No DTW, no variance check. 3-strike cap is real. |
| T2 | ~63 | "Active MITM that forwards Nearby connection requests — Identity challenge: each side signs a 32-byte nonce with its long-lived Keystore EC key; the attacker can't forge an ECDSA signature without the private key." | Correct for SECOND meeting (TOFU). **First meeting is TOFU with no SAS verification** — the identity key is accepted unconditionally from a new endpoint. A MITM that controls both Nearby endpoints on first meeting can substitute keys. This is the standard TOFU caveat and should be documented honestly. |

---

## Summary — Lines to delete / rewrite

```bash
# Confirm no DTW/accelerometer remnants remain after fixes:
grep -rni "dtw\|accelerometer\|variance\|resample\|50-point\|50 point\|magnitude" docs/
# Must return zero hits after Prompt 4 lands.

# Confirm test count in README matches reality:
grep "unit.*instrumentation\|instrumentation.*unit" README.md
# Must match: 51 unit + 20 instrumented (or higher after new tests added).
```
