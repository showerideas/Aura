# AURA — Project Roadmap

> **Canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every
> session. Keep it current — strike through completed items, add blockers inline.
>
> **Reading guide:**
> ✅ = Fully shipped and on `main`
> 🔧 = Implementation exists; minor wiring/test task remains
> 📋 = Planned — not yet started
> 🔭 = Long-horizon — spec not written yet

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | `main` — v2.0.0-dev; all Phase 6.x PRs merged (2026-05-25) |
| Security audit | Wave 3 complete — all findings A1–A15 resolved |
| Localization | 262 strings × 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) — **100% coverage, CI-enforced** |
| Test suite | Unit: 23+ files · 274+ methods · Instrumented: 13 files · 55 methods · 0 failures |
| CI pipeline | Green — unit + JaCoCo (50% branch floor) + lint + `assembleRelease` + APK size gate |
| Distribution | GitHub Releases — signed APK splits (arm64-v8a + armeabi-v7a) |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| QR relay | Implemented — AES-256-GCM encrypted profile POST/GET over HTTPS relay |
| Room DB | v8 — full schema: Contact, Profile (version), KnownPeer (rotation_cert + last_seen_version), BlockedEndpoint, ExchangeAuditEntry |
| Product flavors | `gms` (Nearby Connections transport) + `foss` (Wi-Fi Direct, no GMS) — both building and testing green |
| Crypto stack | ECDH+HKDF-SHA256+AES-256-GCM per session · ECDSA identity keypair in AndroidKeyStore · DoubleRatchet symmetric chain · SAS MITM protection · TOFU registry |
| Transport | gms: NearbyConnectionsTransport · foss: WifiDirectTransport · NFC tap-to-bootstrap · QR relay fallback |

---

## ✅ COMPLETED PHASES

### Phase 2 — Localization ✅
> 262 strings × 7 locales. `LocalizationCoverageTest.kt` enforces 100% on CI.

---

### Phase 3 — Test coverage hardening ✅
> 274 unit test methods + 55 instrumented test methods, 0 failures.

| Test file | Covers |
|---|---|
| `SettingsEspressoTest` | Settings + Blocked screens |
| `OnboardingEspressoTest` | Onboarding flow |
| `PermissionRationaleEspressoTest` | Permission-rationale sheet |
| `ExchangeFlowEspressoTest` | Exchange flow activation + cancel |
| `SasDialogEspressoTest` | SAS confirm / mismatch / identicon paths |
| `NfcExchangeEspressoTest` | NDEF round-trip, HCE APDU, manifest, NFC lifecycle |
| `BiometricAvailabilityTest` | Biometric unlock |
| `LocalizationCoverageTest` | Localization CI enforcement |
| `WireProtocolTest` (17 tests) | ECDH, profile encryption, crypto math |
| `SasVerifierTest` (17 tests) | SAS determinism, ordering, uniqueness, range |
| `NfcExchangeHelperTest` | NFC tap bootstrap path |
| `DoubleRatchetStateTest` | Double Ratchet symmetric chain state machine |
| `LivenessGuardTest` | Liveness guard drift thresholds |
| `IdenticonGeneratorTest` | Identicon generation determinism |
| `IdentityRotationDetectorTest` | Identity key rotation detection |
| `SecurityHardeningTest` | Payload field bounds, size gates |
| `NearbyExchangeServiceUnitTest` (15 tests) | Service companion constants + StateFlow contracts |

---

### Phase 4 — QR Relay ✅
> Encrypted profile POST/GET via HTTPS relay. `RELAY_BASE_URL` backed by `BuildConfig`.
> `docs/qr-relay-setup.md` covers Firebase Realtime Database zero-ops deployment.

---

### Phase 5.1 — SAS Dialog UI hardening ✅
> 30-second auto-abort countdown, haptic feedback, identicon alongside 6-digit code
> (dual-channel MITM defence), `ExchangeViewModel.sasDialogShown` guard survives
> configuration changes. Espresso tests: `SasDialogEspressoTest`.

---

### Phase 5.4 — Volume button reliability improvement ✅
> `AuraAccessibilityService` implements triple-press via `AccessibilityService` as a
> GMS-independent activation path. `VolumeButtonTriplePressTest.kt` covers the contract.

---

### Phase 5.6 — QR relay self-hosting guide ✅
> `docs/qr-relay-setup.md` — Firebase Realtime Database setup, security rules,
> auto-expiry Cloud Function, `RELAY_BASE_URL` CI wiring.

---

### Phase 6.1 — NFC Tap-to-Exchange ✅
> `NfcExchangeHelper.kt`, `AuraHceService.kt`, `MainActivity` wiring (keypair generation,
> enable/disable), `ExchangeFragment` NFC chip indicator, `NearbyExchangeService` bootstrap
> handling, manifest NFC permissions and HCE service declaration — all in place.
> Espresso tests: `NfcExchangeEspressoTest`.

