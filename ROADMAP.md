# AURA — Engineering Roadmap

> Canonical planning document for the AURA gesture-biometric contact exchange system.
> Written as a strictly ordered sequence of engineering tasks for a software team.
> Tasks are sorted by dependency: each task may depend on those above it.
> References are cited inline where they are relevant — there is no separate reference table.
> Last rewrite: 2026-05-26 | Tasks 1–44 shipped | Research expansion: 2026-05-26

---


## How to Read This Document

This document is a **dependency-ordered implementation sequence**, not a feature wishlist.
Every task is placed where it is because something either upstream requires it, or completing it
unlocks the most subsequent work. Read it top to bottom before starting any task.

Status markers:
- `[ ]` — open, ready to implement
- `[PARTIAL]` — scaffolded or substantially implemented but not production-complete; see task detail
- `[R&D]` — design/research phase only; no code until explicitly moved to `[ ]`

Current baseline: **v3.3.0** on `main`. PRs #62–#112 all merged. All 44 original tasks complete.
Open work: Tasks 45–66 (22 implementation tasks) + R&D-A through R&D-V (22 research items).
Desktop companion shipped. LoRa transport shipped. JaCoCo 80% floor. CI green.

---

## Current System Snapshot

| Layer | State |
|---|---|
| Core app | v4.0.0 — production-ready |
| Gesture gate | MediaPipe Hands + temporal classifier (motion-profile analysis, 30-frame window — not LSTM) + 2-layer liveness (passive drift + active challenge) + continuous IMU collection + feature extraction (ML inference pending behavioral model enrollment) |
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
| Test suite | 623+ unit methods + 72 instrumented + 36 iOS tests (19 AuraCoreTests + 17 AuraCompanionTests) — JaCoCo 60% branch floor |
| CI | Green — lint + unit + JaCoCo + assembleRelease + APK size gate + iOS build/test |
| Distribution | F-Droid reproducible build script + submission guide — live |
| Signing | PKCS12 keystore in GitHub Secrets — signed AAB confirmed |
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, WireProtocol.swift, MultipeerTransport, NFCExchangeBootstrap; 36 tests across AuraCoreTests + AuraCompanionTests |
| Wear OS | Glance tile + Health Connect HRV + SasPinActivity + WristRaiseTrigger; Wear OS 7 production-complete |
| Android Auto | Voice action + biometric auth gate; full screen library (Advertising/Completed/Idle/Sas) |
| Room sessions | Multi-party card exchange — star topology, 10-min TTL, delivery ACK, MLS group key agreement |
| Mesh | Store-and-forward (BLE bloom filter) + multi-hop Wi-Fi Direct mesh (5 hops) |
| Analytics | On-device exchange analytics — transport breakdown, heatmap, PDF export (differential privacy) |
| Enterprise | 6 MDM restriction keys + zero-touch enrollment + signed audit export + Advanced Protection API |
| Desktop | KMP desktop companion — QR relay transport |
| Auth hardening | CryptoObjectBiometricHelper (CryptoObject KeyAgreement, API 36+) · StrongBox key migration · predictive back · app shortcuts |
| Privacy | PSI contact discovery (no server) · OHTTP relay · Tor SOCKS5 · SPKI pinning · differential privacy |

---

## Tasks in Research / Design Phase

The following are design-tracked. Each has an explicit trigger condition that moves it from
`[R&D]` to a scheduled implementation task. Items with full architecture and prototype steps
are marked accordingly. Implemented items (A, B, C, E, F, I, J, K) have been stripped — work shipped in Tasks 45–66.

---

### R&D-D — Decentralized Identity (DID) Full Integration

**Goal:** Deepen beyond `did:key` derivation (Task 51). Support `did:web` (domain-anchored),
`did:peer:2` (per-exchange pairwise), and full DID Document publishing. AURA becomes a
DID wallet, not just a DID identifier generator.

