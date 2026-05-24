# AURA — Project Roadmap

> **This is the canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every session.
> Keep it current — strike through completed items, add blockers inline.

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | Production-ready on `main` — v1.1.0 tagged and released |
| Security audit | Wave 3 complete — all findings resolved (A1–A15) |
| Localization | 209 strings × 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) — **100% coverage, CI-enforced** |
| Test suite | Unit: 18 files · 184 methods · Instrumented: 12 files · 51 methods · 0 failures |
| CI pipeline | Green — unit + JaCoCo (40% branch floor) + lint + `assembleRelease` + APK size gate + MediaPipe class check |
| Play Store pipeline | `upload-to-play` wired in `ci.yml` — blocked only on `GOOGLE_PLAY_JSON_KEY` secret |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| AUDIT.md | All items reviewed — Wave 3 complete, Phase 2 + 3 + 4 complete |
| QR relay | Implemented — AES-256-GCM encrypted profile POST/GET over HTTPS relay |
| **Primary blocker to Play Store** | **`GOOGLE_PLAY_JSON_KEY` secret + Play Console app listing assets (icon, screenshots, feature graphic)** |

---

## PHASE 1 — Play Store Launch *(primary objective)*

> All items in this phase are required before AURA ships to real users on the Play Store.
> Tasks are ordered by dependency. Work top-to-bottom.

---

### 1.1 Configure signing secrets (already done for GitHub Releases)

The release keystore is already generated and configured. The signing pipeline is wired.
The 4 keystore secrets (`KEYSTORE_BASE64`, `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`,
`KEYSTORE_KEY_PASSWORD`) are already set and confirmed working (v1.1.0 APK slices signed).

**Only remaining signing prerequisite: Play Console service account**

1. Go to Play Console → Setup → API access
2. Link to a Google Cloud project (or create one)
3. Create a service account with the role **Release manager**
4. Download the JSON key file
5. Add it as `GOOGLE_PLAY_JSON_KEY` in GitHub → Settings → Secrets and variables → Actions

**Verification after setting:**

- [ ] Trigger the `upload-to-play` workflow manually from GitHub → Actions
- [ ] Confirm the signed AAB appears in Play Console → Internal testing → Releases
- [ ] Verify the AAB installs cleanly on a physical device

---

### 1.2 Play Console — App listing setup

These items must be complete before Google will accept the app for review.

**Store listing (Main store page)**

- [ ] App name: `AURA`
- [ ] Short description (≤ 80 chars): confirm final copy from `STORE_LISTING.md`
- [ ] Full description (≤ 4000 chars): final copy from `STORE_LISTING.md` — review relay mention
- [ ] App icon: 512×512 PNG, no alpha channel — upload to Play Console
- [ ] Feature graphic: 1024×500 PNG — required for store display (currently missing)
- [ ] Screenshots — minimum 2 required per device type:
  - [ ] Home screen with activation tile
  - [ ] Profile setup screen
  - [ ] Exchange in progress (gesture animation)
  - [ ] Contacts list with received card
  - [ ] QR fallback screen
  - [ ] 7-inch tablet screenshots (optional but improves discoverability)

**App content**

- [ ] Privacy policy URL: confirm `https://showerideas.github.io/Aura/privacy` resolves and renders
- [ ] Content rating questionnaire: fill out IARC questionnaire in Play Console
  - AURA has no user-generated content shared externally, no violence, no ads
  - Expected rating: Everyone / PEGI 3
- [ ] Target audience: confirm 18+ or All ages (no child-directed content)
- [ ] Data safety form — complete honestly:
  - Data collected: none sent to us (all data stays on-device or to peer directly)
  - Data shared: user profile shared peer-to-peer, encrypted, on user action only
  - Security practices: encrypted in transit (AES-256-GCM over BLE/Wi-Fi-P2P + HTTPS relay), encrypted at rest (Android Keystore)
  - Note: the QR relay transmits only ciphertext — no plaintext profile data reaches the relay server

**App categorization**

- [ ] Category: `Tools` or `Communication` — decide and set
- [ ] Tags: contact exchange, offline, privacy, gesture, bluetooth
- [ ] Country availability: set distribution (all countries or restricted)
- [ ] Pricing: Free

---

### 1.3 Update STORE_LISTING.md for QR relay

The existing copy says "no outbound network calls" which is no longer accurate for the QR relay path.

- [ ] Update full description to accurately describe the optional QR relay ("encrypted relay — the server only sees ciphertext")
- [ ] Keep the "fully offline" claim scoped to BLE/Wi-Fi-P2P paths only
- [ ] Update the data safety section inline in STORE_LISTING.md

---

### 1.4 Internal testing track