---

### Phase 6.2 — Transport Injection (gms/foss product flavors) ✅
> `NearbyTransport` interface, `NearbyConnectionsTransport` (gms) and `WifiDirectTransport`
> (foss) wired via Hilt `TransportModule` in flavor-specific source sets. Service injected,
> no more direct `Nearby.getConnectionsClient()` calls. `FakeNearbyTransport` + `FakeWifiDirectTransport`
> test doubles. 15 JVM unit tests in `NearbyExchangeServiceUnitTest`.

---

### Phase 6.3 — Contact Deduplication Engine ✅
> `ContactRepository.saveDeduped()` matches by `identityKeyHash`, diffs fields, returns
> `MergeEvent?`. `ContactMergeBottomSheet` presents per-field diffs. `ContactDiffEngine`
> with full edge-case test suite.

---

### Phase 6.4 — Multiple Profiles (Personal / Work / Custom) ✅
> Room DB v6 migration. `ProfileType` enum (PERSONAL, WORK, CUSTOM). `ProfileSwitcherBottomSheet`
> in `HomeFragment`. `ProfileDao.setActive()` atomic swap. 18 unit tests in `MultiProfileTest.kt`.

---

### Phase 6.5 — Identity Key Rotation ✅
> `CryptoUtils.rotateDeviceIdentityKey()`, `RotationCertificate` (new key signed by old key),
> Room DB v7 migration (`rotation_certificate` on `KnownPeer`). Settings → Security wired.
> `IdentityRotationDetector` verifies certificates on reconnect. 12 tests in `KeyRotationTest.kt`.

---

### Phase 6.6 — Exchange Audit Log UI ✅
> `AuditFragment` with timestamp, peer name, exchange outcome, CSV export via `ExportUtils`.
> Navigation entry points from Contacts screen and Settings.

---

## 🔧 NEARLY COMPLETE — small tasks only

### Phase 5.2 — Coverage gate hardening 🔧
> `NearbyExchangeServiceUnitTest.kt` (15 tests) shipped. JaCoCo floor still at 50%.

**Remaining:**
- [ ] Raise `minimum` branch coverage in `build.gradle.kts` from 50% → 55%
  - *Why:* The 15 new service unit tests meaningfully improve branch coverage. Locking
    the floor at 55% catches any future regression where a new branch escapes test.
- [ ] Raise floor to 60% — add `QRExchangeViewModel` relay-state unit tests
  - *Why:* The QR relay path (`RelayClient` → `QRExchangeViewModel`) is the least-covered
    critical path. A full relay state cycle (POST → pending → GET → success/timeout) is
    testable in JVM without a real network using a fake `RelayClient`.

---

### Phase 6.7 — Profile Versioning 🔧
> **Implementation is complete.** `Profile.version` auto-increments in `ProfileRepository.update()`.
> `KnownPeer.lastSeenProfileVersion` stored in Room DB v8. `NearbyExchangeService.handleIncomingProfile()`
> compares incoming version against stored value and sets `ExchangeSession.profileVersionBumped = true`.
> `ExchangeFragment` already shows a "Card updated" Snackbar on `profileVersionBumped = true`.

**Remaining:**
- [ ] Instrumented test: inject a `COMPLETED` session with `profileVersionBumped = true`,
  verify the "Card updated" Snackbar appears.
  - *Why this matters:* The Snackbar guard (`cardUpdatedSnackbarShown`) and the dedup
    logic inside `ExchangeFragment` are subtle — this is exactly the kind of UI-state
    contract that breaks silently when someone refactors the session observer.

---

### Phase 6.8 — "Share AURA" Deeplink 🔧
> **Almost complete.** `DeeplinkUtils.generateShareUrl(profile)` encodes the profile to a
> URL-safe base64 JSON URL (`https://aura.app/c/<base64>`). `ProfileFragment.btnShareCard`
> is wired to call it and fire the Android share sheet. `docs/c/index.html` GitHub Pages
> landing page decodes the payload entirely client-side (no server sees the data).

**Remaining:**
- [ ] Add `<intent-filter>` in `AndroidManifest.xml` for `https://aura.app/c/*` with
  `android.intent.action.VIEW` + `BROWSABLE` + `DEFAULT` categories.
  - *Why:* Without this filter, tapping an `aura.app/c/...` link on a device that has
    AURA installed opens the browser instead of the app. The filter lets the OS route
    the link directly into AURA where we can parse and pre-fill a contact save dialog.
- [ ] Add `DeeplinkUtils.decodeShareUrl()` integration in `MainActivity.onNewIntent()`:
  parse the incoming URL → display a pre-filled "Add contact" sheet.
  - *Why:* The decoding utility already exists. Without this wiring, the intent filter
    above opens the app but nothing happens with the link payload.

---

