# AURA — Engineering Roadmap

> Canonical planning document for the AURA gesture-biometric contact exchange system.
> Written as a strictly ordered sequence of engineering tasks for a software team.
> Tasks are sorted by dependency: each task may depend on those above it.
> References are cited inline where they are relevant — there is no separate reference table.
> Last rewrite: 2026-05-27 | v4.0.0 baseline | Phases 5–11 added | R&D-G,L,O,R,S,T,U,V,W graduated to scheduled implementation.

---

## How to Read This Document

This document is a **dependency-ordered implementation sequence**, not a feature wishlist.
Every task is placed where it is because something either upstream requires it, or completing it
unlocks the most subsequent work. Read it top to bottom before starting any task.

Status markers:
- `[ ]` — open, ready to implement
- `[PARTIAL]` — scaffolded or substantially implemented but not production-complete; see task detail
- `[R&D]` — design/research phase only; no code until explicitly moved to `[ ]`

**Current baseline: v4.0.0** on `main`. PRs #62–#134 all merged. All 66 original tasks complete.
Open work: Phases 5–11 (Tasks 67–118, 52 implementation tasks) + remaining R&D items (D, H, M, N, P, Q, X).

---

## Current System Snapshot

| Layer | State |
|---|---|
| Core app | v4.0.0 — production-ready |
| Gesture gate | MediaPipe Hands + temporal classifier (motion-profile analysis, 30-frame window) + 2-layer liveness (passive drift + active challenge) + continuous IMU collection + feature extraction. **Enrollment: single static hold (12 consecutive stable frames, cosine threshold 0.88). Phase 5 replaces this with temporal dual-descriptor enrollment.** |
| Transport | Google Nearby Connections (GMS) + Wi-Fi Direct (FOSS) + BLE GATT (BLE 6.2 SCI) + NFC HCE + QR relay + LoRa (opt-in: requires Meshtastic app + ENABLE_LORA=true build flag) |
| NFC | HCE ISO 7816-4 full impl + NDEF tap + reader mode + session token bootstrap |
| BLE GATT | Full GATT server/client + MTU 517 + chunked transfer + BLE Channel Sounding ranging (API 36+) |
| QR relay | AES-256-GCM HTTPS + Tor SOCKS5 (Orbot) + OHTTP RFC 9458 + QUIC/HTTP3 (Cronet) |
| Crypto | Hybrid KEM ML-KEM-768+X25519 · ML-DSA-65 identity signatures · PQXDH full prekey bundle · Sealed sender · Noise_XX channel · Double Ratchet + SPQR · MLS RFC 9420 rooms (simplified flat topology, not full TreeKEM) · SAS · TOFU |
| Wire protocol | v9 — SPKI pinning · ML-DSA-65 hybrid sigs · identity rotation · replay protection · PQ hybrid KEM · Noise_XX overlay |
| Multi-profile | Personal / Work — wired; enterprise MDM retention |
| Audit log | ExchangeAuditLog Room table + CSV export + AuditFragment UI + differential privacy ε=1.0 analytics export |
| Identity | W3C Verifiable Credentials (did:key + JsonWebSignature2020) · ISO 18013-5 mdoc/mDL (id.aura.contact.1) · OpenID4VP verifiable presentation |
| Localization | 365 strings × 7 locales — 100% coverage, human-reviewed |
| Test suite | 623+ unit methods + 72 instrumented + 36 iOS tests — JaCoCo 60% branch floor |
| CI | Green — lint + unit + JaCoCo + assembleRelease + APK size gate + iOS build/test |
| Distribution | F-Droid reproducible build script + submission guide — live |
| Signing | PKCS12 keystore in GitHub Secrets — signed AAB confirmed |
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, WireProtocol.swift, MultipeerTransport, NFCExchangeBootstrap; 36 tests |
| Wear OS | Glance tile + Health Connect HRV + SasPinActivity + WristRaiseTrigger; Wear OS 7 production-complete |
| Android Auto | Voice action + biometric auth gate; full screen library |
| Room sessions | Multi-party card exchange — star topology, 10-min TTL, delivery ACK, MLS group key agreement |
| Mesh | Store-and-forward (BLE bloom filter) + multi-hop Wi-Fi Direct mesh (5 hops) |
| Analytics | On-device exchange analytics — transport breakdown, heatmap, PDF export (differential privacy) |
| Enterprise | 6 MDM restriction keys + zero-touch enrollment + signed audit export + Advanced Protection API |
| Desktop | KMP desktop companion — QR relay transport |

---

## Phase 5 — User-Defined Gesture Enrollment Engine (Target: v5.0)

### Architecture decision record — LOCKED. Do not negotiate these constraints.

These four decisions are locked. Any implementation that deviates must get explicit sign-off:

1. **2-second temporal capture window.** Exactly 60 frames at 30 fps. Not shorter. Not longer. Not user-configurable. The window is fixed and machine-enforced.
2. **Hard open-palm anchor.** If the open-palm landmark configuration is not present at capture start (first 3 frames), the capture is rejected immediately. There is no partial-capture fallback. The user receives a retry prompt.
3. **Dual overlapping temporal bone graph windows.** Window A spans frames 0→1.5s (45 frames). Window B spans frames 0.5→2.0s (frames 15→60). Both windows are computed independently. Each produces one descriptor. Both descriptors are stored.
4. **Dual-descriptor independent matching.** During verification, both stored descriptors must independently satisfy the similarity threshold. A match on one descriptor alone is not sufficient. This provides confidence without additional user cost.

These constraints apply identically at enrollment time and verification time — same capture window, same anchor requirement, same dual-descriptor production. Architectural symmetry is mandatory.

---

### Task 67 — `GestureEnrollmentCapture`: temporal 2-second capture pipeline

**What it does:** Replaces the existing 12-frame consecutive-stability gate with a 2-second
fixed-duration temporal capture. The camera runs for exactly 2 seconds (60 frames at 30 fps).
Frames are accumulated into a buffer. At window end, the buffer is handed to the downstream
descriptor extractor.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/auth/enrollment/GestureEnrollmentCapture.kt`
- `app/src/main/java/com/showerideas/aura/auth/enrollment/EnrollmentCaptureState.kt`

**Files to modify:**
- `GestureAuthManager.kt` — add enrollment mode flag; route to new pipeline when enrolling

**`EnrollmentCaptureState` sealed class:**

```kotlin
sealed class EnrollmentCaptureState {
    object WaitingForPalm : EnrollmentCaptureState()           // anchor not yet confirmed
    data class Capturing(val progressFraction: Float) : EnrollmentCaptureState()  // 0.0..1.0
    object AnchorFailed : EnrollmentCaptureState()             // palm absent at start — retry
    data class CaptureComplete(val frames: List<FloatArray>) : EnrollmentCaptureState()
}
```

**Open-palm anchor validation (hard constraint):**
- Frames 0–2 (first 100 ms): check that `GestureRecognizer` top category is `Open_Palm`
  with confidence ≥ 0.72 AND hand landmark set is present.
- If either condition fails for any of the first 3 frames → emit `AnchorFailed`. Do not
  proceed to capture. The UI shows a retry prompt: "Start with your palm open and flat."
- Do not collect a partial buffer and continue — abort immediately.

**Frame buffer:**
- `frameBuffer: MutableList<FloatArray>` — each entry is a 63-float normalised landmark
  embedding (same normalisation as existing `CameraHandEmbedder.normalizeEmbedding()`).
- Capacity: 60 frames. If MediaPipe drops a frame (returns null landmarks), insert the
  previous frame's embedding as a duplicate (no gap interpolation needed — temporal
  continuity is more important than frame-perfect accuracy here).
- At frame 60, emit `CaptureComplete(frameBuffer.toList())`.

**Timing:**
- The 2-second clock starts from the first successfully validated anchor frame, not from
  when the camera starts. The user may spend 0–N seconds presenting their palm before the
  anchor is confirmed. The 2-second window begins at anchor confirmation.

**References:**
- Existing `CameraHandEmbedder.normalizeEmbedding()` — reuse this method for embedding
  extraction. Do not duplicate the normalization logic.
- [MediaPipe GestureRecognizer Android docs](https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer/android)
- `docs/GESTURE_AUTH.md` §2 — existing camera pipeline reference

**Acceptance criteria:**
- `AnchorFailed` is emitted if palm landmark is absent in any of the first 3 frames
- `Capturing(progress)` emits every frame during the 2-second window; `progress` is
  `frameCount / 60f` and is monotonically increasing
- `CaptureComplete` is emitted with exactly 60 entries
- Unit test: mock 60 frames of landmarks → `CaptureComplete` with correct buffer size
- Unit test: feed non-palm frame at position 0 → `AnchorFailed`

---

### Task 68 — `DualBoneGraphTracker`: dual overlapping temporal descriptor extraction

**What it does:** Takes the 60-frame buffer from Task 67 and produces two independent
descriptors — one from window A (frames 0→44, 0→1.5s) and one from window B (frames
15→59, 0.5→2.0s). Each window produces a bone-graph motion descriptor.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/auth/enrollment/DualBoneGraphTracker.kt`
- `app/src/main/java/com/showerideas/aura/auth/enrollment/GestureDescriptor.kt`