**Architecture:**
- `did:key`: implemented in Task 51. Self-contained, no resolution.
- `did:peer:2`: per-exchange DID encoding key material directly in the DID string; no public
  publication; replaces raw `identityKeyHash` in `WireProtocol` with standardized identifier
- `did:web`: publish DID Document at `https://[user-domain]/.well-known/did.json`; AURA generates
  the document, user hosts it; enables enterprise domain-anchored identity
- W3C Digital Credentials API: register AURA as a credential provider so web pages can request
  AURA VCs from Chrome/Safari without a native app install

See: [w3.org/TR/did-core/] — DID Core 1.0.
See: [identity.foundation/peer-did-method-spec/] — `did:peer` method.
See: [w3c-ccg.github.io/did-method-web/] — `did:web` method.

- [R&D] `did:peer:2` prototype: encode AURA exchange public key as `did:peer:2.<base58(keyType+keyBytes)>`;
  replace raw `identityKeyHash` in WireProtocol — standardizes identity referencing cross-ecosystem
- [R&D] `did:web` publishing UI: Settings → Identity → "Publish DID Document":
  - AURA generates DID Document JSON; provides instructions for hosting at `/.well-known/did.json`
  - Not self-hosting — AURA generates, user hosts; enterprise-facing feature
- [R&D] `DidResolver.kt`: resolves `did:key` (local), `did:web` (HTTP GET + cache), `did:peer:2` (decode)
- Trigger: implement `did:peer:2` when Task 51 VCs are deployed and cross-ecosystem DID demand confirmed;
  implement `did:web` as enterprise power-user feature

---


### R&D-G — AR Exchange Overlay (ARCore + Depth API)

**Goal:** Look at another AURA user → floating AR business card appears → tap → exchange.
Most frictionless exchange UX possible. Requires bilateral explicit consent and on-device-only face detection.

**Architecture:**
1. ARCore `AugmentedFaces` detects face mesh at 1–3 m (Pixel 6+, Galaxy S22+)
2. BLE scan for AURA GATT advertisement from detected device
3. UWB distance < 1.5 m AND same AID in BLE advertisement → show AR floating card
4. Both parties tap "Accept" → exchange proceeds via NFC or BLE GATT
5. AR mode ONLY activates on explicit user action with Camera permission granted

See: [developers.google.com/ar/develop/augmented-faces] — ARCore Augmented Faces API.
See: [github.com/google-ar/arcore-android-sdk] — ARCore 1.40+; Filament rendering, not Sceneform.

- [R&D] ARCore Augmented Faces latency benchmark: 1–3 m detection on Pixel 8 Pro and Galaxy S23 Ultra;
  target < 500 ms from face detected to AR card display
- [R&D] Privacy: ARCore uses on-device face mesh only — no facial recognition, no biometric database;
  verify in Google's ARCore docs and document for users in privacy notice before AR mode activation
- [R&D] UWB + BLE correlation: false-positive rate of "wrong person gets AR card" in crowded room;
  UWB (Task 52) distance + BLE identity must both match before card is shown
- [R&D] `BuildConfig.ENABLE_AR_EXCHANGE = false` — default off; enterprise opt-in with privacy review
- Trigger: after UWB Task 52 confirmed reliable; enterprise-only opt-in with privacy board sign-off

---


### R&D-H — Satellite Fallback (Android SatelliteManager + Garmin inReach)

**Goal:** Exchange cards at zero-infrastructure locations — remote hiking, disaster zones,
maritime, off-grid events. Android 14+ `SatelliteManager` (T-Mobile/Starlink Direct-to-Cell),
Garmin inReach BLE bridge, Iridium GO!.

**Compression target:** LZ4 + base91 → minimal AURA vCard (name + email) ≤ 160 bytes = one satellite SMS.