### Phase 6.9 — Identicon for Unknown Peers 🔧
> **Mostly done.** `IdenticonGenerator.kt` and `IdenticonGeneratorTest.kt` exist.
> SAS dialog shows identicon alongside the 6-digit code. `ContactDetailBottomSheet`
> already shows identicon with fallback to saved avatar.

**Remaining:**
- [ ] Wire identicon to `BlockedDevicesAdapter` — for each blocked peer, generate an
  identicon from `BlockedEndpoint.identityKeyHash` and display it in the row.
  - *Why:* The blocklist shows endpoint IDs (opaque hex strings). When a user tries to
    identify which blocked entry corresponds to a real person they met, a visual
    identicon is the only non-textual cue available without a stored display name.

---

## 📋 REMAINING WORK

### Phase 5.3 — Accessibility improvements (v2.1)

**Why:** Phase 5.4 added an `AccessibilityService` for volume-button activation. But AURA
itself is not yet accessible as a *target* for TalkBack users. A gesture-auth app that
can't be navigated by screen reader users misses a significant subset of potential users
and fails WCAG 2.1 AA.

**Tasks:**
- [ ] Automated TalkBack / Accessibility Scanner CI pass — add a Robolectric shadow-based
  test that enumerates all interactive views and asserts non-empty `contentDescription` or
  a linked label. This moves the check from manual review to every CI run.
- [ ] Add `contentDescription` to all icon-only buttons:
  `btnExchange`, `btnNfc`, `btnQrScan`, the audit export FAB, profile switcher.
- [ ] Verify minimum touch-target sizes (48dp) on gesture enrollment page.
  The canvas preview area currently fills available width — the control buttons beside
  it may shrink below threshold on small screens.
- [ ] Add `accessibilityLiveRegion="polite"` to the SAS dialog 6-digit display so
  TalkBack announces when the code updates (relevant in room-mode multi-peer scenarios).
- [ ] Add `importantForAccessibility="no"` to purely decorative views (identicon
  background, wave animation) so TalkBack doesn't narrate meaningless content.

---

### Phase 5.5 — Native-speaker localization review (v2.1)

**Why:** The 262-string × 7-locale matrix was translated programmatically. Machine
translations of technical terms (e.g. "SAS verification", "identity key rotation",
"gesture enrollment") are often unnatural or incorrect in context, particularly in JA, KO,
and ZH-CN where compound technical nouns don't map directly from English.

**Tasks:**
- [ ] Commission a native-speaker review for each of the 7 locales (DE, ES, FR, HI, JA, KO, ZH-CN).
  Focus areas: authentication flow labels, security terminology, error messages.
- [ ] Fix all identified awkward/incorrect translations. Commit as one PR per locale so
  each reviewer's changes are reviewable separately.
- [ ] Add a `strings_review_log.md` in `docs/` documenting what was changed and why —
  useful for future reviewers to understand intentional terminology choices.
- [ ] Re-run `LocalizationCoverageTest` to confirm 0 regressions after edits.

---

### Phase 5.7 — Certificate pinning for QR relay (v2.1)

**Why:** `RelayClient` uses plain `HttpURLConnection`. While HTTPS validates the server's
certificate against the system trust store, it does not prevent a network-level attacker
who has installed a rogue CA certificate (e.g. corporate MITM proxy, compromised root CA)
from intercepting relay traffic. The relay carries AES-256-GCM encrypted profile data, so
confidentiality is preserved even if TLS is broken — but the attacker can see the timing
and size of exchanges (traffic analysis), and drop or replay relay slots.

**Tasks:**
- [ ] Implement certificate pinning in `RelayClient` using a SHA-256 public key hash of
  the relay server's TLS leaf certificate. Use `network_security_config.xml` (Android's
  built-in mechanism) rather than manual `TrustManager` code to avoid implementation bugs.
- [ ] Add a 30-day pin rotation reminder mechanism: store the pin expiry date in
  `BuildConfig.RELAY_PIN_EXPIRY_EPOCH_MS` and log a warning if the build is within 30
  days of expiry, surfaced in CI.
- [ ] Document the pin rotation process in `docs/qr-relay-setup.md` — operators need to
  know how to update the pin without breaking existing installs.
- [ ] Add a unit test: verify `RelayClient` rejects connections with a mismatched certificate.

---

### Phase 5.8 — Bundle MediaPipe gesture model for offline / FOSS (v2.2)

**Why:** `GestureAuthManager` downloads the MediaPipe hand gesture model from a CDN at
runtime. This creates three problems:
1. First-run requires an internet connection — fails in airplane mode, on restricted networks.
2. The `foss` flavor is supposed to have zero external service dependencies (no GMS, no
   Google CDN). Downloading from Google infrastructure violates the spirit of the FOSS build.
3. The CDN URL could change or the file could be modified — a build that depends on
   a remotely-fetched binary cannot be reproducibly verified.

**Tasks:**
- [ ] Bundle the MediaPipe gesture recognizer `.task` model file in `app/src/main/assets/`.
  The model is ~15 MB — within the acceptable range for an APK split (arm64-v8a only since
  the model is architecture-independent).
