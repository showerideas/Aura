# AURA — Project Roadmap

> **This is the canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every session.
> Keep it current — strike through completed items, add blockers inline.

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | Production-ready on `main` |
| Security audit | Wave 3 complete — all findings resolved |
| Localization | 209 strings × 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) — **100% coverage** |
| Test suite | Unit: 18 files · Instrumented: 9 files · 0 failures |
| CI pipeline | Green — unit, lint, `assembleRelease`, instrumented emulator |
| Play Store pipeline | `upload-to-play` wired in `ci.yml` — blocked only on 5 GitHub secrets |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| AUDIT.md | All items reviewed — A9/A10 resolved, Phase 2 complete |
| **Blocker to launch** | **5 GitHub repo secrets not yet set — this is the only gate** |

---

## PHASE 1 — Play Store Launch *(primary objective)*

> Everything in this phase is required before AURA ships to real users.
> Tasks are ordered by dependency. Work top-to-bottom.

---

### 1.1 Configure signing and Play Console secrets

The `upload-to-play` CI job is fully wired. The moment these 5 secrets are set, the next
push to `main` will build a signed AAB and upload it to the Play Console internal track
automatically — no manual steps required.

**Prerequisite: generate a release keystore (if not already done)**

```bash
keytool -genkey -v \
  -keystore aura-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias aura
```

Keep `aura-release.jks` somewhere safe and backed up. This file signs every release forever.
If you lose it, you cannot update the app on the Play Store.

**Prerequisite: create a Play Console service account**

1. Go to Play Console → Setup → API access
2. Link to a Google Cloud project (or create one)
3. Create a service account with the role **Release manager** (minimum scope needed)
4. Download the JSON key file

**Set these 5 secrets in GitHub → Settings → Secrets and variables → Actions:**

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 aura-release.jks` (full base64 output, no newlines) |
| `KEYSTORE_STORE_PASSWORD` | Password you chose for the keystore |
| `KEYSTORE_KEY_ALIAS` | `aura` (or whatever alias you used above) |
| `KEYSTORE_KEY_PASSWORD` | Password for the key inside the keystore |
| `GOOGLE_PLAY_JSON_KEY` | Full contents of the service account JSON file |

**Verification after setting secrets:**

- [ ] Trigger the `upload-to-play` workflow manually from GitHub → Actions
- [ ] Confirm the signed AAB appears in Play Console → Internal testing → Releases
- [ ] Download the AAB artifact from the CI run and verify it installs cleanly on a physical device
- [ ] Verify `apksigner verify --verbose your-app-release.aab` shows signature is valid

---

### 1.2 Play Console — App listing setup

These items must be complete before Google will accept the app for review.

**Store listing (Main store page)**

- [ ] App name: `AURA`
- [ ] Short description (≤ 80 chars): write final copy based on `STORE_LISTING.md`
- [ ] Full description (≤ 4000 chars): final copy from `STORE_LISTING.md` — review and confirm
- [ ] App icon: 512×512 PNG, no alpha channel — upload to Play Console
- [ ] Feature graphic: 1024×500 PNG — required for store display (currently missing)
- [ ] Screenshots — minimum 2 required per device type, recommended 4-8:
  - [ ] Phone screenshots (min 2): capture on a real device or emulator
    - Home screen with activation tile
    - Profile setup screen
    - Exchange in progress (gesture animation)
    - Contacts list with received card
  - [ ] 7-inch tablet screenshots (optional but improves discoverability)

**App content**

- [ ] Privacy policy URL: confirm `https://showerideas.github.io/Aura/privacy` resolves and renders
- [ ] Content rating questionnaire: fill out IARC questionnaire in Play Console
  - AURA has no user-generated content, no violence, no ads, no location data shared
  - Expected rating: Everyone / PEGI 3
- [ ] Target audience: confirm 18+ or All ages (no child-directed content)
- [ ] Data safety form: complete the Data safety section honestly
  - Data collected: none (all data stays on-device)
  - Data shared: none (no network calls, no backend)
  - Security practices: data encrypted in transit (Bluetooth/Wi-Fi P2P, AES-256-GCM), data encrypted at rest (Android Keystore)

**App categorization**

- [ ] Category: `Tools` or `Social` — decide and set
- [ ] Tags: contact exchange, offline, privacy, gesture, bluetooth
- [ ] Country availability: set distribution (all countries or restricted)
- [ ] Pricing: Free

---

### 1.3 Internal testing track

Once the signed AAB is uploaded via CI:

- [ ] Publish to internal testing track in Play Console
- [ ] Add internal testers (your own Google accounts + any trusted testers)
- [ ] Install via Play Store on a real device — confirm the Play-distributed build:
  - [ ] App installs without sideload warnings
  - [ ] Gesture recording works end-to-end
  - [ ] BLE exchange completes between two physical devices
  - [ ] QR fallback works
  - [ ] vCard export works
  - [ ] Blocklist persists across app restarts
  - [ ] All 7 locales display correctly (switch device language and reopen)
  - [ ] Biometric unlock triggers correctly
  - [ ] Volume button triple-press activates from background
- [ ] Run through the AUDIT.md headline claims H1-H17 manually on the Play-distributed build
- [ ] Confirm no crash on fresh install (no Room migration issues from null state)

---

### 1.4 Closed testing *(recommended before production)*

- [ ] Create a closed testing track with a small group (5-20 people)
- [ ] Collect feedback on gesture sensitivity, onboarding clarity, and BLE reliability
- [ ] Fix any P1/P2 bugs found before promoting to production
- [ ] Minimum 1 week in closed testing before production submission

---

### 1.5 Production submission

- [ ] Promote the internal/closed testing AAB to the production track
- [ ] Set a staged rollout percentage (recommended: start at 10%, then 50%, then 100%)
- [ ] Submit for Google Play review
- [ ] Expected review time: 1-3 days for a new app
- [ ] Address any policy rejection reasons immediately
- [ ] Tag the production release in git: `git tag v1.0.0-play && git push origin v1.0.0-play`
- [ ] Update `README.md` badge/link to point to the Play Store listing URL

---

### 1.6 Post-launch monitoring *(first 2 weeks)*

- [ ] Watch Play Console → Android vitals for ANRs and crashes
- [ ] Watch Play Console → Ratings and reviews daily for the first week
- [ ] Set up an alert or check-in schedule for review responses
- [ ] Confirm the GitHub Actions `upload-to-play` job continues to succeed on new pushes
- [ ] Confirm GitHub Pages privacy policy URL remains live

---

## PHASE 2 — Localization completion ✅

> **Complete as of 2026-05-24.** All 209 strings covered across all 7 locales.
> `LocalizationCoverageTest.kt` (JVM unit test) now enforces full coverage on every CI run —
> any future string added to `values/strings.xml` without translations will fail the build.

~~Audit `values/strings.xml` — count total strings vs. translated strings per locale~~
~~Identify untranslated strings falling back to English in each `values-xx/` folder~~
~~Add a CI lint step that fails if any string key exists in `values/` but is missing in any `values-xx/`~~

Remaining localization work (v1.1):
- [ ] Native-speaker review pass for each locale (DE, ES, FR, HI, JA, KO, ZH-CN)
- [ ] Add screenshot automation (Screengrab / Fastlane) for localized Play Store assets

---

## PHASE 3 — Test coverage hardening ✅

> **Complete as of 2026-05-24.** All previously yellow AUDIT.md §2 items now have
> automated coverage. No manual-QA-only items remain.

| Test added | Covers |
|---|---|
| `SettingsEspressoTest` | Row 19 — Settings + Blocked screens |
| `OnboardingEspressoTest` | Row 05 — Onboarding flow |
| `PermissionRationaleEspressoTest` | Row 03 — Permission-rationale sheet |
| `BiometricAvailabilityTest` | Row 16 — Biometric unlock |
| `LocalizationCoverageTest` | Row 20 — Localization CI enforcement |

~~Biometric unlock — add instrumented test~~
~~Permission-rationale sheet — add Espresso test~~
~~Onboarding flow — add Espresso smoke test~~
~~Settings screen — add Espresso test covering blocked device filter~~
~~Localization — add CI lint step~~

---

## PHASE 4 — v1.x Polish *(post-launch)*

> Small improvements that ship after v1.0.0 stabilises in production.
> Each item is scoped to a patch or minor release.

### 4.1 SAS UI hardening (v1.1)

The SAS dialog is wired. These make it production-grade:

- [ ] Add a countdown timer in the SAS dialog (30 s) — auto-abort on timeout
- [ ] Add haptic feedback when the SAS dialog appears (draws attention on noisy environments)
- [ ] Store the SAS confirmation event in the `ExchangeAuditLog` with timestamp
- [ ] Write an Espresso test that mocks `NearbyExchangeService` broadcasts and verifies the dialog appears and dismisses correctly

### 4.2 Accessibility (v1.1)