Once the signed AAB is uploaded via CI:

- [ ] Publish to internal testing track in Play Console
- [ ] Add internal testers (your own Google accounts + any trusted testers)
- [ ] Install via Play Store on a real device — confirm the Play-distributed build:
  - [ ] App installs without sideload warnings
  - [ ] Gesture recording works end-to-end
  - [ ] BLE exchange completes between two physical devices
  - [ ] QR fallback (direct scan path) works
  - [ ] QR relay (HTTPS relay path) works — POST profile + poll for peer
  - [ ] vCard export works
  - [ ] Blocklist persists across app restarts
  - [ ] All 7 locales display correctly (switch device language and reopen)
  - [ ] Biometric unlock triggers correctly
  - [ ] Volume button triple-press activates from background
  - [ ] SAS dialog appears on first-meet exchanges and displays a 6-digit code
- [ ] Run through AUDIT.md headline claims H1–H17 on the Play-distributed build
- [ ] Confirm no crash on fresh install (no Room migration issues from null state)

---

### 1.5 Closed testing *(recommended before production)*

- [ ] Create a closed testing track with a small group (5–20 people)
- [ ] Collect feedback on: gesture sensitivity, onboarding clarity, BLE reliability, QR relay latency
- [ ] Fix any P1/P2 bugs found before promoting to production
- [ ] Minimum 1 week in closed testing before production submission

---

### 1.6 Production submission

- [ ] Promote the closed testing AAB to the production track
- [ ] Set a staged rollout percentage (recommended: 10% → 50% → 100%)
- [ ] Submit for Google Play review
- [ ] Expected review time: 1–3 days for a new app
- [ ] Address any policy rejection reasons immediately
- [ ] Tag the production release in git: `git tag v1.1.0-play && git push origin v1.1.0-play`
- [ ] Update `README.md` badge/link to point to the Play Store listing URL

---

### 1.7 Post-launch monitoring *(first 2 weeks)*

- [ ] Watch Play Console → Android vitals for ANRs and crashes
- [ ] Watch Play Console → Ratings and reviews daily for the first week
- [ ] Set up an alert or check-in schedule for review responses
- [ ] Confirm the GitHub Actions `upload-to-play` job continues to succeed on new pushes
- [ ] Confirm GitHub Pages privacy policy URL remains live

---

## PHASE 2 — Localization ✅

> **Complete as of 2026-05-24.** All 209 strings covered across all 7 locales.
> `LocalizationCoverageTest.kt` (JVM unit test) enforces full coverage on every CI run —
> any future string added to `values/strings.xml` without translations fails the build.

~~Audit `values/strings.xml` — count total strings vs. translated strings per locale~~
~~Identify untranslated strings falling back to English in each `values-xx/` folder~~
~~Add a CI lint step that fails if any string key exists in `values/` but is missing in any `values-xx/`~~

Remaining localization work (v1.2):
- [ ] Native-speaker review pass for each locale (DE, ES, FR, HI, JA, KO, ZH-CN)
- [ ] Add screenshot automation (Screengrab / Fastlane) for localized Play Store assets

---

## PHASE 3 — Test coverage hardening ✅

> **Complete as of 2026-05-24.** All previously yellow AUDIT.md §2 items now have
> automated coverage. 184 unit test methods + 51 instrumented test methods.

| Test added | Covers |
|---|---|
| `SettingsEspressoTest` | Row 19 — Settings + Blocked screens |
| `OnboardingEspressoTest` | Row 05 — Onboarding flow |
| `PermissionRationaleEspressoTest` | Row 03 — Permission-rationale sheet |
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
> All 7 locale files updated (209 keys × 8 files).

~~Implement `RelayClient.kt` using `HttpURLConnection` (no new deps, HTTPS-only)~~
~~Integrate relay POST/poll/decrypt/save into `QRExchangeViewModel`~~
~~Restore `INTERNET` permission with manifest documentation~~
~~Configure `RELAY_BASE_URL` as env-var-backed `BuildConfig` field~~
~~Update localization strings across all 8 locale files~~

---

## PHASE 5 — v1.x Polish *(post-launch)*

> Small improvements shipping after v1.1.0 stabilises in production.
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

### 5.4 Play Store assets automation (v1.3)

- [ ] Integrate Screengrab / Fastlane for automated screenshot generation
- [ ] Generate localized screenshots for all 7 locales in CI
- [ ] Auto-upload screenshots to Play Console via Fastlane Supply

---

### 5.5 Volume button reliability improvement (v1.3)

