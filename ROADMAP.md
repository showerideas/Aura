# AURA — Engineering Roadmap

> Canonical planning document for the AURA gesture-biometric contact exchange system.
> Written as a strictly ordered sequence of engineering tasks for a software team.
> Tasks are sorted by dependency: each task may depend on those above it.
> References are cited inline where they are relevant — there is no separate reference table.
> Last updated: 2026-05-27 | v5.7 baseline

---

## How to Read This Document

This document is a **dependency-ordered implementation sequence**, not a feature wishlist.
Every task is placed where it is because something either upstream requires it, or completing it
unlocks the most subsequent work. Read it top to bottom before starting any task.

Status markers:
- `[x]` — complete and merged to main
- `[ ]` — open, ready to implement
- `[PARTIAL]` — scaffolded or substantially implemented but not production-complete; see task detail
- `[R&D]` — design/research phase only; no code until explicitly moved to `[ ]`

**Current baseline: v5.6** on `main`. All 118 tasks complete (Tasks 1–118, Phases 1–11).
Remaining: R&D items D, H, M, N, P, Q, X (research-only, no code until trigger conditions met).

---

## Current System Snapshot

| Layer | State |
|---|---|
| Core app | v5.6 — all 118 tasks complete |
| Gesture gate | MediaPipe Hands + temporal classifier + dual-descriptor temporal enrollment (Phase 5): 2s/60-frame capture, open-palm anchor, Window A (frames 0–44) + Window B (frames 15–59), 107-float compound descriptors, AND-logic cosine similarity ≥ 0.85 matching. ZK-SNARK Groth16 proof of match (Phase 8, enterprise opt-in). AR mode gesture confirmation via ARCore + UWB gate (Phase 9). |
| Transport | Google Nearby Connections (GMS) + Wi-Fi Direct (FOSS) + BLE GATT (BLE 6.2 SCI) + NFC HCE + QR relay + LoRa (opt-in: requires Meshtastic app + ENABLE_LORA=true build flag) |
| NFC | HCE ISO 7816-4 full impl + NDEF tap + reader mode + session token bootstrap |
| BLE GATT | Full GATT server/client + MTU 517 + chunked transfer + BLE Channel Sounding ranging (API 36+) |
| QR relay | AES-256-GCM HTTPS + Tor SOCKS5 (Orbot) + OHTTP RFC 9458 + QUIC/HTTP3 (Cronet) |
| Crypto | Hybrid KEM ML-KEM-768+X25519 · ML-DSA-65 identity signatures · PQXDH full prekey bundle · Sealed sender · Noise_XX channel · Double Ratchet + SPQR · MLS RFC 9420 rooms (simplified flat topology, not full TreeKEM) · SAS · TOFU |
| Wire protocol | v9 — SPKI pinning · ML-DSA-65 hybrid sigs · identity rotation · replay protection · PQ hybrid KEM · Noise_XX overlay |
| Multi-profile | Personal / Work — wired; enterprise MDM retention |
| Audit log | ExchangeAuditLog Room table + CSV export + AuditFragment UI + differential privacy ε=1.0 analytics export |
| Identity | W3C Verifiable Credentials (did:key + JsonWebSignature2020) · ISO 18013-5 mdoc/mDL (id.aura.contact.1) · ISO 18013-7 async mDL (Phase 10) · OpenID4VP verifiable presentation · DIDComm v2 authcrypt/anoncrypt exchange (Phase 10) · FIDO2 passkeys via CredentialProviderService (Phase 7) |
| Localization | 365 strings × 7 locales — 100% coverage, human-reviewed |
| Test suite | 623+ unit methods + 72 instrumented + 36 iOS tests — JaCoCo 60% branch floor |
| CI | Green — lint + unit + JaCoCo + assembleRelease + APK size gate + iOS build/test |
| Distribution | GitHub Releases direct APK distribution |
| Signing | PKCS12 keystore in GitHub Secrets — signed AAB confirmed |
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, WireProtocol.swift, MultipeerTransport, NFCExchangeBootstrap; 36 tests |
| Wear OS | Glance tile + Health Connect HRV + SasPinActivity + WristRaiseTrigger; Wear OS 7 production-complete |
| Android Auto | Voice action + biometric auth gate; full screen library |
| Room sessions | Multi-party card exchange — star topology, 10-min TTL, delivery ACK, MLS group key agreement |
| Mesh | Store-and-forward (BLE bloom filter) + multi-hop Wi-Fi Direct mesh (5 hops) |
| Analytics | On-device exchange analytics — transport breakdown, heatmap, PDF export (differential privacy) |
| Enterprise | 6 MDM restriction keys + zero-touch enrollment + signed audit export + Advanced Protection API + 2-of-3 MPC Shamir threshold co-signed audit export (Phase 11) + Privacy Pass v2 relay rate limiting (Phase 11) |
| Desktop | KMP desktop companion — QR relay transport |