- [ ] Automated TalkBack / Accessibility Scanner pass (currently 🟡 manual only)
- [ ] Add `contentDescription` to all icon-only buttons (camera preview toggle, cancel, QR scan)
- [ ] Verify minimum touch-target sizes (48dp) on the gesture enrollment page
- [ ] Ship as v1.1.0

### 4.3 Play Store assets automation (v1.2)

- [ ] Integrate Screengrab / Fastlane for automated screenshot generation
- [ ] Generate localized screenshots for all 7 locales in CI
- [ ] Auto-upload screenshots to Play Console via Fastlane Supply

### 4.4 Volume button reliability improvement (v1.2)

- [ ] Evaluate `AccessibilityService` path as an alternative to `MediaSession` for OEM compatibility
- [ ] If AccessibilityService approach is viable: implement and gate behind a user opt-in in Settings
- [ ] Document updated OEM compatibility matrix in `VOLUME_BUTTON_RELIABILITY.md`

---

## PHASE 5 — v2.x Core Features

> Next-generation feature set. Each subsection below is a self-contained workstream
> with its own spec, implementation, and test gate before merge.

---

### 5.1 NFC Tap-to-Exchange

**Why:** Volume-button activation has >50% OEM failure rate on Samsung/MIUI. NFC is
hardware-guaranteed on all modern Android devices and is the most intuitive physical gesture.

**Design:**
- AURA registers as an `NfcAdapter.ReaderCallback` foreground listener + NFC HCE service
- On NFC tap: exchange the ephemeral ECDH public key over the NFC data channel (APDU frames)
- Continue session over Nearby Connections (BLE/Wi-Fi) for the bulk profile payload
- NFC bootstraps the session; large-payload crypto stays on Nearby

**Key decisions:**
- HCE (`HostApduService`) vs. P2P (`IsoDep` + `NdefRecord`): use HCE for device-to-device; it works without both devices being in active mode simultaneously
- Fallback: if NFC is unavailable, volume button and QR remain available
- Test: instrumented test using `NfcAdapter.enableReaderMode` mock + `NdefMessage` fixture

**Milestone:** v2.0.0

---

### 5.2 Wi-Fi Direct Transport

**Why:** Nearby Connections over Wi-Fi P2P is constrained by Google Play Services and requires
the Nearby permission. A pure Wi-Fi Direct transport removes the GMS dependency and enables
F-Droid distribution.

**Design:**
- Implement `WifiDirectTransport` satisfying the existing `NearbyTransport` interface
- Use `WifiP2pManager` + `WifiP2pManager.Channel` for peer discovery and connection
- Profile payload encryption is transport-agnostic (already implemented in `WireProtocol`)
- The `NearbyTransport` interface abstraction means the entire crypto stack is reused unchanged

**Key decisions:**
- Wi-Fi Direct group owner negotiation is non-deterministic; implement a tiebreaker via the lexicographic key ordering already used in SAS derivation
- Keep Nearby Connections as the default; offer Wi-Fi Direct as an opt-in in Settings
- F-Droid eligibility requires no Nearby Connections SDK at all — move Nearby to a separate `app-gms` product flavor; `app-foss` uses Wi-Fi Direct only

**Milestone:** v2.1.0

---

### 5.3 Contact Deduplication Engine

**Why:** Receiving an updated card from someone you already have produces a duplicate in Room.
Users are stuck managing this manually.

**Design:**
- After a successful exchange, query Room for contacts with matching `identityKeyHash`
- If a match exists: show a merge dialog — "Update existing contact?" with a diff view
- Diff view shows which fields changed (name, email, phone, avatar)
- User can accept all, reject all, or pick per-field
- `ContactDao` gets a new `upsertByIdentity(contact)` transaction

**Key decisions:**
- Identity matching uses `identityKeyHash` (cryptographic) not name/email (fuzzy) to prevent false merges
- If the incoming key doesn't match any known identity, it's always a new contact — no deduplication attempt
- The merge dialog is non-blocking — the contact is saved as-is and the merge prompt is shown afterwards

**Milestone:** v2.1.0

---

### 5.4 Multiple Profiles (Personal / Work)

**Why:** Power users want to share a different card in professional vs. personal contexts
without switching apps or editing their profile each time.

**Design:**
- Room `Profile` table already exists; add a `profileType: ProfileType` column (`PERSONAL`, `WORK`, `CUSTOM`)
- `GestureAuthManager` stores one embedding per profile; gesture selects the active profile
- UI: a profile switcher in the Home screen bottom sheet; each profile tile shows type badge and name
- During exchange, the active profile is sent — no runtime UI change needed
- `ExchangeAuditLog` records which profile was shared