- [ ] Update `CameraHandEmbedder` to load the model from `assets://` instead of the CDN URL.
  Keep CDN as a runtime fallback only for the `gms` flavor if the bundled file is absent.
- [ ] Add a CI step that verifies the SHA-256 of the bundled model file matches the
  expected hash in `build.gradle.kts`. Prevents silent model corruption or substitution.
- [ ] Update `docs/GESTURE_AUTH.md` to document the model version, source, and verification hash.

---

### Phase 6.10 — Encrypted contact backup / restore (v2.2)

**Why:** AURA stores contacts in a Room database in the app's private directory. If the
user reinstalls AURA, switches phones without using Android Backup, or clears app data,
all contacts are lost. There is currently no recovery path. This is a significant
usability gap given that exchanging contacts via gesture is effort-intensive — every
lost contact represents a real-world interaction that cannot be recovered.

**Design:**
- Export the `contact` + `known_peers` tables to a JSON bundle.
- Encrypt the bundle with a user-supplied passphrase using Argon2id (key derivation)
  + AES-256-GCM. The gesture credential (stored in EncryptedSharedPreferences) is
  explicitly excluded — it binds to the device's AndroidKeyStore and cannot be
  exported in a useful form.
- Save the encrypted blob to a user-chosen location via the Android Storage Access Framework
  (no special permissions required). Drive, local file, or any SAF target all work.
- On restore: prompt for passphrase → decrypt → Room `insertOrReplace` transaction.

**Tasks:**
- [ ] `BackupUtils.export(contacts, knownPeers, passphrase): ByteArray`
  — Argon2id KDF (t=3, m=65536, p=4) + AES-256-GCM encryption of JSON bundle.
- [ ] `BackupUtils.restore(encryptedBytes, passphrase): RestoreResult`
  — decrypt → parse JSON → upsert into Room via a single transaction.
- [ ] `BackupFragment` — triggered from Settings → "Backup & restore" with export and
  import buttons, passphrase entry, file picker via `ActivityResultContracts.CreateDocument`
  and `ActivityResultContracts.OpenDocument`.
- [ ] Backup format version field in the JSON header so future schema changes can be
  handled with a migration function rather than silently dropping old fields.
- [ ] `BackupUtilsTest` — roundtrip: export a known contact set, restore into a fresh
  database, assert exact equality.

---

### Phase 6.11 — Quick Settings tile + home screen shortcut (v2.2)

**Why:** The current activation paths (triple volume-press or opening the app) both
require multi-step interaction. A QS tile gives one-tap access from the notification shade,
which is the standard UX for "activate a sensor/mode" on Android. This is particularly
useful in busy social situations where pulling out a phone and navigating UI takes too long.

**Tasks:**
- [ ] `AuraQsTileService` extending `TileService` — tile state mirrors `NearbyExchangeService.sessionState`.
  Active state = blue, inactive = grey. Tap starts/stops the exchange session.
- [ ] Register `AuraQsTileService` in `AndroidManifest.xml` with `BIND_QUICK_SETTINGS_TILE` permission.
- [ ] `QsTileServiceTest` (Robolectric) — verify tile state transitions mirror exchange session state.
- [ ] App shortcut via `shortcuts.xml` — static shortcut "Start Exchange" pointing to
  `MainActivity` with `ACTION_START` extra. Available from long-press on the launcher icon
  without opening the app.

---

### Phase 6.7 — Profile Versioning (v2.0) 🔧
> See the **Nearly Complete** section above — only one instrumented test remains.

---

### Phase 6.8 — "Share AURA" Deeplink (v2.0) 🔧
> See the **Nearly Complete** section above — only manifest filter + `onNewIntent` wiring remain.

---

### Phase 6.9 — Identicon for Unknown Peers (v2.0) 🔧
> See the **Nearly Complete** section above — only the blocklist screen identicon remains.

---

## PHASE 7 — v3.x Platform expansion

> These phases begin when Phase 6 is fully closed and stable. Spec documents will be
> written at the start of each phase.

---

### Phase 7.1 — iOS Companion App — SwiftUI (v3.0)

**Why:** AURA is currently Android-only. In practice, roughly half of any face-to-face
exchange involves at least one iPhone user. Without a compatible iOS app, AURA's gesture
authentication layer cannot be completed on mixed-platform pairs — users fall back to
QR relay (which skips the gesture gate on the iOS side) or cannot exchange at all.

**Technical constraints:**
- Cryptographic interoperability is the hardest constraint. Both platforms must produce
  identical ECDH shared secrets. Android uses `KeyPairGenerator.getInstance("EC")` with
  256-bit curve (secp256r1/P-256). iOS must use `SecKeyCreateRandomKey` with
  `kSecAttrKeyTypeECSECPrimeRandom` at 256 bits. Both encode public keys as X.509
  SubjectPublicKeyInfo (SPKI) — the base64 encoding exchanged over the transport.
  This is the canonical cross-platform encoding and must be tested explicitly with
  a shared test vector.
