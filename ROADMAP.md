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
| Core app | v3.3.0 — production-ready |
| Gesture gate | MediaPipe Hands + LSTM temporal classifier + 2-layer liveness (passive drift + active challenge) |
| Transport | Google Nearby Connections (GMS) + Wi-Fi Direct (FOSS) + BLE GATT + NFC HCE + QR relay + LoRa |
| NFC | HCE ISO 7816-4 full impl + NDEF tap + reader mode + session token bootstrap |
| BLE GATT | Full GATT server/client + MTU 517 + chunked transfer + UWB OOB |
| QR relay | AES-256-GCM HTTPS + Tor path wired |
| Crypto | Hybrid KEM ML-KEM-768+X25519 · Sealed sender · Double Ratchet (symmetric) · SAS · TOFU |
| Wire protocol | v8 — SPKI runtime pinning · identity rotation · replay protection · PQ hybrid KEM · HybridKemEngine negotiation |
| Multi-profile | Personal / Work — wired; enterprise MDM retention |
| Audit log | ExchangeAuditLog Room table + CSV export + AuditFragment UI |
| Localization | 313 strings × 7 locales — 100% coverage, human-reviewed |
| Test suite | 300+ unit methods + 55 instrumented + 15 iOS AuraCoreTests — JaCoCo 80% floor |
| CI | Green — lint + unit + JaCoCo + assembleRelease + APK size gate + iOS build/test |
| Distribution | F-Droid reproducible build script + submission guide — live |
| Signing | PKCS12 keystore in GitHub Secrets — signed AAB confirmed |
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, WireProtocol.swift, MultipeerTransport, 15 tests |
| Wear OS | Pairing flow — WearPairingViewModel + BottomSheet + PhoneWearSender; wearos/ module production-complete |
| Android Auto | Voice action + biometric auth gate; full screen library (Advertising/Completed/Idle/Sas) |
| Room sessions | Multi-party card exchange — star topology, 10-min TTL, delivery ACK |
| Mesh | Store-and-forward (BLE bloom filter) + multi-hop Wi-Fi Direct mesh (5 hops) |
| Analytics | On-device exchange analytics — transport breakdown, heatmap, PDF export |
| Enterprise | 6 MDM restriction keys + zero-touch enrollment + signed audit export |
| Desktop | KMP desktop companion — QR relay transport |

---

## Task 45 — Android 16/17 Compatibility + Biometric API Upgrade

**Why now:** Android 16 introduced breaking changes to the biometric authentication stack that
directly affect AURA's gesture gate: Identity Check enforcement, CryptoObject KeyAgreement
support, and elimination of screen-lock credential fallback. Android 15 removed the developer
option that previously let users test predictive back — it is now unconditional, meaning apps
with unhandled `onBackPressed()` overrides silently misbehave on Android 15+ devices.
Shipping this block clears all Android 16/17 compatibility debt before the next wave of feature
work begins.

**Identity Check (Android 16 QPR2):** Google's Identity Check expands to cover any app calling
`BiometricPrompt`. When a user enables Identity Check on their device, AURA's gesture gate
must respond correctly: no screen-lock PIN fallback allowed in protected contexts, biometric
only. AURA already uses biometric-only paths but the fallback logic must be explicitly disabled.

**CryptoObject KeyAgreement:** Android 16 added `KeyAgreement` to `BiometricPrompt.CryptoObject`.
This means AURA can gate the ECDH key agreement itself behind a biometric prompt — the private
key is only usable for one key agreement operation per successful authentication. This is
banking-grade security: the identity key cannot be extracted even if the app process is
compromised while running.

See: [developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.CryptoObject]
See: [developer.android.com/guide/navigation/custom-back/predictive-back-gesture]
See: [developer.android.com/develop/ui/compose/system/shortcuts]

- [ ] `BiometricPrompt.CryptoObject(KeyAgreement)` — gate ECDH on successful biometric:
  initialize `KeyAgreement` on Android Keystore key bound with
  `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`;
  wrap in `CryptoObject`; pass to `BiometricPrompt.authenticate()`; on
  `onAuthenticationSucceeded`, extract `result.cryptoObject!!.keyAgreement` and proceed
- [ ] Disable screen-lock fallback in `BiometricPrompt.PromptInfo`:
  set `setAllowedAuthenticators(BIOMETRIC_STRONG)` only — no `DEVICE_CREDENTIAL` flag;
  handle `AuthenticationError.NO_BIOMETRICS` by routing to Settings biometric enrollment
- [ ] Predictive Back migration: audit all `onBackPressed()` overrides across all fragments
  and activities; replace with `onBackPressedDispatcher.addCallback(OnBackPressedCallback)`;
  ensure `SasDialogHardening` (Task 20) uses `OnBackInvokedDispatcher.registerOnBackInvokedCallback()`
  for Android 33+ compatibility with backwards compat via AndroidX
- [ ] Enable `android:enableOnBackInvokedCallback="true"` in `AndroidManifest.xml` — required
  for the system to animate predictive back; verify SAS countdown dialog back-block still works
- [ ] App Shortcuts: `res/xml/shortcuts.xml` — static shortcuts:
  - "Start Exchange" → `ExchangeFragment` → gesture gate immediately
  - "My Card" → `ProfileFragment`
  Dynamic shortcut updated after each exchange: "Exchange with [last contact name]"
  use `ShortcutManagerCompat.pushDynamicShortcut()` — limit to 4 shortcuts total
- [ ] Android photo picker for avatar: replace any `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`
  with `ActivityResultContracts.PickVisualMedia()` — Android 13+ photo picker;
  no permission dialog on Android 13+; graceful fallback to `READ_MEDIA_IMAGES` on older
- [ ] Unit tests: predictive back callback fires correctly in SAS dialog fragment;
  CryptoObject KeyAgreement path produces same shared secret as non-biometric path

---

## Task 46 — StrongBox Key Migration and Hardware Attestation

**Why now:** AURA identity keys and room session keys are currently generated in the Android
Keystore TEE (Trusted Execution Environment). Android 9+ devices that include a StrongBox
Keymaster — a dedicated secure microcontroller isolated from the main SoC — provide stronger
physical tamper resistance. StrongBox is appropriate for AURA's highest-value keys: the
long-term identity key pair (P-256) and the BouncyCastle ML-KEM key generation seed.

Hardware Key Attestation (Android 8+) allows AURA to generate a certificate chain rooted in
Google's attestation root, proving that a key is hardware-backed. For enterprise deployments,
this enables the audit export server to verify that the signing device is a genuine Android
device with an uncompromised Keystore. Attestation is a prerequisite for the zero-knowledge
audit export signature validation described in Task 29.

See: [source.android.com/docs/security/best-practices/hardware]
See: [developer.android.com/privacy-and-security/keystore]
See: [proandroiddev.com/your-app-is-secure-but-is-the-device-android-hardware-attestation-explained]

- [ ] Identity key generation: try `setIsStrongBoxBacked(true)` first; on `StrongBoxUnavailableException`,
  retry without `setIsStrongBoxBacked` (TEE fallback); log which security level was used to
  `ExchangeAuditLog.keySecurityLevel` enum: `STRONGBOX | TEE | SOFTWARE`
- [ ] `isInsideSecureHardware()` check at session start: if identity key returns `SOFTWARE` level,
  display a persistent warning in `SecurityStatusFragment` — "Key stored in software; consider
  re-enrolling on a device with hardware security"
- [ ] For room session keys (Task 8): generate per-room AES-256 keys with `setIsStrongBoxBacked(true)`;
  same TEE fallback pattern; alias: `"aura-room-${roomId.toHexString().take(16)}"`
- [ ] Hardware Key Attestation for enterprise audit export (extends Task 29):
  - Generate a `KeyPairGenerator` key with `setAttestationChallenge(challenge)` where challenge
    = 32-byte random from audit export server
  - Include the attestation certificate chain in the signed audit export payload
  - Receiving server verifies chain back to Google attestation root
  - Document attestation verification procedure in `docs/ENTERPRISE.md`
- [ ] StrongBox limitation: max 4 concurrent operations; document in code that AURA's key usage
  (one identity key, max 3 room keys concurrently) fits within this limit
- [ ] Unit test: mock `KeyInfo.securityLevel` = SOFTWARE → warning shown; STRONGBOX → no warning

---

## Task 47 — PQXDH Full Prekey Bundle System

**Why this follows Task 33:** Task 33 delivered `HybridKemEngine` — the ML-KEM-768+X25519
session KEM. That handles the synchronous case (both peers present simultaneously). PQXDH
adds asynchronous exchange capability: Alice can send to Bob without Bob being online, using
Bob's prekey bundle. This is required for the store-and-forward transport (Task 37) to achieve
full post-quantum security — currently the deferred delivery uses the recipient's long-term
public key for encryption, which does not provide forward secrecy.

