# AURA — Project Roadmap

> **This is the canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every session.
> Keep it current — strike through completed items, add blockers inline.

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | Production-ready on `main` — v1.1.0 tagged and released; Phase 6.2 (gms/foss flavors) merged |
| Security audit | Wave 3 complete — all findings resolved (A1–A15) |
| Localization | 262 strings × 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) — **100% coverage, CI-enforced** |
| Test suite | Unit: 22 files · 259 methods · Instrumented: 12 files · 51 methods · 0 failures |
| CI pipeline | Green — unit + JaCoCo (40% branch floor) + lint + `assembleRelease` + APK size gate + MediaPipe class check |
| Distribution | GitHub Releases — signed APK splits (arm64-v8a + armeabi-v7a) |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| AUDIT.md | All items reviewed — Wave 3 complete, Phase 2 + 3 + 4 + 6.1–6.6 complete |
| QR relay | Implemented — AES-256-GCM encrypted profile POST/GET over HTTPS relay |
| Room DB | v8 — profile_type/is_active/custom_label (v6) + rotation_certificate (v7) + Phase 6.2 migrations (v8) |
| Product flavors | `gms` (Google Play Services) + `foss` (open source, no GMS) — both building and testing green |

---

## PHASE 2 — Localization ✅

> **Complete as of 2026-05-24.** All 262 strings covered across all 7 locales.
> `LocalizationCoverageTest.kt` (JVM unit test) enforces full coverage on every CI run —
> any future string added to `values/strings.xml` without translations fails the build.

~~Audit `values/strings.xml` — count total strings vs. translated strings per locale~~
~~Identify untranslated strings falling back to English in each `values-xx/` folder~~
~~Add a CI lint step that fails if any string key exists in `values/` but is missing in any `values-xx/`~~

Remaining localization work (v1.2):
- [ ] Native-speaker review pass for each locale (DE, ES, FR, HI, JA, KO, ZH-CN)

---

## PHASE 3 — Test coverage hardening ✅

> **Complete as of 2026-05-25.** All previously yellow AUDIT.md §2 items now have
> automated coverage. 259 unit test methods + 51 instrumented test methods, 0 failures.

| Test added | Covers |
|---|---|
| `SettingsEspressoTest` | Row 19 — Settings + Blocked screens |
| `OnboardingEspressoTest` | Row 05 — Onboarding flow |
| `PermissionRationaleEspressoTest` | Row 03 — Permission-rationale sheet |
| `ExchangeFlowEspressoTest` | Row 07 — Exchange flow activation + cancel |
| `BiometricAvailabilityTest` | Row 16 — Biometric unlock |
| `LocalizationCoverageTest` | Row 20 — Localization CI enforcement |
| `WireProtocolTest` (17 tests) | ECDH, profile encryption, crypto math |
| `SasVerifierTest` (17 tests) | SAS determinism, ordering, uniqueness, range |
| `NfcExchangeHelperTest` | NFC tap bootstrap path |
| `DoubleRatchetStateTest` | Double Ratchet state machine |
| `LivenessGuardTest` | Liveness guard logic |
| `IdenticonGeneratorTest` | Identicon generation |
| `IdentityRotationDetectorTest` | Identity key rotation detection |
| `SecurityHardeningTest` | Payload field bounds, size gates |

~~Biometric unlock — add instrumented test~~
~~Permission-rationale sheet — add Espresso test~~
~~Onboarding flow — add Espresso smoke test~~
~~Settings screen — add Espresso test covering blocked device filter~~
~~Localization — add CI lint step~~

---

## PHASE 4 — QR Relay ✅

> **Complete as of 2026-05-24.** Encrypted profile POST/GET via HTTPS relay.
> All 7 locale files updated (262 keys × 8 files).