- Transport: `MultipeerConnectivity` replaces Nearby Connections on iOS. The wire protocol
  (HELLO / KEY_EXCHANGE / PROFILE_PAYLOAD message types, their byte-layout and field names)
  must be spec'd in `docs/WIRE_PROTOCOL.md` and remain stable across both platforms.
  Currently this spec is implicit in `NearbyExchangeService` — it must be extracted
  before iOS development begins.
- vCard payload format must be identical. Both platforms already use vCard 3.0 (RFC 2426)
  via `VCardUtils.kt` — the iOS counterpart must produce byte-for-byte identical output
  for the same contact to ensure deduplication by `identityKeyHash` works cross-platform.
- CoreNFC on iOS supports tag reading and HCE writing differently from Android's NFC stack.
  The NFC bootstrap path (tap → exchange start) may need a protocol-level adaptation.

**Tasks:**
- [ ] Extract wire protocol to `docs/WIRE_PROTOCOL.md` — byte layout, message type enum,
  field names, version field, max payload sizes.
- [ ] Write a cross-platform ECDH test vector: fixed private key bytes + fixed peer public
  key bytes → expected shared secret bytes. Verified on both Android and iOS in CI.
- [ ] SwiftUI app scaffold with `MultipeerConnectivity` transport, P-256 ECDH, HKDF-SHA256,
  AES-256-GCM, SAS verification, vCard 3.0 serialization.
- [ ] GitHub Actions workflow for iOS build on `macos-latest`.

**Milestone:** v3.0.0

---

### Phase 7.2 — Wear OS Glance tile (v3.1)

**Why:** The gesture authentication on a watch face is a compelling UX — hold out your
wrist, make the gesture, exchange. The Wear OS tile provides a single-tile "Ready / Active"
indicator and tap-to-start, deferring the heavy sensor work to the paired phone via
`ChannelClient`. The watch becomes a remote trigger only.

**Technical design:**
- `WearTileService` uses `androidx.wear.tiles` (Glance Tiles API). The tile shows exchange
  state mirrored from the phone via `ChannelClient.sendChannel()`.
- The watch never performs ECDH or camera work itself — it sends a start signal to the
  paired phone and displays the resulting state (ADVERTISING, VERIFYING with SAS code, COMPLETED).
- SAS display on the watch face is a secondary channel for MITM protection — the user
  can compare the 6-digit code on both the phone and the watch without speaking.

**Tasks:**
- [ ] `wearos/` module in the Gradle project with `apply plugin: 'com.android.application'`
  + Wear OS target SDK.
- [ ] `AuraWearTileService` — renders IDLE / ACTIVE / SAS tiles.
- [ ] `WearPhoneBridge` using `ChannelClient` for bidirectional state sync.
- [ ] Wear OS companion app pairing flow in Settings → "Wear OS" (phone-side).

**Milestone:** v3.1.0

---

### Phase 7.3 — Android Auto integration (v3.1)

**Why:** Automotive scenarios (business meetups, conference valet) are high-friction for
gesture auth. Android Auto allows a simplified, voice-driven contact exchange flow where
the driver dictates "AURA exchange" and receives a TTS announcement of the received contact.

**Technical design:**
- `CarAppService` (Jetpack Car App Library 1.x) for head unit UI. Not a media session —
  uses the Navigation/POI template category.
- Voice actions: `ACTION_EXCHANGE` mapped to a `CarVoiceInteractionService`.
- TTS read-out: received contact name + company. "Add to contacts" confirmation uses a
  single-button `LongMessageTemplate`.
- Camera gesture auth cannot run on the head unit — Auto mode automatically uses biometric
  fallback (phone-side) when the driver's camera is unavailable.

**Tasks:**
- [ ] `automotive/` Gradle module, `CarAppService` scaffold.
- [ ] Voice action registration in `AndroidManifest.xml`.
- [ ] Car App Library screen: IDLE, ADVERTISING, SAS display, COMPLETED with received name.
- [ ] Biometric-only auth gate when no camera input is available (Auto context).

**Milestone:** v3.1.0

---

### Phase 7.4 — Enterprise / MDM distribution (v3.2)

**Why:** Enterprise IT administrators need to control which auth methods are available,
disable the QR relay fallback (data exfiltration vector on locked-down networks), enforce
minimum gesture similarity thresholds, and retain exchange audit logs for compliance.

**Technical design:**
- Android Managed Configurations (`app_restrictions.xml`) expose a restricted key bundle
  that MDM consoles (Intune, VMware Workspace ONE, etc.) can push to enrolled devices.
- `BuildConfig.IS_ENTERPRISE` flavor gate separates enterprise-only UI (audit export,
  policy status banner) from the consumer build.