**`GestureDescriptor` data class:**
```kotlin
data class GestureDescriptor(
    val centroid: FloatArray,      // mean landmark vector across the window (63 floats)
    val motionProfile: FloatArray, // frame-to-frame cosine delta sequence (44 floats for 45-frame window, 44 for B)
    val windowTag: WindowTag,      // A or B
    val capturedAtMs: Long
) {
    enum class WindowTag { A, B }
}
```

**Bone graph extraction per window:**
1. Take the sub-array of frames for the window (A: indices 0–44, B: indices 15–59).
2. `centroid` = element-wise mean across all 45 frames → 63-float vector. This is the
   static shape component — where the hand was on average.
3. `motionProfile` = for each consecutive frame pair, compute cosine similarity of the
   two 63-float embeddings → produces 44 values (N-1 deltas for N frames). This is the
   temporal motion component — how the hand moved.
4. The descriptor is `[centroid (63) || motionProfile (44)]` = 107-float compound vector.
   Matching in Task 70 operates on the full compound vector.

**Why two windows:** Window A captures the initiation of the gesture (how it starts).
Window B captures the completion (how it ends). Together they bracket the full gesture arc.
A replay or impersonation attack that matches one window is statistically unlikely to
match both independently.

**No cross-window averaging.** Window A and Window B are fully independent extractions.
Their descriptors are never merged, blended, or averaged. They are stored separately and
matched separately.