See: [developer.android.com/reference/android/telephony/satellite/SatelliteManager] — API 34+.
`requestIsSatelliteSupported()` + `sendSatelliteDatagram()` for message send/receive.
See: [developer.garmin.com/connect-iq] — Garmin Connect IQ SDK for inReach BLE bridge.
See: [technetbooks.com/2026/03/mediatek-starlink-satellite-alerts-for.html] — DTC device list.

- [R&D] Android `SatelliteManager` API availability audit: T-Mobile + Starlink DTC devices (Pixel 9,
  Galaxy S25); `requestIsSatelliteSupported()` on non-DTC devices returns `false` — note compatibility
- [R&D] Compression benchmark: LZ4 + base91 on 20 representative AURA vCards; 95th percentile < 160 bytes;
  if not, define minimal satellite profile (name + email only, max 80 bytes)
- [R&D] Latency expectations: Starlink DTC ~30 s, Iridium ~5 s, Garmin inReach ~10 s;
  full SAS verification = 2 satellite messages = 60–600 s; assess whether TOFU-only mode is
  acceptable for satellite path (no real-time bidirectional verification possible)
- Trigger: implement after LoRa integration (Task 39) shows real-world demand for longer-range paths

---


### R&D-L — ISO 18013-7 Online Presentation Protocol

**Goal:** ISO 18013-5 (Task 61) covers proximity mDL via NFC/BLE. ISO 18013-7 (finalizing 2026)
extends this to online presentation via `OpenID4VP` (OpenID for Verifiable Presentations). AURA
as a verifier could accept remote mDL presentations without physical proximity — enabling async
identity verification for remote exchange scenarios.

See: [openid.net/developers/draft-openid-4-verifiable-presentations/] — OpenID4VP spec.
See: [mobileidworld.com/w3c-advances-did-standard-that-underpins-mobile-wallets-and-digital-credentials/]

- [R&D] ISO 18013-7 standardization timeline: expect final publication Q3–Q4 2026; implement
  only against final spec
- [R&D] Async identity verification flow: Alice requests verification → Bob presents mDL via
  OpenID4VP → Alice verifies online presentation → result stored as `MdlVerifiedFields`
- [R&D] Privacy: online mDL presentation reveals IP to verifier; consider Tor path (R&D-A) for
  remote mDL verification
- Trigger: implement after ISO 18013-7 finalized and Android Credential Manager adds OpenID4VP support

---

### R&D-M — Matter/Thread IoT Identity Bridge

**Goal:** AURA identity key (P-256) is structurally compatible with Matter Node Operational
Certificates (NOC). An AURA–Matter bridge could allow AURA identity to authorize Matter device
pairing — tap phone to IoT device → device receives identity cert → grants access.

See: [github.com/project-chip/connectedhomeip] — Matter SDK reference implementation.
See: [csa-iot.org/developer-resource/specifications-download-request/] — Matter spec.

- [R&D] Matter NOC compatibility: NOC requires P-256 ECDSA (AURA uses P-256 identity key) —
  assess whether AURA can issue a NOC-compatible cert using existing identity key
- [R&D] `MatterIdentityBridge.kt` prototype: derive NOC from AURA identity key; PASE commission
  a Matter device; revoke by issuing new NOC on key rotation
- [R&D] Privacy: use derived fabric-specific key `HKDF(identity_key || fabric_id)` not identity key
  directly — prevents fabric ID correlation across devices
- Trigger: when Matter ecosystem reaches mainstream (50%+ new smart home devices) and user demand
  for AURA-authenticated IoT pairing is demonstrated

---

### R&D-N — AI-Powered Contact Import (ML Kit OCR + Gemini Nano)

**Goal:** Import contact info from a photographed business card or screenshot using on-device OCR
(ML Kit Text Recognition v2) and on-device LLM (Gemini Nano via Android AICore). No data leaves device.