- Managed config keys:
  - `allowed_auth_methods` (enum: GESTURE, BIOMETRIC, BOTH)
  - `qr_relay_enabled` (boolean, default true)
  - `min_gesture_similarity` (float 0.7–0.99, default 0.88)
  - `audit_log_retention_days` (int, default 90)
  - `force_key_rotation_days` (int, default 0 = disabled)

**Tasks:**
- [ ] `enterprise/` flavor source set with `app_restrictions.xml`.
- [ ] `EnterprisePolicy` singleton that reads managed config on startup and publishes
  a `StateFlow<PolicySnapshot>` consumed by `GestureAuthManager`, `RelayClient`, Settings.
- [ ] Policy enforcement in `GestureAuthManager.authenticate()`: if `allowed_auth_methods`
  is `BIOMETRIC`, bypass gesture pipeline entirely.
- [ ] Audit log retention enforcement: periodic Room cleanup job on `WorkManager`.
- [ ] `EnterpriseSettingsFragment` visible only in enterprise flavor.

**Milestone:** v3.2.0

---

### Phase 7.5 — F-Droid distribution + reproducible builds (v3.2)

**Why:** The `foss` product flavor removes all Google services, making AURA suitable for
distribution through F-Droid — the primary app store for privacy-conscious Android users
who refuse Google Play. F-Droid requires reproducible builds (byte-for-byte identical APK
from source) and zero proprietary SDK dependencies.

**Blockers that must be resolved first:**
1. Phase 5.8 (bundle MediaPipe model) — F-Droid cannot accept APKs that download binaries
   at runtime from external CDNs. The model must be in the APK.
2. The `foss` flavor must be verified clean of all GMS dependencies via `./gradlew lint`
   with a custom rule that flags any `com.google.android.gms` import in `foss` source sets.

**Tasks:**
- [ ] Enable `android.enableBundleR8Minification` and `android.bundleRelease.shrinkResources`
  for `foss` flavor only.
- [ ] Set `android.defaultConfig.versionCode` derivation to be source-only (no CI timestamp
  injection) so builds are reproducible.
- [ ] `reproducible_build_test.sh` — build the `fossRelease` APK twice from the same source,
  compare SHA-256 hashes, assert equality.
- [ ] Submit `fdroid/com.showerideas.aura.yml` metadata file to the F-Droid data repository.
- [ ] Verify with `fdroidserver build` locally before submission.

**Milestone:** v3.2.0

---

## PHASE 8 — v4.x Security hardening wave 4

> These phases represent the security evolution path for when AURA reaches a larger user
> base where the threat model expands beyond opportunistic MITM to nation-state-level
> adversaries. Each phase is independent and can ship separately.

---

### Phase 8.1 — Post-quantum key exchange (v4.0)

**Why:** The current ECDH key agreement (secp256r1) is broken by Shor's algorithm on a
sufficiently capable quantum computer. NIST finalized ML-KEM-768 (Kyber) in FIPS 203
(August 2024) as the primary post-quantum KEM. A hybrid construction (`X25519 || ML-KEM-768`)
provides security if *either* primitive is unbroken — classic security for today's threats,
PQ security for future threats. This is the approach adopted by Signal, Apple iMessage
PQ3, and Google Chrome.

**Technical design:**
- Wire protocol bumps to v6. A `version` field in the handshake HELLO message allows v5
  (classical) and v6 (hybrid PQ) peers to interoperate: v6 peers fall back to v5 if the
  remote does not advertise PQ support.
- On Android, ML-KEM-768 must be implemented in pure Kotlin/Java (no JCA provider supports
  it yet in Android 14). The reference implementation from the NIST submission or BouncyCastle
  1.78+ can be used.
- Key encapsulation: initiator generates `(ek_X25519, ek_MLKEM768)`. Responder encapsulates
  to both and sends `(ct_X25519, ct_MLKEM768)`. Initiator decapsulates both, XORs the two
  shared secrets before HKDF extraction. The XOR ensures the hybrid is at least as strong
  as either component.
- Symmetric session key derivation (HKDF) absorbs both shared secrets with domain separation:
  `HKDF(salt, X25519_ss XOR MLKEM_ss, "AURA-HYBRID-KEM-v6")`.

**Tasks:**
- [ ] Integrate BouncyCastle 1.78+ or a vetted ML-KEM reference implementation.
  Verify with NIST Known Answer Tests (KATs).
- [ ] Update `CryptoUtils` with `generateHybridKEMKeyPair()`, `encapsulate()`, `decapsulate()`.
- [ ] Wire protocol v6 negotiation in `NearbyExchangeService` HELLO handshake.
- [ ] `HybridKEMTest` — KAT vectors, roundtrip with self-generated keys, backwards compat
  with v5 (classical) peer simulation.
- [ ] Update `docs/SECURITY.md` with the hybrid construction rationale and algorithm choices.

**Milestone:** v4.0.0