- [ ] Evaluate `AccessibilityService` path as an alternative to `MediaSession` for OEM compatibility
- [ ] If AccessibilityService approach is viable: implement and gate behind a user opt-in in Settings
- [ ] Document updated OEM compatibility matrix in `VOLUME_BUTTON_RELIABILITY.md`

---

### 5.6 Native-speaker localization review (v1.3)

- [ ] Commission a native-speaker review for each of the 7 locales
- [ ] Fix any awkward translations identified in the review
- [ ] Re-run `LocalizationCoverageTest` to confirm no regressions

---

### 5.7 QR relay self-hosting guide (v1.2)

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

### 6.2 Wi-Fi Direct Transport (v2.1)

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
- F-Droid eligibility requires no Nearby Connections SDK — move to `app-gms` / `app-foss` product flavors

**Open tasks:**
- [ ] Implement `WifiDirectTransport.kt` implementing `NearbyTransport`
- [ ] Write `FakeWifiDirectTransport` test double and integration tests
- [ ] Add product flavor dimension `transport` with values `gms` and `foss`
- [ ] Update CI to build both flavor variants

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

### 6.4 Multiple Profiles — Personal / Work (v2.2)

**Why:** Power users want to share a different card in professional vs. personal contexts
without switching apps or editing their profile each time.

**Design:**
- Room `Profile` table already exists; add a `profileType: ProfileType` column (`PERSONAL`, `WORK`, `CUSTOM`)
- `GestureAuthManager` stores one embedding per profile; the gesture you perform selects the active profile
- UI: profile switcher in the Home screen bottom sheet; each profile tile shows type badge and name
- During exchange, the active profile is sent — no runtime UI change needed
- `ExchangeAuditLog` records which profile was shared

**Key decisions:**
- Gesture-per-profile is the differentiating UX
- Start with 2 profiles (Personal / Work); `CUSTOM` is v2.3+
- Room migration: `ALTER TABLE profiles ADD COLUMN profile_type TEXT NOT NULL DEFAULT 'PERSONAL'`

**Milestone:** v2.2.0

---

### 6.5 Key Rotation (v2.2)

**Why:** Long-lived Keystore identity keys have no expiry. If a device is compromised or sold,
the identity key should be invalidatable.

**Design:**
- "Rotate identity key" action in Settings → Security
- Generates a new EC key pair in Android Keystore under a new alias
- Signs the new key with the old key (rotation certificate) and broadcasts to known peers on next exchange
- Known peers that receive a rotation certificate: verify the old-key signature, update their TOFU registry
- `KnownPeerDao` gets a `rotationCertificate: ByteArray?` column
- `IdentityRotationDetectorTest.kt` already scaffolded — tests can be expanded

**Key decisions:**
- Old key retained for 30 days (verifying rotation certs from slow-updating peers) then deleted
- Rotation is voluntary — AURA never forces rotation
- TOFU registry update is opportunistic: peers learn of the rotation at the next physical exchange

**Milestone:** v2.2.0

---

### 6.6 Exchange Audit Log UI (v2.2)

**Why:** The `ExchangeAuditLog` Room table exists but has no UI. Users have no visibility into
their exchange history.

**Design:**
- New `AuditFragment` accessible from Contacts → overflow menu → "Exchange history"
- Timeline view: each row shows timestamp, peer name (if in contacts), transport used, outcome
- Error rows show the error code in human-readable form
- Export: "Export audit log" button writes a CSV to the Downloads folder
- Retention: UI shows the 90-day rolling window enforced by the existing `pruneOldEntries()`

**Key decisions:**
- Audit log is local-only — no sync, no cloud
- Peer name lookup is by `identityKeyHash`; if peer not in contacts, show "Unknown peer"

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
- Deeplink opens a web landing page with the vCard download button + App Store badge
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
- F-Droid and App Store distribution are independent tracks

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
- Managed Google Play listing (separate from consumer Play Store track)
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
- No runtime profile editing required

**Milestone:** v5.0.0

---

### 9.2 On-Device Exchange Analytics (v5.0)

**Why:** Users have no sense of how many exchanges they've done, with whom, or how their
network is growing.

**Design:**
- A local-only analytics screen (not a dashboard sent anywhere) showing:
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

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit — every feature claim vs. code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model, dependency rules |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions, NIST SP 800-56A compliance |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step sequence diagram for a complete exchange |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, gesture embedding, cosine-similarity matching |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Room schema (v5), entity-relationship diagram, migration history |
| [`STORE_LISTING.md`](STORE_LISTING.md) | Play Store copy — short description, full description, keywords |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source (hosted via GitHub Pages) |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-24 — v1.1.0 shipped. Phases 2, 3, and 4 complete.
Primary blocker to Play Store: `GOOGLE_PLAY_JSON_KEY` + listing assets.*