**Key decisions:**
- Gesture-per-profile is the differentiating UX — the gesture you make selects what you share
- Start with 2 profiles (Personal / Work); `CUSTOM` is v2.3+
- Room schema migration: `ALTER TABLE profiles ADD COLUMN profile_type TEXT NOT NULL DEFAULT 'PERSONAL'`

**Milestone:** v2.2.0

---

### 5.5 Key Rotation

**Why:** Long-lived Keystore identity keys have no expiry. If a device is compromised or sold,
the identity key should be invalidatable.

**Design:**
- Add a "Rotate identity key" action in Settings → Security
- Generates a new EC key pair in Android Keystore under a new alias
- Signs the new key with the old key (rotation certificate) and broadcasts to known peers on next exchange
- Known peers that receive a rotation certificate: verify the old-key signature, update their TOFU registry
- `KnownPeerDao` gets a `rotationCertificate: ByteArray?` column

**Key decisions:**
- Old key is retained for 30 days (verifying rotation certs from slow-updating peers) then deleted
- Rotation is voluntary — AURA never forces rotation — this preserves the "no cloud, no server" model
- TOFU registry update is opportunistic: peers learn of the rotation at the next physical exchange

**Milestone:** v2.2.0

---

### 5.6 Exchange Audit Log UI

**Why:** The `ExchangeAuditLog` Room table exists but has no UI. Users have no visibility into
their exchange history.

**Design:**
- New `AuditFragment` accessible from Contacts → overflow menu → "Exchange history"
- Timeline view: each row shows timestamp, peer name (if in contacts), transport used, outcome
- Error rows show the error code in human-readable form
- Export: "Export audit log" button writes a CSV to the Downloads folder
- Retention: UI shows the 90-day rolling window enforced by the existing `pruneOldEntries()`

**Key decisions:**
- Audit log is local-only — no sync, no cloud — consistent with AURA's privacy promise
- Peer name lookup is by `identityKeyHash`; if the peer is not in contacts, show "Unknown peer"

**Milestone:** v2.2.0

---

### 5.7 Profile Versioning

**Why:** After exchanging cards, users have no way to know when someone updates their contact
details. The next exchange sends the full updated profile, but there's no delta notification.

**Design:**
- `Profile` gets a `version: Int` field (auto-incremented on any field change)
- `KnownPeer` stores `lastSeenProfileVersion: Int`
- On exchange: if received `version > lastSeenProfileVersion`, surface a "Card updated" banner
- Banner shows a diff of changed fields (same diff engine as §5.3 deduplication)

**Milestone:** v2.3.0

---

### 5.8 "Share AURA" Deeplink

**Why:** Non-AURA users can't receive a contact. There's no fallback for the other person.

**Design:**
- Generate a `https://aura.app/c/<base64-vcard>` deeplink from your profile
- Deeplink opens a web landing page with the vCard download button + App Store badge
- The vCard is encoded client-side; no server receives any data
- Share sheet: any app (iMessage, WhatsApp, email) can receive the link
- Deeplink is one-time use by design (no persistent server storage — the data is in the URL)

**Key decisions:**
- Hosted on GitHub Pages (consistent with current privacy policy hosting — zero backend cost)
- Maximum vCard size is ~4KB; base64 overhead takes it to ~5.5KB — well within URL limits
- The page is a static HTML file: no tracking, no analytics, no cookies

**Milestone:** v2.3.0

---

## PHASE 6 — v3.x Platform Expansion

> Longer-horizon work. Specs will be written when Phase 5 is stable.

---

### 6.1 iOS Companion App (SwiftUI)

**Interoperability target:** AURA on Android can exchange with AURA on iOS.

**Architecture:**
- The crypto layer (X3DH + Double Ratchet, AES-256-GCM, ECDH on P-256) is
  platform-agnostic — identical algorithms, identical wire format
- The NFC and Nearby Connections transport is replaced by:
  - iOS: `CoreNFC` (tag reading) + `MultipeerConnectivity` (equivalent to Nearby)
  - Wire protocol bytes are identical — only the transport carrier changes
- `WireProtocol.kt` is the spec; the iOS implementation is `WireProtocol.swift`
- SAS verification, TOFU registry, and replay protection all have direct Swift equivalents

**Key decisions:**
- Android and iOS identity keys use the same curve (P-256/secp256r1) and encoding (SPKI) — cross-platform key comparison works without any adaptation layer
- vCard payload format is identical — a contact received from iOS is indistinguishable from one received from Android
- F-Droid and App Store distribution are independent tracks; neither depends on a shared backend

**Milestone:** v3.0.0

---