~~Implement `RelayClient.kt` using `HttpURLConnection` (no new deps, HTTPS-only)~~
~~Integrate relay POST/poll/decrypt/save into `QRExchangeViewModel`~~
~~Restore `INTERNET` permission with manifest documentation~~
~~Configure `RELAY_BASE_URL` as env-var-backed `BuildConfig` field~~
~~Update localization strings across all 8 locale files~~

---

## PHASE 5 — v1.x Polish *(post-launch)*

> Small improvements shipping after v1.1.0 stabilises.
> Each item is scoped to a patch or minor release.

---

### 5.1 SAS Dialog UI hardening (v1.2)

The SAS PIN verifier (`SasVerifier.kt`) and `ExchangeFragment.showSasDialog()` are wired.
These items make the dialog production-grade:

- [ ] Add a countdown timer in the SAS dialog (30 s) — auto-abort on timeout
- [ ] Add haptic feedback when the SAS dialog appears (draws attention in noisy environments)
- [ ] Store the SAS confirmation event in the `ExchangeAuditLog` with timestamp
- [ ] Write an Espresso test that mocks `NearbyExchangeService` broadcasts and verifies the dialog appears, displays the correct 6-digit code, and dismisses correctly
- [ ] Add SAS dialog to QR relay path (currently only on Nearby path)

---

### 5.2 Coverage gate hardening (v1.2)

Current JaCoCo branch-coverage floor is 40% — a conservative starting point.

- [ ] Raise floor to 50% — add tests for `NearbyExchangeService` state transitions (mock transport layer)
- [ ] Raise floor to 60% — add ViewModel tests for `QRExchangeViewModel` relay states
- [ ] Target: 70% branch coverage by v1.3

---

### 5.3 Accessibility improvements (v1.2)

- [ ] Automated TalkBack / Accessibility Scanner CI pass (currently 🟡 manual only)
- [ ] Add `contentDescription` to all icon-only buttons (camera preview toggle, cancel, QR scan)
- [ ] Verify minimum touch-target sizes (48dp) on the gesture enrollment page
- [ ] Add `accessibilityLiveRegion` to the SAS dialog digit display

---

### 5.4 Volume button reliability improvement (v1.3)

- [ ] Evaluate `AccessibilityService` path as an alternative to `MediaSession` for OEM compatibility
- [ ] If AccessibilityService approach is viable: implement and gate behind a user opt-in in Settings
- [ ] Document updated OEM compatibility matrix in `VOLUME_BUTTON_RELIABILITY.md`

---

### 5.5 Native-speaker localization review (v1.3)

- [ ] Commission a native-speaker review for each of the 7 locales
- [ ] Fix any awkward translations identified in the review
- [ ] Re-run `LocalizationCoverageTest` to confirm no regressions

---

### 5.6 QR relay self-hosting guide (v1.2)

- [ ] Write `docs/qr-relay-setup.md` with step-by-step instructions for Firebase Realtime Database (zero-ops, free tier)
- [ ] Document how to configure `RELAY_BASE_URL` env var for CI and local dev
- [ ] Provide a reference implementation of the relay server contract (POST/GET semantics, slot TTL, ciphertext-only guarantee)
- [ ] Add alternative: serverless function on Cloudflare Workers (equally zero-ops)

---

## PHASE 6 — v2.x Core Features

> Next-generation feature set. Each subsection is a self-contained workstream
> with its own spec, implementation, and test gate before merge.

---

### 6.1 NFC Tap-to-Exchange (v2.0)

**Why:** Volume-button activation has >50% OEM failure rate on Samsung/MIUI. NFC is
hardware-guaranteed on all modern Android devices and is the most intuitive physical gesture.
`NfcExchangeHelper.kt` and `NfcExchangeHelperTest.kt` are already scaffolded.

**Design:**
- AURA registers as an `NfcAdapter.ReaderCallback` foreground listener + NFC HCE service
- On NFC tap: exchange the ephemeral ECDH public key over the NFC data channel (APDU frames)
- Continue session over Nearby Connections (BLE/Wi-Fi) for the bulk profile payload
- NFC bootstraps the session; large-payload crypto stays on Nearby