See: [developers.google.com/ml-kit/vision/text-recognition/android] — ML Kit Text Recognition v2;
on-device, no network, Latin + CJK script support.
See: [developer.android.com/ai/aicore] — Android AICore; Gemini Nano on-device inference;
available on Pixel 8 Pro+, Galaxy S24+; degrades gracefully on unsupported hardware.

- [R&D] Prototype `BusinessCardImporter.kt`:
  - CameraX image capture → `TextRecognizer.process(inputImage)` → raw text blocks
  - Gemini Nano prompt: "Extract name, email, phone, company, title, website from this business
    card text. Return JSON only. Text: {raw_text}" — minimal prompt for Nano context window
  - Map JSON → `ContactProfile` fields; validate email/phone format; show confirm screen before saving
  - Fallback on non-Nano devices: regex extraction for email + phone; user fills name/company
- [R&D] Privacy verification: confirm Gemini Nano runs entirely on-device; no network calls;
  no Google account sign-in required
- [R&D] Accuracy benchmark: 50 business cards (corporate, handwritten, multilingual); target
  > 90% field extraction accuracy for email and phone on Latin-script cards
- Trigger: when Gemini Nano available on > 40% of active Android devices; ship as opt-in with
  "processed on-device" disclosure

---

### R&D-O — DIDComm v2 Secure Messaging Protocol

W3C DIDComm v2 defines a standard for authenticated, encrypted messaging between DID holders.
With AURA VCs (Task 51) and `did:key` identity anchors, AURA could receive DIDComm messages
from any DIDComm-compatible wallet — enabling interoperability with enterprise identity systems
(Microsoft Entra Verified ID, Auth0 Digital ID, etc.).

See: [identity.foundation/didcomm-messaging/spec] — DIDComm v2 specification
See: [securityboulevard.com/2026/03/decentralized-identity-and-verifiable-credentials-the-enterprise-playbook-2026]

- [R&D] Evaluate DIDComm v2 message encryption (`authcrypt` / `anoncrypt`) compatibility with
  AURA's existing AES-256-GCM + X25519 envelope — determine if a bridge layer is sufficient
- Trigger: implement only if enterprise customers request interoperability with existing DID wallets

### R&D-P — Satellite Direct-to-Device (Android 15+ SatelliteManager)

At MWC 2026, MediaTek and Starlink announced satellite emergency alerts for mobile devices.
T-Mobile Starlink direct-to-cell enables compatible Android devices to send messages via
satellite with no additional hardware. Android 14 introduced `SatelliteManager` API.
AURA profile payloads compress to ~256 bytes — well within satellite SMS constraints.

See: [technetbooks.com/2026/03/mediatek-starlink-satellite-alerts-for.html]
See: [satellitetoday.com/connectivity/2024/08/14/google-brings-satellite-sos-feature-to-android-with-pixel-9]
See: [techlicious.com/guide/all-the-phones-that-have-satellite-messaging-2026]

- [R&D] Assess `android.telephony.satellite.SatelliteManager` API:
  - `requestSatelliteEnabled()` — check if satellite modem is available
  - `sendSatelliteDatagram()` — 160-byte limit per message; test LZ4 + base91 encoding of typical AURA vCard
- [R&D] LZ4 + base91 encoding feasibility study: typical AURA profile (name + email + phone)
  compresses to ~80-120 bytes LZ4 → base91 encoding adds ~15% → ~138 bytes: fits in one satellite message
- Trigger: implement after LoRa integration (Task 39) ships; use same compression pipeline;
  add `SatelliteTransport.kt` as the highest-latency fallback transport

### R&D-Q — Android 17 Contact Picker Integration

Android 17 is introducing a privacy-preserving Contact Picker analogous to the photo picker:
apps can request contacts from the user's address book without getting `READ_CONTACTS` permission
for the full address book. The user selects which contacts to share; others are invisible to the app.

See: [makeuseof.com/this-new-android-privacy-feature-is-actually-brilliant]