**References:**
- [kinivi/hand-gesture-recognition-mediapipe](https://github.com/kinivi/hand-gesture-recognition-mediapipe) — MLP over
  keypoints as reference for descriptor design decisions
- Existing `CameraHandEmbedder.cosineSimilarity()` — reuse for motionProfile computation

**Acceptance criteria:**
- Given a 60-frame buffer, produces exactly two `GestureDescriptor` objects — one A, one B
- `motionProfile` length is 44 for both windows (45-frame window → 44 deltas)
- `centroid` length is always 63
- Unit test: identical frames in both windows → motionProfile is all-1.0f (no motion)
- Unit test: window A and B receive correct slice of the 60-frame buffer

---

### Task 69 — `GestureDescriptorStore`: encrypted dual-descriptor persistence

**What it does:** Persists both `GestureDescriptor` objects from Task 68 into
`EncryptedSharedPreferences` under the existing `aura_gesture_prefs` file. Replaces
the current single-embedding storage path.

**Files to modify:**
- `GestureAuthManager.kt` — remove `gesture_feature_vector` write path; add new storage calls

**Files to create:**
- `app/src/main/java/com/showerideas/aura/auth/enrollment/GestureDescriptorStore.kt`

**Storage schema:**

| Key | Type | Content |
|---|---|---|
| `gesture_descriptor_a_centroid` | String (comma-separated floats) | Window A centroid (63 floats) |
| `gesture_descriptor_a_motion` | String (comma-separated floats) | Window A motionProfile (44 floats) |
| `gesture_descriptor_b_centroid` | String (comma-separated floats) | Window B centroid (63 floats) |
| `gesture_descriptor_b_motion` | String (comma-separated floats) | Window B motionProfile (44 floats) |
| `gesture_descriptor_version` | Int | Schema version (start at 2) |
| `gesture_pattern_id` | String (UUID) | Unchanged — per-enrollment ID |

**Backward compatibility:**
- On load, if `gesture_descriptor_version` is absent or < 2, the old single embedding
  is present at `gesture_feature_vector`. Emit a migration prompt: the user must re-enroll.
  Do not attempt to fabricate a dual-descriptor from the old single embedding.
- Retain the existing `EXPECTED_EMBEDDING_SIZE = 63` const in `CameraHandEmbedder`
  for legacy detection only.

**StrongBox path:**
- Existing `EncryptedSharedPreferences` master key alias `aura_esp_master` is already
  backed by StrongBox where hardware supports it. No change to the key hierarchy.
- The compound descriptor (centroid + motionProfile) is written as two separate comma-separated
  strings for simplicity; this is acceptable given the existing encryption envelope.

**Acceptance criteria:**
- Write both descriptors → read them back → byte-exact match
- If old schema key `gesture_feature_vector` is present, `hasLegacyEnrollment()` returns true
- `hasValidEnrollment()` returns true only when all four new keys are present and parseable
- Unit test: round-trip serialization of `GestureDescriptor` through the store

---

### Task 70 — `GestureVerificationEngine`: dual-descriptor matching at verification

**What it does:** During verification, runs the same capture pipeline (Task 67) and
descriptor extraction (Task 68), then matches both live descriptors against both stored
descriptors. Both must pass independently.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/auth/enrollment/GestureVerificationEngine.kt`

**Matching algorithm:**
1. Run `GestureEnrollmentCapture` to produce 60 live frames.
2. Run `DualBoneGraphTracker` to produce `liveA` and `liveB`.
3. Load `storedA` and `storedB` from `GestureDescriptorStore`.
4. Match A: compute cosine similarity of `[liveA.centroid || liveA.motionProfile]` against
   `[storedA.centroid || storedA.motionProfile]`. Pass threshold: 0.85.
5. Match B: compute cosine similarity of `[liveB.centroid || liveB.motionProfile]` against
   `[storedB.centroid || storedB.motionProfile]`. Pass threshold: 0.85.
6. Return `VerificationResult.Success` only if **both** A and B pass.
7. If either fails, return `VerificationResult.Failure(whichWindowFailed)`.

**Threshold note:** 0.85 for the compound 107-float vector. This is slightly below the
existing 0.88 single-embedding threshold because the compound vector is larger and inherently
noisier (motionProfile introduces temporal variance). Tune post-integration via
`HandEmbeddingEntropyTest`.

**Hard constraint:** Do not soften to OR logic under any circumstance. If A passes and B
fails, the result is failure. This is a locked architectural constraint.

**`VerificationResult` sealed class:**
```kotlin
sealed class VerificationResult {
    object Success : VerificationResult()
    data class Failure(val failedWindow: GestureDescriptor.WindowTag?) : VerificationResult()
    object AnchorFailed : VerificationResult()       // palm not detected at capture start
    object NoEnrollment : VerificationResult()       // no descriptor in store
    object LegacyEnrollment : VerificationResult()   // old schema — re-enrollment required
}
```

**Integration with `GestureAuthManager`:**
- Replace `GestureAuthManager.match(candidate)` call site with
  `GestureVerificationEngine.verify()` coroutine
- Preserve the 3-attempt lockout logic in `GestureAuthManager` — wire it to
  `VerificationResult.Failure`

**Acceptance criteria:**
- Both-pass → `Success`
- A-fails, B-passes → `Failure(WindowTag.A)`
- A-passes, B-fails → `Failure(WindowTag.B)`
- AnchorFailed from capture → `AnchorFailed`
- Empty store → `NoEnrollment`
- Legacy schema → `LegacyEnrollment`
- Unit tests cover all six result paths

---

### Task 71 — `GestureEnrollmentFragment`: enrollment UI with capture feedback

**What it does:** New Fragment implementing the enrollment UX. Accessible from Profile →
Gesture Library. Shows camera preview during capture, visualizes progress, handles retry
on anchor failure, and confirms success.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/ui/enrollment/GestureEnrollmentFragment.kt`
- `app/src/main/res/layout/fragment_gesture_enrollment.xml`
- `app/src/main/res/layout/view_enrollment_progress.xml`

**Files to modify:**
- `app/src/main/res/navigation/nav_graph.xml` — add enrollment destination
- Profile settings entry point — add "Set Up Gesture" navigation action

**UI state machine (driven by `EnrollmentCaptureState`):**

| State | UI |
|---|---|
| `WaitingForPalm` | Camera preview active. Overlay text: "Hold your palm open and flat to begin." Pulsing border — cyan. |
| `AnchorFailed` | Brief haptic (single tick). Overlay text: "Start with your palm open and flat." Auto-reset to `WaitingForPalm` after 1.5s. |
| `Capturing(progress)` | Circular progress arc fills over 2 seconds (driven by `progressFraction`). Overlay: "Hold your gesture..." Border transitions to solid green. |
| `CaptureComplete` | Spinner for ~0.3s during descriptor extraction. Then `VerificationResult`. |
| Verification pass | Bottom sheet: "Gesture saved." Primary action: "Done". Optional: "Try it now" → re-runs verification for user confidence. |
| Verification fail | Toast: "Couldn't recognise your gesture. Please try again." Returns to `WaitingForPalm`. |

**Anchor feedback overlay:**
- The open-palm landmark positions (wrist + 5 MCP joints, 5 fingertip joints — 11 key
  points from MediaPipe 21-point model) should be drawn as a ghost skeleton on the
  preview during `WaitingForPalm`, showing the user exactly what hand shape to present.
- Use `android.graphics.Canvas` over a transparent overlay `SurfaceView` — do not use
  a separate ARCore dependency for this simple overlay.

**Accessibility:**
- All states have TalkBack-compatible content descriptions.
- Progress arc has `android:accessibilityLiveRegion="polite"` equivalent via
  `ViewCompat.setAccessibilityLiveRegion`.

**Acceptance criteria:**
- Full enrollment flow completes without crash on Pixel 8 (API 35) and Galaxy A52 (API 34)
- Anchor-failed retry loop works: miss palm 3 times → still recovers to successful enrollment
- "Try it now" re-verification path exercises `GestureVerificationEngine.verify()`
- UI tests cover state transitions: `WaitingForPalm → AnchorFailed → WaitingForPalm → Capturing → Complete`

---

### Task 72 — `GestureEnrollmentViewModel`: enrollment state management

**What it does:** `@HiltViewModel` bridging the UI (Task 71) and the capture/extraction
pipeline (Tasks 67–69). Owns the enrollment coroutine scope and exposes `StateFlow`.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/ui/enrollment/GestureEnrollmentViewModel.kt`

**Key StateFlows:**
```kotlin
val captureState: StateFlow<EnrollmentCaptureState>
val enrollmentStatus: StateFlow<EnrollmentStatus>   // Idle | Enrolling | Success | Failed(reason)
val verificationResult: StateFlow<VerificationResult?>
```

**Enrollment sequence:**
1. `startEnrollment()` → launches `GestureEnrollmentCapture.start()` coroutine
2. Collects `EnrollmentCaptureState` → forwards to `captureState`
3. On `CaptureComplete(frames)` → calls `DualBoneGraphTracker.extract(frames)` → descriptors
4. Calls `GestureDescriptorStore.save(descriptorA, descriptorB)` → emits `EnrollmentStatus.Success`
5. Optionally calls `GestureVerificationEngine.verify()` for confirmation — emits `verificationResult`

**Error handling:**
- `AnchorFailed` → reset state → allow retry; do not count toward any lockout
- Extraction failure (exception) → `EnrollmentStatus.Failed("Extraction error")` → allow retry
- Storage failure → `EnrollmentStatus.Failed("Could not save gesture")` → allow retry

**Acceptance criteria:**
- ViewModel survives configuration change mid-capture (capture continues in background)
- Unit test with `TestCoroutineDispatcher`: full happy path emits `Success`
- Unit test: `AnchorFailed` three times in a row → state resets correctly each time

---

### Task 73 — Migrate `GestureAuthManager` to dual-descriptor stack

**What it does:** Removes the old single-embedding enrollment path from `GestureAuthManager`
and routes all enrollment and verification calls through the new pipeline from Tasks 67–70.

**Files to modify:**
- `GestureAuthManager.kt`
- `CameraHandEmbedder.kt` — keep normalisation utilities; remove `COMMIT_FRAMES = 12` gate
  which is superseded by `GestureEnrollmentCapture`
- All call sites of `GestureAuthManager.match()` — update to `GestureVerificationEngine.verify()`

**Backward compatibility gate:**
- `GestureAuthManager.hasEnrollment(): Boolean` now delegates to `GestureDescriptorStore.hasValidEnrollment()`
- Add `GestureAuthManager.hasLegacyEnrollment(): Boolean` → if true, show re-enrollment prompt
  in the exchange flow before the first verification attempt post-upgrade
- The legacy `gesture_feature_vector` key is read-only for detection; never write to it again

**Exchange flow integration:**
- `ExchangeFragment` calls `GestureAuthManager.verify()` which now calls
  `GestureVerificationEngine.verify()` internally
- `VerificationResult.LegacyEnrollment` → navigate to `GestureEnrollmentFragment` with a
  dialog: "Your gesture enrollment needs to be updated for improved security. This takes 2 seconds."
- `VerificationResult.NoEnrollment` → existing prompt: "No gesture set — exchange is unprotected. Continue?"

**Acceptance criteria:**
- No references to `gesture_feature_vector` remain in write paths
- `COMMIT_FRAMES = 12` constant removed from `CameraHandEmbedder` (or explicitly deprecated)
- Full integration test: enroll → verify → success, on a single device

---

### Task 74 — Update `docs/GESTURE_AUTH.md` for dual-descriptor architecture

**What it does:** Rewrites the gesture authentication documentation to reflect the new
2-second temporal enrollment system. The existing doc describes the 12-frame stability gate
which is superseded.

**Sections to rewrite:**
- §1 Pipeline overview — new Mermaid flowchart showing anchor → 2s capture → dual-descriptor extraction → match
- §2 CameraX + MediaPipe pipeline — unchanged hardware path; add note about 60-frame buffer
- §3 Embedding extraction — unchanged 63-float normalisation formula; add note that it's
  now applied to all 60 frames, not just the single stable frame
- §4 Consecutive-frame stability gate → replace entirely with §4 "Temporal capture window
  and open-palm anchor"
- §6 Matching → rewrite for dual-descriptor 107-float compound cosine match
- §7 Security properties → update FAR estimate for dual-descriptor system (expected to be
  significantly lower than current 30–70% because both temporal windows must match)

**Do not remove:**
- §9 Historical note mentioning the DTW/accelerometer removal — add a new historical note
  for the 12-frame stability gate removal in Phase 5

**Acceptance criteria:**
- All references to `COMMIT_FRAMES = 12` and `STABILITY_THRESHOLD = 0.97` are removed
  or moved to the historical note
- New flowchart accurately reflects `WaitingForPalm → AnchorFailed | Capturing → CaptureComplete`
- False-accept rate section carries a `TODO: remeasure with dual-descriptor` marker

---

### Task 75 — Unit tests: enrollment pipeline

**What it does:** Full unit test coverage for Tasks 67–70.

**Files to create:**
- `app/src/test/java/com/showerideas/aura/auth/enrollment/GestureEnrollmentCaptureTest.kt`
- `app/src/test/java/com/showerideas/aura/auth/enrollment/DualBoneGraphTrackerTest.kt`
- `app/src/test/java/com/showerideas/aura/auth/enrollment/GestureDescriptorStoreTest.kt`
- `app/src/test/java/com/showerideas/aura/auth/enrollment/GestureVerificationEngineTest.kt`

**Test helper:** `TestLandmarkFactory.kt` — generates synthetic 63-float landmark frames
for test scenarios: constant pose, slow rotation, fast flick, random noise.

**Key test cases:**
- `GestureEnrollmentCaptureTest`: anchor present → 60 frames collected; anchor absent at frame 0 → `AnchorFailed`; dropped frame at frame 30 → duplicate inserted, count still 60
- `DualBoneGraphTrackerTest`: identical frames → motionProfile all 1.0; A and B receive correct slices; descriptor lengths correct (63+44=107 total components)
- `GestureDescriptorStoreTest`: round-trip serialization; legacy key detection; `hasValidEnrollment` false when partial keys missing
- `GestureVerificationEngineTest`: both match → Success; A only → Failure(A); B only → Failure(B); no enrollment → NoEnrollment; legacy enrollment → LegacyEnrollment

**JaCoCo floor:** These new classes should bring branch coverage to ≥ 65% (up from 60%).
The `GestureVerificationEngine` branch paths are the primary coverage drivers.

**Acceptance criteria:**
- All tests pass in `./gradlew :app:testDebugUnitTest`
- No flakiness — tests use deterministic synthetic landmark data, never real camera frames

---

### Task 76 — Instrumented test: full enrollment + verification integration

**What it does:** End-to-end instrumented test on a real device verifying the full enrollment
→ verification round-trip without mocking the camera pipeline.

**Files to create:**
- `app/src/androidTest/java/com/showerideas/aura/auth/enrollment/EnrollmentIntegrationTest.kt`

**Approach:**
- Inject a `FakeLandmarkSource` (test double) that feeds synthetic landmark sequences into
  `GestureEnrollmentCapture` via constructor injection in test builds.
- `FakeLandmarkSource` implements the same interface as the `CameraHandEmbedder`'s landmark
  emission path so no real camera access is needed in CI.
- The test double emits: 3 frames of `Open_Palm` (anchor) → 60 frames of a synthetic gesture
  sequence → end of stream.

**Acceptance criteria:**
- Test passes on API 26 emulator (min SDK)
- Test passes on API 35 device (current target)
- Enrollment → verify with same sequence → Success
- Enrollment → verify with shuffled sequence → Failure

---

## Phase 6 — Android 17 Native PQC Keystore (Target: v5.1)

### Context

Android 17 (API 37, expected Q3 2026) adds `KeyPairGenerator` support for ML-DSA-65 and
ML-DSA-87 directly in hardware-backed Android Keystore. AURA currently uses BouncyCastle
for ML-DSA-65 signing operations with the private key held in software. This phase migrates
signing to the hardware-backed native API, eliminating the BouncyCastle dependency for signing
and improving key isolation.

See: [Android 17 PQC announcement](https://www.privacyguides.org/news/2026/03/26/android-17-is-getting-a-post-quantum-cryptography-upgrade/)
See: [Google Security blog — PQC in Android](https://blog.google/security/security-for-the-quantum-era-implementing-post-quantum-cryptography-in-android/)

---

### Task 77 — Conditional ML-DSA-65 native Keystore path (API 37+)

**What it does:** Adds an API-level-gated code path in `HybridIdentityKey.kt` that uses
`KeyPairGenerator("ML-DSA-65", "AndroidKeyStore")` on API 37+ devices instead of the
BouncyCastle provider.

**Files to modify:**
- `app/src/main/java/com/showerideas/aura/crypto/HybridIdentityKey.kt`
- `app/src/main/java/com/showerideas/aura/crypto/HybridSignature.kt`

**Implementation pattern:**
```kotlin
private fun generateIdentityKey(): KeyPair {
    return if (Build.VERSION.SDK_INT >= 37) {
        KeyPairGenerator.getInstance("ML-DSA-65", "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(IDENTITY_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setIsStrongBoxBacked(true)   // request StrongBox; falls back if unavailable
                    .build()
            )
        }.generateKeyPair()
    } else {
        // existing BouncyCastle path — unchanged
        generateIdentityKeyLegacy()
    }
}
```

**Key alias:** Keep existing alias `aura_device_identity`. The migration triggers only for
newly generated keys (new installs or explicit key rotation). Existing installs retain the
BouncyCastle-generated key until the user rotates.

**Key rotation path:**
- Add `HybridIdentityKey.migrateToNativeKeystore(): Boolean` — returns true if API 37+
  and migration was performed. Called from `IdentityKeyRotator` on first launch post-upgrade.
- Old BouncyCastle key is deleted from EncryptedSharedPreferences after migration confirmed.
- Peers see this as a normal identity rotation (existing `IdentityRotationDetector` path).

**Acceptance criteria:**
- API 37+ emulator: new identity key uses `AndroidKeyStore` provider for ML-DSA-65
- API 35 device: falls back to existing BouncyCastle path without crash
- `HybridSignature.sign()` produces valid signatures on both paths
- Unit test: sign + verify round-trip on both API paths (mock `Build.VERSION.SDK_INT`)

---

### Task 78 — Remote Attestation PQC certificate chain upgrade

**What it does:** AURA's existing remote attestation path (used in enterprise deployments
to prove device integrity) receives KeyMint certificate chains that are transitioning to
PQC-hybrid on Android 17. Update the attestation parser to accept both the legacy ECDSA
chain and the new ML-DSA hybrid chain.

**Files to modify:**
- `app/src/main/java/com/showerideas/aura/security/StrongBoxKeyManager.kt` — attestation
  certificate chain validation

**What changes:**
- Certificate chain parser must accept both `SHA256withECDSA` and `ML-DSA-65` signature
  algorithms on intermediate certificates.
- Add `AttestationChainValidator.supportsHybridChain(): Boolean` — returns true on API 37+.
- The root of trust check (Google Hardware Attestation Root CA) is unchanged.

**Acceptance criteria:**
- Existing ECDSA chains still validate on API ≤ 36
- ML-DSA hybrid chains validate on API 37+
- Invalid chain (tampered cert) still throws `CertificateException`

---

### Task 79 — Deprecate BouncyCastle ML-DSA dependency on API 37+ builds

**What it does:** Once Tasks 77 and 78 are complete on a device, the BouncyCastle ML-DSA
provider is no longer needed for the signing hot path. Add a ProGuard rule to strip the
BouncyCastle ML-DSA classes on API 37+ release builds, reducing APK size.

**Files to modify:**
- `app/proguard-rules.pro`
- `build.gradle.kts` — add split APK dimension or `resConfigs` if build-time API targeting
  is feasible; otherwise runtime check is acceptable

**Note:** BouncyCastle is still needed for ML-KEM-768 key encapsulation on all API levels
until Android 17 adds ML-KEM native Keystore support (not yet announced). Only the ML-DSA
signing path is removable at API 37+.

**Acceptance criteria:**
- Release APK on API 37+ build: ML-DSA BouncyCastle classes absent (verify with `apkanalyzer`)
- Release APK on API 35 build: ML-DSA BouncyCastle classes present
- Test suite still passes on both builds

---

### Task 80 — `BuildConfig.NATIVE_ML_DSA_AVAILABLE` flag + CI matrix

**What it does:** Adds a `BuildConfig` boolean computed at build time (or at runtime via
`Build.VERSION.SDK_INT >= 37`) so tests and feature flags can branch cleanly. Updates CI
to run the test matrix on both API 35 and API 37 emulators.

**Files to modify:**
- `app/build.gradle.kts` — add `buildConfigField` or compute at runtime
- `.github/workflows/android.yml` — add `api-level: [35, 37]` matrix to instrumented test job

**Acceptance criteria:**
- CI runs instrumented tests on API 35 and API 37 emulators
- `BuildConfig.NATIVE_ML_DSA_AVAILABLE` is `false` on API 35, `true` on API 37

---

## Phase 7 — FIDO2 Platform Authenticator + Hardware Key Bridge (Target: v5.2)

### Context

AURA's gesture gate is architecturally a FIDO2 platform authenticator: it gates access to
a cryptographic private key behind a behavioral biometric. This phase exposes AURA as a
`CredentialProvider` to the Android system so any app or website that requests passkey
authentication can use AURA's gesture gate as the user verification method.

The hardware key bridge (R&D-S) is paired here: enterprise users may have a hardware security
key (YubiKey NFC, SoloKey) that they want AURA to relay, providing hardware root-of-trust
without requiring the relying party to know about AURA.

See: [Android CredentialManager API](https://developer.android.com/training/sign-in/passkeys)
See: [CTAP2 specification](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html)
See: [SoloKeys open FIDO2](https://solokeys.com/)
See: [libfido2 — Yubico](https://github.com/Yubico/libfido2)

**Prerequisite:** Phase 5 (gesture enrollment) must be complete. The FIDO2 assertion
is signed with the ML-DSA identity key gated behind `GestureVerificationEngine.verify()`.

---

### Task 83 — `AuraCredentialProviderService`: register AURA as Android CredentialManager provider

**Files to create:**
- `app/src/main/java/com/showerideas/aura/fido/AuraCredentialProviderService.kt`
- `app/src/main/res/xml/credential_provider_config.xml`

**Manifest entry:**
```xml
<service android:name=".fido.AuraCredentialProviderService"
         android:exported="true"
         android:permission="android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE">
    <intent-filter>
        <action android:name="android.service.credentials.CredentialProviderService" />
    </intent-filter>
    <meta-data android:name="android.credentials.provider"
               android:resource="@xml/credential_provider_config" />