**Key decisions:**
- HCE (`HostApduService`) vs. P2P (`IsoDep` + `NdefRecord`): use HCE — works without both devices in active mode simultaneously
- Fallback: if NFC is unavailable, volume button and QR remain available
- Test: instrumented test using `NfcAdapter.enableReaderMode` mock + `NdefMessage` fixture

**Current state:** `NfcExchangeHelper.kt` scaffolded, `NfcExchangeHelperTest.kt` covering helper logic — full integration not yet wired to `NearbyExchangeService`

**Open tasks:**
- [ ] Wire `NfcExchangeHelper` into `ExchangeFragment` and `NearbyExchangeService` start path
- [ ] Add NFC foreground dispatch intent filter to `AndroidManifest.xml`
- [ ] Add HCE service declaration (`HostApduService` subclass)
- [ ] Instrumented test on a real NFC-capable device pair

**Milestone:** v2.0.0

---

### 6.2 Wi-Fi Direct Transport (v2.1) — Flavors ✅ / Transport pending

> **Product flavors complete as of 2026-05-25 (merged #56).** `gms` and `foss` build dimensions,
> conditional ABI splits (AGP #402800800 workaround), CI updated for both flavor variants.
> `FakeNearbyTransport` test double implemented. All CI green.
> `WifiDirectTransport` implementation still pending.

**Why:** Nearby Connections over Wi-Fi P2P is constrained by Google Play Services.
A pure Wi-Fi Direct transport removes the GMS dependency and enables F-Droid distribution.

**Design:**
- Implement `WifiDirectTransport` satisfying the existing `NearbyTransport` interface
- Use `WifiP2pManager` + `WifiP2pManager.Channel` for peer discovery and connection
- Profile payload encryption is transport-agnostic (already in `WireProtocol`)
- The `NearbyTransport` interface abstraction means the entire crypto stack is reused unchanged

**Key decisions:**
- Wi-Fi Direct group owner negotiation is non-deterministic; implement a tiebreaker via lexicographic key ordering (already used in SAS derivation)
- Keep Nearby Connections as the default; offer Wi-Fi Direct as an opt-in in Settings

**Open tasks:**
- [ ] Implement `WifiDirectTransport.kt` implementing `NearbyTransport`
- [ ] Write `FakeWifiDirectTransport` test double and integration tests

~~Add product flavor dimension `transport` with values `gms` and `foss`~~
~~Update CI to build both flavor variants~~

**Milestone:** v2.1.0

---

### 6.3 Contact Deduplication Engine (v2.1)

**Why:** Receiving an updated card from someone you already have produces a duplicate in Room.

**Design:**
- After a successful exchange, query Room for contacts with matching `identityKeyHash`
- If a match exists: show a merge dialog — "Update existing contact?" with a diff view
- Diff view shows which fields changed (name, email, phone, avatar)
- User can accept all, reject all, or pick per-field
- `ContactDao` gets a new `upsertByIdentity(contact)` transaction

**Key decisions:**
- Identity matching uses `identityKeyHash` (cryptographic), not name/email (fuzzy) to prevent false merges
- If the incoming key doesn't match any known identity, it's always a new contact
- The merge dialog is non-blocking — the contact is saved as-is and the merge prompt follows

**Milestone:** v2.1.0

---

### 6.4 Multiple Profiles — Personal / Work (v2.2) ✅

> **Complete as of 2026-05-24.**
> Room DB v6 migration adds `profile_type`, `is_active`, `custom_label`.
> `ProfileSwitcherBottomSheet` + `ProfileSwitcherAdapter` wired into HomeFragment.
> `ProfileRepository` fully multi-profile with atomic `setActive()` transaction.
> 18 new JVM unit tests in `MultiProfileTest.kt`.

**Milestone:** v2.2.0

---

### 6.5 Key Rotation (v2.2) ✅

> **Complete as of 2026-05-24.**
> `CryptoUtils.rotateDeviceIdentityKey()` generates new EC key pair and signs it with old private key.
> `RotationCertificate` data class carries old key, new key, signature, and timestamp.
> `KnownPeer.rotationCertificate: ByteArray?` added with Room DB v7 migration.
> Settings → Security section wired with confirmation dialog in `SettingsFragment`.
> `SettingsViewModel.rotateIdentityKey()` runs on `Dispatchers.IO`.
> 12 new JVM unit tests in `KeyRotationTest.kt`.
> Deferred: persisting/broadcasting rotation certificate to peers at next exchange (Phase 6.5.2).

**Milestone:** v2.2.0

---

### 6.6 Exchange Audit Log UI (v2.2) ✅

> **Complete as of 2026-05-24.**
> `AuditFragment` + `AuditViewModel` + `AuditAdapter` implemented.
> Accessible from Contacts overflow menu and Settings → Security → Exchange history.
> Color-coded outcome chips (cyan=success, red=failed/spoof, gray=others).
> CSV export to Downloads folder via `AuditViewModel.exportToCsv()`.
> Navigation wired in `nav_graph.xml` from both `contactsFragment` and `settingsFragment`.

**Milestone:** v2.2.0

---

### 6.7 Profile Versioning (v2.3)

**Why:** After exchanging cards, users have no way to know when someone updates their contact
details.

**Design:**
- `Profile` gets a `version: Int` field (auto-incremented on any field change)
- `KnownPeer` stores `lastSeenProfileVersion: Int`
- On exchange: if received `version > lastSeenProfileVersion`, surface a "Card updated" banner
- Banner shows a diff of changed fields (same diff engine as §6.3 deduplication)

**Milestone:** v2.3.0

---

### 6.8 "Share AURA" Deeplink (v2.3)

**Why:** Non-AURA users can't receive a contact. There's no fallback for the other person.

**Design:**
- Generate a `https://aura.app/c/<base64-vcard>` deeplink from your profile
- Deeplink opens a web landing page with the vCard download button + GitHub Releases badge
- The vCard is encoded client-side; no server receives any data
- Share sheet: any app (iMessage, WhatsApp, email) can receive the link
- Hosted on GitHub Pages (consistent with current privacy policy hosting — zero backend cost)
- Maximum vCard size ~4 KB; base64 overhead takes it to ~5.5 KB — well within URL limits

**Milestone:** v2.3.0

---

### 6.9 Identicon for Unknown Peers (v2.3)

**Why:** When a peer's identity key is not in the TOFU registry, there is no visual way
to distinguish "new person" from "potential impersonator". An identicon provides a visual
fingerprint of the identity key that the user can compare verbally.

**Design:**
- `IdenticonGenerator.kt` is already implemented and tested (`IdenticonGeneratorTest.kt`)
- Surface the identicon in:
  - The SAS verification dialog (alongside the 6-digit code)
  - The `ContactDetailBottomSheet` for unknown peers
  - The blocklist screen (to help identify blocked parties)

**Milestone:** v2.3.0

---

## PHASE 7 — v3.x Platform Expansion

> Longer-horizon work. Specs will be written when Phase 6 is stable.

---

### 7.1 iOS Companion App — SwiftUI (v3.0)

**Interoperability target:** AURA on Android can exchange with AURA on iOS.

**Architecture:**
- Crypto layer (AES-256-GCM, ECDH on P-256, HKDF-SHA256) is platform-agnostic — identical algorithms and wire format
- Nearby Connections replaced by:
  - iOS: `CoreNFC` (tag reading) + `MultipeerConnectivity` (equivalent to Nearby)
- `WireProtocol.kt` is the spec; the iOS implementation is `WireProtocol.swift`
- SAS verification, TOFU registry, and replay protection all have direct Swift equivalents

**Key decisions:**
- Both platforms use P-256/secp256r1 and SPKI encoding — cross-platform key comparison works natively
- vCard payload format is identical — a contact received from iOS is indistinguishable from one received from Android

**Milestone:** v3.0.0

---

### 7.2 Wear OS Glance Tile (v3.1)

**Design:**
- A single Wear OS Glance tile showing "Ready to exchange" / "Exchange active" state
- Tap the tile: starts an exchange session on the paired phone via `ChannelClient`
- The tile mirrors `NearbyExchangeService` session state in real-time

**Milestone:** v3.1.0

---

### 7.3 Android Auto Integration (v3.1)

**Design:**
- Declare `AURA for Auto` in `automotive_app_desc.xml`
- When a contact is received while the phone is in Auto mode: TTS reads out the contact name and company
- "Add to contacts" voice action via `MediaBrowserService`

**Milestone:** v3.1.0

---

### 7.4 Enterprise / MDM Distribution (v3.2)

**Design:**
- Managed configuration (`app:managedConfigurations`): IT admins can pre-set allowed exchange transports, enforce gesture-only auth, disable QR fallback
- Zero-touch enrollment: AURA profile pre-provisioned from MDM
- `BuildConfig.IS_ENTERPRISE` flavor gate — enterprise build disables personal profile type, hides deeplink, enforces audit log retention

**Milestone:** v3.2.0

---

### 7.5 F-Droid Distribution (v3.2)

**Requirements:**
- Fully reproducible build (currently blocked by Nearby Connections SDK — not reproducible)
- No proprietary dependencies in the `app-foss` flavor
- Replace Nearby Connections with Wi-Fi Direct transport (§6.2 must ship first)
- Replace MediaPipe binary model download with a bundled model (no CDN dependency at runtime)

**Milestone:** v3.2.0 (contingent on §6.2 Wi-Fi Direct shipping)

---

## PHASE 8 — v4.x Security Hardening Wave 4

> Proactive security improvements. Not responses to known vulnerabilities —
> defence-in-depth upgrades for a mature, widely-deployed app.

---

### 8.1 Post-Quantum Key Exchange (v4.0)

**Context:** X3DH + ECDH on P-256 is vulnerable to a cryptographically-relevant quantum computer.
NIST finalised ML-KEM (CRYSTALS-Kyber, FIPS 203) in 2024 as the post-quantum KEM standard.

**Design:**
- Hybrid KEM: `X25519 || ML-KEM-768` — the session key is derived from both KEMs via HKDF
- If either KEM is broken the session remains secure (hybrid holds as long as one component holds)
- Android Keystore does not yet support ML-KEM natively; use a software implementation (BouncyCastle 1.77+ or Tink 2.x)
- Wire protocol version bumped to v6 with negotiation: v5 devices fall back to pure ECDH

**Milestone:** v4.0.0

---

### 8.2 Sealed Sender Profile Payload (v4.1)

**Context:** The current wire protocol reveals that a profile is being sent (JPEG magic bytes and
payload framing are identifiable). A passive observer with BLE traffic capture can infer an
exchange occurred, leaking metadata.

**Design:**
- Wrap the AES-256-GCM ciphertext in an additional `SealedEnvelope` that pads to a fixed size (1024 bytes)
- All AURA exchanges look identical on the wire — no field-length side-channel
- `PayloadValidator` updated to validate the inner ciphertext after unsealing

**Milestone:** v4.1.0

---

### 8.3 QR Relay Anonymisation (v4.1)

**Context:** The QR relay currently receives an HTTPS request from each device's IP address.
Even though the body is ciphertext, the server sees the client IP and timing, which can
infer that an exchange occurred.

**Design:**
- Route QR relay requests through a Tor hidden service or an anonymising proxy (opt-in only)
- Alternatively: evaluate a zero-knowledge relay architecture (client posts a commitment; relay can't link sender and receiver)
- Gate this behind Settings → Privacy → Advanced → Anonymous QR relay (disabled by default)

**Milestone:** v4.1.0 *(research phase only — no implementation until design is reviewed)*

---

### 8.4 Remote Blocklist Sync *(opt-in only)* (v4.2)

**Context:** If a user reports a bad actor's identity key to an opt-in blocklist service,
other AURA users who also opt in can pre-block that identity before ever meeting them.

**Design:**
- Entirely opt-in — disabled by default — users must explicitly enroll
- Identity keys are hashed with a pepper before submission (no plaintext key ever leaves the device)
- Blocklist is a signed append-only log hosted on a transparency-log infrastructure (no central authority)

**Key decisions:**
- This is fundamentally at tension with AURA's "local-first" promise — strictly opt-in with prominent disclosure
- The design must be reviewed by a cryptographer before implementation

**Milestone:** v4.2.0 *(research phase only — no implementation until design is reviewed)*

---

## PHASE 9 — v5.x Intelligence & Personalisation

> Forward-looking features exploring privacy-respecting personalisation.
> All items are on-device only — no cloud, no profiling, no telemetry.

---

### 9.1 Smart Share Presets (v5.0)

**Why:** Users in a professional context might want to share only name + email + title,
while in a personal context they share everything including bio and social handles.

**Design:**
- Up to 5 user-defined "share presets" — named sets of field toggles (e.g. "Work", "Personal", "Minimal")
- Each preset activates via a long-press on the Activate button or a quick-settings tile sub-action
- Presets are stored in DataStore and synced to the profile's `shareField` toggles on activation

**Milestone:** v5.0.0

---

### 9.2 On-Device Exchange Analytics (v5.0)

**Why:** Users have no sense of how many exchanges they've done, with whom, or how their
network is growing.

**Design:**
- A local-only analytics screen showing:
  - Total exchanges this week / month / all-time
  - Most recent exchange date
  - Exchange heatmap by day-of-week / time-of-day (patterns only, no PII)
  - Transport breakdown: Nearby / QR relay / QR direct
- Source: `ExchangeAuditLog` — already populated by `NearbyExchangeService` and `QRExchangeViewModel`
- All data stays on device; the analytics screen reads Room, produces no network calls

**Milestone:** v5.0.0

---

### 9.3 Gesture Library (v5.1)

**Why:** The current system stores exactly one gesture per device. Power users may want
different gestures for different contexts (e.g. right hand vs. left hand, different poses
for different profiles).

**Design:**
- `GestureAuthManager` extended to support a named gesture library (up to 5 gestures)
- Each gesture entry has: name, enrollment date, associated profile (`PERSONAL` / `WORK` / `CUSTOM`)
- On authentication, the top-scoring gesture across the library is used
- If a different gesture wins than the previously active profile's gesture, switch the active profile automatically

**Milestone:** v5.1.0

---

## Version history

| Version | Released | Key changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate (MediaPipe), ECDH+HKDF, room exchange, QR fallback, blocklist, replay protection, biometric, accessibility, 22 features, R8-shrunk release APK |
| v1.1.0 | 2026-05-24 | QR relay (encrypted HTTPS profile exchange), 7 locales at 100% key coverage (CI-enforced), 184 unit + 51 instrumented tests, signed APK splits (arm64-v8a + armeabi-v7a), versionCode 2 |
| v1.2.0-dev | main | Phase 6.2: gms/foss product flavors, conditional ABI splits (AGP #402800800 workaround), FakeNearbyTransport, Room schema files v1/2/5, all Espresso tests green |

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit — every feature claim vs. code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model, dependency rules |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions, NIST SP 800-56A compliance |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step sequence diagram for a complete exchange |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, gesture embedding, cosine-similarity matching |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Room schema (v8), entity-relationship diagram, migration history |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source (hosted via GitHub Pages) |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-25 — Phase 6.2 merged. Distribution: GitHub Releases only.
Next: Phase 6.3 contact deduplication or Phase 6.7 profile versioning.*