---

## Completed Phases — v5.0 through v5.7

All 118 implementation tasks (Tasks 1–118, Phases 5–11) are complete and merged to main.
Task specifications are preserved in git history (commits  through ).

| Phase | Version | What shipped |
|---|---|---|
| 5 — Gesture Enrollment Engine | v5.0 | Dual-descriptor temporal enrollment · DualBoneGraphTracker · GestureVerificationEngine · EnrollmentUI |
| 6 — Android 17 ML-DSA Keystore | v5.1 | Native ML-DSA-65 KeyPairGenerator · StrongBox migration · PQC attestation chain · API 37 CI matrix |
| 7 — FIDO2 + NFC Key Relay | v5.2 | AuraCredentialProviderService · PasskeyRepository · CtapNfcRelay · DB v12 migration |
| 8 — ZK-SNARK Gesture Privacy | v5.3 | gnark Groth16 circuit · GestureZkProver JNI · ZK audit export column · Benchmark doc |
| 9 — AR/XR Exchange Overlay | v5.4 | ArExchangeCoordinator (UWB gate) · ContactCardNode · ArGestureExchangeBridge · XrExchangeActivity |
| 10 — DIDComm v2 + ISO 18013-7 | v5.5 | DIDCommTransport · DIDCommMessage · MdocDocument.fromOid4vpResponse() · DIDComm Inbox UI |
| 11 — MPC + Privacy Pass | v5.6 | ShamirSecretSharing 2-of-3 · AuditSigningCoordinator · PrivacyPassClient (RFC 9576) · TokenStore |
| R&D-D (graduated) | v5.7 | DidResolver (did:key · did:peer:2 · did:web) · encodePeer2Did · did:peer:2 unit tests |
| R&D-N (graduated) | v5.7 | BusinessCardImporter · ML Kit OCR pipeline · regex parser · 15 unit tests |

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
**Trigger:** Only relevant if PSI contact discovery expands to device address book.
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
| v3.2.0 | 2026-05-26 | Enterprise audit retention, GitHub Releases APK distribution |
| v3.3.0 | 2026-05-26 | Tasks 1–44 complete — full transport stack, PQ crypto, room exchange, analytics |
| v4.0.0 | 2026-05-26 | Tasks 45–66 complete — PQ identity, Noise/MLS/SPQR, OHTTP, OpenID4VP, mdoc, QUIC, UWB FiRa 3.0, BLE CS, continuous auth, Advanced Protection |
| v5.0 | 2026-05-27 | Phase 5: User-defined gesture enrollment — dual temporal bone graph descriptors |
| v5.1 | 2026-05-27 | Phase 6: Android 17 native ML-DSA-65 Keystore + BouncyCastle deprecation |
| v5.2 | 2026-05-27 | Phase 7: FIDO2 platform authenticator + NFC hardware key relay |
| v5.3 | 2026-05-27 | Phase 8: ZK-SNARK gesture template privacy + enterprise ZK audit export |
| v5.4 | 2026-05-27 | Phase 9: AR (ARCore) + Android XR spatial contact card exchange |
| v5.5 | 2026-05-27 | Phase 10: DIDComm v2 messaging + ISO 18013-7 async mDL presentation |
| v5.6 | 2026-05-27 | Phase 11: MPC 2-of-3 threshold audit signing + Privacy Pass relay rate limiting |

---

*Last updated: 2026-05-27 — All Phases 5–11 (Tasks 67–118) complete and merged to main. v5.6 baseline achieved. R&D-D, H, M, N, P, Q, X retained as research-only with unchanged trigger conditions.*