PQXDH (Signal's Post-Quantum Extended Diffie-Hellman) augments X3DH with an ML-KEM-768
one-time prekey. The final shared secret is derived from both the X25519 and ML-KEM KEM
outputs, preventing harvest-now-decrypt-later on asynchronous messages.

See: [signal.org — PQXDH specification]
See: [github.com/signalapp/libsignal] — Signal's reference implementation in Rust + Kotlin
See: [cryspen.com/post/pqxdh] — formal cryptographic analysis of PQXDH

**Architecture:** Each AURA user maintains a prekey bundle:
- Identity key: existing P-256 + (ML-DSA-65 in Task 48 when available)
- Signed prekey: X25519 keypair, signed by identity key, rotated every 7 days
- One-time prekeys: pool of 10 × (X25519 || ML-KEM-768) keypairs; replenished when pool < 3
- Prekey bundles are exchanged during NFC bootstrap or stored in the QR relay slot for async
  delivery; the relay server sees only opaque bytes — keys are never in plaintext to the relay

- [ ] `PreKeyBundle.kt`: data class containing identity public key, signed prekey (X25519),
  signed prekey signature, one-time prekey (X25519 || ML-KEM-768 public keys)
- [ ] `SignedPreKeyStore.kt`: local DataStore; key pair + signature; rotate on 7-day TTL;
  `SignedPreKey.createdAt` + `expiresAt = createdAt + 604_800_000` (7 days)
- [ ] `OneTimePreKeyStore.kt`: pool of 10 one-time prekeys; each is a
  `(x25519KeyPair, mlKem768KeyPair)` pair; delete on use (one-time semantics); alert user
  when pool drops below 3 — trigger background replenishment via `WorkManager`
- [ ] PQXDH sender: `PQXDHSender.encapsulate(bundle: PreKeyBundle)` →
  `senderEphemeralX25519 + PQXDH_combinedSecret` using:
  DH1 = X25519(senderIdentityKey, receiverSignedPreKey)
  DH2 = X25519(senderEphemeral, receiverIdentityKey)
  DH3 = X25519(senderEphemeral, receiverSignedPreKey)
  DH4 = X25519(senderEphemeral, receiverOneTimePreKeyX25519)
  KEM1 = ML-KEM-768-Encapsulate(receiverOneTimePreKeyMLKEM)
  masterSecret = HKDF(DH1 || DH2 || DH3 || DH4 || KEM1.sharedSecret, info="aura-pqxdh-v1")
- [ ] PQXDH receiver: symmetric decapsulation; verify signed prekey signature before use
- [ ] Wire PQXDH into `PendingExchangeQueue` (Task 37): async packets now use full PQXDH instead
  of long-term public key encryption — one-time prekey provides per-packet forward secrecy
- [ ] One-time prekey deletion: zero-fill private key bytes immediately after use —
  `Arrays.fill(privateKeyBytes, 0)` before removing from store
- [ ] Unit tests: full PQXDH round-trip (sender→receiver), prekey exhaustion handling,
  signed prekey rotation timing, ML-KEM-768 decapsulation from sender ciphertext

---

## Task 48 — ML-DSA (FIPS 204) Post-Quantum Identity Signatures

**Why this follows Task 47:** The current identity key is P-256 (ECDSA). The rotation certificate
(Task 30), TOFU verification, and signed prekeys (Task 47) all use P-256 signatures. A quantum
computer running Shor's algorithm breaks P-256 in polynomial time. NIST finalized ML-DSA
(CRYSTALS-Dilithium, FIPS 204) in August 2024 — this is the primary post-quantum signature
standard alongside SLH-DSA (FIPS 205). AURA should add ML-DSA-65 as a co-signature alongside
P-256 — the hybrid approach identical to the KEM: both must be broken to forge a signature.

CNSA 2.0 mandates post-quantum algorithm adoption across all national security systems by 2030.
Enterprise deployments of AURA should begin migrating to hybrid signatures now.

See: [nvlpubs.nist.gov/nistpubs/fips/nist.fips.204.pdf] — FIPS 204 ML-DSA specification
See: [github.com/MichaelsPlayground/PostQuantumCryptographyBc172] — BouncyCastle 1.72 ML-DSA
See: [quantumsecuritydefence.com/insights/nist-fips-standards]

**Architecture:** AURA identity key becomes a hybrid key pair: `(P-256, ML-DSA-65)`.
Signatures are concatenated: `[p256Sig(64 bytes) || mlDsaSig(3293 bytes)]`.
Wire protocol v9 adds a `HYBRID_SIG_V9` TLV field alongside the existing signature field.
Receivers that understand v9 verify both; v8 receivers verify P-256 only (backward compat).

BouncyCastle already included in build (Task 33 bcpqc). ML-DSA-65 is available via
`org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner`.

- [ ] Generate ML-DSA-65 identity key pair on enrollment — stored in Android Keystore
  (software-backed since Android Keystore does not yet support PQ algorithms natively;
  document limitation; key material encrypted to Android Keystore AES wrapping key)
- [ ] `HybridIdentityKey.kt`: holds `(ecdhKey: ECPrivateKey, mlDsaKey: DilithiumPrivateKeyParameters)`;
  `sign(data: ByteArray): HybridSignature` → `HybridSignature(p256Sig, mlDsaSig)`
- [ ] `HybridIdentityKey.verify(data, sig): Boolean` → both sigs must verify; fail if either invalid
- [ ] `WireProtocol` v9: add optional TLV `HYBRID_SIG_V9` carrying ML-DSA-65 signature;
  v8/v7 receivers skip unknown TLV fields; v9 receivers verify hybrid; version negotiation
  via `WireProtocolNegotiator.kt` extended for v9
- [ ] ProGuard rules: add BouncyCastle Dilithium classes to keep rules (extends Task 43):
  `-keep class org.bouncycastle.pqc.crypto.crystals.dilithium.** { *; }`
- [ ] Key rotation cert (Task 30): include ML-DSA-65 signature on rotation cert alongside P-256 —
  `rotationCert.mlDsaSignature` field in `WireProtocol` v9 TLV
- [ ] APK size impact: Dilithium public key 1952 bytes, private key 4000 bytes, signature 3293 bytes;
  document size impact in `docs/SECURITY.md`; these are acceptable for identity exchange payloads
- [ ] Unit tests: ML-DSA-65 sign/verify round-trip, hybrid signature byte layout, TLV parsing,
  v9→v8 backward compatibility (v8 receiver ignores ML-DSA TLV)

---

## Task 49 — Differential Privacy for Exchange Analytics

**Why now:** Task 17 shipped exchange analytics with PDF export. For enterprise MDM use cases
(Task 28), the audit export endpoint receives raw exchange counts per transport per day. With
enough data points, an adversary with access to the export stream could potentially infer
individual exchange events (e.g., "this device exchanged exactly 3 times via NFC on Tuesday
between 2-3 PM"). Differential privacy adds calibrated Laplace noise to aggregated counters,
providing a formal privacy guarantee: an attacker cannot distinguish whether any individual
exchange event was included in a dataset.

See: [developers.google.com/privacy-sandbox/protections/on-device-personalization/differential-privacy-semantics-for-odp]
See: [ieeexplore.ieee.org/document/9240660] — differential privacy for Android analytics

**Architecture:** Laplace mechanism with privacy budget ε = 1.0 per export cycle.
Applied at aggregate query level, not to raw events. Raw events stay on-device;
only the noised aggregates leave the device in the signed export (Task 29).
The noise scale λ = sensitivity / ε where sensitivity = 1 (adding/removing one exchange
changes any count by at most 1). `LaplaceNoise.sample(scale: Double)` can be implemented
in ~20 lines using the standard inverse CDF method.

- [ ] `DifferentialPrivacyEngine.kt`:
  - `addLaplaceNoise(count: Int, sensitivity: Int = 1, epsilon: Double = 1.0): Int`
  - Uses `java.util.Random.nextExponential()` (Java 17+) or Box-Muller fallback for Laplace
  - Clamp noised result to `max(0, noisedValue)` — negative counts are nonsensical
- [ ] Apply in `AnalyticsViewModel.kt`: all aggregate queries for export pass through `DifferentialPrivacyEngine`
  before being included in the signed export payload; raw on-device display does NOT apply noise
  (user sees their real data; noise is for export only)
- [ ] Privacy budget accounting: track ε consumed per 24-hour window in `DataStore`;
  refuse to export if budget exhausted (max 3 exports per day at ε=1.0 each)
- [ ] Document privacy guarantees in `docs/PRIVACY_POLICY.md`: "Exchange analytics exported to
  enterprise endpoints include calibrated Laplace noise (ε=1.0) providing ε-differential privacy.
  Individual exchange events cannot be identified from exported aggregates."
- [ ] Unit tests: noise distribution test (1000 samples — Kolmogorov-Smirnov against Laplace CDF),
  budget exhaustion rejection, negative-count clamping, on-device display path bypasses noise

---

## Task 50 — Wear OS 7 + Health Connect Integration

**Why this follows Task 35:** Wear OS 7 launched May 2026 with breaking changes to the Tile
architecture: the `androidx.wear.tiles:tiles-material` ProtoLayout API is deprecated in favor
of Wear Widgets — Jetpack Glance composables rendered via RemoteCompose. Any new Wear OS
features must target the Wear Widget model. Simultaneously, Google Fit APIs are being deactivated
in 2026 — any HRV or health data read must migrate to Health Connect.

See: [android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html]
See: [developer.android.com/training/wearables/versions/5/features]
See: [developer.android.com/health-and-fitness/fitness/basic-app/integrate-wear-os]

- [ ] Migrate `AuraTileService` from Tiles ProtoLayout to Wear Widget (Jetpack Glance + RemoteCompose):
  - Replace `onTileRequest` / `TileBuilders.Tile` DSL with `@Composable GlanceWidget`
  - State: `ReadyState` (green pulse icon) / `ActiveState` (exchange animation) / `ErrorState`
  - Tap target: `GlanceModifier.clickable(actionRunActivity(ExchangeActivity::class.java))`
- [ ] New Wear OS 7 complication: Goal Progress complication showing "exchanges this week"
  progress toward a configurable weekly goal (default: 10); shown on watch face via
  `GoalProgressComplicationData`
- [ ] Health Connect HRV read (gate for R&D-F):
  - Request `READ_HEALTH_DATA_IN_BACKGROUND` + `HealthPermission(HeartRateVariabilityRmssd, READ)`
  - `HealthConnectClient.readRecords(HeartRateVariabilityRmssdRecord)` → latest reading
  - Freshness check: only use readings within last 5 minutes for auth purposes (older = stale)
  - Emit `HrvResult(rmssd: Double?, isStale: Boolean)` via `StateFlow` in `WearStateStore`
  - Surface HRV freshness status in `SecurityStatusFragment` — "HRV data available (3 min old)"
- [ ] Decommission Google Fit API calls if any remain: search codebase for
  `com.google.android.gms.fitness` — remove or migrate to Health Connect
- [ ] Watch Face Format v2 migration: if any WFF assets exist, migrate before January 2026 deadline
  (already passed — audit and complete if not done)
- [ ] Unit tests: Glance widget state transitions; HRV freshness check boundary (4:59 = valid, 5:01 = stale)

---

## Task 51 — W3C Verifiable Credentials for AURA Profiles

**Why now:** The W3C DID v1.1 Candidate Recommendation was published March 2026, and the
W3C VC Data Model is stable. AURA already has all the primitives: P-256 identity keys for
signing, `ContactProfile` as a structured data model, and the existing `did:key` derivation
groundwork from R&D-D. Issuing AURA profiles as W3C Verifiable Credentials enables:
1. Interoperability with any VC-compatible wallet (Microsoft Entra, Apple Wallet VC support)
2. Selective disclosure — share only specific credential claims without revealing the full profile
3. Enterprise PKI integration — employer VCs combined with AURA personal VC for dual verification

See: [w3.org/TR/did-1.1] — W3C DID v1.1 spec
See: [dock.io/post/verifiable-credentials] — VC fundamentals
See: [securityboulevard.com/2026/03/decentralized-identity-and-verifiable-credentials-the-enterprise-playbook-2026]

**Architecture:** AURA DID = `did:key:z<multibase-base58btc-encoded P-256 public key>`.
VC = JSON-LD document signed with `JsonWebSignature2020` using the AURA identity key.
VC payload: `{ "@context", "type": ["VerifiableCredential", "AuraContactCredential"],
"issuer": did, "issuanceDate", "credentialSubject": { ...ContactProfile fields... } }`.
VCs are stored locally and exchanged via existing `WireProtocol` as an optional TLV field.

- [ ] `DIDKeyResolver.kt`: derive `did:key` URI from existing P-256 identity public key
  - Encode: `0x1200` (P-256 multicodec prefix) || raw 64-byte public key → multibase base58btc → `z<result>`
  - Full DID: `did:key:z<encoded>`; DID Document generated deterministically from key
- [ ] `VerifiableCredential.kt`: data class with JSON-LD context, `@context`, `type`, `issuer`, `issuanceDate`,
  `credentialSubject` mapping to `ContactProfile` fields; serialize to compact JSON
- [ ] `VCIssuer.kt`: `issueProfileCredential(profile: ContactProfile): VerifiableCredential`
  - Sign with existing P-256 identity key via `JsonWebSignature2020` (ES256 algorithm)
  - JWS compact serialization; store issuer DID in `proof.verificationMethod`
- [ ] `VCVerifier.kt`: verify incoming VCs — resolve `did:key`, verify JWS signature, check
  `expirationDate` (optional), check `issuer` matches sender's TOFU identity key
- [ ] `WireProtocol` v9 TLV field `VC_PAYLOAD`: optional; carries self-signed VC alongside raw profile;
  v8 receivers ignore TLV; v9 receivers may prefer VC over raw profile for interoperability
- [ ] Export as W3C VC: Settings → My Card → "Export as Verifiable Credential" → share as `.jsonld` file
  via `ACTION_SEND` intent
- [ ] Unit tests: DID derivation round-trip (encode → decode → same public key), VC issuance,
  VC verification, tampered VC rejection, unknown TLV skip on v8 receiver

---

## Task 52 — UWB FiRa 3.0 Compliance and Aliro Access Control Profile

**Why now:** The FiRa Consortium published FiRa 3.0 specification in 2025/2026, significantly
expanding UWB use cases beyond proximity confirmation. The Aliro smart lock standard launched
in 2026 using UWB + NFC — several enterprise venues are deploying Aliro-compatible readers.
AURA's existing UWB implementation (Task 11) targets FiRa MAC 1.3 compliance; updating to
FiRa 3.0 adds centimeter-level accuracy improvements and the Aliro profile.

For enterprise AURA deployments in corporate headquarters with Aliro-enabled access control,
AURA could act as the credential carrier — the user's AURA identity key is the access token,
proximity-confirmed via UWB. This eliminates the need for a separate NFC badge.

See: [firaconsortium.org/resource-hub/blog/uwb-secure-ranging-revolutionizing-security-technology]
See: [firaconsortium.org/resource-hub/blog/fira-3-0-use-cases-expanding-the-future-of-uwb-technology]
See: [ubos.tech/news/aliro-smart-lock-standard-launches-in-2026-with-uwb-and-nfc-integration]
See: [developer.android.com/develop/connectivity/uwb] — Android UWB API

- [ ] Update `UwbRangingManager.kt` for FiRa 3.0:
  - Target `FiRa MAC 1.3` compliance (required for ranging against IoT devices per Android docs)
  - Use `RangingParameters.UWB_CONFIG_ID_3` (FiRa 3.0 config) when available; fallback to 1
  - Apply 5-sample sliding window (Task 11) with exponential moving average for improved accuracy
  - Log `rangingResult.status` — `RESULT_STATUS_SYSTEM_ERROR` retry with 500ms × 3 (already done)
- [ ] Aliro Access Control profile (enterprise feature, gate behind `IS_ENTERPRISE` flag):
  - Register AURA as an Aliro credential provider via `android.hardware.credentials.AliCredential`
    (Android 15+ API) — allows lock readers to request identity via UWB+NFC tap
  - `AliCredential` payload: AURA identity key hash + UWB session parameters + nonce signed
    with P-256 identity key
  - Present credential on UWB + NFC proximity: `UwbManager.controllerSessionScope()` with
    Aliro session parameters received via NFC HCE tap
  - Log Aliro access events to `ExchangeAuditLog` with `exchangeType = ALIRO_ACCESS_CONTROL`
- [ ] Enterprise MDM restriction key: `aliro_enabled: Boolean` in `app_restrictions.xml` —
  allows IT admin to enable/disable Aliro credential provider per device
- [ ] Unit tests: FiRa 3.0 config selection, Aliro credential construction, UWB session param encoding

---

## Task 53 — Federated Gesture Model and AI Coaching

**Why this consolidates R&D-E:** Gesture Library (Task 27) now has real usage data from
enrolled gestures. AI Gesture Coaching is no longer speculative — `GestureCoach.kt` can be
built on the LSTM landmark data already collected during enrollment. Federated learning adds
a privacy-preserving model improvement loop: individual landmark variance is never sent to a
server; only model gradient updates are aggregated (opt-in).

See: [github.com/kinivi/hand-gesture-recognition-mediapipe] — per-landmark variance reporting
See: [ncbi.nlm.nih.gov/pmc/articles/PMC11769222] — on-device continual learning for gesture
See: [flower.ai/apps/fraunhofer-ims/aifes-gesture-recognition] — federated gesture recognition

**Architecture:** `GestureCoach.kt` operates on the 5-sample enrollment landmark set already
stored in `GestureLibrary`. It computes per-landmark standard deviation across enrolled samples
and overlays a heat map on the camera preview during re-enrollment guidance: green = stable
joint, yellow = moderate variance, red = high variance. No model retraining occurs locally;
coaching is purely statistical reporting on existing data. Federated model update is opt-in
via Settings → Gesture → "Contribute to model improvement" — if enabled, a local gradient
update is computed and submitted to a coordinator endpoint (`BuildConfig.FEDERATED_ENDPOINT`).

- [ ] `GestureCoach.kt`:
  - Input: `List<LandmarkVector>` (5 enrolled samples per gesture)
  - Compute `stdDev[i]: Float` for each of 63 landmark coordinates (21 × xyz)
  - Classify: stdDev < 0.02 = STABLE (green), 0.02–0.05 = VARIABLE (yellow), > 0.05 = NOISY (red)
  - Emit `CoachingReport(landmarks: List<LandmarkStability>, overallScore: Float)` as `StateFlow`
- [ ] `EnrollmentFragment`: after 5-sample capture, show `GestureCoachingOverlay`:
  - Skeleton hand view (21 joints) colored by stability
  - Overall consistency score 0–100%
  - "Tap any joint to see improvement tip" → per-landmark coaching text stored in `strings.xml`
- [ ] Opt-in federated improvement (gate behind `BuildConfig.FEDERATED_ENDPOINT` not empty):
  - `FederatedGradientWorker` (WorkManager, `NETWORK_TYPE_CONNECTED`, Wi-Fi only):
    compute gradient of per-user model on local landmark dataset; POST to coordinator
  - No raw landmarks ever leave the device; only computed gradient tensor transmitted
  - Coordinator runs FedAvg aggregation; publishes updated global model to `BuildConfig.GESTURE_MODEL_URL`
  - GDPR compliance: opt-in only; revocable; no persistent user identifier in gradient payload
- [ ] Unit tests: per-landmark stdDev computation, stability classification thresholds,
  coaching report generation from mock landmark set

---

## Task 54 — Private Set Intersection Contact Discovery

**Why this consolidates R&D-C:** PSI enables AURA users to discover mutual contacts without
revealing their full contact list to any party — not even to each other. Alice knows which of
her AURA contacts are also in Bob's AURA contacts, but Bob learns nothing about Alice's other
contacts and vice versa. This is the same privacy guarantee Signal uses for contact discovery.

For AURA, PSI enables: "Do we have mutual contacts?" as a trust signal during an exchange —
without a directory server, without uploading address books, purely peer-to-peer.

See: [contact-discovery.github.io] — "Breaking & Fixing Contact Discovery in Mobile Messengers"
See: [signal.org/blog] — Signal contact discovery PSI
See: [arxiv.org/pdf/2011.09350] — Asymmetric PSI with applications to contact tracing
See: [mdpi.com/2073-431X/15/1/44] — SM2-based PSI protocol

**Architecture:** Diffie-Hellman PSI using existing P-256 curve. Both parties compute
`H(identityKeyHash)^r` for each of their contacts (where `r` is a random exponent and `H`
is a hash-to-curve function). Exchange blinded sets. Each party unblind the other's set and
intersects. No contact identities are revealed beyond the intersection.

- [ ] `PSIEngine.kt`:
  - `blindSet(contacts: Set<ByteArray>): Pair<Set<ECPoint>, BigInteger>` — returns blinded
    points + blinding factor `r`; `H(id)` = deterministic hash-to-curve (SSWU method for P-256)
  - `unblind(blindedPeerSet: Set<ECPoint>, r: BigInteger): Set<ECPoint>`
  - `intersect(myUnblindedPeerSet: Set<ECPoint>, peerBlindedMySet: Set<ECPoint>): Int` →
    count of mutual contacts; does not reveal which contacts are mutual (count only)
- [ ] Wire over existing exchange session: PSI is an optional pre-exchange step — both parties
  must opt in; disabled by default; enabled in Settings → Privacy → "Mutual contact hints"
- [ ] PSI run during `NearbyExchangeService` session before card transfer: if both peers
  have PSI enabled, run protocol; emit `MutualContactHint(count: Int)` before SAS dialog;
  SAS dialog shows: "You have N mutual AURA contacts" (count only, no names)
- [ ] Enterprise MDM restriction: `disable_psi: Boolean` in `app_restrictions.xml` — some
  enterprise policies may prohibit disclosure of contact set cardinality
- [ ] Performance gate: PSI set size limited to 200 contacts; abort silently if either party
  has > 200 AURA contacts (cardinality attack surface grows with set size)
- [ ] Unit tests: PSI round-trip with known intersection, false-positive rate, empty set,
  maximum set size, unblinding correctness

---

## Task 55 — Tor + Zero-Knowledge Relay Anonymization (Promote R&D-A)

**Why now (promoted from R&D-A):** The guardianproject/tor-android library is stable and
actively maintained. QR relay is already wired. Adding Tor as an optional relay path provides
real-world privacy for high-risk users (journalists, activists, enterprise whistleblowers) who
need to exchange contacts without linking their IP addresses. The ZK relay variant provides
the same property without Tor's latency — implemented as a simple hash-keyed lookup.

See: [github.com/guardianproject/tor-android] — Tor binary for Android
See: [github.com/guardianproject/onionkit] — SOCKS proxy support for OkHttp
See: [github.com/acomminos/OnionKit] — Android library for Tor + SOCKS proxy connections

**Architecture:** Two anonymization modes selectable in Settings → Privacy → Relay Mode:
1. **Tor mode**: `guardianproject/tor-android` embedded Tor binary; routes all relay HTTP
   traffic through `OkHttpClient` with `Proxy(SOCKS, "127.0.0.1", 9050)`
2. **ZK mode**: client posts `H(sessionId)` to relay; server stores encrypted payload keyed
   by hash; receiver queries `H(sessionId)` to retrieve; server cannot link sender to receiver
   since it sees only the hash of a session ID it never observes in plaintext

- [ ] Add `guardianproject/tor-android` dependency (AAR); initialize on app start if Tor mode
  enabled; listen on `localhost:9050` SOCKS proxy
- [ ] `TorRelayTransport.kt`: wraps `QRRelayTransport` with SOCKS-proxied `OkHttpClient`;
  `TorProxy.isBootstrapped(): Boolean` check before first request — wait up to 30s for Tor
  circuit to establish; surfaced in UI as "Connecting to Tor..." spinner
- [ ] `ZKRelayClient.kt`: Cloudflare Worker endpoint (extends `tools/relay-worker/`):
  - POST `/store`: body = `{ "key": H(sessionId), "payload": encryptedBase64 }`; no IP logged
  - GET `/retrieve?key=H(sessionId)`: returns stored payload or 404; key deleted after first read
  - Worker stores maximum 1 payload per key; TTL = 10 minutes (Cloudflare KV TTL)
- [ ] Settings → Privacy → Relay Anonymization: `Off | ZK Relay | Tor + ZK Relay`
  - `Off` = default (current behavior)
  - `ZK Relay` = hash-keyed relay; no Tor; adds ~0ms latency; hides nothing from relay operator
  - `Tor + ZK Relay` = Tor routing + hash-keyed; hides IP from relay operator; adds ~3-5s latency
- [ ] Enterprise MDM: `disable_tor_proxy` restriction key already declared in `EnterprisePolicy.kt` —
  read this key and disable Tor mode if true; ZK relay remains available
- [ ] Unit tests: SOCKS proxy configuration, ZK relay key derivation, hash pre-image resistance
  (H(sessionId) does not reveal sessionId), Tor bootstrap timeout handling



---

## Task 56 — Noise Protocol Framework Encrypted Channel (Noise_XX)

**Why now:** AURA's `WireProtocol.kt` implements a bespoke handshake: ephemeral ECDH,
HKDF, AES-256-GCM, Double Ratchet. This is cryptographically sound but ad-hoc — not
machine-verified and not externally audited. The Noise Protocol Framework is the industry
answer to this problem: it defines 59+ formally verified handshake patterns over a minimal
state machine. WhatsApp, WireGuard, and Signal all use Noise-based channels.

The `Noise_XX` pattern provides mutual authentication (both parties prove knowledge of their
static keys) + forward secrecy (ephemeral DH on each session) + identity hiding (static keys
encrypted in the handshake). This is strictly better than AURA's current handshake for the
relay transport path, where AURA communicates with the relay server rather than directly
peer-to-peer. The Noise_NK pattern (responder authenticated, initiator anonymous) is ideal
for the relay connection: the relay server has a known static key (pinned in the APK); the
client is ephemeral.

The peer-to-peer (device-to-device) path already benefits from NFC/gesture trust bootstrapping
and can adopt `Noise_KK` (both keys known prior to handshake) since identity keys are exchanged
in-person.

See: [noiseprotocol.org] — Noise Protocol Framework specification
See: [github.com/signalapp/libsignal] — Signal's Noise usage in contact enrollment
See: [ieeexplore.ieee.org/document/9833621] — formal verification of Noise patterns
See: [github.com/emilbayes/noise-peer] — Noise-peer library using libsodium for reference

**Architecture:** Three Noise sessions:
- Relay upload/download: `Noise_NK` (server key pinned via SPKI; client anonymous)
- Direct device-to-device WireProtocol: `Noise_KK` (both identity keys known from in-person NFC tap)
- First-contact NDEF tap: `Noise_XX` (mutual identity exchange, no prior knowledge)

Each pattern uses X25519 for DH, ChaChaPoly (ChaCha20-Poly1305) for AEAD symmetric cipher,
SHA-256 for hash — all available in BouncyCastle and/or Android Keystore.

- [ ] Add `noise-java` library (`com.github.rweather:noise-java`) or port `com.southernstorm.noise`
  (25K line production Noise Java implementation — widely used, Apache 2.0)
- [ ] `NoiseRelayTransport.kt`: wrap `QRRelayTransport` POST/GET calls in `Noise_NK` session;
  server static public key pinned in `BuildConfig.RELAY_NOISE_PUBLIC_KEY` (32-byte X25519);
  replaces existing TLS+SPKI pinning at relay path — Noise provides equivalent security with
  smaller code surface and no dependency on Android TLS stack
- [ ] `NoiseDirectChannel.kt`: `Noise_KK` handshake using `(localIdentityKey, peerIdentityKey)`
  as the known static keys; produces `(sendKey, recvKey)` pair for `WireProtocol` framing;
  replaces the custom ECDH session in `NearbyExchangeService`
- [ ] Channel binding: export `GetHandshakeHash()` after handshake — use as the `sessionToken`
  (replaces current HKDF-based token in Task 3) — same entropy, standard derivation
- [ ] ProGuard: ensure Noise library classes are kept (add to Task 43 rules)
- [ ] Unit tests: XX handshake round-trip, NK handshake with pinned server key, KK identity
  binding (wrong key rejected), handshake hash determinism across platforms

---

## Task 57 — MLS Group Key Agreement for Room Sessions (RFC 9420)

**Why this upgrades Tasks 8–10:** IETF RFC 9420 (Messaging Layer Security) became the
group E2EE standard for Google Messages and Apple Messages in May 2026. MLS provides what
AURA's current star-topology Room exchange lacks: forward secrecy between individual messages
within a group session, cryptographically enforced membership (add/remove operations are
signed by group members), and scalability to hundreds of participants. The current Room
host decrypt-and-re-encrypt model (Task 10) requires trusting the host — MLS eliminates
this trust assumption.

See: [rfc-editor.org/rfc/rfc9420.html] — MLS Protocol (RFC 9420)
See: [rfc-editor.org/rfc/rfc9750.html] — MLS Architecture (RFC 9750)
See: [github.com/awslabs/mls-rs] — AWS production MLS Rust implementation (Apache 2.0)
See: [ietf.org/blog/rcs-adopts-mls] — RCS Universal Profile 3.0 adopts MLS

**Architecture:** MLS replaces the Room DB session key (Task 8) with an MLS group epoch.
When Alice creates a Room, she initializes an MLS group. When Bob joins, Alice commits a
`MLS_Add` proposal that distributes the current group epoch key to Bob (encrypted to Bob's
prekey — Task 47's prekey infrastructure is reused). Each message in the Room session
advances the epoch. Removing a member rotates the epoch automatically, achieving
post-compromise security. An MLS Delivery Service (DS) routes Proposals and Commits — AURA
uses its existing QR relay slot as a minimal MLS DS.

- [ ] Add MLS Kotlin library: port/wrap `awslabs/mls-rs` via Rust FFI (JNI) or use
  `wireapp/core-crypto` (Kotlin Multiplatform MLS bindings — actively maintained,
  used in Wire messenger)
- [ ] Replace `RoomRepository.createRoom()` → `MLSGroupManager.createGroup(myKeyPackage)`:
  returns `(groupId, GroupState)` where `groupState` holds the MLS tree and current epoch
- [ ] `MLSKeyPackage.kt`: generate `KeyPackage` (the MLS equivalent of a prekey bundle):
  `credential` (identity key hash + name), `init_key` (HPKE public key for group addition),
  `lifetime` (7 days), signature (identity key) — stored in DataStore; rotated weekly
- [ ] Room join: joiner sends `KeyPackage` via QR or NFC; host runs `mlsGroup.addMember(keyPackage)`
  → produces `Welcome` message encrypted to joiner; joiner processes `Welcome` → derives epoch key
- [ ] `RoomExchangeService` (Task 10) rewritten: messages encrypted under current MLS epoch key;
  on each card delivery, advance epoch (symmetric ratchet within epoch); DS = QR relay slot
- [ ] Remove member: `mlsGroup.removeMember(memberId)` → `Commit` → epoch rotation; removed
  member cannot decrypt future messages even if they retained the previous epoch key
- [ ] Store MLS group state in encrypted `AppDatabase` column (AES-256-GCM, Keystore-wrapped key)
- [ ] Unit tests: 2-member MLS group creation, 5-member add sequence, member removal + epoch rotation,
  `Welcome` message decryption, past-epoch message rejection

---

## Task 58 — Bluetooth 6.x Channel Sounding Ranging (Android Ranging API)

**Why this complements Task 11/52:** UWB ranging (Task 11) requires UWB silicon — present in
~40% of 2026 smartphones. Bluetooth 6.0 Channel Sounding (BT 6.x CS), introduced in the
May 6, 2026 Bluetooth 6.3 specification, provides ±10cm ranging using Phase-Based Ranging
(PBR) + Round-Trip Time (RTT) — no UWB chip required. Android 15+ exposes both UWB and BLE
Channel Sounding through a unified `android.ranging` Ranging API, allowing AURA to use
whichever proximity technology the device supports.

BT 6.3 (May 2026) adds inline PCT transfer, PHY-specific RTT accuracy, and ACP/C-I limit
relaxation — substantially improving Channel Sounding reliability compared to BT 6.0.

See: [bluetooth.com/learn-about-bluetooth/feature-enhancements/channel-sounding]
See: [developer.android.com/develop/connectivity/uwb] — Android Ranging API covers both UWB and CS
See: [minew.com/bluetooth-6-channel-sounding] — Channel Sounding hardware overview
See: [eetasia.com/secure-ranging-with-bluetooth-channel-sounding] — security applications

**Architecture:** `android.ranging.RangingManager` (Android 15+) is transport-agnostic —
it accepts both `UwbRangingParams` and `BluetoothLeChannelSoundingRangingParams`. AURA's
`UwbRangingManager.kt` (Task 11) should be generalized to a `ProximityRangingManager` that:
1. Prefers UWB if `FEATURE_UWB` present
2. Falls back to BLE Channel Sounding if BT 6.x hardware present (`FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING`)
3. Falls back to RSSI-based proximity estimate (±3m) as a last resort

- [ ] Rename `UwbRangingManager.kt` → `ProximityRangingManager.kt`; add factory method:
  `ProximityRangingManager.create(context)` → returns `UwbRangingSession` or
  `BleChannelSoundingSession` depending on available hardware
- [ ] `BleChannelSoundingSession.kt`:
  - Check `PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING` + `Manifest.permission.BLUETOOTH_RANGING`
  - `RangingManager.startRanging(BluetoothLeChannelSoundingRangingParams)` — BLE CS session
  - Same 5-sample sliding window average as UWB (CS measurements also noisy; ±10-50cm raw)
  - `RangingResult.distanceMeters` → same auto-confirm threshold: < 50cm → skip SAS
- [ ] `ProximityCapability` enum: `UWB | BLE_CHANNEL_SOUNDING | RSSI_ONLY` — exposed to
  `ExchangeFragment` for displaying "Distance: 34cm (UWB)" vs "Distance: ~45cm (BLE CS)"
- [ ] `ExchangeAuditLog.rangingMethod: ProximityCapability?` — log which method was used
- [ ] Unit tests: factory selection logic (UWB present, CS only, neither), threshold auto-confirm,
  5-sample average with CS data, sliding window reset on new session

---

## Task 59 — Oblivious HTTP Relay (RFC 9458)

**Why this replaces the custom ZK relay in Task 55:** The IETF published RFC 9458 (Oblivious
HTTP) in 2023. Cloudflare and Fastly both operate public OHTTP relay infrastructure. Apple uses
OHTTP for Private Cloud Compute requests. OHTTP achieves the same IP unlinkability goal as the
custom hash-keyed relay in Task 55, but via a standardized, formally analyzed protocol supported
by a production CDN — eliminating the need to maintain a custom Cloudflare Worker.

OHTTP architecture: client encrypts request using HPKE (DHKEM-X25519-HKDF-SHA256 + AES-128-GCM)
to the gateway's public key; sends to relay; relay forwards to gateway; gateway decrypts and
forwards to origin server. Relay sees client IP but not request content. Gateway sees content but
not client IP. Neither can link IP to content.

See: [ietf.org/rfc/rfc9458.html] — RFC 9458 Oblivious HTTP
See: [blog.cloudflare.com/stronger-than-a-promise-proving-oblivious-http-privacy-properties] — formal proofs
See: [datatracker.ietf.org/doc/draft-ietf-privacypass-arc-protocol] — Privacy Pass + OHTTP combo

**Architecture:** `OhttpRelayClient.kt` replaces `TorRelayTransport.kt` as the IP-unlinking
relay mode. The AURA QR relay server acts as the OHTTP gateway (exposed endpoint for relay GET/POST
only); Cloudflare acts as the OHTTP relay. Client-side HPKE encapsulation using BouncyCastle
(already in build): `DHKEM(X25519, HKDF-SHA256)` + `AES-128-GCM`.

- [ ] `OhttpRelayClient.kt`:
  - Pin gateway public key in `BuildConfig.OHTTP_GATEWAY_KEY` (32-byte X25519, updated with relay cert)
  - Encapsulate `RelayClient` POST/GET requests using HPKE:
    `encapKey, ct = HPKE.seal(gwPublicKey, info="message/bhttp", aad="", plaintext=httpRequest)`
  - POST encapsulated request to Cloudflare OHTTP relay endpoint (`https://ohttp.cloudflare.com`)
  - Receive OHTTP response; `HPKE.open(encapKey, ct)` → original HTTP response
- [ ] `tools/relay-worker/` OHTTP gateway extension: add `/ohttp-keys` endpoint (returns
  `application/ohttp-keys` — HPKE public key configuration); add `/ohttp-relay` handler
  that decapsulates and processes the inner request
- [ ] Settings → Privacy → Relay Mode: replace custom ZK relay option with `OHTTP (Oblivious HTTP)`
  — more auditable than custom Worker approach; backed by Cloudflare infrastructure
- [ ] Tor mode remains as highest-latency / highest-privacy option (T55 still ships)
- [ ] ProGuard: ensure HPKE-related BouncyCastle classes kept (extends Task 43 rules)
- [ ] Unit tests: HPKE encapsulation/decapsulation round-trip, gateway key pinning, OHTTP
  request formatting per RFC 9458 §6, response decryption

---

## Task 60 — OpenID4VP Verifiable Presentation (W3C / GSMA)

**Why this follows Task 51:** Task 51 issues AURA profiles as W3C VCs. This task wires those VCs
into Android's `Credential Manager` Digital Credentials API so AURA profiles can be *presented*
to websites and enterprise verifiers using OpenID4VP — the same standard used by government mDL
wallets and enterprise ID systems.

OpenID4VP + OpenID4VCI achieved IETF self-certification launch in February 2026. Chrome 141,
Safari 26, and Firefox 149 all ship stable Digital Credentials API support. EUDI (European
Digital Identity) architecture requires OpenID4VP for all member-state wallets. An AURA user
can hand over their AURA card to any business that asks — through the same tap-and-confirm flow
as Google Pay.

See: [android-developers.googleblog.com/2025/04/announcing-android-support-of-digital-credentials.html]
See: [docs.walt.id/concepts/data-exchange-protocols/openid4vp] — OpenID4VP developer guide
See: [digitalcredentials.dev/docs/wallets/android] — Android wallet integration guide
See: [openid.net/specs/openid-4-verifiable-presentations-1_0.html] — OpenID4VP 1.0 spec

- [ ] Implement `DigitalCredentialsProvider` extending `CredentialProvider`:
  - Register in `AndroidManifest.xml` as `android.credentials.DigitalCredentials.HOLDER` service
  - Handle `GetCredentialRequest` containing an OpenID4VP authorization request
  - Show AURA credential picker UI: which VC to present (profile fields to share)
  - Return `DigitalCredential(credentialJson)` — the VP (Verifiable Presentation) response
- [ ] `VPBuilder.kt`: construct W3C VP from stored VCs (Task 51); include `nonce` from
  verifier request; sign with `JsonWebSignature2020`; embed `presentation_submission`
- [ ] Selective disclosure: before presenting, show field-level consent dialog:
  "This site requests: name ✓, email ✓, phone number — tap to toggle sharing"
  Same UI pattern as `ProfileDiffBottomSheet`
- [ ] `OpenID4VCIClient.kt`: receive VC issuance offer (`credential_offer` URI from QR/deeplink);
  exchange authorization code for VC via HTTPS (enterprise employer can issue work credentials
  that AURA stores and presents alongside personal profile)
- [ ] Deeplink handler: `aura://receive-credential?offer=<base64>` → `OpenID4VCIClient.exchange()`
- [ ] Unit tests: VP construction, nonce binding, selective disclosure field masking,
  credential_offer parsing, VCI token exchange mock

---

## Task 61 — mdoc/mDL Profile (ISO 18013-5 / OpenWallet Foundation)

**Why this follows Task 60:** OpenID4VP (Task 60) enables presentation via web. ISO 18013-5
mdoc enables offline physical presentation — NFC tap, QR scan, BLE — the same mechanisms AURA
already uses. Adding mdoc encoding to AURA profiles allows exchanges with mDL readers (TSA
checkpoints, age verification kiosks, enterprise physical access). The `openwallet-foundation/multipaz`
SDK (Apache 2.0) provides a production-grade Android mdoc implementation.

See: [github.com/openwallet-foundation/multipaz] — OpenWallet Foundation mdoc SDK (Kotlin)
See: [android-developers.googleblog.com] — Android Identity Credential API (Android 11+)
See: [dock.io/post/iso-18013-5] — ISO 18013-5 explainer

**Architecture:** An AURA mdoc (`docType = "com.showerideas.aura.1"`) extends the mdoc container
with AURA-specific namespaces. Presentation uses Android's existing `IdentityCredentialStore` or
the `multipaz` library's `DocumentStore`. The `DeviceEngagement` can be via AURA's existing NFC
HCE (Task 1) or a QR code — reusing infrastructure already built.

`openwallet-foundation/multipaz` (`io.github.openwallet-foundation:multipaz`) is a pure Kotlin
library targeting Android and Desktop — directly compatible with AURA's KMP module structure.

- [ ] Add `multipaz` dependency: `io.github.openwallet-foundation:multipaz-android`
- [ ] `AuraDocument.kt`: define mdoc namespace `"com.showerideas.aura.1"` with fields
  mapping to `ContactProfile`: `given_name`, `family_name`, `email`, `phone_number`,
  `organization`, `aura_identity_key_hash` (32-byte, non-PII proof of identity continuity)
- [ ] `DocumentStore` backed by Android Keystore-encrypted Room DB column — reuses Task 8 DB
- [ ] `DeviceEngagement`: QR code encodes `DeviceEngagement` per ISO 18013-5 §8; NFC HCE
  `AuraHceService` (Task 1) extended: SELECT AID `A0 00 00 02 48 00` (ISO 18013-5 AID)
  triggers mdoc presentation flow alongside existing AURA exchange AID
- [ ] Selective disclosure: before any mdoc presentation, show field picker (same UX as Task 60)
- [ ] `mdoc.sign()`: sign `DeviceAuth` with existing P-256 identity key (ISO 18013-5 §9.1.3)
- [ ] Unit tests: mdoc encoding/decoding, selective disclosure, DeviceEngagement parsing,
  `aura_identity_key_hash` field verification

---

## Task 62 — QUIC/HTTP3 Transport for QR Relay (Cronet)

**Why now:** AURA's `RelayClient.kt` uses OkHttp over TLS 1.3. QUIC (RFC 9000) + HTTP/3 provides
two critical improvements for relay transport: connection migration (exchange session survives
WiFi→LTE handoff without re-establishing the relay session) and 0-RTT resumption (returning
session latency drops by ~150ms). Google's `cronet-transport-for-okhttp` library adds QUIC
support to any OkHttp client with two lines of code. The relay server must support HTTP/3;
Cloudflare Workers support QUIC natively.

See: [github.com/google/cronet-transport-for-okhttp] — official library, actively maintained
See: [calmops.com/network/http3-quic-protocol-complete-guide] — HTTP/3 QUIC guide 2026

**Connection migration detail:** QUIC uses Connection IDs instead of IP:port tuples to identify
connections. When the client's IP changes (WiFi → LTE), the QUIC connection survives via the
Connection ID — no relay session restart required. Critical for mobile exchange: a user walking
away from a WiFi network during an exchange does not lose the relay session.

- [ ] Add `com.google.net.cronet:cronet-okhttp` + `org.chromium.net:cronet-api` dependencies
  (Play Services Cronet provider — zero APK size cost; platform delivers the binary)
- [ ] `CronetRelayInterceptor.kt`: application interceptor wrapping `RelayClient`'s `OkHttpClient`
  with Cronet transport: `OkHttpClient.Builder().addInterceptor(CronetInterceptor(cronetEngine))`
- [ ] `CronetEngine` configuration:
  - Enable QUIC: `enableQuic(true).addQuicHint(relayHost, 443, 443)`
  - Enable HTTP/2: `enableHttp2(true)`
  - HTTP cache: 5 MB on-disk cache for relay server capability discovery (`alt-svc` header)
  - 0-RTT: `enableBrotli(true)` + QUIC 0-RTT session resumption for returning relay connections
- [ ] Fallback: if Cronet engine init fails (device has no Play Services), fall back to standard
  `OkHttpClient` — no behavior change for FOSS variant
- [ ] FOSS flavor: replace Play Services Cronet with `org.chromium.net:cronet-embedded` (bundles
  Chromium networking stack — adds ~5 MB; gate behind FOSS variant APK size gate)
- [ ] Unit tests: Cronet interceptor integration (mock Cronet engine), QUIC hint configuration,
  0-RTT session tag propagation, fallback to OkHttp on Cronet init failure

---

## Task 63 — Sparse Post-Quantum Ratchet (SPQR) for Room Sessions

**Why now (upgraded from R&D-L):** Signal shipped SPQR in October 2025, released
`signalapp/SparsePostQuantumRatchet` as Apache 2.0 open source. The library implements the
Triple Ratchet: a KEM-based ML-KEM ratchet running in parallel with the classical X25519 DH
ratchet, with outputs XOR-combined to produce the final message key. Unlike the symmetric
ratchet already in AURA (Task 41), SPQR provides break-in recovery — a compromised message
key doesn't expose future messages. The Eurocrypt 2025 formal analysis confirms the design.

AURA's Room sessions (Tasks 8–10) are multi-turn: host sends multiple card delivery messages to
multiple recipients, with ACKs flowing back. This is exactly the multi-turn scenario where SPQR
adds value. Single bilateral exchanges are too short to benefit.

See: [github.com/signalapp/SparsePostQuantumRatchet] — Apache 2.0, Rust implementation
See: [signal.org/blog/spqr] — Signal's SPQR announcement
See: [blog.quarkslab.com/triple-threat-signals-ratchet-goes-post-quantum.html] — technical analysis
See: [csrc.nist.gov/csrc/media/events/2025/sixth-pqc-standardization-conference] — NIST PQC conference presentation

**Architecture:** `signalapp/SparsePostQuantumRatchet` is Rust. Integrate via JNI (Android NDK)
or use the Kotlin/JVM reimplementation pattern (SPQR is only ~1500 lines of Rust — portability
to Kotlin is feasible). SPQR KEM: ML-KEM-768 (already in BouncyCastle build). SPQR state is
small: one ML-KEM key pair + ratchet counter + last 50 received KEM ciphertexts (dedup window).

- [ ] `SpqrState.kt`: Kotlin port of SPQR core:
  - Sender: generate `(mlKemPk, mlKemSk)` pair; encapsulate on every Nth message (N=50 = "sparse");
    `kemCt` included in message header; advance KEM ratchet output
  - Receiver: if message contains `kemCt`, decapsulate with current `mlKemSk`; advance KEM chain
  - Combine: `finalKey = KDF(dhRatchetKey XOR kemRatchetKey)` — SPQR hybrid
- [ ] `SpqrState` stored alongside `DoubleRatchetState` (Task 41) in Room session context:
  `RoomExchangeSession(doubleRatchet: DoubleRatchetState, spqr: SpqrState)`
- [ ] `RoomExchangeService` (Task 10): use `SpqrState.nextMessageKey()` for all Room session
  message encryption; include `kemCt: ByteArray?` in message header TLV (null on non-KEM messages)
- [ ] Sparse frequency: KEM encapsulation every 50 messages (same as Signal SPQR default);
  configurable via `BuildConfig.SPQR_KEM_INTERVAL`
- [ ] Unit tests: KEM send/receive sync, sparse interval boundary, dedup window (same CT twice
  = rejected), hybrid key derivation matches between sender and receiver, KEM state serialization

---

## Task 64 — Continuous Behavioral Authentication Layer

**Why now:** All prior gesture work (Tasks 12–14, 27) is point-in-time authentication — the
gesture gate fires once at exchange start. Continuous behavioral authentication provides a
background passive signal that detects session takeover between exchanges: if someone snatches
the device after authentication, their motion patterns differ from the enrolled user and the
session is flagged. This is achievable with 100% on-device ML using only the accelerometer +
gyroscope — no camera required, no privacy concern.

See: [ncbi.nlm.nih.gov/pmc/articles/PMC11769222] — real-time continual learning for gait
See: [arxiv.org/pdf/2001.08578] — behavioral biometrics survey (140+ approaches)
See: [abuhamad.cs.luc.edu/pub/Sensor-Based_Continuous_Authentication] — sensor-based auth survey
See: [twosense.ai/blog/exploring-behavior-as-a-biometric] — enterprise continuous auth

**Architecture:** `ContinuousAuthMonitor.kt` runs as a bound service during active AURA sessions.
It reads 50Hz accelerometer + gyroscope data and computes 5-second window feature vectors:
mean, variance, zero-crossing rate, and dominant frequency across 3 axes (12 features total).
A lightweight MLP (2 hidden layers × 32 units each, ~5KB model) trained at enrollment time
classifies each window as `OWNER | UNKNOWN`. Confidence score drops below threshold after
N consecutive UNKNOWN windows → silent re-auth prompt.

- [ ] `ContinuousAuthMonitor.kt`: `BoundService`; lifecycle: starts on exchange session open,
  stops on session close; reads `TYPE_ACCELEROMETER` + `TYPE_GYROSCOPE` at 50Hz via
  `SensorManager.registerListener(SENSOR_DELAY_GAME)`
- [ ] Feature extraction (5-second rolling window, 50% overlap):
  - Per-axis: mean, variance, zero-crossing rate, peak count, spectral peak frequency
  - Magnitude: `sqrt(ax² + ay² + az²)` — same features for resultant vector
  - Total: 36 features (6 features × 3 axes × 2 sensors)
- [ ] Enrollment: capture 30 seconds of normal device-holding IMU data during gesture enrollment;
  train on-device MLP using Android ML Kit's `NNAPIDelegate` or TFLite via `OnDevicePersonalization`
  framework; model stored in `filesDir/behavioral/model.tflite` (excluded from backup)
- [ ] `AuthSessionManager`: if `ContinuousAuthMonitor.confidence` drops below `0.6f` for 10
  consecutive 5-second windows (50 seconds), emit `SessionEvent.BehavioralAnomalyDetected`
  → `ExchangeFragment` shows re-auth prompt (silent: no explanation to potential attacker)
- [ ] Enterprise MDM key: `require_continuous_auth: Boolean` in `app_restrictions.xml` — default
  off; enterprise can enforce for high-security environments
- [ ] Privacy: IMU data never leaves device; model is purely local; `ExchangeAuditLog` records
  `behavioralConfidence: Float` at session end — aggregated score, not raw sensor data
- [ ] Unit tests: feature extraction correctness, MLP inference latency < 5ms on Snapdragon 730G,
  anomaly detection threshold, service lifecycle (no leak on session close)



## Task 65 — Android 16 Advanced Protection API Hardening

**Why here:** Android 16 adds `AdvancedProtectionManager` — a system API indicating the user
has enrolled in Google's Advanced Protection Program. When active, the device blocks unknown-source
installs, restricts USB data, and enables the strongest theft protection. Third-party apps can
query `AdvancedProtectionManager.isAdvancedProtectionEnabled()` (API 36) and apply their own
security hardening in response. This is a zero-cost security upgrade: AURA reads one API and
automatically enables stricter defaults for users who have already opted into maximum security
at the OS level.

**Architecture:** On `AuraApplication.onCreate()`, check `AdvancedProtectionManager.isAdvancedProtectionEnabled()`.
If `true`: apply a stricter `AuraSecurityProfile` — force `SAS_REQUIRED = true`, reduce
`MAX_GESTURE_ATTEMPTS` from 3 to 2, disable QR relay (server-assisted path leaks metadata),
and disable LoRa (unverified underlay channel). These stack on top of MDM policy but yield to
explicit MDM keys — most restrictive setting always wins.

See: [blog.google/security/whats-new-in-android-security-privacy-2026/] — Advanced Protection
API announcement and `AdvancedProtectionManager` class entry in API 36 reference.

- [ ] `AdvancedProtectionIntegration.kt` in `security/`:
  - `isEnabled(): Boolean` — `AdvancedProtectionManager.isAdvancedProtectionEnabled()` on API 36+;
    returns `false` on older API levels (never assume enabled)
  - `applyHardening(config: EnterpriseConfig): AuraSecurityProfile` — merged profile:
    Advanced Protection overrides → MDM policy → user preferences (priority order)
  - `AuraSecurityProfile`: `maxGestureAttempts: Int`, `sasRequired: Boolean`,
    `qrRelayEnabled: Boolean`, `loraEnabled: Boolean`, `allowedTransports: Set<TransportType>`
- [ ] `AuraApplication.onCreate()`: initialize `AdvancedProtectionIntegration`; store result in
  `AppSecurityState` singleton; inject via Hilt into all services and ViewModels
- [ ] `NearbyExchangeService`: respect `AppSecurityState.allowedTransports` — silently skip
  disabled transports without surfacing error to user
- [ ] Settings → Security: non-dismissible "Advanced Protection Active" banner when AP detected;
  explains which fallback transports are disabled
- [ ] Advanced Protection + MDM intersection: `allowed_transports = [NFC, BLE, QR]` (MDM) + AP active
  → result is `{NFC, BLE}` (QR removed by AP); document this intersection rule in `docs/SECURITY.md`
- [ ] `WorkManager` one-time task: re-evaluate `AppSecurityState` when Advanced Protection state changes
- [ ] Unit tests:
  - `applyHardening()` with AP enabled: `maxGestureAttempts = 2`, `qrRelayEnabled = false`
  - MDM `max_gesture_attempts = 1` + AP enabled: result is `1` (MDM stricter)
  - MDM `allowed_transports = [NFC, BLE, QR]` + AP enabled: result excludes QR
  - API < 36: `isEnabled()` returns `false` without exception

---

## Task 66 — BLE 6.2 Shorter Connection Interval Optimization

**Why here:** Bluetooth Core Specification 6.2 (November 2025) introduces the Shorter Connection
Interval (SCI) feature — BLE LE connections can now negotiate intervals down to 375 µs (compared
to the 30 ms minimum in BLE 5.x). This is 80× faster, dramatically reducing GATT round-trip
latency for `BleGattTransport` (Task 7). An AURA BLE GATT exchange currently takes 800–1200 ms
on BLE 5.x. With SCI on BLE 6.2-capable device pairs, the target is under 200 ms — making BLE
competitive with NFC for exchange speed. Pixel 9 series and Galaxy S25 series both ship with
BLE 6.2-capable Bluetooth chipsets.

**Architecture:** SCI is negotiated via `LE Connection Parameters Request`. Android 16+ exposes
this via an extended `requestConnectionPriority()` constant. Both devices must support
`LE_SCI_SUPPORTED` in their controller feature mask. Check `BluetoothDevice.getSupportedFeatures()`
before attempting negotiation; silently fall back to `CONNECTION_PRIORITY_HIGH` on older devices.

See: [argenox.com/blog/bluetooth-6-2-vs-6-1-deep-dive-into-the-latest-bluetooth-updates] — SCI
feature specification, negotiation procedure, and device compatibility matrix.

See: [NordicSemiconductor/Android-BLE-Library] — Nordic BLE library; `CONNECTION_PRIORITY_DCK`
constant for ultra-low-latency mode negotiation on Android 16+.

- [ ] `BleGattTransport.kt`: add `attemptSciNegotiation(gatt: BluetoothGatt)`:
  - Check `Build.VERSION.SDK_INT >= 36` AND `gatt.device.getSupportedFeatures().contains(FEATURE_LE_SCI)`
  - If both true: call `gatt.requestConnectionPriority(CONNECTION_PRIORITY_DCK)` after `onMtuChanged()` —
    SCI must be negotiated AFTER MTU to avoid parameter collision
  - Log result to `BleSessionMetrics.connectionIntervalMs` for performance monitoring
- [ ] `BleSessionMetrics` data class: `mtu: Int`, `connectionIntervalMs: Float`, `sci: Boolean`,
  `totalExchangeDurationMs: Long` — stored in `ExchangeAuditLog.bleMetrics: String?` as JSON blob
- [ ] Fallback: SCI negotiation failure → silently fall back to `CONNECTION_PRIORITY_HIGH`
  (11.25–15 ms interval, same as current behavior); no user-visible error
- [ ] Compatibility matrix in code comment: BLE 6.2+BLE 6.2 = full SCI; BLE 6.2+BLE 5.x =
  negotiate BLE 5.x params; BLE 5.x+BLE 5.x = existing behavior unchanged
- [ ] CI benchmark: `BenchmarkRule` measuring BLE GATT full exchange round-trip on emulator;
  fail if regression from 3 s baseline (SCI improvement not measurable in emulator but regressions are)
- [ ] Unit tests:
  - `attemptSciNegotiation()` called after `onMtuChanged()`, never before
  - API < 36: `attemptSciNegotiation()` no-ops silently without throwing
  - `BleSessionMetrics.sci = true` only when FEATURE_LE_SCI confirmed on both sides
  - `BleSessionMetrics` serialized to valid JSON and round-trips correctly

---

## Tasks in Research / Design Phase

The following are design-tracked. Each has an explicit trigger condition that moves it from
`[R&D]` to a scheduled implementation task. Items A–K are enriched with full architecture and
prototype steps. Items L–V cover new research directions.

---

### R&D-A — QR Relay Anonymisation (Tor + Zero-Knowledge Relay)

**Goal:** The QR relay transport leaks sender and receiver IP addresses to the relay server.
This task adds two complementary anonymisation layers: Tor routing (hides IP) and a ZK-blind
relay scheme (hides request patterns from the relay server itself).

**ZK blind relay design:** Client posts `H(sessionId)` as slot identifier. Server stores
ciphertext keyed by hash. Receiver queries by hash. Server never sees the sessionId pre-image
and cannot link sender to receiver. Both queries go via Tor — IPs are hidden independently.

See: [github.com/guardianproject/tor-android] — Android Tor integration via Orbot or embedded tor JNI.
See: [github.com/guardianproject/netcipher] — `StrongConnectionBuilder.forMaxSecurity()` wraps
any `OkHttp` client with Tor SOCKS5 proxy transparently.
See: [github.com/briarproject/onionwrapper] — Kotlin Tor wrapper (~400 KB); lighter than full Orbot embed.

- [R&D] Evaluate `guardianproject/netcipher` + embedded Orbot for relay anonymisation:
  - APK size increase: Tor binary ~8 MB uncompressed; measure against APK size gate
  - First-connection latency: Tor circuit establishment 2–5 s; assess UX acceptability
  - Evaluate `briarproject/onionwrapper` as a lighter alternative (Kotlin, ~400 KB)
- [R&D] ZK blind relay server: prototype `tools/relay-worker/zk-blind-relay.js` for Cloudflare Workers:
  - POST `/slot` with `{ hash: H(sessionId), ciphertext }` — no sessionId in plaintext
  - GET `/slot/:hash` — returns ciphertext; server logs hash + timestamp only, not IP pair
  - TTL: 60 s; auto-delete slot after first retrieval
- [R&D] Timing correlation risk: Cloudflare Workers can correlate `hash` + POST timestamp + GET timestamp;
  assess whether Private Information Retrieval (PIR) is needed for highest-threat users
- Trigger: implement ZK blind relay (zero latency overhead) as default; implement Tor path if
  >15% of users indicate demand for full IP anonymity

---

### R&D-B — Remote Blocklist via Transparency Log

**Goal:** Privacy-preserving, opt-in blocklist using an append-only Merkle log. Users flag
abusive identity keys. AURA clients query the log without revealing which keys they are checking.

**Architecture:**
1. Submission: user flags key → AURA hashes `SHA256(keyHash || devicePepper)` — pepper unique
   per device and never transmitted; prevents correlation of submissions across devices
2. Log server (Trillian or Sigsum): appends `H(keyHash || pepper)` to Merkle log; issues SCT
3. Query: client submits `H(keyHash || ownPepper)` — server returns inclusion proof without
   seeing the pre-image; different peppers per device prevent cross-device query correlation
4. `TransparencyLogClient (security/)` already has Merkle verification — reusable

See: [github.com/google/trillian] — Trillian transparent, append-only Merkle log. Self-hostable.
See: [sigsum.org] — minimal privacy-focused Merkle log; stronger privacy design; open infrastructure.
See: Signal contact discovery blog — PSI (Private Set Intersection) for query-pattern privacy.

- [R&D] Threat model: define acceptable leakage bound before design approval:
  - What does log server learn from submissions? From queries? Across sessions?
  - Is device-pepper sufficient, or is DH-PSI-based querying required?
- [R&D] Evaluate Trillian vs Sigsum: Sigsum has stronger privacy (witness cosigning, no centralized state);
  Trillian has broader tooling; both are self-hostable
- [R&D] Prototype PSI-based query: DH-PSI prevents server from learning even hashed key queries;
  benchmark query latency for N=1000 keys on Snapdragon 8 Gen 2 target: < 100 ms
- Trigger: only after external cryptographic review of the query protocol privacy properties; never
  ship without a published security audit

---

### R&D-C — Contact Graph PSI (Private Set Intersection)

**Goal:** Detect mutual AURA contacts between two users without either party revealing their
full contact list to the other or to any server. "Do we know Alice?" answered as a boolean.

**Architecture:** DH-PSI using existing P-256 curve:
1. Alice hashes her identity key set with random blinding factor `r`: `H(id)^r` for each contact
2. Exchange blinded sets
3. Each party unblind the other's set: `(H(id)^r)^(1/r) = H(id)` for Alice's set
4. Intersection = entries appearing in both unblinded sets
Neither party learns anything about non-intersecting entries.

See: [signal.org/blog/private-contact-discovery-service-2] — Signal's PSI-based discovery.
See: Kales et al., "Private Contact Discovery from the Strong DH Assumption" (2019).
See: [arxiv.org/pdf/2011.09350] — Asymmetric PSI with contact tracing applications.

- [R&D] Prototype `PSIEngine.kt` with `blindSet()`, `unblind()`, `intersect(count-only)`:
  - DH-PSI scales O(N) multiplications per side; benchmark N=50, N=500, N=5000 contacts
  - On Snapdragon 8 Gen 2: estimate 10–50 ms for N=500 — acceptable for optional pre-exchange step
- [R&D] UI: "Mutual AURA contacts" indicator in SAS dialog — shows count only (never names)
  when both parties opt in; disabled by default; toggle in Settings → Privacy
- [R&D] Network density analysis: PSI useful primarily at N>200 average contacts;
  assess actual AURA network density before investing implementation effort
- Trigger: implement when average active user has > 200 AURA contacts (network density threshold)

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

### R&D-E — AI Gesture Coaching (GestureCoach)

**Goal:** Help users improve gesture consistency post-enrollment. The LSTM model already computes
per-frame landmark positions; post-enrollment analysis of enrollment variance identifies which
joints drift most and overlays coaching guidance on the camera preview.

**Architecture:**
1. **Enrollment analysis**: after 5 samples, compute per-landmark σ across all 5 samples →
   identify top 3 highest-variance landmarks
2. **Real-time overlay** (opt-in): during capture window, color-coded rings on MediaPipe landmarks:
   green (σ < 0.02 = stable), amber (0.02–0.05 = variable), red (> 0.05 = noisy)

See: [github.com/kinivi/hand-gesture-recognition-mediapipe] — per-landmark variance visualization.
See: [arXiv:1309.0073 — SilentSense] — behavioral biometrics consistency analysis.

- [R&D] `GestureCoach.kt`:
  - `analyzeEnrollment(samples: List<LandmarkVector>): CoachingReport`
    - `highVarianceLandmarks: List<Int>` (MediaPipe indices 0–20 with highest σ)
    - `stabilityScore: Float` — mean pairwise cosine similarity (0–1)
    - `suggestion: String` — localized coaching hint: "Keep your wrist steadier"
  - `overlayCoaching(canvas: Canvas, landmarks: LandmarkList, report: CoachingReport)`
- [R&D] A/B test via `GestureClassifierABTest.kt`: Group A = coaching overlay enabled;
  Group B = no coaching; measure 30-day auth failure rate difference; target > 5% reduction
- Trigger: schedule after gesture library (Task 27) has 6 months of real-world usage data;
  only ship if A/B test confirms > 5% auth failure rate reduction

---

### R&D-F — Wearable Biometric Fusion (HRV Second Factor)

**Goal:** Use HRV from Wear OS as a passive second factor.
`finalScore = 0.7 × gestureScore + 0.3 × hrvScore`. HRV proves the body is present NOW —
cannot be spoofed by replaying a gesture video.

**Architecture:**
1. During exchange, `WearPhoneBridge` requests HRV reading via `ChannelClient`
2. Watch: `HealthServices.passiveListenerForEvent(HEART_RATE_BPM)` → compute RMSSD over 60 s
3. Phone: receive `rmssdValue: Float` → Z-score vs enrolled baseline:
   `|rmssd - mean| / std < 2.5` → HRV verified
4. Degrade gracefully when watch not paired: HRV factor = 0, gesture score = 100%

See: [arXiv:1309.0073 — SilentSense] — behavioral biometric fusion combining motion + HRV.
See: [github.com/fmeum/WearAuthn] — Wear OS FIDO2; `ChannelClient` auth signal pattern.
See: PMC4541821 — HRV RMSSD-based identity verification precision/recall analysis.

- [R&D] Literature review: target > 92% accuracy at Z-score threshold 2.5; assess intra-user
  day-to-day HRV variability (stress, caffeine, sleep affect RMSSD); determine enrollment period
- [R&D] Privacy model: HRV baseline stored locally only, encrypted with Keystore key tied to
  strong biometric; never transmitted in any wire protocol message
- [R&D] Enrollment: 7-day baseline collection; compute `mean_rmssd`, `std_rmssd`; store encrypted
- Trigger: only if HRV uniqueness confirmed > 92% accuracy in ≥ 50-participant controlled study

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

### R&D-I — App Shortcuts (Launcher Long-Press Integration)

**Goal:** Expose "Start Exchange", "My QR Code", "Exchange with [last contact]", and "Join Active Room"
as home screen shortcuts via long-press on the AURA launcher icon.

See: [developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts]

- [R&D] `res/xml/shortcuts.xml`: static shortcuts 1 ("Start Exchange") and 2 ("My Card") with
  localized labels via `@string/` references; icons from `mipmap/ic_launcher`
- [R&D] `ShortcutManager.updateShortcuts()` after each exchange: dynamic shortcut 3 = "Exchange with [name]"
  via `ShortcutManagerCompat.pushDynamicShortcut()`; limit 4 total shortcuts
- [R&D] Dynamic shortcut 4: "Join Room" shown only during active Room session within TTL
- [R&D] Pinned shortcuts: Settings → Shortcuts → "Pin to home screen" via `requestPinShortcut()`
- Trigger: low-effort polish item; implement in any low-velocity sprint; no dependencies

---

### R&D-J — Predictive Back Gesture (Android 14+ Compliance)

**Goal:** Android 14+ requires `android:enableOnBackInvokedCallback="true"` in manifest.
AURA's SAS dialog back-block (Task 20) must use `OnBackInvokedCallback`, not deprecated `onBackPressed()`.

See: [developer.android.com/guide/navigation/custom-back/predictive-back-gesture]

- [R&D] Audit: all `Fragment.onBackPressed()` overrides → replace with
  `requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)`
- [R&D] API 34+: register `OnBackInvokedDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, ...)`
  for fragments that need to intercept the predictive swipe animation itself (not just the final action)
- [R&D] `SasVerifierDialog`: must use `onBackPressedDispatcher.addCallback(enabled = true)` during
  countdown — NOT `Dialog.setOnCancelListener`
- [R&D] `android:enableOnBackInvokedCallback="true"` in `AndroidManifest.xml` `<application>` tag
- Trigger: required for API 36 compliance; implement as part of Task 45 work

---

### R&D-K — Android Health Connect Full Integration

**Goal:** Android Health Connect (GA since Android 14) provides a unified, permissioned health
data store. Non-Wear-OS path to HRV data (R&D-F) — aggregates readings from Fitbit, Garmin,
Samsung Health across any connected wearable.

**Key feasibility questions:**
1. Freshness: `HeartRateVariabilityRmssdRecord` — typical lag from measurement to Health Connect availability?
2. Permission: `READ_HEART_RATE_VARIABILITY_RMSSD` — user grant rate and permission UX friction?
3. Device availability: what fraction of AURA users have a wearable syncing HRV to Health Connect?

See: [developer.android.com/health-and-fitness/health-connect/data-types] — `HeartRateVariabilityRmssdRecord`.
See: [developer.android.com/health-and-fitness/health-connect/get-started] — permission model.

- [R&D] Prototype `HealthConnectHrvReader.kt`:
  - `readRecentHrv(windowMs: Long = 60_000): HrvReading?` — latest RMSSD record within `windowMs`
  - Check `HealthConnectClient.isAvailable()` first; not installed on all devices
  - Return `HrvReading(rmssd: Float, measuredAt: Instant, source: String)`
- [R&D] Freshness benchmark: Pixel 8 + Fitbit Charge 6 — measure median and 95th-percentile lag
  from wrist measurement to Health Connect read
- [R&D] If freshness > 120 s median: Health Connect NOT viable for real-time auth; document;
  Wear OS direct `HealthServices` path (R&D-F) remains the only viable HRV auth path
- Trigger: only invest if R&D-F HRV uniqueness study is positive AND freshness benchmark < 120 s

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

---




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

---

*Last updated: 2026-05-26 — Full roadmap rewrite. Removed Tasks 1–44 (all shipped to v3.3.0).
Retained Tasks 45–64 from prior research expansion. Added Tasks 65–66 (Advanced Protection API,
BLE 6.2 SCI). Massively enriched R&D section A–N with full architecture, research citations,
and prototype steps. Extended R&D to O–X with DIDComm v2, Satellite DTC, Contact Picker,
FIDO2 Credential Provider, HW Security Key NFC, ZK-SNARK Gesture, MPC Threshold Signing,
Android XR, Privacy Pass, and Kotlin 2.2 Swift Export.*