- [R&D] Evaluate: AURA currently stores only contacts received via AURA exchange — does not
  read the device address book. If a future feature (e.g., "Find friends on AURA") reads
  device contacts for PSI discovery (Task 54), the Contact Picker is the mandatory access path
- Trigger: evaluate only if PSI contact discovery (Task 54) expands to device address book integration;
  no change needed if AURA continues to only manage its own contact store

### R&D-R — FIDO2 Platform Authenticator Integration

AURA's gesture gate is a behavioral biometric that gates access to cryptographic keys.
This is architecturally identical to what a FIDO2 platform authenticator does. The question
is whether AURA should expose itself as a FIDO2 credential provider to the Android system —
meaning websites and apps could use AURA gesture authentication for their own FIDO2 login flows.

See: [securityboulevard.com/2026/04/the-complete-guide-to-passwordless-authentication-in-2026]
See: [1kosmos.com/resources/blog/best-fido2-passkey-solutions]
See: [mdpi.com/2079-9292/14/20/4018] — FIDO2 on Android GDPR analysis

**Feasibility:**
- Android 14+ `CredentialManager` API allows third-party apps to register as credential providers
- AURA would register a `CredentialProviderService` and expose passkeys backed by gesture auth
- When a user authenticates to any app via passkey, AURA shows its gesture capture UI and
  provides the FIDO2 assertion signed with the identity key gated behind gesture auth

- [R&D] Prototype AURA as `CredentialProviderService` (Android 14+ API) — evaluate whether
  gesture latency (1-second LSTM window) is acceptable for FIDO2 assertion generation
- [R&D] Evaluate passkey sync requirements: FIDO2 passkeys must sync via cloud backup or be
  device-bound; AURA's current model is device-bound with optional backup (Task 87) —
  this is compatible with FIDO2 discoverable credential (non-synced) model
- Trigger: implement as an enterprise feature after Task 47 (PQXDH) ships; requires separate
  security review for the FIDO2 authenticator trust model

### R&D-S — Hardware Security Key NFC Bridge

For enterprise deployments, AURA could relay NFC FIDO2 authentication requests to a hardware
security key (YubiKey NFC, FIDO2 token) held by the user, providing a hardware root of trust
without embedding secure hardware in the AURA device. The phone acts as an NFC relay between
the FIDO2 reader and the hardware key.

See: [token2.com/site/page/understanding-fido2-authentication-across-different-operating-systems]
See: [cpl.thalesgroup.com/access-management/authenticators/fido-devices]

- [R&D] CTAP2 relay via Android HCE: phone receives APDU from reader via HCE (Task 1 AID
  registration extended), forwards to YubiKey via NFC reader mode (Task 2), returns response
- [R&D] Relay latency: measure RTT for relay vs. direct reader → key; must be < 2 seconds to
  avoid CTAP2 timeout
- Trigger: enterprise customer request only; no implementation until hardware key pairing UX
  and relay security model are reviewed




### R&D-T — ZK-SNARK Gesture Template Privacy

Zero-knowledge proofs allow a prover to convince a verifier that they know a gesture matching
an enrolled template — without revealing the template itself. Applied to AURA: the enrolled
landmark template never needs to leave secure storage; the authentication proof is a ZK-SNARK
that says "I produced this gesture with cosine similarity > 0.82 to my enrolled template."
The verifier (the app itself, or a remote enterprise audit server) can verify the proof without
ever seeing the raw landmarks.

See: [calmops.com/emerging-technology/zero-knowledge-proofs-zksnark-zkstark-2026]
See: [zimperium.com/glossary/zero-knowledge-proofs] — mobile ZKP applications
See: [hashstudioz.com/blog/zero-knowledge-authentication-in-mobile-apps] — mobile ZKP auth

- [R&D] Circuit design: cosine similarity circuit in Groth16 (ZK-SNARK) — inputs: enrolled
  centroid (private), query landmark vector (public); output: bit (1 = cosine_sim > 0.82);
  proof size Groth16 ≈ 192 bytes (verifiable in < 1ms); proving time on Snapdragon 730G ≈ 2-4s
