# AURA — Project Roadmap

> **This is the canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every session.
> Keep it current — strike through completed items, add blockers inline.

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | `main` — v1.1.0; Phase 6.2 transport injection PR #57 open (CI pending) |
| Security audit | Wave 3 complete — all findings resolved (A1–A15) |
| Localization | 262 strings × 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) — **100% coverage, CI-enforced** |
| Test suite | Unit: 23 files · 274 methods · Instrumented: 13 files · 55 methods · 0 failures |
| CI pipeline | Green — unit + JaCoCo (50% branch floor) + lint + `assembleRelease` + APK size gate |
| Distribution | GitHub Releases — signed APK splits (arm64-v8a + armeabi-v7a) |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| QR relay | Implemented — AES-256-GCM encrypted profile POST/GET over HTTPS relay |
| Room DB | v8 — profile_type/is_active/custom_label (v6) + rotation_certificate (v7) + Phase 6.2 migrations (v8) |
| Product flavors | `gms` (Google Play Services) + `foss` (open source, no GMS) — both building and testing green |

### Open PRs
| PR | Branch | Scope |
|---|---|---|
| #57 | `phase/6.2-transport-injection` | Phase 6.2: NearbyConnectionsTransport + Hilt DI modules + service refactor + Phase 5.2 unit tests |
| #58 | `phase/5.1-sas-dialog-espresso` | Phase 5.1: SAS dialog Espresso tests (confirm / mismatch / identicon) |
| #59 | `phase/6.1-nfc-espresso-test` | Phase 6.1: NFC instrumented tests (NDEF round-trip + HCE APDU + manifest check) |
| #60 | `docs/qr-relay-setup` | Phase 5.6: `docs/qr-relay-setup.md` Firebase Realtime Database setup guide |

---

## ✅ COMPLETED PHASES

### Phase 2 — Localization ✅
> **Complete as of 2026-05-24.** 262 strings × 7 locales. `LocalizationCoverageTest.kt` enforces 100% on CI.

~~Audit `values/strings.xml` — count total strings vs. translated strings per locale~~
~~Identify untranslated strings falling back to English in each `values-xx/` folder~~
~~Add a CI lint step that fails if any string key exists in `values/` but is missing in any `values-xx/`~~

---

### Phase 3 — Test coverage hardening ✅
> **Complete as of 2026-05-25.** 259 unit test methods + 51 instrumented test methods, 0 failures.

| Test added | Covers |
|---|---|
| `SettingsEspressoTest` | Settings + Blocked screens |
| `OnboardingEspressoTest` | Onboarding flow |
| `PermissionRationaleEspressoTest` | Permission-rationale sheet |
| `ExchangeFlowEspressoTest` | Exchange flow activation + cancel |
| `BiometricAvailabilityTest` | Biometric unlock |
| `LocalizationCoverageTest` | Localization CI enforcement |
| `WireProtocolTest` (17 tests) | ECDH, profile encryption, crypto math |
| `SasVerifierTest` (17 tests) | SAS determinism, ordering, uniqueness, range |
| `NfcExchangeHelperTest` | NFC tap bootstrap path |
| `DoubleRatchetStateTest` | Double Ratchet state machine |
| `LivenessGuardTest` | Liveness guard logic |
| `IdenticonGeneratorTest` | Identicon generation |
| `IdentityRotationDetectorTest` | Identity key rotation detection |
| `SecurityHardeningTest` | Payload field bounds, size gates |

---

### Phase 4 — QR Relay ✅
> **Complete as of 2026-05-24.** Encrypted profile POST/GET via HTTPS relay.

~~Implement `RelayClient.kt`~~
~~Integrate relay into `QRExchangeViewModel`~~
~~Configure `RELAY_BASE_URL` as env-var-backed `BuildConfig` field~~
~~Update localization strings across all 8 locale files~~

---

### Phase 5.1 — SAS Dialog UI hardening ✅
> **Implementation complete.** SAS dialog wired in both `ExchangeFragment` (Nearby path) and
> `QRExchangeFragment` (QR relay path). 30-second auto-abort, haptic feedback, identicon,
> `ExchangeViewModel.sasDialogShown` guard survives configuration changes.
> **Espresso test coverage:** PR #58 in CI.