</service>
```

**What it does:**
- Registers AURA as a passkey credential provider on Android 14+ (`CredentialManager` API).
- Returns a list of AURA-managed passkeys when the system queries for matching credentials.
- Launches `GestureEnrollmentFragment` for biometric verification when a passkey assertion
  is requested.

**Acceptance criteria:**
- AURA appears in Android Settings → Passwords & Accounts → Credential providers
- Basic passkey creation and assertion flow demonstrated with a test relying party

---

### Task 84 — Passkey storage backed by `GestureVerificationEngine`

**What it does:** Implements passkey creation and signing using AURA's identity key hierarchy.
Passkey private key is stored in Android Keystore, gated behind gesture verification.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/fido/PasskeyRepository.kt`
- Room `PasskeyDao` + `PasskeyEntity` — stores relying party ID, credential ID, user handle

**Key binding:** Passkey signing key uses `KeyGenParameterSpec` with
`setUserAuthenticationRequired(true)` and `setUserAuthenticationParameters(0, KeyProperties.AUTH_DEVICE_CREDENTIAL)`.
AURA unlocks the key by completing gesture verification, then presents the FIDO2 assertion.

**Acceptance criteria:**
- Passkey creation: challenge signed with key that required gesture unlock
- Assertion: live gesture verification → unlock → sign → return to relying party