- [R&D] Library evaluation: `gnark` (Go, compiles to ARM64 via CGo) vs `bellman` (Rust, JNI)
  vs `snarkjs` (JS via QuickJS embedding for Node) — assess mobile proving time feasibility
- [R&D] Enterprise use case: enterprise audit server verifies gesture auth occurred without
  receiving any biometric data — proof replaces the raw `livenessConfidence` float in audit export
- Trigger: only if enterprise compliance requires biometric data minimization for audit logs

### R&D-U — MPC Threshold Signing for Enterprise Audit Export

NIST's 2026 Threshold Cryptography Workshop evaluated 25 threshold signature scheme proposals.
For AURA enterprise deployments, the signed audit export (Task 29) is currently signed with a
single device key. A 2-of-3 MPC threshold signing scheme would require two of three
administrators to co-sign any export — preventing a single compromised admin from forging audit records.

See: [csrc.nist.gov/projects/threshold-cryptography] — NIST threshold cryptography project
See: [csrc.nist.gov/Projects/pec/threshold] — NIST MPC/threshold schemes 2026

- [R&D] Evaluate NIST threshold call submissions for Android compatibility (JVM/Kotlin FFI)
- [R&D] Key ceremony: 2-of-3 Shamir Secret Sharing of the audit export signing key;
  each shard held by a different enterprise administrator device
- [R&D] Signing protocol: administrator devices co-sign via BLE/NFC exchange (uses existing
  AURA transport stack) rather than an online coordinator
- Trigger: enterprise customer requirement for tamper-evident multi-administrator audit logs

### R&D-V — Android XR / Jetpack XR Business Card Exchange

Android XR SDK Developer Preview 4 was released May 2026. Samsung's XR headset is the first
hardware; Lenovo follows. Jetpack SceneCore provides spatial anchors, plane detection, hand
tracking, and eye tracking via ARCore for Jetpack XR. The exchange interaction AURA could
enable: look at a person → their AURA card appears as a floating panel in space → perform
the enrolled gesture in the air → card exchanges. This is the most natural possible exchange UX.

See: [android-developers.googleblog.com/2026/05/android-xr-sdk-developer-preview-4-updates.html]
See: [developer.android.com/develop/xr/jetpack-xr-sdk/arcore]
See: [developer.android.com/develop/xr/jetpack-xr-sdk]

- [R&D] Evaluate `ARCore for Jetpack XR`: hand tracking API — can enrolled hand gesture
  be recognized from spatial hand landmarks rather than camera feed? Same 21-landmark model
  (MediaPipe Hands uses same joint skeleton as Jetpack XR Hand Tracking)
- [R&D] Spatial card rendering: 3D business card composable via `SceneCore.Entity` and
  Jetpack Compose for 3D (`Compose XR`) — floating panel anchored to person's face/torso
- [R&D] Discovery: Jetpack XR spatial anchor detects nearby AURA users via BLE beacon
  (Task 37 bloom filter advertisement) → renders their card before they've tapped — ambient
  awareness mode
- Trigger: when Samsung XR headset reaches developer availability and Jetpack XR API achieves
  beta stability (currently Developer Preview 4)

### R&D-W — Privacy Pass Rate-Limiting for QR Relay (RFC 9578)

Privacy Pass (RFC 9578, IETF) uses blind RSA signatures to issue anonymous tokens. A client
proves it solved a challenge (e.g., first exchange of the day) and receives N blind tokens.
Each QR relay request redeems one token — the relay server cannot link the token to the
original challenge solution, providing rate-limiting without tracking.

See: [rfc-editor.org/rfc/rfc9578.html] — Privacy Pass Issuance Protocols (RFC 9578)
See: [rfc-editor.org/rfc/rfc9576.html] — Privacy Pass Architecture (RFC 9576)
See: [blog.cloudflare.com/privacy-pass-standard] — Cloudflare OHTTP + Privacy Pass integration
See: [draft-ietf-privacypass-arc-protocol] — Anonymous Rate-Limited Credentials (ARC) draft