~~Add a countdown timer in the SAS dialog (30 s) — auto-abort on timeout~~
~~Add haptic feedback when the SAS dialog appears~~
~~Add SAS dialog to QR relay path (QRExchangeFragment)~~
~~Show identicon alongside 6-digit code (dual-channel MITM defence)~~
- [🔄 PR #58] Espresso test: inject VERIFYING state, verify dialog, confirm/mismatch paths

---

### Phase 5.4 — Volume button reliability improvement ✅
> **Complete.** `AuraAccessibilityService` implemented and wired. Handles volume button
> triple-press via `AccessibilityService` as a GMS-independent activation path.
> `VolumeButtonTriplePressTest.kt` covers the activation contract.

~~Evaluate `AccessibilityService` path~~
~~Implement and gate behind a user opt-in in Settings~~

---

### Phase 6.1 — NFC Tap-to-Exchange ✅
> **Implementation complete as of Phase 6.1 merge.** `NfcExchangeHelper.kt`,
> `AuraHceService.kt`, `MainActivity` wiring (keypair generation, enable/disable),
> `ExchangeFragment` NFC chip indicator, `NearbyExchangeService` bootstrap handling,
> manifest NFC permissions and HCE service declaration — all in place.
> **Espresso test coverage:** PR #59 in CI.

~~Wire `NfcExchangeHelper` into `ExchangeFragment` and `NearbyExchangeService`~~
~~Add NFC foreground dispatch intent filter to `AndroidManifest.xml`~~
~~Add HCE service declaration (`HostApduService` subclass: `AuraHceService`)~~
~~NDEF message build/parse round-trip (JVM unit test: `NfcExchangeHelperTest.kt`)~~
- [🔄 PR #59] Instrumented test: NDEF round-trip, HCE APDU, manifest verification

---

### Phase 6.3 — Contact Deduplication Engine ✅
> **Complete.** `ContactRepository.saveDeduped()` matches by `identityKeyHash`,
> diffs fields, returns `MergeEvent?`. `ContactMergeBottomSheet` lets users review
> per-field diffs. `ContactDiffEngineTest` + `ContactDiffEngineEdgeCasesTest` provide coverage.

~~After exchange: query Room for contacts with matching `identityKeyHash`~~
~~Show merge dialog with per-field diff view~~
~~`ContactDao.upsertByIdentity(contact)` transaction~~

---

### Phase 6.4 — Multiple Profiles (Personal / Work) ✅
> **Complete as of 2026-05-24.** Room DB v6 migration. `ProfileSwitcherBottomSheet` wired into HomeFragment.
> 18 new JVM unit tests in `MultiProfileTest.kt`.

---

### Phase 6.5 — Key Rotation ✅
> **Complete as of 2026-05-24.** `CryptoUtils.rotateDeviceIdentityKey()`, `RotationCertificate`,
> Settings → Security wired. 12 new JVM unit tests in `KeyRotationTest.kt`.

---

### Phase 6.6 — Exchange Audit Log UI ✅
> **Complete as of 2026-05-24.** `AuditFragment` + CSV export. Navigation from Contacts and Settings.

---

## 🔄 IN PROGRESS

### Phase 6.2 — Wi-Fi Direct Transport / Transport Injection (PR #57)

> `WifiDirectTransport.kt` already fully implemented.
> PR #57 completes the Hilt DI wiring that makes it the foss-flavor transport.

**What PR #57 delivers:**
- `NearbyConnectionsTransport` — Nearby Connections adapter (gms flavor)
- `TransportModule` in `src/gms/` and `src/foss/` — Hilt provides the right transport per flavor
- `NearbyExchangeService` refactored to inject `NearbyTransport` (no more direct `Nearby.getConnectionsClient()`)
- `NearbyTransport.onConnectionInitiated` — async blocklist check hook
- `build.gradle.kts` — gms/foss source sets, `gmsImplementation(play.services.nearby)`
- `NearbyExchangeServiceUnitTest.kt` — 15 JVM unit tests (Phase 5.2)

**Milestone:** v2.1.0

---

### Phase 5.2 — Coverage gate hardening (PR #57)

> `NearbyExchangeServiceUnitTest.kt` (15 tests) added on the 6.2 branch.
> JaCoCo floor is at 50%; post-6.2 merge target is 55%.

- [🔄 PR #57] 15 unit tests covering sessionState, action constants, ExchangeSession invariants
- [ ] Raise floor to 55% after PR #57 merges (update `minimum` in `build.gradle.kts`)
- [ ] Raise floor to 60% — add `QRExchangeViewModel` relay state tests (v1.3)

---

### Phase 5.6 — QR relay self-hosting guide (PR #60)

> `docs/qr-relay-setup.md` added. Covers Firebase Realtime Database (zero-ops, free tier),
> security rules, optional auto-expiry Cloud Function, and `RELAY_BASE_URL` CI wiring.

- [🔄 PR #60] `docs/qr-relay-setup.md` Firebase setup guide

---

## 📋 REMAINING WORK

### Phase 5.3 — Accessibility improvements (v1.2)

- [ ] Automated TalkBack / Accessibility Scanner CI pass (currently 🟡 manual only)
- [ ] Add `contentDescription` to all icon-only buttons
- [ ] Verify minimum touch-target sizes (48dp) on gesture enrollment page
- [ ] Add `accessibilityLiveRegion` to SAS dialog digit display

---

### Phase 5.5 — Native-speaker localization review (v1.3)

- [ ] Commission a native-speaker review for each of the 7 locales (DE, ES, FR, HI, JA, KO, ZH-CN)
- [ ] Fix any awkward translations identified in the review
- [ ] Re-run `LocalizationCoverageTest` to confirm no regressions

---

### Phase 6.7 — Profile Versioning (v2.3)

**Why:** After exchanging cards, users have no way to know when someone updates their contact details.

**Design:**
- `Profile` gets a `version: Int` field (auto-incremented on any field change)
- `KnownPeer` stores `lastSeenProfileVersion: Int`
- On exchange: if received `version > lastSeenProfileVersion`, surface a "Card updated" banner
- Banner shows a diff of changed fields (same `ContactDiffEngine` as Phase 6.3)

**Open tasks:**
- [ ] Add `profileVersion: Int` to `Profile` entity + Room DB v9 migration
- [ ] Increment `profileVersion` in `ProfileRepository.update()` on any field change
- [ ] Compare versions in `NearbyExchangeService.handleIncomingProfile()` — set `profileVersionBumped = true` on `ExchangeSession` when applicable
- [ ] `ExchangeFragment` already shows "Card updated" Snackbar on `profileVersionBumped = true` — verify with instrumented test

**Milestone:** v2.3.0

---

### Phase 6.8 — "Share AURA" Deeplink (v2.3)

**Why:** Non-AURA users can't receive a contact. There's no fallback for the other person.

**Design:**
- Generate a `https://showerideas.github.io/Aura/c/<base64-vcard>` deeplink from your profile
- Hosted on GitHub Pages — zero backend cost, consistent with current privacy policy hosting
- vCard is encoded client-side; no server receives any data
- Share sheet: any app (iMessage, WhatsApp, email) can receive the link

**Open tasks:**
- [ ] Add "Share via link" button to `ProfileFragment`
- [ ] `VCardUtils.toBase64Url(profile)` — encode to URL-safe base64 vCard
- [ ] GitHub Pages landing page: decode the fragment, render contact fields, download button
- [ ] Deep-link intent filter in AndroidManifest for `https://showerideas.github.io/Aura/c/*`

**Milestone:** v2.3.0

---

### Phase 6.9 — Identicon for Unknown Peers (v2.3)

> `IdenticonGenerator.kt` and `IdenticonGeneratorTest.kt` are already implemented.
> The SAS dialog already shows the identicon alongside the 6-digit code.
> Remaining: surface identicon in `ContactDetailBottomSheet` and blocklist screen.

**Open tasks:**
- [ ] Show identicon in `ContactDetailBottomSheet` for unknown peers (identity key not in TOFU registry)
- [ ] Show identicon on the blocklist screen to help identify blocked parties

**Milestone:** v2.3.0

---

## PHASE 7 — v3.x Platform Expansion

> Longer-horizon work. Specs will be written when Phase 6 is stable.

### 7.1 iOS Companion App — SwiftUI (v3.0)
- Cross-platform ECDH (P-256/secp256r1 + SPKI encoding) — algorithm-compatible with Android
- `CoreNFC` + `MultipeerConnectivity` replaces Nearby Connections
- vCard payload format identical across platforms

### 7.2 Wear OS Glance Tile (v3.1)
- Single tile showing "Ready / Active" state; tap starts session on paired phone via `ChannelClient`

### 7.3 Android Auto Integration (v3.1)
- TTS read-out of received contact name + company; "Add to contacts" voice action

### 7.4 Enterprise / MDM Distribution (v3.2)
- Managed configuration: enforce auth method, disable QR fallback, audit log retention
- `BuildConfig.IS_ENTERPRISE` flavor gate

### 7.5 F-Droid Distribution (v3.2)
- Fully reproducible build — requires Phase 6.2 (Wi-Fi Direct, no GMS) to ship first
- Bundled MediaPipe model (no CDN download at runtime)

---

## PHASE 8 — v4.x Security Hardening Wave 4

### 8.1 Post-Quantum Key Exchange (v4.0)
- Hybrid KEM: `X25519 || ML-KEM-768` (NIST FIPS 203)
- Wire protocol v6 with v5 fallback

### 8.2 Sealed Sender Profile Payload (v4.1)
- Fixed-size `SealedEnvelope` padding — all exchanges look identical on the wire

### 8.3 QR Relay Anonymisation (v4.1)
- Opt-in Tor/anonymising proxy for QR relay requests *(research phase)*

### 8.4 Remote Blocklist Sync (v4.2)
- Opt-in, hashed-key submissions, transparency-log infrastructure *(research phase)*

---

## PHASE 9 — v5.x Intelligence & Personalisation

### 9.1 Smart Share Presets (v5.0)
### 9.2 On-Device Exchange Analytics (v5.0)
### 9.3 Gesture Library (v5.1)

---

## Version history

| Version | Released | Key changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate (MediaPipe), ECDH+HKDF, room exchange, QR fallback, blocklist, replay protection, biometric, accessibility |
| v1.1.0 | 2026-05-24 | QR relay (encrypted HTTPS), 7 locales 100% coverage, 259 unit + 51 instrumented tests, signed APK splits |
| v1.2.0-dev | main | Phase 6.2 transport injection (PR #57), SAS Espresso tests (PR #58), NFC Espresso tests (PR #59), QR relay docs (PR #60) |

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

*Last updated: 2026-05-25 — Phases 6.1/6.3/5.1/5.4 complete; 6.2/5.2/5.6 in PR.
Distribution: GitHub Releases only (no App Store).*