---

### Task 85 — FIDO2 gesture latency benchmark and threshold tuning

**What it does:** Measures end-to-end latency from FIDO2 assertion request to signed
response, with the gesture verification path in the middle. CTAP2 has a 30-second
timeout; the gesture capture is 2 seconds + descriptor extraction ~50ms + signing ~200ms.
Total should be well within limits.

**Output:** A benchmark report committed to `docs/FIDO2_LATENCY.md` with p50/p95/p99
measurements on Pixel 8, Galaxy S24, and a low-end device (Pixel 4a or equivalent).

**Acceptance criteria:**
- p99 end-to-end latency (assertion request to signed response) < 5 seconds on all tested devices
- Report committed and linked from `ROADMAP.md`

---

### Task 86 — NFC CTAP2 relay to hardware security key

**What it does:** AURA receives a CTAP2 APDU from a reader via NFC HCE (existing AID
registration in `AuraHceService`), forwards it to a paired hardware security key via
NFC reader mode, and returns the response. The phone is a transparent relay.

**Files to modify:**
- `AuraHceService.kt` — detect CTAP2 APDUs (AID `A0000006472F0001`) and route to relay path
- Add `CtapNfcRelay.kt` — opens NFC reader session, forwards APDU, returns response within 2s

**Relay latency requirement:** Total RTT for relay path < 2 seconds (CTAP2 timeout tolerance).
Benchmark before shipping.

**Enterprise toggle:** `BuildConfig.ENABLE_HW_KEY_RELAY = false` by default.
Enable via MDM policy key `fido2HardwareKeyRelay: true`.

**Acceptance criteria:**
- YubiKey 5 NFC successfully relayed through AURA to a test CTAP2 verifier
- Relay path RTT < 2s on Pixel 8

---

### Task 87 — FIDO2 provider settings UI

**What it does:** Settings → Security → AURA as Password Provider: shows enrolled passkeys
with relying party, creation date, and delete action. Shows hardware key relay status if enabled.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/ui/settings/FidoSettingsFragment.kt`

**Acceptance criteria:**
- PasskeyEntity list renders correctly
- Delete passkey removes it from Room and from CredentialManager
- Hardware key relay toggle visible only when `ENABLE_HW_KEY_RELAY = true`

---

### Task 88 — FIDO2 unit and integration tests

**Files to create:**
- `PasskeyRepositoryTest.kt` — CRUD + key binding logic
- `CtapNfcRelayTest.kt` — mock NFC stack; verify APDU forwarding round-trip

**Acceptance criteria:**
- Test coverage for `PasskeyRepository` ≥ 80% branch
- Relay test passes with mock NFC stack (no real hardware needed in CI)

---

## Phase 8 — ZK-SNARK Gesture Template Privacy (Target: v5.3)

### Context

The enrolled gesture descriptors stored in `GestureDescriptorStore` (Phase 5) are raw
floating-point vectors. A ZK-SNARK circuit allows AURA to prove it performed a gesture
matching the enrolled template — without the verifier (or an enterprise audit server)
ever seeing the template itself. The enrolled descriptors become private witnesses; only
a zero-knowledge proof is exported.

Enterprise compliance use case: audit logs currently export `livenessConfidence: Float`.
With ZK proofs, this becomes `gestureProof: ByteArray (192 bytes)` — the verifier confirms
authentication occurred without receiving any biometric data.

See: [gnark — fastest Go ZK-SNARK library](https://github.com/ConsenSys/gnark)
See: [arkworks-rs](https://github.com/arkworks-rs/arkworks)
See: [ZKP mobile applications overview](https://zimperium.com/glossary/zero-knowledge-proofs)
See: [Zero-knowledge auth in mobile apps](https://hashstudioz.com/blog/zero-knowledge-authentication-in-mobile-apps)

**Library recommendation:** `gnark` (ConsenSys, Apache-2.0). Groth16 proof size is 192 bytes,
verification time < 1ms. Proving time on mid-range Android (Snapdragon 730G equivalent)
is estimated 2–4s for a circuit of this complexity — within acceptable bounds post-gesture-capture.
JNI bridge: compile gnark circuit to ARM64 shared library via `gomobile bind` or CGo.

---

### Task 89 — ZK circuit design: cosine similarity gate

**What it does:** Designs and implements the ZK-SNARK circuit in gnark that proves
"the live descriptor has cosine similarity ≥ 0.85 to the enrolled descriptor" without
revealing the enrolled descriptor.

**Circuit inputs:**
- Private witness: `enrolledDescriptor [107]float64` — the stored compound vector
- Public input: `liveDescriptor [107]float64` — the live capture (not secret)
- Public output: `1` if `cosine_sim(live, enrolled) ≥ 0.85`, else `0`

**Circuit implementation (Go, gnark):**
```go
type GestureMatchCircuit struct {
    EnrolledDescriptor [107]frontend.Variable  `gnark:",secret"`
    LiveDescriptor     [107]frontend.Variable  `gnark:",public"`
    IsMatch            frontend.Variable        `gnark:",public"`
}

func (c *GestureMatchCircuit) Define(api frontend.API) error {
    // compute dot product, norms, cosine similarity as field arithmetic
    // assert IsMatch == (cosine_sim >= 0.85)
    ...
}
```

**Implementation notes:**
- Cosine similarity in a ZK circuit requires fixed-point arithmetic. Use `bits = 64`,
  `precision = 32` for acceptable numeric stability.
- The threshold comparison uses a range proof: `sim_numerator * scale >= threshold_numerator * norm_product`.
- Proving key / verifying key are generated once at build time and bundled in `assets/zk/`.
- Proof generation runs on a `Dispatchers.Default` coroutine, not the main thread.

**Files to create:**
- `zk/gesture_circuit.go` — gnark circuit definition (lives in repo root `zk/` directory)
- `app/src/main/jniLibs/arm64-v8a/libgesturezk.so` — compiled ARM64 library
- `app/src/main/java/com/showerideas/aura/zk/GestureZkProver.kt` — JNI wrapper
- `app/src/main/assets/zk/gesture_proving_key.bin`
- `app/src/main/assets/zk/gesture_verifying_key.bin`

**Acceptance criteria:**
- Circuit compiles in gnark with Groth16 backend
- Valid gesture → proof generated → proof verified locally → `isMatch = 1`
- Invalid gesture → proof generated → verified → `isMatch = 0` (honest prover)
- Proof size ≤ 200 bytes

---

### Task 90 — `GestureZkProver`: Android JNI integration

**What it does:** Android Kotlin interface to the gnark JNI library for proof generation
and verification.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/zk/GestureZkProver.kt`
- `app/src/main/java/com/showerideas/aura/zk/ZkProofResult.kt`