- [R&D] Token issuance: on each successful exchange, AURA requests 10 Privacy Pass tokens
  from the AURA relay server; each token is a blinded RSA signature on a nonce
- [R&D] Token redemption: each QR relay POST attaches one token; relay verifies and deducts;
  server cannot link token redemption to the original issuance session
- [R&D] Evaluate `cloudflare/pat-go` (Go) vs `privacypass-rs` (Rust/JNI) for Android
- Trigger: if relay abuse becomes a problem requiring rate-limiting without user identification

### R&D-X — Kotlin 2.2 Swift Export for iOS AuraCore

Kotlin 2.2.20 introduced Swift export by default (no longer experimental). This allows direct
Swift idiom access to Kotlin Multiplatform code — eliminating the Objective-C header intermediary.
AURA's `:protocol` KMP module (Task 35) could be exported as native Swift types with proper
Swift naming conventions, documentation, and generics — instead of the current `@ObjC` wrapper
layer that maps Kotlin classes to Objective-C and then Swift.

See: [kotlinlang.org/docs/whatsnew2220.html] — Swift export stable in 2.2.20
See: [medium.com/@androidlab/what-kotlin-2-3-tells-us-about-the-future-of-the-language] — KMP 2.3 roadmap

- [R&D] Evaluate: current iOS `AuraCore` Swift code manually re-implements some `:protocol`
  logic. With Swift export, `WireProtocol.kt`, `SasVerifier.kt`, `HybridKEM.kt` all become
  directly callable from Swift with Swift-idiomatic APIs and async/await support
- [R&D] Breaking change assessment: Swift export changes symbol naming vs current `@ObjC` exports;
  requires iOS module migration with backward compatibility shim during transition
- Trigger: evaluate once Kotlin 2.2.20 or 2.3 is stable on AURA's build toolchain (currently
  on Kotlin 2.0.x — migration to 2.2.x requires K2 compiler full adoption)

## Version History

| Version | Released | Key Changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate, ECDH+HKDF, room exchange, QR fallback, blocklist, biometric |
| v1.1.0 | 2026-05-24 | QR relay, 7 locales 100%, 259 unit + 51 instrumented tests, signed APK splits |
| v2.0.0 | 2026-05-25 | 22 PRs: transport injection, NFC scaffold, profiles, identity rotation, audit log, backup |
| v2.0.1 | 2026-05-26 | NFC HCE ISO 7816-4 full impl, SPKI runtime pinning, GestureModelLoader, backup polish |
| v2.1.0 | 2026-05-26 | JaCoCo 60%, l10n human review (313 strings), deeplink Add Contact sheet |
| v3.0.0 | 2026-05-26 | iOS AuraCore companion (vCard 3.0, SAS, ECDH), iOS CI with cache + coverage |
| v3.1.0 | 2026-05-26 | Wear OS pairing UI, Android Auto voice + biometric gate |
| v3.2.0 | 2026-05-26 | Enterprise audit retention, F-Droid reproducible build + submission guide |
| v3.3.0 | 2026-05-26 | Tasks 1–44 complete — full transport stack, PQ crypto, room exchange, analytics |
| v4.0.0 | 2026-05-26 | Tasks 45–66 complete — PQ identity, Noise/MLS/SPQR, OHTTP, OpenID4VP, mdoc, QUIC, UWB FiRa 3.0, BLE CS, continuous auth, Advanced Protection |


---

*Last updated: 2026-05-26 — Stripped Tasks 45–66 (all shipped to v4.0.0). Removed implemented R&D items (A–C, E–F, I–K). Retained R&D-D, G, H, L–X as active research. No open implementation tasks — full baseline on main.*