### 6.2 Wear OS Glance Tile

**Design:**
- A single Wear OS Glance tile showing "Ready to exchange" / "Exchange active" state
- Tap the tile: starts an exchange session on the paired phone via `ChannelClient`
- The tile mirrors `NearbyExchangeService` session state in real-time

**Milestone:** v3.1.0

---

### 6.3 Android Auto Integration

**Design:**
- Declare `AURA for Auto` in `automotive_app_desc.xml`
- When a contact is received while the phone is in Auto mode: TTS reads out the contact name and company
- "Add to contacts" voice action via `MediaBrowserService`

**Milestone:** v3.1.0

---

### 6.4 Enterprise / MDM Distribution

**Design:**
- Managed Google Play listing (separate from consumer Play Store track)
- Managed configuration (`app:managedConfigurations`): IT admins can pre-set allowed exchange transports, enforce gesture-only auth, disable QR fallback
- Zero-touch enrollment: AURA profile pre-provisioned from MDM
- `BuildConfig.IS_ENTERPRISE` flavor gate — enterprise build disables personal profile type, hides "Share AURA" deeplink, enforces audit log retention policy

**Milestone:** v3.2.0

---

### 6.5 F-Droid Distribution

**Requirements:**
- Fully reproducible build (currently blocked by Nearby Connections SDK — not reproducible)
- No proprietary dependencies in the `app-foss` flavor
- Replace Nearby Connections with Wi-Fi Direct transport (§5.2 must ship first)
- Replace MediaPipe binary model download with a bundled model (no CDN dependency at runtime)

**Milestone:** v3.2.0 (contingent on §5.2 Wi-Fi Direct shipping)

---

## PHASE 7 — v4.x Security Hardening Wave 4

> Proactive security improvements. These are not responses to known vulnerabilities —
> they are defence-in-depth upgrades for a mature, widely-deployed app.

### 7.1 Post-Quantum Key Exchange

**Context:** X3DH + ECDH on P-256 is vulnerable to a cryptographically-relevant quantum computer.
NIST finalised ML-KEM (CRYSTALS-Kyber, FIPS 203) in 2024 as the post-quantum KEM standard.

**Design:**
- Hybrid KEM: `X25519 || ML-KEM-768` — the session key is derived from both KEMs via HKDF
- If either KEM is broken the session remains secure (hybrid provides security as long as one component holds)
- Android Keystore does not yet support ML-KEM natively; use a software implementation (BouncyCastle 1.77+ or Tink 2.x)
- Wire protocol version bumped to v6 with negotiation: v5 devices fall back to pure ECDH

**Milestone:** v4.0.0

---

### 7.2 Sealed Sender Profile Payload

**Context:** The current wire protocol reveals that a profile is being sent (the JPEG magic bytes
and payload framing are identifiable). A passive observer with Bluetooth traffic capture can
infer that an exchange occurred, which leaks metadata.

**Design:**
- Wrap the AES-256-GCM ciphertext in an additional `SealedEnvelope` that pads to a fixed size (1024 bytes)
- The padded size makes all AURA exchanges look identical on the wire — no field-length side-channel
- `PayloadValidator` updated to validate the inner ciphertext after unsealing

**Milestone:** v4.1.0

---

### 7.3 Remote Blocklist Sync *(opt-in only)*

**Context:** If a user reports a bad actor's identity key to an opt-in blocklist service,
other AURA users who also opt in can pre-block that identity before ever meeting them.

**Design:**
- Entirely opt-in — disabled by default — users must explicitly enroll
- Identity keys are hashed with a pepper before submission (no plaintext key ever leaves the device)
- Blocklist is a signed append-only log hosted on a transparency-log infrastructure (no central authority)
- The "remote blocklist sync" toggle lives in Settings → Privacy → Advanced

**Key decisions:**
- This is fundamentally at tension with AURA's "no server, no cloud" promise — it is strictly opt-in with prominent disclosure
- The design must be reviewed by a cryptographer before implementation

**Milestone:** v4.2.0 *(research phase only for now — no implementation until design is reviewed)*

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit — every feature claim vs. code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model, dependency rules |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions, NIST SP 800-56A compliance |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step sequence diagram for a complete exchange |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, gesture embedding, cosine-similarity matching |
| [`STORE_LISTING.md`](STORE_LISTING.md) | Play Store copy — short description, full description, keywords |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source (hosted via GitHub Pages) |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-24 — Phase 2 (localization) and Phase 3 (test coverage) complete.
Phase 5–7 future architecture designed. Single remaining blocker: 5 GitHub secrets for Play Store.*