```kotlin
object GestureZkProver {
    external fun generateProof(
        enrolledDescriptor: FloatArray,  // 107 floats — private witness
        liveDescriptor: FloatArray,      // 107 floats — public input
        provingKeyPath: String
    ): ByteArray   // 192-byte Groth16 proof

    external fun verifyProof(
        proof: ByteArray,
        liveDescriptor: FloatArray,
        verifyingKeyPath: String
    ): Boolean
}
```

**Performance requirement:** `generateProof` must complete in < 5 seconds on a Snapdragon
730G equivalent device. If it exceeds 4 seconds in testing, investigate `gnark`'s multi-core
proving options (`WithNbTasks(runtime.NumCPU())`).

**Acceptance criteria:**
- JNI library loads without `UnsatisfiedLinkError` on API 26+
- `generateProof` + `verifyProof` round-trip succeeds in < 5s on mid-range device
- `verifyProof` completes in < 100ms (Groth16 verification is fast)

---

### Task 91 — ZK proof audit export integration

**What it does:** Replaces `livenessConfidence: Float` in `ExchangeAuditLog` with an
optional `gestureZkProof: ByteArray?`. Enterprise audit exports include the proof;
the audit server can verify authentication occurred without receiving biometric data.

**Files to modify:**
- `ExchangeAuditLog.kt` (Room entity) — add `gestureZkProof: ByteArray? = null`
- `AppDatabase` — migration 11 → 12: add nullable column `gesture_zk_proof BLOB`
- `AuditExportWorker.kt` — include proof in CSV/PDF export as hex-encoded string

**Enterprise feature toggle:** `BuildConfig.ENABLE_ZK_AUDIT_PROOF = false` by default.
Enable via MDM policy key `zkGestureProofAudit: true`.

**Acceptance criteria:**
- Room migration 11→12 succeeds without data loss
- When feature enabled, `gestureZkProof` is populated for all exchanges with gesture auth
- Audit export includes proof column with valid hex string

---

### Task 92 — ZK proof benchmark and size verification

**What it does:** Runs a benchmark suite measuring proof generation time across device
tiers and documents the APK size impact of bundling the proving key.

**Output:** `docs/ZK_SNARK_BENCHMARK.md` with:
- p50/p95 proof generation time by device tier (high/mid/low)
- Proving key size (expected ~48 MB for Groth16 at this circuit complexity — document if
  this is prohibitive and consider a smaller circuit or SRS)
- Verification time (expected < 5ms)

**APK size gate:** If proving key exceeds 20 MB, explore `gnark`'s `PlonK` backend (no
trusted setup, smaller SRS) or on-demand download path. Do not ship if proving key
causes APK to exceed the existing APK size gate in CI.

**Acceptance criteria:**
- Benchmark results committed
- APK size gate still passes

---

### Task 93 — ZK unit tests

**Files to create:**
- `app/src/test/java/com/showerideas/aura/zk/GestureZkProverTest.kt`

**Test cases:**
- Valid gesture → proof → verify → true
- Invalid gesture (different descriptor) → proof → verify → false
- Tampered proof (flip one byte) → verify → false
- Proof size assertion ≤ 200 bytes

---

## Phase 9 — AR / Android XR Gesture Exchange Overlay (Target: v5.4)

### Context

Two R&D items graduate here: R&D-G (ARCore Augmented Faces) and R&D-V (Jetpack XR / Android XR).
Both share the same core interaction model: detect nearby AURA user spatially → render their
contact card in AR space → exchange by performing the enrolled gesture in the air.

The UWB ranging from Task 52 provides distance (< 1.5m anchor) and BLE advertisement provides
identity. Together they gate the AR overlay to prevent wrong-person card display in crowded rooms.