---

### Phase 8.2 — Sealed sender profile payload (v4.1)

**Why:** The current wire protocol reveals who is sending in the Nearby Connections session
(endpoint IDs are visible to the transport layer). An observer of the P2P session metadata
(endpoint ID + payload size + timing) can correlate exchanges with real-world identities
even without breaking encryption. Fixed-size padded envelopes and sealed-sender techniques
make all exchanges look identical on the wire.

**Technical design:**
- `SealedEnvelope` wraps the profile payload in a fixed-size frame:
  `[version: 1 byte][payload_len: 4 bytes][encrypted_payload: N bytes][padding: (MAX_SIZE - N) bytes]`
  where `MAX_SIZE = 4096 bytes` (covers all realistic profile payloads with avatar thumbnails).
- The sender's identity is encrypted *inside* the envelope along with the profile.
  The recipient's transport-layer endpoint ID is present, but the sender ID is hidden.
- Padding strategy: PKCS#7-style deterministic padding to the nearest 256-byte block,
  then zero-pad to `MAX_SIZE`. Constant-time to prevent timing side channels.

**Tasks:**
- [ ] `SealedEnvelope.wrap(profile: ByteArray): ByteArray` — pad and seal.
- [ ] `SealedEnvelope.unwrap(envelope: ByteArray): ByteArray` — verify frame, strip padding.
- [ ] Update `NearbyExchangeService.sendProfile()` and `handleIncomingProfile()` to use sealed envelopes.
- [ ] `SealedEnvelopeTest` — roundtrip, fixed-size invariant, padding correctness.
- [ ] Wire protocol bump to v7.

**Milestone:** v4.1.0

---

### Phase 8.3 — QR relay traffic anonymization (v4.1)

**Why:** The QR relay (Firebase or self-hosted) receives HTTP requests carrying the sender's
IP address. Even though the payload is AES-GCM encrypted, the relay operator (or anyone
who subpoenas them) can see which IP address uploaded a profile slot and which IP address
retrieved it. For users in high-risk contexts (journalists, activists), this metadata is
as sensitive as the payload.

**Technical design:**
- Opt-in mode: user enables "High privacy relay" in Settings → Security.
- When enabled, `RelayClient` routes requests through an HTTP-over-Tor proxy using the
  Android Orbot SDK (`info.guardianproject:netcipher`) or a SOCKS5 proxy if Orbot is installed.
- Fall back to direct HTTPS if Tor is unavailable or the user has not opted in.
- This is a transport-layer change only — the relay protocol and payload format are unchanged.

**Tasks:**
- [ ] Add Orbot/NetCipher dependency to `gms` and `foss` flavors.
- [ ] `RelayClient.setAnonymizationProxy(socksAddress: InetSocketAddress?)` — configure proxy.
- [ ] Settings toggle "Route relay via Tor (requires Orbot)".
- [ ] Detect Orbot installation via `PackageManager.getPackageInfo("org.torproject.android", 0)`.
- [ ] `RelayClientProxyTest` — verify requests are routed through the proxy when configured.

**Milestone:** v4.1.0

---

### Phase 8.4 — Remote blocklist sync + transparency log (v4.2)

**Why:** A user who blocks a peer on one device has no mechanism to propagate that block
to their other devices, or to warn others in their network about a bad actor. A
transparency-log-backed blocklist (similar to Certificate Transparency) provides
a tamper-evident, publicly auditable record of reported abusive endpoints without revealing
which user reported them (hashed identity keys only).

**Technical design:**
- Opt-in. User can submit an endpoint's `identityKeyHash` to the transparency log.
  Submission is hashed again server-side — no raw key hashes are stored.
- On exchange: app fetches the peer's hash from the log (or checks a locally-cached Bloom
  filter for efficiency) and warns if the peer has received community-reported blocks.
- Transparency log is append-only (Merkle tree), publicly auditable, no deletions.
- Infrastructure: self-hosted or Cloudflare Workers + KV for zero-ops deployment.

**Tasks:**
- [ ] Transparency log server spec (`docs/BLOCKLIST_TRANSPARENCY.md`).
- [ ] `TransparencyLogClient` — submit hash, fetch Bloom filter, verify Merkle proof.
- [ ] On-device Bloom filter with weekly refresh via WorkManager.
- [ ] UI: "⚠️ This peer has been reported by other AURA users" warning in exchange flow.
- [ ] Settings: opt-in toggle + "Submit this peer as abusive" action from blocklist screen.

**Milestone:** v4.2.0

---

## PHASE 9 — v5.x Intelligence & personalisation

> These phases improve the quality of the gesture authentication pipeline and add smart
> defaults that reduce friction without weakening security.

---

### Phase 9.1 — Smart share presets (v5.0)

**Why:** The current share-fields selector is static — the user chooses once in Settings.
In practice, users share different fields in different contexts: full contact at a business
meeting, just email at a conference, phone only with close friends. Smart presets let the
user tag each profile (Personal, Work, Custom) with a context-specific field set and select
the right preset with one tap before exchanging.

**Tasks:**
- [ ] `SharePreset` entity in Room: name, field set, last-used timestamp.
- [ ] Quick-select preset sheet in `ExchangeFragment` shown before session starts.
- [ ] "Save as preset" button in Profile → Share fields settings.
- [ ] Default presets: "Full card", "Professional", "Minimal".

---

### Phase 9.2 — On-device exchange analytics (v5.0)

**Why:** The `ExchangeAuditLog` captures raw events but provides no aggregated view.
Users have no insight into when they exchange most, with whom they exchange repeatedly, or
how their network grows over time. On-device analytics (never uploaded) add value without
privacy risk.

**Tasks:**
- [ ] `AnalyticsFragment` — weekly exchange count chart, top-10 recurring contacts,
  exchange success rate (completed vs cancelled), average session duration.
- [ ] All data computed from the local `ExchangeAuditEntry` Room table — zero telemetry.
- [ ] Export analytics as CSV from Settings → Data & privacy.

---

### Phase 9.3 — Gesture library expansion (v5.1)

**Why:** AURA currently supports a single enrolled gesture pattern (with optional 2-step
sequence). Different gestures have different FAR/FRR profiles depending on hand anatomy.
A gesture library lets users enroll multiple gesture options and select the one that
gives them the best authentication rate on their specific hand shape.

**Current architecture context:**
- `GestureAuthManager` stores a single centroid embedding in `EncryptedSharedPreferences`.
- A 2-step sequence (`gesture_sequence_step2`) already exists as a second credential slot.
- The 21-landmark 63-float embedding space is well-suited to storing multiple enrolled patterns.

**Tasks:**
- [ ] Extend `EncryptedSharedPreferences` storage to a named map of up to 5 gesture profiles.
- [ ] `GestureLibraryFragment` — list enrolled gestures, enroll new, test similarity score
  in real-time against live camera feed, delete.
- [ ] `GestureAuthManager.authenticateAny()` — pass if any enrolled gesture scores above threshold.
- [ ] A/B FAR test: measure false-accept rate with 1 vs 3 enrolled gestures on the same device.

---

### Phase 9.4 — ML gesture model improvement (v5.1)

**Why:** The current matching uses cosine similarity on raw MediaPipe landmark embeddings.
This works but has known weaknesses: it is sensitive to hand scale variation (partially
mitigated by normalization), and has no rejection class (it scores everything against the
enrolled pattern rather than learning "this is not a valid gesture at all"). A lightweight
on-device classifier trained on the enrolled samples would improve both FAR and FRR.

**Design approach:**
- At enrollment time (5 samples recorded), train a tiny binary classifier (SVM or 2-layer
  MLP) on the enrolled positive samples plus synthetically-generated negative samples
  (random embeddings in the same normalized space). Model is ~10KB — trivially storable
  in `EncryptedSharedPreferences`.
- At authentication time: run classifier → binary accept/reject before cosine similarity.
  The cosine threshold becomes a second gate rather than the sole decision boundary.
- Use TensorFlow Lite (already a transitive dependency via MediaPipe) for model inference.
  Training can be done fully on-device using TFLite Model Maker or a pre-compiled training
  loop.

**Tasks:**
- [ ] `GestureClassifier.train(enrolledSamples: List<FloatArray>): ByteArray` — returns
  serialized TFLite model bytes.
- [ ] `GestureClassifier.predict(embedding: FloatArray): Float` — returns confidence score.
- [ ] Store trained model in `EncryptedSharedPreferences` alongside the centroid embedding.
- [ ] A/B test: 100 genuine + 100 impostor auth attempts, record FAR and FRR for cosine-only
  vs cosine+classifier. Target: FAR < 0.5%, FRR < 2%.

---

## Version history

| Version | Released | Key changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate (MediaPipe), ECDH+HKDF, room exchange, QR fallback, blocklist, replay protection, biometric, accessibility |
| v1.1.0 | 2026-05-24 | QR relay (encrypted HTTPS), 7 locales 100% coverage, 259 unit + 51 instrumented tests, signed APK splits |
| v2.0.0-dev | main | Phase 6.2 transport injection (gms/foss Hilt DI), SAS Espresso tests, NFC Espresso tests, QR relay docs, Phase 6.7/6.8/6.9 nearly complete |

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step exchange sequence diagram |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Room schema (v8), migration history |
| [`docs/qr-relay-setup.md`](docs/qr-relay-setup.md) | Firebase Realtime Database relay setup guide |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-25 — All Phase 6.x PRs merged to main. Phases 6.7/6.8/6.9 nearly
complete (instrumented test + manifest filter + blocklist identicon). Next: close out 6.x
minor tasks then begin Phase 5.7 (cert pinning) and 6.10 (backup/restore).*