See: [ARCore Augmented Faces](https://developers.google.com/ar/develop/augmented-faces)
See: [SceneView Android — Kotlin ARCore wrapper](https://github.com/SceneView/sceneview-android)
See: [Android XR SDK Developer Preview 4 — May 2026](https://android-developers.googleblog.com/2026/05/android-xr-sdk-developer-preview-4-updates.html)
See: [Jetpack XR Hand Tracking](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore)

**Default state:** `BuildConfig.ENABLE_AR_EXCHANGE = false`. Enterprise opt-in.
Privacy review required before enabling because ARCore Augmented Faces processes camera
feed for face mesh (on-device only — no facial recognition database).

---

### Task 98 — ARCore face detection + UWB distance correlation

**What it does:** Detects faces in the camera feed via ARCore `AugmentedFace`, correlates
with UWB distance < 1.5m AND matching BLE AURA advertisement, and gates AR card rendering
on both conditions simultaneously.

**False-positive mitigation:** In a room with 10 AURA users within BLE range, the UWB
distance filter (< 1.5m) should reduce false AR card display to near zero. If UWB is
unavailable, fall back to BLE RSSI with higher threshold (-65 dBm minimum).

**Files to create:**
- `app/src/main/java/com/showerideas/aura/ar/ArExchangeCoordinator.kt`
- `app/src/main/java/com/showerideas/aura/ar/ArExchangeOverlay.kt`

**Acceptance criteria:**
- AR card appears only when UWB distance < 1.5m AND BLE advertisement from same identity
- No AR card in a room with 10 users at > 2m distance
- Camera permission prompt explains on-device face mesh processing (no biometric storage)

---

### Task 99 — Floating contact card composable (ARCore path)

**What it does:** 3D business card rendered via `SceneView` (`sceneview-android`, Apache-2.0)
anchored to the detected person's torso location. Displays name initial, name, gesture icon.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/ar/ContactCardNode.kt` — SceneView `Node` subclass
- `app/src/main/res/layout/fragment_ar_exchange.xml`

**Acceptance criteria:**
- Card renders at correct spatial position relative to detected person
- Card updates identity when BLE advertisement changes
- Card dismisses when distance > 2m or BLE advertisement lost

---

### Task 100 — Gesture recognition in AR mode

**What it does:** In AR mode, performing the enrolled gesture confirms the exchange. The
existing `GestureVerificationEngine` (Phase 5) runs against the ARCore camera feed in
parallel with the face detection path.

**Integration note:** MediaPipe Hands and ARCore Augmented Faces share the same camera
feed. Bind both to the same `CameraX ImageAnalysis` use-case via the existing
`STRATEGY_KEEP_ONLY_LATEST` backpressure path.

**Acceptance criteria:**
- Performing enrolled gesture in AR mode triggers exchange consent dialog
- Non-enrolled gesture is ignored
- Both users must confirm (existing mutual contract completion constraint)

---

### Task 101 — Android XR (Jetpack XR) spatial card variant

**What it does:** Parallel implementation of Task 99 using `Jetpack SceneCore` for the
Android XR headset form factor. Floating panels via `Compose XR`, spatial anchoring to
peer location, hand tracking input via `Jetpack XR Hand Tracking` API.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/xr/XrExchangeActivity.kt`
- `app/src/main/java/com/showerideas/aura/xr/SpatialContactCard.kt`

**Form factor condition:** `XrExchangeActivity` is only surfaced when
`Session.isXrSpatialCapable()` returns true. Standard phone/tablet form factors do not
see this entry point.

**Hand tracking note:** Jetpack XR Hand Tracking uses the same 21-joint hand skeleton
as MediaPipe Hands. `GestureVerificationEngine` should accept spatial joint positions
as input with no model change — same 63-float embedding normalization.

**Acceptance criteria:**
- XR activity launches on Samsung XR Developer Preview hardware
- Spatial card renders correctly in passthrough mode
- Hand gesture triggers exchange consent

---

### Tasks 102–103 — AR privacy disclosure + settings UI

**Task 102:** Privacy disclosure bottom sheet on first AR mode enable:
"AURA uses your camera to detect nearby users. This happens on your device — no images
are sent to any server."

**Task 103:** Settings → Privacy → AR Exchange toggle with sub-options:
- AR Range: 1m / 1.5m / 2m (maps to UWB or RSSI threshold)
- XR Ambient Mode: ON / OFF (whether spatial cards appear without explicit activation)

---

## Phase 10 — DIDComm v2 + ISO 18013-7 Online Presentation (Target: v5.5)

### Context

Two R&D items graduate: R&D-O (DIDComm v2) and R&D-L (ISO 18013-7).
AURA already has W3C VCs, `did:key` identity, and OpenID4VP (Task 61). This phase
adds the messaging protocol layer (DIDComm v2) so AURA can receive authenticated
messages from any DIDComm-compatible enterprise wallet, and extends the identity
verifier to support remote (async) mDL presentation via ISO 18013-7.

See: [DIDComm v2 specification](https://identity.foundation/didcomm-messaging/spec)
See: [EU Digital Identity Wallet Android library](https://github.com/eu-digital-identity-wallet/eudi-lib-android-iso18013-data-model)
See: [OpenID4VP spec](https://openid.net/developers/draft-openid-4-verifiable-presentations/)

**Prerequisite:** R&D-D `did:peer:2` prototype should be evaluated before this phase.
DIDComm v2 routing uses `did:peer:2` as the standard pairwise DID for message routing.

---

### Task 106 — `DIDCommTransport.kt`: DIDComm v2 message reception

**What it does:** Implements `authcrypt` and `anoncrypt` message decryption per DIDComm
v2 spec. Maps DIDComm message types to AURA exchange flows.

**Encryption compatibility:** DIDComm v2 uses ECDH-ES + AES-256-GCM for `anoncrypt` and
ECDH-1PU + AES-256-GCM for `authcrypt`. AURA's existing X25519 keys are directly compatible
with the DIDComm key agreement algorithms (X25519 with HKDF-SHA256 is the default KA in DIDComm).

**Files to create:**
- `app/src/main/java/com/showerideas/aura/identity/didcomm/DIDCommTransport.kt`
- `app/src/main/java/com/showerideas/aura/identity/didcomm/DIDCommMessage.kt`

**Acceptance criteria:**
- `anoncrypt` message from a test DIDComm sender decrypts correctly
- `authcrypt` message from a known DID decrypts and sender is verified
- Unknown message type is logged and discarded without crash

---

### Task 107 — DIDComm contact exchange request message type

**What it does:** Defines AURA's custom DIDComm message type `aura.exchange.v1/request`
that enterprise wallets can send to request a contact card. AURA shows a consent dialog
and responds with `aura.exchange.v1/response` containing the AURA vCard VC.

**Acceptance criteria:**
- End-to-end flow: enterprise sender → DIDComm request → AURA consent dialog → accept →
  DIDComm response with vCard VC
- Reject path: user declines → DIDComm `problem-report` sent

---

### Task 108 — ISO 18013-7 async mDL verifier

**What it does:** Extends the existing ISO 18013-5 proximity verifier (Task 61) to accept
remote mDL presentations via OpenID4VP. The user can verify another person's mDL without
physical proximity.

**Library:** `eu-digital-identity-wallet/eudi-lib-android-iso18013-data-model` (Apache-2.0)
for mdoc data model parsing. OpenID4VP request/response handled by existing `VpBuilder.kt`.

**Files to modify:**
- `app/src/main/java/com/showerideas/aura/identity/MdocDocument.kt` — add `fromOid4vpResponse()`
- `app/src/main/java/com/showerideas/aura/identity/VpBuilder.kt` — add OpenID4VP request builder

**Privacy note:** Online mDL presentation reveals requester IP to the MDL holder's issuer.
Route requests through the existing OHTTP relay (`ObliviousHttpClient`) when privacy mode
is enabled.

**Acceptance criteria:**
- ISO 18013-7 request generated with correct `response_type` and `presentation_definition`
- Received OpenID4VP response parsed into `MdlVerifiedFields`
- Privacy mode: request routed through OHTTP relay

---

### Tasks 109–111 — DIDComm settings UI, tests, documentation

**Task 109:** Settings → Identity → DIDComm Inbox: list of received DIDComm messages with
sender DID, message type, timestamp, and response status.

**Task 110:** Unit tests for `DIDCommTransport` (mock encrypted messages), `MdocDocument.fromOid4vpResponse()`.

**Task 111:** Update `docs/EXCHANGE_FLOW.md` to document DIDComm exchange path alongside
existing Nearby/NFC/QR paths.

---

## Phase 11 — MPC Threshold Audit Signing + Privacy Pass (Target: v5.6)

### Context

Two R&D items graduate: R&D-U (MPC threshold signing) and R&D-W (Privacy Pass).

**MPC:** The enterprise audit export (signed with a single device key today) upgrades to
2-of-3 Shamir threshold signing. Two of three designated administrator AURA devices must
co-sign any audit export. Co-signing happens over the existing AURA BLE/NFC transport —
no external coordinator required.

**Privacy Pass:** The QR relay currently has no rate-limiting. Privacy Pass (RFC 9578)
adds anonymous rate limiting: one token per exchange, issued blindly so the relay cannot
link token issuance to token redemption. Prevents relay abuse without identifying users.

See: [NIST threshold cryptography project](https://csrc.nist.gov/projects/threshold-cryptography)
See: [RFC 9578 — Privacy Pass Issuance](https://www.rfc-editor.org/rfc/rfc9578.html)
See: [RFC 9576 — Privacy Pass Architecture](https://www.rfc-editor.org/rfc/rfc9576.html)
See: [Cloudflare pat-go library](https://github.com/cloudflare/pat-go)

---

### Task 112 — Shamir Secret Sharing key ceremony for audit signing key

**What it does:** Generates a 2-of-3 Shamir secret sharing of the audit export signing key.
Each of three enterprise administrator devices holds one shard. Reconstruction requires
exactly two shards — no single device can sign an audit export alone.

**Libraries:** `tss-lib` (Go, MIT via JNI) or a pure Kotlin Shamir implementation.
For AURA's field size (256-bit key), a pure Kotlin Shamir over GF(2^256) is feasible
and avoids a JNI dependency.

**Key ceremony flow:**
1. Admin A generates the master key + 3 shards on their device.
2. Admin A exports encrypted shard B → Admin B via AURA NFC exchange (existing AID + session token).
3. Admin A exports encrypted shard C → Admin C similarly.
4. Master key is deleted from Admin A's device immediately.
5. Each admin's shard is stored in their device's StrongBox (no cloud backup).

**Files to create:**
- `app/src/main/java/com/showerideas/aura/enterprise/mpc/ShamirSecretSharing.kt`
- `app/src/main/java/com/showerideas/aura/enterprise/mpc/AuditSigningCoordinator.kt`

**Acceptance criteria:**
- 3 shards generated; any 2 reconstruct the key; any 1 alone cannot
- Shard exchange over NFC succeeds
- Key ceremony UI walks admin through the flow step by step

---

### Task 113 — 2-of-3 co-signature for audit export

**What it does:** Modifies `AuditExportWorker` to require co-signature from a second admin
device before exporting. The signing request is sent via AURA's BLE/NFC transport to
the second admin's device, which shows a consent prompt.

**Files to modify:**
- `AuditExportWorker.kt` — add co-signature request step before writing export file
- `NearbyExchangeService.kt` — add `MSG_TYPE: AUDIT_COSIGN_REQUEST` and `AUDIT_COSIGN_RESPONSE`

**Acceptance criteria:**
- Export requires two device approvals
- If second admin declines → export aborted, reason logged
- Single-admin approval attempt → export blocked with clear UI message

---

### Task 114 — Privacy Pass token issuance on QR relay

**What it does:** On each successful exchange via QR relay, AURA requests 10 Privacy Pass
tokens from the relay server. Tokens are blind RSA signatures on random nonces.

**Client library:** `cloudflare/pat-go` compiled for Android via JNI (Apache-2.0), or
a minimal blind RSA implementation in Kotlin using BouncyCastle's existing RSA primitives.

**Token storage:** `TokenStore.kt` — list of up to 50 tokens in `EncryptedSharedPreferences`.

**Files to create:**
- `app/src/main/java/com/showerideas/aura/relay/privacypass/PrivacyPassClient.kt`
- `app/src/main/java/com/showerideas/aura/relay/privacypass/TokenStore.kt`

**Acceptance criteria:**
- Token issuance request sent after exchange success
- Token stored in `TokenStore`
- `TokenStore` capped at 50 tokens; oldest discarded when full

---

### Task 115 — Privacy Pass token redemption on QR relay requests

**What it does:** Each QR relay HTTP POST attaches one Privacy Pass token in the
`Sec-Private-State-Token` header. The relay server verifies and deducts. If no tokens
remain, `RelayClient` falls back to CAPTCHA-gated path.

**Files to modify:**
- `RelayClient.kt` — attach token header on POST; handle 401 (token exhausted) gracefully

**Acceptance criteria:**
- QR relay POST includes token header when `TokenStore` is non-empty
- On token exhaustion: fallback path (CAPTCHA or wait for re-issuance) activated
- Relay server cannot link token redemption to issuance session (blind RSA guarantee — document in `docs/WIRE_PROTOCOL.md`)

---

### Tasks 116–118 — MPC settings UI, Privacy Pass tests, documentation

**Task 116:** Settings → Enterprise → Audit Export: shows co-signature requirement status,
co-signer device list, pending co-signature requests.

**Task 117:** Unit tests — `ShamirSecretSharing` (2-of-3 reconstruction), `PrivacyPassClient`
(token blinding and unblinding), `TokenStore` (FIFO cap).

**Task 118:** Update `docs/SECURITY.md` to document threshold audit signing model and
Privacy Pass anti-abuse mechanism.

---

## Remaining R&D Items

These items have not yet graduated to scheduled implementation tasks.
Each has an explicit trigger condition.

---

### R&D-D — Decentralized Identity (DID) Full Integration

**Goal:** Deepen beyond `did:key` derivation. Support `did:web` (domain-anchored),
`did:peer:2` (per-exchange pairwise), and full DID Document publishing.

**Trigger:** Implement `did:peer:2` before Phase 10 (DIDComm v2 uses it for routing).
Implement `did:web` as enterprise power-user feature on demand.

See: [DID Core 1.0](https://w3.org/TR/did-core/)
See: [did:peer method](https://identity.foundation/peer-did-method-spec/)
See: [did:web method](https://w3c-ccg.github.io/did-method-web/)

- [R&D] `did:peer:2` prototype: encode AURA exchange public key as `did:peer:2.<base58(keyType+keyBytes)>`
- [R&D] `did:web` publishing UI: Settings → Identity → "Publish DID Document"
- [R&D] `DidResolver.kt`: resolves `did:key` (local), `did:web` (HTTP GET + cache), `did:peer:2` (decode)

---

### R&D-H — Satellite Fallback (Android SatelliteManager + Garmin inReach)

**Goal:** Exchange cards at zero-infrastructure locations.
**Trigger:** After LoRa integration shows real-world demand for longer-range transport.

See: [Android SatelliteManager API 34+](https://developer.android.com/reference/android/telephony/satellite/SatelliteManager)
See: [Garmin Connect IQ SDK](https://developer.garmin.com/connect-iq)

- [R&D] `SatelliteManager` API availability audit on Pixel 9 and Galaxy S25
- [R&D] Compression benchmark: LZ4 + base91 on representative AURA vCards; target < 160 bytes
- [R&D] TOFU-only mode assessment for satellite path (no real-time SAS possible at 30s+ latency)

---

### R&D-M — Matter/Thread IoT Identity Bridge

**Goal:** AURA identity key (P-256) compatible with Matter NOC — authorize IoT device
pairing via tap.
**Trigger:** When Matter ecosystem reaches 50%+ new smart home device market share.

See: [Matter SDK — project-chip/connectedhomeip](https://github.com/project-chip/connectedhomeip)

- [R&D] NOC compatibility assessment with AURA P-256 identity key
- [R&D] `MatterIdentityBridge.kt` prototype: derive NOC; PASE commission; revoke via key rotation
- [R&D] Privacy: use derived fabric-specific key `HKDF(identity_key || fabric_id)` to prevent correlation

---

### R&D-N — AI-Powered Contact Import (ML Kit OCR + Gemini Nano)

**Goal:** Import contact info from photographed business card. On-device only.
**Trigger:** When Gemini Nano available on > 40% of active Android devices.

See: [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/android)
See: [Android AICore — Gemini Nano](https://developer.android.com/ai/aicore)

- [R&D] Prototype `BusinessCardImporter.kt` with ML Kit OCR + Gemini Nano structured extraction
- [R&D] Accuracy benchmark: 50 business cards, target > 90% email+phone extraction on Latin script

---

### R&D-P — Satellite Direct-to-Device (Android 15+ SatelliteManager)

Overlaps with R&D-H above. Combined into one implementation task when triggered.
**Trigger:** After LoRa ships; add `SatelliteTransport.kt` as highest-latency fallback transport.

See: [SatelliteManager — Google Pixel 9 satellite SOS](https://satellitetoday.com/connectivity/2024/08/14/google-brings-satellite-sos-feature-to-android-with-pixel-9)

---

### R&D-Q — Android 17 Contact Picker Integration

**Goal:** Privacy-preserving contact picker — apps access only selected contacts.
**Trigger:** Only relevant if PSI contact discovery (Task 54) expands to device address book.
AURA currently manages only its own contact store. No action until that changes.

See: [Android 17 Contact Picker privacy feature](https://makeuseof.com/this-new-android-privacy-feature-is-actually-brilliant)

---

### R&D-X — Kotlin 2.2 Swift Export for iOS AuraCore

**Goal:** Swift export stable in Kotlin 2.2.20 — eliminate `@ObjC` wrapper intermediary.
`WireProtocol.kt`, `SasVerifier.kt`, `HybridKEM.kt` callable directly from Swift with
Swift-idiomatic APIs and async/await.

**Trigger:** After Kotlin 2.2.20 is stable on AURA's build toolchain (currently Kotlin 2.0.x).
Migration requires K2 compiler full adoption and iOS module symbol migration with compat shim.

See: [Kotlin 2.2.20 Swift export](https://kotlinlang.org/docs/whatsnew2220.html)
See: [KMP 2.3 roadmap](https://medium.com/@androidlab/what-kotlin-2-3-tells-us-about-the-future-of-the-language)

- [R&D] Evaluate current iOS `AuraCore` scope of re-implemented `:protocol` logic
- [R&D] Breaking change assessment: `@ObjC` vs Swift export symbol naming differences
- [R&D] Migration plan: backward compat shim during transition period

---

## Version History

| Version | Released | Key Changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate, ECDH+HKDF, room exchange, QR fallback, blocklist, biometric |
| v1.1.0 | 2026-05-24 | QR relay, 7 locales 100%, 259 unit + 51 instrumented tests, signed APK splits |
| v2.0.0 | 2026-05-25 | Transport injection, NFC scaffold, profiles, identity rotation, audit log, backup |
| v2.0.1 | 2026-05-26 | NFC HCE ISO 7816-4 full impl, SPKI runtime pinning, GestureModelLoader, backup polish |
| v2.1.0 | 2026-05-26 | JaCoCo 60%, l10n human review (313 strings), deeplink Add Contact sheet |
| v3.0.0 | 2026-05-26 | iOS AuraCore companion (vCard 3.0, SAS, ECDH), iOS CI with cache + coverage |
| v3.1.0 | 2026-05-26 | Wear OS pairing UI, Android Auto voice + biometric gate |
| v3.2.0 | 2026-05-26 | Enterprise audit retention, F-Droid reproducible build + submission guide |
| v3.3.0 | 2026-05-26 | Tasks 1–44 complete — full transport stack, PQ crypto, room exchange, analytics |
| v4.0.0 | 2026-05-26 | Tasks 45–66 complete — PQ identity, Noise/MLS/SPQR, OHTTP, OpenID4VP, mdoc, QUIC, UWB FiRa 3.0, BLE CS, continuous auth, Advanced Protection |
| v5.0 | planned | Phase 5: User-defined gesture enrollment — dual temporal bone graph descriptors |
| v5.1 | planned | Phase 6: Android 17 native ML-DSA-65 Keystore + BouncyCastle deprecation |
| v5.2 | planned | Phase 7: FIDO2 platform authenticator + NFC hardware key relay |
| v5.3 | planned | Phase 8: ZK-SNARK gesture template privacy + enterprise ZK audit export |
| v5.4 | planned | Phase 9: AR (ARCore) + Android XR spatial contact card exchange |
| v5.5 | planned | Phase 10: DIDComm v2 messaging + ISO 18013-7 async mDL presentation |
| v5.6 | planned | Phase 11: MPC 2-of-3 threshold audit signing + Privacy Pass relay rate limiting |

---

*Last updated: 2026-05-27 — Added Phases 5–11 (Tasks 67–118). R&D-G, L, O, R, S, T, U, V, W graduated to scheduled implementation. R&D-D, H, M, N, P, Q, X retained as research-only with updated triggers.*
