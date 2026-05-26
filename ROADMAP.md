# AURA — Engineering Roadmap

> Canonical planning document for the AURA gesture-biometric contact exchange system.
> Written as a strictly ordered sequence of engineering tasks for a software team.
> Tasks are sorted by dependency: each task may depend on those above it.
> References are cited inline where they are relevant — there is no separate reference table.
> Last rewrite: 2026-05-26 | Sprint complete: 2026-05-26 | Tasks 1–44 fully shipped.

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
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, WireProtocol.swift, MultipeerTransport.swift, 15 tests |
| Wear OS | Pairing flow — WearPairingViewModel + BottomSheet + PhoneWearSender; wearos/ module production-complete |
| Android Auto | Voice action + biometric auth gate; full screen library (Advertising/Completed/Idle/Sas) |
| Room sessions | Multi-party card exchange — star topology, 10-min TTL, delivery ACK |
| Mesh | Store-and-forward (BLE bloom filter) + multi-hop Wi-Fi Direct mesh (5 hops) |
| Analytics | On-device exchange analytics — transport breakdown, heatmap, PDF export |
| Enterprise | 6 MDM restriction keys + zero-touch enrollment + signed audit export |
| Desktop | KMP desktop companion — QR relay transport |

---

## Task 45 — SPQR: Sparse Post-Quantum Ratchet

**Why this is first:** AURA already ships a session-level post-quantum KEM (ML-KEM-768 + X25519,
Task 33 complete). That protects the session key from harvest-now-decrypt-later attacks on the
initial key exchange. But once a session key is established, all messages within the session
are encrypted with derived keys from the symmetric `DoubleRatchetState` — which is classical-only.
SPQR adds a third, PQ ratchet that advances in parallel with the Double Ratchet, providing
post-quantum forward secrecy and post-compromise security for every message in the session.
Signal shipped SPQR (Sparse Post-Quantum Ratchet) on October 2, 2025 — it is the current state
of the art and the correct reference implementation.

**Architecture:** SPQR operates as a third ratchet running in parallel with the existing
Double Ratchet (`DoubleRatchetState`). The key innovation is chunked transmission: an ML-KEM-768
public key (1184 bytes) is too large to fit in a single message header without disrupting the
transport. SPQR uses Reed-Solomon erasure coding to split the key material into N chunks, then
embeds one chunk per outgoing message. The receiver accumulates chunks until reconstruction
is possible — as long as any N-of-M chunks arrive (where M > N due to redundancy), the key
is recovered. This makes SPQR resilient to dropped messages, which is common on BLE/Nearby
transports. Both parties ratchet simultaneously: sender includes ML-KEM public key chunks in
headers; receiver accumulates and encapsulates; result feeds into the existing KDF chain.

See: [github.com/signalapp/SparsePostQuantumRatchet] — official Rust implementation of SPQR.
The chunking API, erasure code parameters, and KDF integration are all specified here.
Port the core chunking logic to Kotlin — the mathematical structure is language-agnostic.

See: [signal.org/blog/spqr/] — Signal's design rationale. Key constraint: no new round-trips
allowed. All PQ material must be piggybacked on application messages via header fields.

See: NIST PQC Standardization Conference 2025 presentation "Post-Quantum Ratcheting for Signal"
(csrc.nist.gov) — formal security proofs, chunking design challenges, ML-KEM adaptation.

See: [pqshield.com/diving-into-signals-new-pq-protocol/] — PQShield's analysis of the protocol
structure, security properties (forward secrecy + post-compromise security), and ratchet lifecycle.

- [ ] Add `SpqrRatchet.kt` in `crypto/`:
  - State: `mlKemKeyPair: MLKEMKeyPair`, `outboundChunks: List<ByteArray>`, `inboundChunks: Map<Int, ByteArray>`,
    `reconstructedSecret: ByteArray?`, `generation: Int`
  - `initSender()`: generate fresh ML-KEM-768 key pair; split public key using Reed-Solomon(N=8, M=12)
    into 12 chunks of ~100 bytes each; store in `outboundChunks`
  - `initReceiver()`: prepare `inboundChunks` accumulation map, awaiting sender's public key
  - `nextOutboundChunk(): ByteArray?`: returns next unsent chunk (index-prefixed 2-byte seq + payload);
    returns null when all chunks sent — caller must advance to next generation
  - `receiveChunk(index: Int, data: ByteArray)`: accumulate; if `inboundChunks.size >= N` → reconstruct
    public key using Reed-Solomon decoding → encapsulate → `SpqrEvent.KeyEstablished(encapsulatedSecret)`
  - `advanceGeneration()`: zero-fill current state, increment `generation`, re-init for next cycle
- [ ] Integrate `SpqrRatchet` into `NearbyExchangeService` wire protocol:
  - Each outbound `WireProtocol` frame header: include `SPQR_CHUNK` TLV field (2-byte seq + chunk data)
    alongside existing `DoubleRatchetState` key material — one chunk per frame, no extra round-trips
  - Receiver: extract `SPQR_CHUNK` TLV → pass to `SpqrRatchet.receiveChunk()` asynchronously
  - On `SpqrEvent.KeyEstablished`: combine with current `DoubleRatchetState.chainKey` via
    `newChainKey = HKDF(spqrSecret || existingChainKey, info="aura-spqr-v1")` — feeds PQ entropy
    into the symmetric ratchet chain from that point forward
- [ ] Reed-Solomon library: use `com.backblaze:erasure-coding:1.0.2` or implement GF(256) RS
  directly — AURA's RS parameters: N=8 data shards, M=4 parity shards (12 total); any 8 of 12
  sufficient for reconstruction
- [ ] `WireProtocol` v9: add `SPQR_CHUNK` optional TLV (tag `0x10`); v8 receivers silently ignore
  unknown TLV tags — backward-compatible; advertise v9 in `WireProtocolNegotiator`
- [ ] Generation lifecycle: after all 12 chunks of one generation are sent AND the receiver has
  acknowledged encapsulation (via `SPQR_ACK` TLV), both sides call `advanceGeneration()` —
  prevents chunk accumulation growing unbounded across long sessions
- [ ] Zero-fill on session teardown: `Arrays.fill(mlKemKeyPair.privateKey, 0)` in `SpqrRatchet.clear()`
- [ ] iOS: port `SpqrRatchet.swift` to `ios/Sources/AuraCore/` — same chunking parameters,
  same `HKDF` integration; add 8 unit tests covering chunk drop scenarios
- [ ] Unit tests:
  - Full 12-chunk round-trip: sender produces 12 chunks, receiver accumulates all 12, key established
  - Dropout resilience: drop chunks 3, 7, 9 — receiver still reconstructs from remaining 9 ≥ 8
  - Generation advance: generation N key does not influence generation N+1 key material (independence)
  - KDF integration: `newChainKey` differs from `existingChainKey` after SPQR injection
  - Zero-fill: post-teardown `mlKemKeyPair.privateKey` is all zeros

---

## Task 46 — W3C Verifiable Credentials Export

**Why here:** AURA contact cards are currently vCard 3.0 documents. The emerging standard for
machine-verifiable, cryptographically-signed identity claims is W3C Verifiable Credentials (VC)
with JSON-LD encoding. With the W3C advancing DIDs v1.1 in 2026 and Google rolling out Aadhaar VCs
in Google Wallet (April 2026), the VC ecosystem is hitting mainstream. AURA is uniquely positioned:
the user already has an Ed25519/P-256 identity key — signing a VC is a 3-line operation. This task
exports AURA contact cards as VCs so they can be consumed by any VC-aware verifier, wallet, or
enterprise directory without requiring AURA on both ends.

**Architecture:** A VC wraps the existing `ContactProfile` fields in a JSON-LD `credentialSubject`
object, signs it with the user's Ed25519 identity key (already in the keystore), and attaches a
`proof` block with `jws` (JSON Web Signature, compact serialization). The DID of the issuer is
`did:key:z<multibase58(ed25519PubKey)>` — no blockchain, no resolver needed. Verification is
self-contained: anyone with the VC can derive the public key from the DID and verify the signature.

See: [w3.org/TR/vc-data-model-2.0/] — W3C Verifiable Credentials Data Model 2.0 (Candidate
Recommendation 2024). Key fields: `@context`, `type`, `issuer`, `issuanceDate`, `credentialSubject`, `proof`.

See: [w3c-ccg.github.io/did-method-key] — `did:key` method. No registration, no blockchain.
Derive DID from public key bytes, encode as multibase58btc. Verification is purely local.

See: [github.com/decentralized-identity/did-jwt-vc] — reference VC library (JS/TS). Study
JSON-LD context, proof format, and SD-JWT encoding before implementing Kotlin port.

See: [github.com/google/identity-credential] — Google's Android identity credential library,
used in Google Wallet. Study their `DocumentCredential` and credential holder patterns.

- [ ] Add `did:key` derivation utility `DidKeyUtils.kt` in `crypto/`:
  - Input: `Ed25519PublicKey` (32 bytes) from Android Keystore
  - Output: `"did:key:z" + multibase58btc(0xED01 || pubKeyBytes)` — prefix `0xED01` is the
    multicodec varint for Ed25519 as defined in the DID Key specification
  - Round-trip test: `didFromKey(key).extractPublicKey() == key`
- [ ] `VerifiableCredentialBuilder.kt` in `export/`:
  - `build(profile: ContactProfile, signingKey: AndroidKeystoreKey): VerifiableCredential`
  - JSON-LD `@context`: `["https://www.w3.org/2018/credentials/v1", "https://schema.org/"]`
  - `type`: `["VerifiableCredential", "ContactCard"]`
  - `credentialSubject`: map `ContactProfile` fields → JSON-LD properties (name, email, org, phone,
    url, photo as `image` field — base64 data URI)
  - `issuanceDate`: ISO 8601 UTC timestamp
  - `proof.type`: `"Ed25519Signature2020"` — sign `credentialSubject` hash with Keystore Ed25519 key
  - `proof.jws`: compact JWS using `JsonWebSignature2020` format (header.payload.signature, detached payload)
- [ ] Selective disclosure: support SD-JWT encoding as an alternative export format
  - Each profile field independently disclosed/withheld by the holder
  - Format: `~fieldName~value` hash commitments in VC + selective reveal proof
  - Use case: share name + company but withhold phone number for a specific exchange
- [ ] Export entry points:
  - `ProfileFragment` → share menu → "Export as Verifiable Credential" → `ACTION_SEND` with
    `application/vc+ld+json` MIME type
  - Deeplink export: `aura://vc/export?fields=name,email,org` — generates VC with only specified fields
  - QR code export: encode compact VC as QR; receiver's wallet app can scan and import
- [ ] Import: accept inbound VCs during exchange — `WireProtocol` v9 adds optional `VC_PAYLOAD` TLV;
  if present, `VerifiableCredentialVerifier.kt` verifies the `proof.jws` against the issuer DID
  before adding to contacts — reject any VC whose signature fails verification
- [ ] Unit tests:
  - `did:key` derivation round-trip for known Ed25519 test vectors
  - VC JSON-LD structure matches W3C spec (validate against JSON Schema)
  - Signature verification: verify VC signed by key A fails verification against key B
  - Selective disclosure: holder discloses field X, verifier can verify X; withheld fields return no data
  - SD-JWT: tampered SD-JWT (modified hidden field) fails verification

---

## Task 47 — Android Digital Credentials API + ISO 18013-5 mDL Verification

**Why this follows Task 46:** AURA can now produce and consume W3C VCs (Task 46). The next step is
accepting verifiable credentials from government-issued digital wallets — mobile driver's licenses
(mDLs) following ISO/IEC 18013-5. Android's `CredentialManager.getCredential()` with a
`DigitalCredentialRequest` can request presentations from any registered wallet app, including
Google Wallet. This transforms AURA from a bilateral contact exchange tool into a verifiable
identity exchange protocol: both parties exchange not just contact cards but cryptographically
verified government-issued identity claims.

**Architecture:** The verifier (AURA) constructs a `DigitalCredentialRequest` specifying the
required fields (e.g., `org.iso.18013.5.1.given_name`, `org.iso.18013.5.1.family_name`,
`org.iso.18013.5.1.portrait`). Android routes the request to the user's wallet app. The wallet
presents the credential as an `mdoc` (ISO 18013-5 CBOR-encoded document) with a `DeviceAuth`
signature from the document signer key. AURA verifies the `IssuerAuth` chain (mDL issuer → state
DMV → IACA root) and the `DeviceAuth` proof of possession. Verified fields are merged into the
incoming `ContactProfile`.

See: [developer.android.com/identity/digital-credentials] — Android Digital Credentials overview.
`CredentialManager.getCredential()` with `DigitalCredentialRequest` is the entry point.

See: [developer.android.com/identity/digital-credentials/credential-verifier] — Verifier API.
Covers request format, response parsing, and issuer certificate chain validation.

See: [iso.org/standard/69084.html] — ISO/IEC 18013-5:2021 — the formal mDL standard. The CBOR
structure, namespace definitions (`org.iso.18013.5.1`), and device authentication spec are here.

See: [github.com/google/identity-credential] — Google's open-source mDL implementation (Android
and iOS). The `IdentityCredential` and `PresentationSession` classes are the direct reference.

- [ ] Add `MdlVerifier.kt` in `identity/`:
  - `requestPresentation(fields: List<MdlField>): Flow<MdlVerificationResult>`
  - Constructs `DigitalCredentialRequest` JSON: `{ "protocol": "preview", "data": { "fields": [...] }}`
  - Calls `CredentialManager.getCredential()` — triggers wallet app selection sheet
  - Parses returned `mdoc` CBOR bytes: extract `IssuerSigned` (issuer-signed items) and `DeviceAuth`
  - Verifies `IssuerAuth` certificate chain: issuer cert → IACA root → AAMVA root (for US mDLs)
    using `CertPathValidator` with a bundled `iaca-roots.p7b` trust store
  - Verifies `DeviceAuth` MAC or signature proving the holder device possesses the document key
  - Returns `MdlVerificationResult.Success(fields: Map<MdlField, MdlValue>)` on valid proof
  - Returns `MdlVerificationResult.Failure(reason: MdlError)` on any verification failure
- [ ] Bundle IACA root certificates: `assets/iaca-roots.p7b` — includes AAMVA root and EU IACA root;
  update via `WorkManager` task checking `https://www.aamva.org/iaca/roots` weekly with pinned hash
- [ ] `MdlField` enum:
  `GIVEN_NAME`, `FAMILY_NAME`, `BIRTH_DATE`, `PORTRAIT`, `DOCUMENT_NUMBER`,
  `ISSUING_COUNTRY`, `ISSUING_AUTHORITY`, `EXPIRY_DATE`, `AGE_OVER_18`
  — `AGE_OVER_18` is a selective disclosure boolean: reveals only true/false, not birth date
- [ ] `ExchangeFragment`: "Verify with government ID" secondary action — shows when in enterprise
  mode (`EnterpriseConfig.requireMdlVerification = true`) or user explicitly enables in settings
- [ ] `ContactProfile` extension: `MdlVerifiedFields` data class attached when mDL verification
  completes; shown in `ContactDetailFragment` with a verified badge and issuing authority
- [ ] Selective disclosure request: AURA should request minimum necessary fields — never request
  document number or expiry date unless enterprise policy explicitly requires it; default request
  = `[GIVEN_NAME, FAMILY_NAME, AGE_OVER_18]` only
- [ ] Age verification only mode: gate entry to an event (conference, meetup) by verifying
  `AGE_OVER_18 = true` without requesting any name or identity — zero PII mode
- [ ] `ExchangeAuditLog.mdlVerified: Boolean?` — log whether mDL verification was performed
  and passed for the exchange session
- [ ] Unit tests:
  - `MdlVerifier` parses CBOR `IssuerSigned` correctly for known test vectors (ISO 18013-5 Annex D)
  - Certificate chain validation passes for valid IACA-rooted cert; fails for self-signed cert
  - `AGE_OVER_18` disclosure extracts boolean without leaking birth date field
  - Tampered `DeviceAuth` signature fails verification

---

## Task 48 — Android 16 Identity Check Integration

**Why here:** Android 16 introduced `Identity Check` — a biometric gate for high-risk device
actions (changing device PIN, modifying passkeys, accessing critical settings). AURA has equivalent
high-risk operations: identity key rotation, backup key export, and MDM policy override. Without
Identity Check, an attacker who observes or guesses the device PIN can silently rotate AURA's
identity key or export credentials. Identity Check closes this gap at the platform level.

**Architecture:** Android 16 provides `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` —
unchanged API — but adds an `IdentityCheckManager` (SDK 36+) that wraps the biometric prompt with
an OS-level flag indicating the operation is high-risk. The flag is reported to device policy
controllers via AMAPI, enabling enterprise audit trails for sensitive key operations. AURA calls
this API before any of its three critical paths: key rotation, VC export, and backup seed export.

See: [developer.android.com/about/versions/16/summary] — Android 16 overview.
Identity Check is documented under "Security and Privacy" in the API diff.

See: [bayton.org/android/android-16-enterprise-features/] — Enterprise perspective on Identity
Check, AMAPI policy hooks, and the audit event model for managed device deployments.

- [ ] `IdentityCheckGate.kt` in `security/`:
  - `suspend fun requireIdentityCheck(context: Context, operation: SensitiveOperation): IdentityCheckResult`
  - On API >= 36: use `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG)` AND
    set `IdentityCheckManager.markAsHighRisk(operation.displayString)` before showing prompt
  - On API < 36: fall through to `BiometricPrompt(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` —
    same security bar, different platform signal
  - `SensitiveOperation` enum: `KEY_ROTATION`, `VC_EXPORT`, `BACKUP_SEED_EXPORT`,
    `MDM_POLICY_OVERRIDE`, `IDENTITY_KEY_DELETION`
- [ ] Gate `IdentityKeyManager.rotateKey()`: call `IdentityCheckGate.requireIdentityCheck(KEY_ROTATION)` —
  block rotation until biometric succeeds; log `ExchangeAuditLog.IDENTITY_CHECK_PASSED` or
  `IDENTITY_CHECK_FAILED`
- [ ] Gate `BackupFragment` "Export seed phrase": call `IdentityCheckGate.requireIdentityCheck(BACKUP_SEED_EXPORT)`
- [ ] Gate `VerifiableCredentialBuilder.build()`: call `IdentityCheckGate.requireIdentityCheck(VC_EXPORT)` —
  VC contains signed identity assertions; must require biometric before every export
- [ ] Gate enterprise MDM override UI (Settings → Enterprise → Override policy):
  call `IdentityCheckGate.requireIdentityCheck(MDM_POLICY_OVERRIDE)`
- [ ] `EnterpriseConfig` new key: `require_identity_check_for_exchange: Boolean` — when `true`,
  also require Identity Check before any card exchange starts; for high-security enterprise deployments
- [ ] `IdentityCheckResult.TemporaryLockout(remainingSeconds: Int)`: surface lockout countdown
  in UI — do not retry silently; show "Too many failed attempts. Try again in N seconds."
- [ ] Unit tests:
  - `requireIdentityCheck()` returns `FAILED` when mock biometric returns `ERROR_NEGATIVE_BUTTON`
  - Key rotation is blocked when `IdentityCheckResult.FAILED` is returned
  - API < 36 fallback path uses `DEVICE_CREDENTIAL` authenticator type correctly
  - `IDENTITY_CHECK_PASSED` event is written to `ExchangeAuditLog` with correct `SensitiveOperation` value

---

## Task 49 — Android 16 Advanced Protection API Hardening

**Why here:** Android 16 adds `AdvancedProtectionManager` — a system service indicating the user
has enabled Google's Advanced Protection program for their account. When active, Advanced Protection
blocks unknown-source app installs, restricts USB data, and enables theft protection. Third-party
apps can query this state via `AdvancedProtectionManager.isAdvancedProtectionEnabled()` and
enable their own hardening in response. This is a zero-cost security upgrade: AURA reads one API
and enables stronger defaults automatically for users who have already opted into maximum security.

**Architecture:** On app startup, `AuraApplication.kt` checks `AdvancedProtectionManager.isAdvancedProtectionEnabled()`.
If true: force `SAS_VERIFICATION_REQUIRED = true`, force `MAX_GESTURE_ATTEMPTS = 2` (instead of 3),
disable QR relay as a fallback (relay path is server-assisted and leaks metadata), and disable
LoRa transport (unencrypted channel before AURA layer — Advanced Protection users should not
trust unverified underlay channels). These overrides stack on top of MDM policy but yield to
explicit MDM `enforce_*` keys.

See: [blog.google/security/whats-new-in-android-security-privacy-2026/] — Advanced Protection API
announcement, `AdvancedProtectionManager` class reference, and the opt-in user flow.

See: [developer.android.com/about/versions/16/summary] — API 36 `AdvancedProtectionManager` entry.

- [ ] `AdvancedProtectionIntegration.kt` in `security/`:
  - `isEnabled(): Boolean` — calls `AdvancedProtectionManager.isAdvancedProtectionEnabled()` on API 36+;
    returns `false` on older API levels (never assume enabled)
  - `applyHardening(config: EnterpriseConfig): AuraSecurityProfile` — returns merged security profile:
    Advanced Protection overrides → MDM policy → user preferences (in that priority order)
  - `AuraSecurityProfile` fields:
    `maxGestureAttempts: Int`, `sasRequired: Boolean`, `qrRelayEnabled: Boolean`,
    `loraEnabled: Boolean`, `allowedTransports: Set<TransportType>`,
    `biometricStrength: BiometricStrength`
- [ ] `AuraApplication.onCreate()`: initialize `AdvancedProtectionIntegration`; store result in
  `AppSecurityState` singleton; inject into all services and ViewModels via Hilt
- [ ] `NearbyExchangeService`: respect `AppSecurityState.allowedTransports` — skip disabled transports
  without surfacing an error to the user (silent graceful degradation)
- [ ] Settings → Security → "Advanced Protection Active" banner (non-dismissible): shown when AP mode
  is detected; explains that certain fallback transports are disabled for maximum security
- [ ] Advanced Protection + Enterprise synergy: if MDM policy sets `allowed_transports = [NFC, BLE]`
  AND Advanced Protection is enabled, use the intersection — `{NFC, BLE}` — most restrictive wins
- [ ] Notification: when Advanced Protection state changes (user toggles it), `WorkManager`
  one-time task re-evaluates and updates `AppSecurityState`; if transports are removed, show
  "Security settings updated" notification
- [ ] Unit tests:
  - `applyHardening()` with AP enabled: `maxGestureAttempts = 2`, `qrRelayEnabled = false`
  - MDM policy `max_gesture_attempts = 1` + AP enabled: result is `1` (MDM stricter)
  - MDM policy `allowed_transports = [NFC, BLE, QR]` + AP enabled: result excludes `QR`
  - AP disabled: `applyHardening()` returns user preferences unchanged

---

## Task 50 — BLE 6.2 Shorter Connection Interval Optimization

**Why here:** Bluetooth Core Specification 6.2 (November 2025) introduces the Shorter Connection
Interval (SCI) feature, enabling BLE LE connections to negotiate intervals down to 375 µs — 80×
faster than the 30 ms minimum in BLE 5.x. This dramatically reduces GATT round-trip latency for
the BLE GATT transport (Task 7, shipped). AURA's BLE GATT session (key exchange + profile
payload) currently takes 800–1200 ms on BLE 5.x hardware. With SCI on BLE 6.2-capable device
pairs, target latency is under 200 ms — making BLE GATT competitive with NFC for exchange speed.

**Architecture:** SCI is negotiated via the `LE Connection Parameters Request` procedure. The
GATT client requests `minConnectionInterval = 6` (7.5 ms) initially; if both devices report
`LE_SCI_SUPPORTED` in their feature mask, re-negotiate to `minConnectionInterval = 1` (1.25 ms)
after MTU exchange completes. Android 16+ exposes this via `BluetoothGatt.requestConnectionPriority()`
extended parameter; check `BluetoothDevice.getSupportedFeatures()` for `FEATURE_LE_SCI` flag.

See: [argenox.com/blog/bluetooth-6-2-vs-6-1-deep-dive-into-the-latest-bluetooth-updates] —
SCI feature specification, negotiation procedure, and compatibility matrix across BLE 6.x chipsets.

See: [NordicSemiconductor/Android-BLE-Library] — Nordic's BLE library already includes
`requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_DCK)` for ultra-low-latency mode.

- [ ] `BleGattTransport.kt`: add `attemptSciNegotiation(gatt: BluetoothGatt)`:
  - Check `Build.VERSION.SDK_INT >= 36` AND `gatt.device.getSupportedFeatures().contains(FEATURE_LE_SCI)`
  - If both true: call `gatt.requestConnectionPriority(CONNECTION_PRIORITY_DCK)` (API 36 constant)
    after `onMtuChanged()` — must wait for MTU negotiation to complete first
  - Log result to `BleSessionMetrics.connectionIntervalMs` for performance monitoring
- [ ] `BleSessionMetrics` data class: `mtu: Int`, `connectionIntervalMs: Float`, `sci: Boolean`,
  `totalExchangeDurationMs: Long` — stored in `ExchangeAuditLog.bleMetrics: String?` as JSON
- [ ] Fallback: if SCI negotiation fails (older device), silently fall back to
  `CONNECTION_PRIORITY_HIGH` (11.25–15 ms interval) — no user-visible error
- [ ] Compatibility matrix: SCI requires BLE 6.2 on BOTH devices. Test matrix:
  - BLE 6.2 + BLE 6.2: full SCI (target 375 µs – 1.25 ms)
  - BLE 6.2 + BLE 5.x: negotiate BLE 5.x params (11.25 ms floor) — graceful downgrade
  - BLE 5.x + BLE 5.x: existing behavior unchanged
- [ ] CI performance gate: add benchmark test via `BenchmarkRule` measuring BLE GATT round-trip
  on emulator; fail if total exchange exceeds 3s baseline (SCI improvement is device-dependent
  and cannot be measured in emulator, but regression from current baseline must be caught)
- [ ] Unit tests:
  - `attemptSciNegotiation()` is called after `onMtuChanged()`, not before
  - API < 36 path: `attemptSciNegotiation()` no-ops without throwing
  - `BleSessionMetrics.sci = true` only when FEATURE_LE_SCI confirmed on both sides

---

## Task 51 — Noise Protocol IK Handshake for BLE Transport

**Why here:** The current BLE GATT transport performs a raw ECDH handshake over the
`EPHEMERAL_KEY` GATT characteristic — a bespoke protocol that provides key agreement but lacks
the formal security properties of the Noise Protocol Framework. Noise IK (`Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s`)
provides mutual authentication, forward secrecy, and identity hiding (the initiator's identity key
is encrypted to the responder's static key) in a well-analyzed, formally specified handshake.
This is identical to what WireGuard uses. Replacing the bespoke ECDH with Noise IK gives AURA
provable security properties and a reference implementation already battle-tested at scale.

**Architecture:** Noise IK uses two messages (initiator → responder, responder → initiator).
Both parties know each other's static public keys beforehand (from the NFC session token or TOFU
registry). Message 1 (initiator): `ephemeral + encrypted static + encrypted payload (empty)`.
Message 2 (responder): `ephemeral + encrypted payload`. Both derive identical transport keys.
The GATT `EPHEMERAL_KEY` characteristic becomes the Noise message 1 payload. The
`SESSION_STATUS` NOTIFY becomes the Noise message 2 acknowledgment. Post-handshake, all
`PAYLOAD_CHUNK` characteristic writes are Noise transport messages encrypted with ChaCha20-Poly1305.

See: [noiseprotocol.org] — Noise Protocol Framework specification. Read the IK pattern section.
`Noise_IKpsk2` adds a 32-byte pre-shared key (AURA uses the NFC session token as the PSK)
for additional identity-binding and defense-in-depth.

See: [github.com/nicowillis/noise-kotlin] — Kotlin Noise Protocol implementation covering
the `HandshakeState` API. Evaluate as a dependency before implementing from scratch.

See: [WireGuard whitepaper — wireguard.com/papers/wireguard.pdf] — Section 5: Noise IK in
WireGuard. Identical construction. The message layout and transport key derivation are canonical.

- [ ] Evaluate `nicowillis/noise-kotlin` vs. `whispersystems/noise-java` — prefer noise-java
  (maintained by Signal's team, BouncyCastle backend compatible with existing AURA crypto stack)
- [ ] `NoiseIkHandshake.kt` in `transport/ble/`:
  - `HandshakeState` configured with `Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s`
  - `initInitiator(myStaticKey, peerStaticKey, psk: ByteArray)`: PSK = NFC session token if
    NFC-bootstrapped; fallback PSK = `HKDF(peerIdentityKeyHash, info="aura-ble-psk-v1")` for
    non-NFC-bootstrapped sessions
  - `writeMessage1(): ByteArray` — 96 bytes: 32 ephemeral + 48 encrypted static + 16 MAC
  - `readMessage2(data: ByteArray)`: complete handshake, derive `transportKeys: NoiseTransportKeys`
  - `split(): Pair<CipherState, CipherState>` — initiator TX / responder TX keys post-handshake
- [ ] `BleGattTransport.kt`: replace raw `ECDH + HKDF` flow with `NoiseIkHandshake`:
  - Initiator writes `writeMessage1()` bytes to `EPHEMERAL_KEY` characteristic (96 bytes — fits in
    single MTU 517 write without chaining)
  - Responder reads message 1, calls `readMessage1()` + `writeMessage2()`, notifies via `SESSION_STATUS`
  - Both sides call `split()` → store `encryptKey`, `decryptKey` as `CipherState` objects
  - All subsequent `PAYLOAD_CHUNK` writes: encrypt with `encryptKey.encryptWithAd(ad, plaintext)`;
    receiver: decrypt with `decryptKey.decryptWithAd(ad, ciphertext)` — Noise nonce management
    is automatic within `CipherState`
- [ ] `WireProtocol` framing unchanged above the transport layer — Noise operates at the transport
  security layer; `WireProtocol` TLV framing sits on top of decrypted Noise payload bytes
- [ ] BLE sessions that were NOT NFC-bootstrapped: still perform Noise IK but PSK is derived from
  TOFU identity key hash — provides mutual authentication from TOFU registry without NFC dependency
- [ ] iOS: port `NoiseIkHandshake.swift` — use CryptoKit for X25519, ChaCha20-Poly1305; same PSK logic
- [ ] Unit tests:
  - Full IK round-trip: initiator + responder produce identical transport keys
  - PSK mismatch (different NFC tokens): handshake fails at message 2 with MAC error
  - `CipherState` nonce advances correctly: messages 0–N encrypted/decrypted in order; out-of-order
    message N+1 before N fails with nonce mismatch
  - Identity hiding: message 1 ciphertext reveals nothing about initiator static key to passive observer

---

## Task 52 — ZK Selective Disclosure Proofs

**Why here:** Verifiable Credentials (Task 46) and mDL verification (Task 47) enable rich identity
claims. ZK selective disclosure adds the next layer: users can prove specific attributes without
revealing any underlying data. Examples: prove membership in a company ("I work at Acme Corp")
without revealing employee ID or name; prove age-over-21 without revealing birth date; prove that
a phone number's prefix is in a specific country without revealing the number. This is the
privacy-maximizing endgame of the AURA identity exchange model.

**Architecture:** AURA uses Groth16 zk-SNARKs (via `snarkjs` compiled to JNI, or the `gnark`
Go library compiled via Gomobile) for selective disclosure of Merkle-committed field sets.
Enrollment: hash each profile field with a per-field salt → construct Merkle tree over committed
fields → store Merkle root in the exchanged VC. At disclosure time: user picks fields to reveal
→ AURA generates a Groth16 proof over the revealed Merkle paths → attaches proof to exchange
payload. Verifier checks: `merkle_root` in VC matches + Groth16 proof verifies → fields are genuine
without the verifier learning any withheld fields.

See: [arxiv.org/pdf/2301.00823] — "Bringing data minimization to digital wallets at scale with
general-purpose zero-knowledge proofs." This paper defines the exact construction used here and
benchmarks proof generation on mobile hardware (100–400 ms on modern phones for 20-field trees).

See: [github.com/iden3/snarkjs] — snarkjs + circom circuit compiler. Groth16 proving key
generation and proof verification. The circuit for Merkle path proof is ~50 lines of circom.

See: [github.com/ConsenSys/gnark] — Go ZK-SNARK library with Android support via Gomobile.
Benchmark: Groth16 proof generation 180 ms on Snapdragon 8 Gen 2; verification 12 ms.

- [ ] `ZkProofEngine.kt` in `crypto/`:
  - `commitFields(fields: Map<ProfileField, String>): MerkleCommitment`
    - Per-field: `commitment_i = Poseidon(salt_i || field_value_i)` — Poseidon hash is SNARK-friendly
    - Merkle tree: SHA256 over commitments (non-SNARK path, for root hash in VC)
    - `MerkleCommitment(root: ByteArray, commitments: Map<ProfileField, ByteArray>, salts: Map<ProfileField, ByteArray>)`
  - `proveDisclosure(commitment: MerkleCommitment, revealedFields: Set<ProfileField>): ZkProof`
    - Generates Groth16 proof over Merkle paths for revealed fields using pre-compiled WASM circuit
    - Returns `ZkProof(proofBytes: ByteArray, publicInputs: List<ByteArray>)` — ~192 bytes for Groth16
  - `verifyDisclosure(vcRoot: ByteArray, proof: ZkProof, fields: Map<ProfileField, String>): Boolean`
    - Verifies: `proof` is valid for `publicInputs` AND `publicInputs` include `vcRoot` AND
      revealed field hashes match `Poseidon(salt || value)` for each revealed field
- [ ] Circuit compilation: include pre-compiled `field_disclosure.zkey` and `verification_key.json`
  in `assets/zk/`; these are static files generated offline from the circom circuit —
  update via CI when circuit changes; gate behind SHA-256 integrity check on load
- [ ] Proof generation is async (100–400 ms): run in `Dispatchers.Default` coroutine;
  show progress spinner in `ExchangeFragment` with "Generating privacy proof..." message
- [ ] `WireProtocol` v9: add optional `ZK_PROOF` TLV (tag `0x11`): `proof_bytes || public_inputs_cbor`
  — receiver verifies proof before accepting revealed fields; silently ignore if not present
- [ ] `SharePreset` integration: each preset can optionally attach a ZK disclosure configuration —
  "Work" preset proves `org` membership via ZK; "Anonymous" preset proves `age_over_18` only
- [ ] Performance gate: CI benchmark — proof generation must complete in < 500 ms on ABI x86_64
  emulator (actual device performance will be faster); fail CI if exceeded
- [ ] Unit tests:
  - `proveDisclosure()` + `verifyDisclosure()` round-trip for single field
  - Multi-field disclosure: reveal 3 of 10 fields; proof verifies; withheld 7 fields not recoverable from proof
  - Tampered revealed value: `verifyDisclosure()` returns `false`
  - Tampered `vcRoot`: `verifyDisclosure()` returns `false`
  - Different commitment (re-generated salts): same value but different salts → different commitment → proof fails

---

## Task 53 — FIDO2 Passkey Identity Key Backup and Cross-Device Restore

**Why here:** AURA's identity key is the root of all cryptographic trust in the system. Currently,
losing the device = losing the identity key = losing TOFU relationships with all contacts.
The backup mechanism (Task 87 region) exports a seed phrase, but seed phrase backup requires
user action and has no cross-device sync. Android Credential Manager now supports device-bound and
cloud-synced discoverable passkeys. AURA can register the identity key as a FIDO2 discoverable
passkey backed by Google Password Manager (synced across Android devices) or a hardware security
key (device-bound for maximum security). This gives users seamless identity recovery when upgrading
devices without any manual seed phrase management.

**Architecture:** The FIDO2 credential is not the identity key itself — exporting private key bytes
to a passkey would violate Android Keystore's non-exportable guarantee. Instead: AURA generates
a passkey (FIDO2 P-256 credential) via Credential Manager and uses it as a recovery key. The
recovery blob: encrypt AURA's identity key seed with `AES-256-GCM(key = HKDF(passkey_private_key,
info="aura-recovery-v1"))` → store encrypted blob in `DataStore` + optionally upload to user's
Drive (Task 46 already established Drive integration path). On new device: authenticate passkey
via Credential Manager → derive recovery key → decrypt identity key seed → re-import to Keystore.

See: [developer.android.com/identity/passkeys] — Android passkey implementation guide.
`CredentialManager.createCredential()` with `CreatePublicKeyCredentialRequest` creates a FIDO2
discoverable passkey. The `JSON` challenge format follows WebAuthn level 3 spec.

See: [developer.android.com/identity/credential-manager] — Credential Manager overview.
Supports device-bound passkeys (stored only on device, backed by TEE) and cloud-synced passkeys
(backed by Google Password Manager). AURA offers both options in Settings → Security → Key Backup.

See: [github.com/android/identity-samples/tree/main/CredentialManager] — official Android samples.

- [ ] `PasskeyRecovery.kt` in `security/`:
  - `enrollRecoveryPasskey(rpId: String = "aura.showerideas.com"): PasskeyEnrollResult`
    - Calls `CredentialManager.createCredential()` with `CreatePublicKeyCredentialRequest`:
      `attestation: "none"`, `authenticatorSelection.userVerification: "required"`,
      `authenticatorSelection.residentKey: "required"` (discoverable)
    - Receives P-256 ECDSA credential; stores `credentialId: ByteArray` in `DataStore`
    - Derives `recoveryKey = HKDF(credentialPrivateKey, salt=credentialId, info="aura-recovery-v1")`
    - Encrypts identity key seed: `encryptedSeed = AES-256-GCM(recoveryKey, identityKeySeed)`
    - Stores `encryptedSeed` in `DataStore.PASSKEY_RECOVERY_BLOB`; optionally backs up to Drive
  - `restoreFromPasskey(credentialId: ByteArray): RestoreResult`
    - Calls `CredentialManager.getCredential()` with `GetPublicKeyCredentialOption` (passkey assertion)
    - Receives signed assertion — verify `clientDataJSON.challenge` matches session nonce
    - Re-derive `recoveryKey` from assertion + `credentialId` → decrypt `PASSKEY_RECOVERY_BLOB`
    - Re-import decrypted seed to Android Keystore; restore `KnownPeerDao` TOFU registry from
      backup if available
- [ ] `BackupFragment` → "Recovery Passkey" section: shows enrollment status, last backup date,
  and option to use hardware security key (YubiKey via FIDO2 CTAP2 USB/NFC)
- [ ] Hardware security key path: `CreatePublicKeyCredentialRequest.authenticatorSelection.authenticatorAttachment: "cross-platform"` —
  allows YubiKey or similar CTAP2 device over USB OTG or NFC; `credentialId` stored in `DataStore`
- [ ] Cloud vs. device-bound choice: Settings → Security → "Sync recovery key" toggle:
  `ON` = cloud-synced passkey (Google Password Manager); `OFF` = device-bound passkey (TEE-only, no sync)
  — clearly explain the security/convenience tradeoff in UI
- [ ] First-time setup flow: after enrollment, prompt user to test recovery by simulating restore
  (on the same device) before considering the backup complete — prevents silent failure discovery
  only when the user actually needs recovery
- [ ] `ExchangeAuditLog.passkeySyncAt: Long?` — timestamp of last successful passkey backup sync
- [ ] Unit tests:
  - `enrollRecoveryPasskey()` with mock `CredentialManager` produces valid `encryptedSeed`
  - `restoreFromPasskey()` decrypts to identical identity key seed
  - Wrong `credentialId`: `restoreFromPasskey()` returns `RestoreResult.InvalidCredential`
  - Tampered `encryptedSeed`: AES-GCM authentication tag fails → `RestoreResult.DecryptionFailed`

---

## Task 54 — MLS RFC 9420 Group Sessions

**Why here:** The current Room exchange (Task 10, shipped) uses a star topology: host decrypts and
re-encrypts for each member. This gives the host momentary plaintext access to all cards — a
deliberate, documented design tradeoff. MLS (Messaging Layer Security, RFC 9420, finalized August
2023) is the IETF standard for cryptographic group key agreement with forward secrecy and
post-compromise security across up to thousands of members. No member — including the host —
ever has plaintext access to another member's content. MLS replaces the star topology with a
TreeKEM-based group key that all members derive independently. This is the cryptographically correct
solution for AURA's conference-room use case.

**Architecture:** MLS `KeyPackage` is each member's public advertisement (identity key + HPKE key).
Room host creates an MLS group, adds members by their `KeyPackage`, and publishes `Welcome` messages
encrypted individually to each joiner. Each joiner processes `Welcome` → derives group key.
All subsequent card exchange payloads are encrypted with `MlsGroup.encrypt(plaintext)` → ciphertext
decryptable by all group members simultaneously. The host orchestrates but cannot decrypt any
member's contribution to other members. AURA encapsulates MLS epochs in the existing Room TTL model.

See: [rfc-editor.org/rfc/rfc9420] — RFC 9420 (MLS). Read Sections 5 (Tree Math), 6 (Key Schedule),
7 (Proposals and Commits), and 11 (Application Messages). These are the implementation-critical sections.

See: [github.com/openmls/openmls] — the most complete open-source MLS implementation (Rust).
Compile via `cargo-ndk` for Android ABI targets and wrap via JNI in `mls-jni/` module.

See: [github.com/beurdouche/mls-rs] — alternative Rust MLS library with WASM targets;
potentially easier JNI bridge than openmls.

See: [github.com/cisco/openssl-mls] — C implementation with Android NDK support — lightest binary
option; study if openmls JNI binary size exceeds APK size gate.

- [ ] Evaluate binary size: `openmls` via cargo-ndk vs `mls-rs` via JNI vs `openssl-mls` C JNI —
  target: MLS library adds < 1 MB to APK; test with `assembleRelease` + `apkanalyzer`
- [ ] `MlsGroupManager.kt` (JNI wrapper) in `mls/`:
  - `createGroup(myKeyPackage: KeyPackage): MlsGroup`
  - `addMember(group: MlsGroup, memberKeyPackage: KeyPackage): Pair<Commit, Welcome>`
  - `processWelcome(welcome: Welcome, myKeyPackage: KeyPackage): MlsGroup`
  - `processCommit(group: MlsGroup, commit: Commit): MlsGroup` — advances epoch
  - `encrypt(group: MlsGroup, plaintext: ByteArray): MlsCiphertext`
  - `decrypt(group: MlsGroup, ciphertext: MlsCiphertext): ByteArray`
  - `removeMember(group: MlsGroup, member: LeafIndex): Commit` — on member TIMED_OUT or LEFT
  - `close(group: MlsGroup)` — sends empty commit signaling group disbandment
- [ ] `KeyPackage` lifecycle: each AURA session generates a fresh `KeyPackage` (init key + leaf key);
  `KeyPackage` is valid for the session duration only — never reuse across sessions
- [ ] `RoomExchangeService.kt` upgrade path:
  - Add `RoomExchangeService.Mode`: `STAR_TOPOLOGY` (existing) | `MLS_GROUP` (new)
  - Mode selection: if all members report `MLS_SUPPORTED` flag in their join request → MLS mode;
    otherwise fall back to star topology — backward-compatible with clients not yet on Task 54
  - Under MLS mode: host acts as `MlsGroupManager.createGroup()` initiator but cannot decrypt other
    members' payloads — each member's `contactPayload` is individually `MlsGroup.encrypt()`'d and
    the ciphertext is the only form forwarded by the host
- [ ] `WireProtocol` v9: add `MLS_KEY_PACKAGE` TLV (tag `0x12`) for join request and
  `MLS_WELCOME` TLV (tag `0x13`) for host's welcome message; `MLS_COMMIT` TLV (tag `0x14`)
  for epoch advances; `MLS_APP_MESSAGE` TLV (tag `0x15`) for encrypted application payloads
- [ ] `docs/ARCHITECTURE.md`: update Room Exchange section — document MLS mode, epoch lifecycle,
  fallback trigger condition, and why the star topology is retained for non-MLS peers
- [ ] Unit tests:
  - 3-member MLS group: member A encrypts, B and C decrypt; host (A) cannot re-derive B→C message
  - Epoch advance: after 1 commit, old epoch keys cannot decrypt new epoch ciphertexts
  - Backward compatibility: one MLS-capable + two star-topology peers → star topology selected
  - Member removal: after `removeMember(B)`, B's attempt to decrypt new ciphertexts fails

---

## Task 55 — Android OS Integrity Verification

**Why here:** AURA performs sensitive biometric authentication and identity key operations. On a
rooted or modified Android device, these operations can be intercepted: the TEE may be bypassed,
the camera feed can be hooked to inject fake gesture frames, and the keystore can be shadowed by a
malicious implementation. Android 16 introduces a public, append-only ledger of official Android
builds (OS verification) and an updated `Play Integrity API` that maps to a 4-level verdict.
AURA should check device integrity at session start and surface a warning (not a hard block —
that respects user autonomy) when the device is not running a verified OS build.

**Architecture:** `PlayIntegrityManager.requestIntegrityToken()` returns a token with:
`deviceIntegrity` (MEETS_DEVICE_INTEGRITY, MEETS_BASIC_INTEGRITY, or none),
`appIntegrity` (PLAY_RECOGNIZED, UNRECOGNIZED_VERSION, UNEVALUATED),
`accountDetails` (LICENSED or UNLICENSED). AURA decodes this on-device (no Play server call
required for verdict decoding) and stores the result in `AppSecurityState`. For FOSS users
(no GMS): substitute `SafetyNetAttestation` via hardware-backed key attestation using
`KeyGenParameterSpec.setAttestationChallenge()` — verifies Android Keystore backed by hardware TEE.

See: [developer.android.com/google/play/integrity/overview] — Play Integrity API docs.
Minimal verdict request avoids server round-trip: use `StandardIntegrityManager` (no nonce required).

See: [blog.google/security/whats-new-in-android-security-privacy-2026/] — Android OS verification
via append-only ledger, available on Pixel first. Check `android.os.Build.TAGS == "release-keys"` as
lightweight non-GMS signal.

- [ ] `DeviceIntegrityChecker.kt` in `security/`:
  - GMS path: `PlayIntegrityManager.requestIntegrityToken(StandardIntegrityTokenRequest)` →
    decode `standardVerdict` → map to `IntegrityLevel` enum:
    `VERIFIED` (MEETS_DEVICE_INTEGRITY + PLAY_RECOGNIZED) |
    `BASIC` (MEETS_BASIC_INTEGRITY only) | `UNVERIFIED` (no integrity signal)
  - FOSS path: `KeyPairGenerator` with `setAttestationChallenge(randomNonce)` → verify returned
    cert chain terminates at a known Google hardware attestation root (bundled in `assets/hw-attest-roots.pem`)
    → `HARDWARE_ATTESTED` if valid; `UNVERIFIED` if no hardware-backed cert
  - Cache result: re-check every 24 hours or on `Application.onTrimMemory(LEVEL_COMPLETE)`
- [ ] `ExchangeFragment`: if `IntegrityLevel.UNVERIFIED` → show non-blocking warning banner:
  "Device security status unknown. Exchange may be less secure. Tap for details." with deep-link
  to Settings → Security → Device Integrity
- [ ] Enterprise policy: `EnterpriseConfig.block_unverified_devices: Boolean` — if `true`,
  hard-block exchange attempt when `UNVERIFIED`; show error screen with remediation steps
- [ ] `ExchangeAuditLog.deviceIntegrityLevel: String?` — log integrity level per session; allows
  enterprise admins to identify patterns of exchanges from potentially compromised devices
- [ ] FOSS flavor: skip Play Integrity (GMS dependency); use hardware attestation path only;
  suppress "unverified" banner for users who knowingly run FOSS/de-Googled builds
  (add toggle: Settings → Privacy → "Suppress device integrity warnings" — default off)
- [ ] Unit tests:
  - GMS path with mock `MEETS_DEVICE_INTEGRITY` → `IntegrityLevel.VERIFIED`
  - GMS path with empty verdict → `IntegrityLevel.UNVERIFIED`
  - Hardware attestation path: cert chain with mock HW-backed root → `HARDWARE_ATTESTED`
  - Hardware attestation path: self-signed cert → `UNVERIFIED`
  - Enterprise hard-block: exchange blocked when `block_unverified_devices = true` + `UNVERIFIED`

---

## Task 56 — API Level 36 Compliance (Android 16 Deadline)

**Why here:** Google Play requires all new app submissions to target API 36 (Android 16) by
August 31, 2026. AURA must be compliant before this date. AURA is not distributed via Play Store
(F-Droid is primary), but targeting API 36 is still required to access Android 16 APIs used in
Tasks 48–55 and to ensure behavior compatibility with Android 16 system changes.

**Key behavior changes in API 36 that affect AURA:**
- `BackgroundRestrictedApps` changes: foreground service start restrictions tightened for
  `CONNECTED_DEVICE` type — must ensure exchange session foreground services start from explicit
  user interaction only, never from background alarm or `BroadcastReceiver`
- Photo/video picker mandatory: `ACTION_PICK_IMAGES` replaces `READ_EXTERNAL_STORAGE` for avatar
  upload flow — `READ_MEDIA_IMAGES` is still available but `MANAGE_EXTERNAL_STORAGE` is disallowed
- `SCHEDULE_EXACT_ALARM` requires `EXACT_ALARM` permission to be re-granted after upgrade for
  existing devices targeting API 35

See: [developer.android.com/about/versions/16/summary] — Android 16 behavior changes for
apps targeting API 36.

See: [medium.com/@expertappdevs/android-16-security-updates-e8d81fc8d6a2] — API 36 security
changes, foreground service categorization, and `BiometricManager` updates.

- [ ] `compileSdk = 36`, `targetSdk = 36` in `app/build.gradle.kts`
- [ ] Audit all `ContextCompat.startForegroundService()` calls in:
  `VolumeButtonListenerService`, `NearbyExchangeService`, `BlocklistRefreshWorker` —
  each must be triggered from a direct user action (tap) or existing foreground context;
  document the trigger chain in KDoc above each `startForegroundService()` call
- [ ] Avatar upload flow: replace `READ_MEDIA_IMAGES` permission request + file picker with
  `PickVisualMedia(PickVisualMediaRequest(ImageOnly))` — system photo picker, no permission needed
- [ ] `SCHEDULE_EXACT_ALARM`: audit `AlarmManager` usage in TTL cleanup tasks; migrate to
  `WorkManager` `setExpedited(ExpeditedJobConstraints.Builder)` where exact timing is not critical
- [ ] `WindowSizeClass` API 36 migration: replace deprecated `WindowMetricsCalculator.computeCurrentWindowMetrics()`
  with `WindowInfoTracker.getOrCreate()` + `WindowLayoutInfo` — affects landscape layout logic
- [ ] `PackageInstaller` session behavior: if AURA triggers any self-update flow via F-Droid,
  verify `PackageInstaller.SessionParams.setPackageSource()` is set correctly for API 36
- [ ] Run full `lint --release` with `targetSdk=36` configuration; fix all `NewApi` warnings
  that were suppressed under `targetSdk=35`
- [ ] CI: add `./gradlew :app:lintGmsRelease` step before `assembleRelease`; fail on `severity=error`
- [ ] Unit/instrumented tests: run on API 36 emulator (`system-images;android-36;google_apis;x86_64`)
  in CI; add to existing API matrix alongside API 29, 31, 33
- [ ] Update `minSdk` dependency: Tasks 48–55 use API 36 APIs — all wrapped in `if (Build.VERSION.SDK_INT >= 36)`
  guards; `minSdk = 24` (Android 7) remains unchanged

---

## Task 57 — Granular Contact Picker Integration (Android 14+)

**Why here:** Android 14 and 17 are progressively tightening contact permissions. Android 17
introduces a system-level contact picker that allows apps to request specific contacts (not the
entire address book) with temporary, task-scoped access to specific fields only. AURA imports
contacts from the phone book during the onboarding "pre-fill from existing contact" flow. Adopting
the granular picker immediately eliminates the `READ_CONTACTS` permission requirement for new users
and signals respect for user privacy.

**Architecture:** `ActivityResultContracts.PickContact()` already existed. Android 14+ introduces
`ActivityResultContracts.PickMultipleContacts()` with field-level filtering. AURA's use case:
user selects a single contact to pre-fill their own profile during setup. Request only `name`,
`email`, `phone`, `organization` fields — not address, birthday, or notes. The temporary access
scoping (Android 17+) ensures AURA has no persistent contact read access.

See: [developer.android.com/about/versions/14/changes/partial-photo-video-access] — Android 14
partial media access pattern, analogous to the contact picker flow.

See: [blog.google/security/whats-new-in-android-security-privacy-2026/] — Contact picker with
field-level access, launching in Android 17 (developer preview available in 2026 Q2).

- [ ] `ContactImporter.kt` in `onboarding/`:
  - API 34+: use `PickContact()` with `ContactsContract.QuickContact` to deep-link into system
    contact viewer for selection; avoid requesting `READ_CONTACTS` at all — extract only from
    `Intent.data` URI returned by picker
  - API 35+ (Android 15): use `PickMultipleContacts` (where available) for family/team pre-fill
  - API 36+ (Android 17): use new field-scoped picker when `PackageManager.hasSystemFeature(FEATURE_GRANULAR_CONTACT_PICKER)`;
    request fields: `GIVEN_NAME, FAMILY_NAME, EMAIL_ADDRESS, ORGANIZATION, PHONE`
  - Extract profile fields from returned URI using `ContentResolver.query()` with minimal projection —
    only the exact columns needed; close cursor immediately after read
- [ ] Remove `READ_CONTACTS` permission from `AndroidManifest.xml` if it is currently declared;
  replace entire contact pre-fill flow with picker-only pattern — no persistent contact access
- [ ] `ProfileSetupFragment`: "Import from Contacts" button → triggers picker → auto-fills
  `name`, `email`, `phone`, `company` fields; user reviews and confirms before saving
- [ ] Show "No permission needed" badge next to the import button — explicitly communicates
  the privacy benefit; differentiates AURA from apps that request full contact access
- [ ] Handle the case where user picks a contact but skips certain fields (partial selection) —
  pre-fill what was provided, leave others blank; do not re-prompt for contact access
- [ ] Unit tests:
  - `ContactImporter` maps `ContactsContract.CommonDataKinds.Email.ADDRESS` → `ProfileField.EMAIL`
  - Null URI returned (user cancelled picker): `ContactImporter` returns `ImportResult.Cancelled`
  - Missing column in cursor (contact has no email): field is `null`, not exception

---

## Tasks in Research / Design Phase

The following are design-tracked. Each has an explicit trigger condition that moves it from
`[R&D]` to a scheduled implementation task. These tasks are ordered from most-likely-to-become
implementation-tasks to longest-horizon research.

---

### R&D-A — QR Relay Anonymisation (Tor + Zero-Knowledge Relay)

**Goal:** The QR relay transport leaks sender and receiver IP addresses to the relay server, plus
the request timing. This task adds two complementary anonymisation layers: Tor routing (hides IP)
and a ZK-blind relay scheme (hides request patterns from the relay server itself).

**ZK blind relay design:**
Client posts `H(sessionId)` as the slot identifier. Server stores ciphertext keyed by hash.
Receiver queries by hash. Server cannot link sender IP to receiver IP because:
1. It never sees the pre-image of `H(sessionId)` in sender or receiver requests
2. Server only observes two unlinkable hash queries, not a request–response pair
3. Both queries are routed via Tor, hiding the IPs

See: [github.com/guardianproject/tor-android] — Android Tor integration via `Orbot` or embedded
`tor_jni`. The `NetCipher` library provides transparent Tor-proxied `OkHttp` client.

See: [github.com/guardianproject/netcipher] — `StrongConnectionBuilder.forMaxSecurity()` wraps
any `OkHttp` client with Tor SOCKS5 proxy transparently. No changes to `RelayClient.kt` beyond
swapping the `OkHttpClient` instance.

- [R&D] Evaluate `guardianproject/netcipher` + embedded Orbot for relay anonymisation
  - Measure APK size increase: Tor binary is ~8 MB uncompressed; assess against APK size gate
  - Measure first-connection latency: Tor circuit establishment typically 2–5 s; is this acceptable
    for the QR relay user experience?
  - Evaluate `briarproject/onionwrapper` as a lighter alternative (Kotlin, ~400 KB)
- [R&D] ZK blind relay server: prototype `tools/relay-worker/zk-blind-relay.js` for Cloudflare Workers
  - POST `/slot` with body `{ hash: H(sessionId), ciphertext: encryptedPayload }` — no sessionId in plaintext
  - GET `/slot/:hash` — returns ciphertext; server logs only the hash, not the IP pair correlation
  - TTL: 60 seconds; server auto-deletes slot after first retrieval
- [R&D] Assess Cloudflare Workers ZK blind relay: can Cloudflare log `hash` + timestamp and correlate
  via timing? Evaluate whether a proper Private Information Retrieval (PIR) scheme is warranted
  for the highest-threat-model users
- Trigger: implement if user research confirms >15% of users would use the Tor path; ZK relay
  can ship independently as a default (zero-latency overhead vs. Tor path)

---

### R&D-B — Remote Blocklist via Transparency Log

**Goal:** Privacy-preserving, opt-in blocklist using an append-only Merkle log. Users can flag
abusive identity keys; AURA clients can query the log without revealing which keys they are
checking. Requires external cryptographic review before any implementation.

**Architecture:**
1. Submission: user flags key → AURA hashes `SHA256(keyHash || devicePepper)` — pepper is unique
   per device and never transmitted, preventing correlation of submissions
2. Log server (Trillian or Sigsum): appends `H(keyHash || devicePepper)` to an append-only Merkle log;
   issues a Signed Certificate Timestamp (SCT)
3. Query: clients submit `H(keyHash || ownPepper)` — server returns inclusion proof;
   because peppers differ per device, server cannot link queries to the same key from different devices
4. `TransparencyLogClient (security/)` already has the Merkle verification infrastructure; this task
   adds the server-side log and the privacy-preserving query scheme

See: [github.com/google/trillian] — Trillian transparent, append-only Merkle log. Self-hostable.
See: [sigsum.org] — minimal, privacy-focused Merkle log; simpler than Trillian; open infrastructure.
See: Signal contact discovery blog posts — PSI (Private Set Intersection) query pattern for
avoiding server-side correlation between client queries.

- [R&D] Threat model analysis: what does the log server learn from submissions and queries?
  Define the acceptable leakage bound before design is approved
- [R&D] Evaluate Trillian vs Sigsum for AURA's threat model — Sigsum has stronger privacy
  design (witness cosigning, no centralized state); Trillian has broader tooling support
- [R&D] Assess PSI-based query: Diffie-Hellman PSI would prevent server from learning even
  hashed key queries — prototype to measure query latency on mobile
- Trigger: only after external cryptographic review of the privacy properties is complete;
  never ship without a published security audit of the query protocol

---

### R&D-C — Contact Graph Privacy Analysis (PSI)

**Goal:** Allow AURA users to discover mutual contacts without revealing their full contact list
to each other or any server. "Do we both know Alice?" answered with a boolean, revealing nothing
else about either user's contact list.

**Architecture:** Diffie-Hellman Private Set Intersection (DH-PSI):
1. Both parties hash their identity key sets with a shared random blinding factor
2. Exchange blinded sets
3. Both unblind the other's set with their own blinding factor
4. Intersection = entries that appear in both unblinded sets
5. Neither party learns anything about the non-intersecting items

See: [signal.org/blog/private-contact-discovery-service-2] — Signal's PSI-based contact discovery.
See: Kales et al., "Private Contact Discovery from the Strong Diffie-Hellman Assumption" (2019) —
the exact mathematical construction for DH-PSI on identity key hash sets.

- [R&D] Prototype DH-PSI on AURA identity key sets: measure round-trip time for N=50, N=500, N=5000 contacts
  - DH-PSI scales as O(N) multiplications per side — estimate 10–50 ms for N=500 on Snapdragon 8 Gen 2
- [R&D] Privacy analysis: does AURA's TOFU registry contain enough data to make PSI worthwhile?
  Most users have 10–50 AURA contacts in early deployment — intersection is often empty; useful
  primarily for power users with large networks
- [R&D] UI design: "Mutual AURA contacts" section in contact detail view — shown after PSI completes;
  opt-in per exchange (both parties must consent before PSI runs)
- Trigger: implement if AURA user base reaches >200 contacts per average active user (indicates
  network density where PSI produces non-empty intersections meaningfully)

---

### R&D-D — Decentralized Identity (DID) Full Integration

**Goal:** AURA already derives `did:key` identifiers in Task 46 (VC export). This R&D phase
explores deeper DID integration: resolution of external DIDs, support for `did:web` (identity
anchored to a domain the user controls), and DID Document publishing. This transforms AURA into a
full DID wallet, not just a DID identifier generator.

**Architecture:**
- `did:key`: already implemented in Task 46. Self-contained, no resolution needed.
- `did:web`: AURA publishes a DID Document at `https://[user-domain]/.well-known/did.json`; the
  document lists the user's AURA public key as a `verificationMethod`. Enables enterprise users
  to anchor AURA identity to their corporate domain.
- `did:peer`: peer-specific DID for private pairwise relationships — no public publication required.
  AURA generates a `did:peer:2` DID for each exchange (encodes key material directly in the DID string);
  enables protocol-agnostic identity referencing without requiring the TOFU registry to be the
  only identity anchor.

See: [w3.org/TR/did-core/] — W3C DID Core 1.0 specification.
See: [w3c-ccg.github.io/did-method-key/] — `did:key` method (already implemented in Task 46).
See: [identity.foundation/peer-did-method-spec/] — `did:peer` method; useful for pairwise DIDs.
See: [w3c-ccg.github.io/did-method-web/] — `did:web` method; domain-anchored identity.

- [R&D] Evaluate `did:peer:2` for per-exchange pairwise identifiers:
  encode AURA exchange public key as `did:peer:2.<base58(keyType + keyBytes)>`;
  replace raw `identityKeyHash` in `WireProtocol` with `did:peer:2` identifier — standardizes identity referencing
- [R&D] `did:web` publishing flow: Settings → Identity → "Publish DID Document":
  - User enters their domain (e.g., `alice.example.com`)
  - AURA generates DID Document JSON and provides share-able link + instructions for hosting
    at `/.well-known/did.json`
  - Not self-hosting — AURA generates the document; user is responsible for hosting
- [R&D] DID resolution: `DidResolver.kt` — resolves `did:key` (local), `did:web` (HTTP GET),
  `did:peer:2` (local decode); returns `DIDDocument` with `verificationMethod` entries
- [R&D] W3C Digital Credentials API integration: Android 16 + Chrome 141+ ship
  `navigator.credentials.get({ digital: { ... } })` — AURA registers as a digital credential
  provider; web pages can request AURA VCs from the browser without a native app install
- Trigger: implement `did:peer:2` when Task 46 VCs are deployed and there is cross-ecosystem
  demand for DID-addressable AURA identities; implement `did:web` as a power-user enterprise feature

---

### R&D-E — AI Gesture Coaching (GestureCoach)

**Goal:** After enrollment, help users improve gesture consistency. The LSTM model already computes
per-frame landmark positions; post-enrollment analysis of enrollment variance identifies which
joints drift most, and coaching overlays guide users to a more repeatable gesture.

**Architecture:** `GestureCoach.kt` operates in two modes:
1. **Enrollment analysis mode**: after 5 enrollment samples are collected, compute per-landmark
   standard deviation across all 5 samples → identify top 3 highest-variance landmarks
2. **Real-time coaching mode** (opt-in): during the 1-second capture window, overlay colored
   rings on MediaPipe landmarks in the camera preview — green for low-variance joints, amber for
   moderate, red for high-variance — guiding the user to control the flagged joints more precisely

See: [github.com/kinivi/hand-gesture-recognition-mediapipe] — per-landmark variance reporting
and visualization in the camera preview. Their normalization + centroid approach is the reference.
See: [arXiv:1309.0073 — SilentSense] — behavioral biometrics analysis of motion consistency.

- [R&D] `GestureCoach.kt` prototype:
  - `analyzeEnrollment(samples: List<LandmarkVector>): CoachingReport`
    - `CoachingReport.highVarianceLandmarks: List<Int>` — MediaPipe landmark indices (0–20) with highest σ
    - `CoachingReport.stabilityScore: Float` — mean pairwise cosine similarity across samples (0–1)
    - `CoachingReport.suggestion: String` — localized coaching hint: "Keep your wrist steadier"
  - `overlayCoaching(canvas: Canvas, landmarkList: LandmarkList, report: CoachingReport)`
    — renders colored rings over landmark positions in the camera preview frame
- [R&D] Determine if real-time overlay increases successful auth rate:
  A/B test via `GestureClassifierABTest.kt` — Group A: coaching overlay enabled post-enrollment;
  Group B: no coaching; measure 30-day auth failure rate difference
- Trigger: schedule after gesture library (Task 27, shipped) has 6 months of real-world usage data;
  only ship if A/B test shows > 5% reduction in auth failure rate

---

### R&D-F — Wearable Biometric Fusion (HRV Second Factor)

**Goal:** Use heart rate variability (HRV) from a paired Wear OS watch as a passive second factor.
Gesture biometrics prove "something you do"; HRV proves "something your body is doing right now".
Combined score: `finalScore = 0.7 × gestureScore + 0.3 × hrvScore`. HRV is individual-specific
and cannot be spoofed by replaying a video of the user performing their gesture.

**Architecture:**
1. During exchange, `WearPhoneBridge.kt` requests HRV reading from the watch via `ChannelClient`
2. Watch-side: `HealthServices.getClient().passiveListenerForEvent(HEART_RATE_BPM)` → compute
   `RMSSD` (root mean square of successive RR differences) over the last 60 seconds
3. Phone-side: receive `rmssdValue: Float` → compare against enrolled HRV baseline (stored in
   Keystore as `PREFS_KEY_HRV_BASELINE_MEAN` + `_STD`); use Z-score: `|rmssd - mean| / std < 2.5`
4. If Z-score passes → HRV factor verified; combine with gesture score; if Z-score fails or
   watch not paired → HRV factor contributes 0 (degrade gracefully)

See: [arXiv:1309.0073 — SilentSense] — behavioral biometrics combining touch, motion, HRV signals.
See: [github.com/BharathVishal/Biometric-Authentication-Android] — Jetpack Compose biometric patterns.
See: [github.com/fmeum/WearAuthn] — Wear OS FIDO2 via BLE + NFC; `ChannelClient` pattern for
passing auth signals from Wear to phone.
See: PMC4541821 — "An Efficient Biometric-Based Algorithm Using Heart Rate Variability for Securing
Body Sensor Networks" — precision/recall analysis for RMSSD-based identity verification.

- [R&D] Literature review: is HRV sufficiently stable for biometric use?
  - Review PMC4541821 and similar papers; target > 92% accuracy with Z-score threshold
  - Assess day-to-day variability: HRV fluctuates with stress, caffeine, sleep — Z-score threshold
    must be calibrated to accommodate intra-user variability without excessive false-rejects
  - Determine enrollment period: how many days of baseline collection produces a stable mean/std?
- [R&D] Android Health Connect feasibility: `READ_HEART_RATE_VARIABILITY_RMSSD` permission —
  assess freshness: Health Connect records may be 20–30 minutes stale; real-time HRV requires
  direct `HealthServices` access on Wear OS, not a Health Connect read
- [R&D] Privacy model: HRV baseline stored locally only, encrypted with Keystore key tied to
  strong biometric authentication; never transmitted in any wire protocol message
- Trigger: only implement if HRV uniqueness confirmed with > 92% accuracy in controlled study
  on ≥ 50 participants; do not ship as a security feature without published accuracy benchmark

---

### R&D-G — AR Exchange Overlay (ARCore + Depth API)

**Goal:** Point phone camera at another AURA user → a floating AR business card appears above
their head showing their name and company. Tap the AR card → triggers mutual exchange consent.
This is the most frictionless exchange UX possible: no gestures, no tapping, just look and confirm.

**Architecture:**
1. ARCore `AugmentedFaces` detects face mesh at 1–3 m range (supported on Pixel 6+, Samsung Galaxy S22+)
2. When face detected: BLE scan for AURA `F0AURA` GATT advertisement from detected device
   (proximity correlation between camera-detected face and BLE advertiser requires UWB ranging,
   Task 11 shipped, to confirm it's the same person)
3. UWB distance < 1.5 m AND same AID in BLE advertisement → show AR floating card
4. Both parties tap "Accept" in AR UI → exchange proceeds normally via NFC or BLE GATT transport
5. Bilateral explicit consent required at all times — the AR face detection NEVER activates without
   the user opening the AURA AR mode intentionally and granting Camera + location permissions

See: [developers.google.com/ar/develop/augmented-faces] — ARCore Augmented Faces API.
Face mesh and rendering API. Supported hardware list is critical — must degrade gracefully on
non-AR devices (e.g., just show BLE scan list without AR overlay).
See: [github.com/google-ar/arcore-android-sdk] — ARCore Android SDK; Sceneform is deprecated;
use `ArFragment` + custom `Node` rendering via Filament directly in ARCore 1.40+.

- [R&D] ARCore Augmented Faces latency at 1–3 m range: is detection reliable enough at exchange distances?
  Benchmark on Pixel 8 Pro and Galaxy S23 Ultra; target < 500 ms face detection to AR card display
- [R&D] Privacy impact assessment: ARCore Augmented Faces uses on-device face mesh — no facial
  recognition, no biometric database; verify Google's ARCore documentation confirms local-only
  processing; document this in a privacy notice shown before AR mode is enabled
- [R&D] UWB + BLE correlation reliability: UWB ranging (Task 11) provides distance; BLE GATT
  provides identity; ARCore provides spatial position; assess false-positive rate of
  "wrong person gets the AR card" in a crowded room scenario
- [R&D] Enterprise-only flag: `BuildConfig.ENABLE_AR_EXCHANGE = false` by default; AR mode
  with face detection is opt-in enterprise feature requiring explicit privacy review and a
  dedicated privacy notice dialog before first use
- Trigger: only after UWB Task 11 is confirmed reliable in practice; only as opt-in enterprise
  feature with privacy review board sign-off

---

### R&D-H — Satellite Fallback (Android SatelliteManager)

**Goal:** Exchange AURA cards at locations with zero cellular or Wi-Fi coverage: remote hiking,
disaster zones, maritime, international travel with no SIM. Android 14 introduced `SatelliteManager`
(API 34) with T-Mobile/SpaceX Starlink Direct-to-Cell support. Garmin inReach and Iridium provide
two-way satellite SMS. AURA's vCard must compress to < 160 bytes (one SMS unit) for single-message
delivery.

**Architecture:**
- Compression: LZ4 + base91 encoding of minimal vCard (name + one contact field only)
  - Typical AURA vCard (name + email + org + phone) = ~220 bytes uncompressed
  - LZ4 + base91: ~130 bytes for typical card → fits in 160-char single satellite SMS unit
  - For larger cards: split into 2 SMS units with reassembly header (3-byte length + 1-byte total)
- Transport API path:
  - Android 14+ `SatelliteManager`: check `isSatelliteSupported()` → send via `sendSatelliteDatagram()`
  - Garmin inReach: requires BLE pairing with inReach device; use `GarminConnectIQ` SDK to
    send ipcMessage via Connect IQ app installed on inReach
  - Iridium: similar BLE-to-device bridge via Iridium GO! or RockSTAR devices

See: [developer.android.com/reference/android/telephony/satellite/SatelliteManager] — API 34+.
`SatelliteManager.requestIsSatelliteSupported()` + `SatelliteDatagram` for message send/receive.
See: [github.com/forresttindall/Meshtastic-LoRa-Radio] — off-grid messaging hardware reference.
See: [developer.garmin.com/connect-iq] — Garmin Connect IQ SDK for inReach BLE bridge.

- [R&D] Assess Android `SatelliteManager` API availability: which devices support it in 2026?
  - T-Mobile + Starlink Direct-to-Cell (Pixel 9, Galaxy S25 series): text messaging available
  - Non-T-Mobile devices: `SatelliteManager.requestIsSatelliteSupported()` returns `false`
  - Assess whether Garmin/Iridium BLE bridge path is necessary for non-DTC-supported devices
- [R&D] Compression benchmark: measure LZ4 + base91 on 20 representative AURA vCards;
  confirm 95th percentile compressed size < 160 bytes; if not, define minimal vCard schema
  (name + email only, max 80 bytes) as the satellite profile
- [R&D] Latency expectations: T-Mobile Starlink DTC: ~30 s message delivery; Iridium: ~5 s; Garmin inReach: ~10 s
  These are one-way latencies — full SAS verification (bidirectional 6-digit confirmation) requires
  2 satellite messages = 60–600 s. Assess whether SAS can be omitted for satellite path
  (use TOFU-only verification) or whether the latency is acceptable
- Trigger: implement `SatelliteTransport.kt` only after LoRa integration (Task 39, shipped) has
  demonstrated real-world demand for ultra-long-range exchange paths

---

### R&D-I — App Shortcuts (Launcher Integration)

**Goal:** Reduce exchange friction for power users by exposing "Start Exchange" and "Exchange with
[last contact]" as home screen shortcuts via long-press on the AURA launcher icon.

See: [developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts] — static and
dynamic shortcuts API.

**Proposed shortcut set:**
- Static shortcut 1: "Start Exchange" → deep-links to `ExchangeFragment` directly
- Static shortcut 2: "My QR Code" → opens `ProfileFragment` in QR sharing mode
- Dynamic shortcut 3: "Exchange with [last contact]" → pre-fills deeplink `ExchangeFragment`
  with last exchanged contact's deeplink URL; updated after each successful exchange
- Dynamic shortcut 4: "Activate [Room Name]" → if an active Room session exists within TTL,
  deep-link directly to `RoomExchangeFragment` as guest

- [R&D] `res/xml/shortcuts.xml`: declare static shortcuts 1 and 2 with localized labels
  (must use `@string/` references — shortcut labels are localized by system)
- [R&D] `ShortcutManager.updateShortcuts()` after each exchange: set shortcut 3 to last contact;
  call in `NearbyExchangeService.onExchangeComplete()` coroutine scope
- [R&D] Pinned shortcuts: Settings → Shortcuts → "Pin to home screen"; uses `requestPinShortcut()`
- Trigger: implement in any low-velocity sprint after Task 57; low-effort, high UX value

---

### R&D-J — Predictive Back Gesture (Android 14+ Compliance)

**Goal:** Android 14 requires apps targeting API 34+ to support the predictive back gesture via
`android:enableOnBackInvokedCallback="true"` in the manifest. AURA's SAS dialog (Task 20, shipped)
blocks back during countdown using `onBackPressedDispatcher.addCallback` — verify this uses the
correct modern API and does not use deprecated `onBackPressed()` overrides.

See: [developer.android.com/guide/navigation/custom-back/predictive-back-gesture]

**Audit scope:**
- `SasVerifierDialog`: must use `onBackPressedDispatcher.addCallback(enabled = true)` — NOT `Dialog.setOnCancelListener`
- `ExchangeFragment`: verify back navigation during active exchange is intercepted
- `RoomExchangeFragment`: verify back during active room session shows confirmation dialog
- `EnrollmentFragment`: verify back during gesture capture sequence asks "Cancel enrollment?" first
- Any `BottomSheetDialogFragment` subclass: `OnBackInvokedCallback` must be registered with
  `PRIORITY_OVERLAY` to intercept before default sheet dismiss behavior

- [R&D] Audit all `Fragment.onBackPressed()` overrides → replace with
  `requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)`
- [R&D] For API 34+: additionally register `OnBackInvokedDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, ...)`
  for fragments that need to intercept the predictive swipe animation itself (not just the final back action)
- [R&D] `android:enableOnBackInvokedCallback="true"` in `AndroidManifest.xml` `<application>` tag —
  this is a single-line change that enables the feature globally for the app
- Trigger: required for API 36 target (Task 56); implement as part of Task 56 work, not separately

---

### R&D-K — Android Health Connect Full Integration

**Goal:** Android Health Connect (API 26+, GA since Android 14) provides a unified, permissioned
health data store. For AURA, this is the non-Wear-OS path to HRV data (R&D-F) — Health Connect
aggregates HRV readings from any connected wearable (Fitbit, Garmin, Samsung Health, Google Fit).
This R&D tracks the feasibility of Health Connect HRV data for real-time biometric authentication.

**Key feasibility questions:**
1. Freshness: `HeartRateVariabilityRmssdRecord` — what is the typical lag between measurement
   and availability in Health Connect? Is it < 30 s (usable for real-time auth)?
2. Permission grant rate: `READ_HEART_RATE_VARIABILITY_RMSSD` is a non-declared sensitive
   permission — does it require a separate Health Connect permission flow separate from runtime
   permissions? What is the expected user grant rate?
3. Data availability: what fraction of AURA users have a wearable syncing HRV to Health Connect?
   Without a wearable, this path is unavailable — the feature must degrade completely gracefully

See: [developer.android.com/health-and-fitness/health-connect/data-types] — `HeartRateVariabilityRmssdRecord` data type.
See: [developer.android.com/health-and-fitness/health-connect/get-started] — permission model,
`HealthConnectClient.getGrantedPermissions()`, and `PermissionController` UI flow.

- [R&D] Prototype `HealthConnectHrvReader.kt`:
  - `readRecentHrv(windowMs: Long = 60_000): HrvReading?` — reads latest `HeartRateVariabilityRmssdRecord`
    within `windowMs` milliseconds; returns `null` if no record within window
  - Check `HealthConnectClient.isAvailable()` first; Health Connect may not be installed on older devices
  - Request `READ_HEART_RATE_VARIABILITY_RMSSD` via `PermissionController` if not granted
  - Return `HrvReading(rmssd: Float, measuredAt: Instant, source: String)`
- [R&D] Freshness benchmark: on Pixel 8 + Fitbit Charge 6 pair, measure mean and 95th-percentile
  latency from wrist measurement to `HeartRateVariabilityRmssdRecord` availability in Health Connect
- [R&D] If freshness > 120 s: Health Connect path is NOT viable for real-time auth; document finding;
  Wear OS direct `HealthServices` path (R&D-F) remains the only viable HRV auth path
- Trigger: only invest implementation effort if (a) R&D-F HRV uniqueness study is positive AND
  (b) freshness benchmark confirms < 120 s median lag on at least 3 wearable models

---

### R&D-L — ISO 18013-7 Online Presentation Protocol

**Goal:** ISO 18013-5 (Task 47, in implementation queue) covers proximity mDL presentation via NFC/BLE.
ISO 18013-7 (under finalization in 2026) extends this to online presentation — the user's mDL is
presented over the internet to a remote verifier via the `OpenID4VP` (OpenID for Verifiable Presentations)
protocol. AURA as a verifier could accept remote mDL presentations without requiring the other
person to be physically present, enabling async identity verification for remote exchange scenarios.

See: [openid.net/developers/draft-openid-4-verifiable-presentations/] — OpenID4VP draft specification.
See: [mobileidworld.com/w3c-advances-did-standard-that-underpins-mobile-wallets-and-digital-credentials/] —
W3C DID + mDL convergence in 2026.

- [R&D] Assess ISO 18013-7 standardization timeline: draft was circulating in 2025;
  expect final publication Q3–Q4 2026; implement only against final spec
- [R&D] Design async identity verification flow: Alice requests verification from Bob remotely →
  Bob presents mDL via OpenID4VP → Alice's AURA verifies online presentation →
  result stored as `MdlVerifiedFields` in `ContactProfile`
- [R&D] Privacy model: online mDL presentation reveals IP address to verifier; document disclosure;
  consider Tor path (R&D-A) for remote mDL verification
- Trigger: implement after ISO 18013-7 final spec published and Android Credential Manager adds
  `OpenID4VP` response format support

---

### R&D-M — Matter/Thread IoT Identity Bridge

**Goal:** Matter (formerly Project CHIP, CSA standard 2022+) is the dominant smart home protocol.
Thread is the IPv6-based mesh transport beneath Matter. Both use X.509 certificates for device
identity. AURA's identity model (Ed25519 keys + TOFU) is structurally compatible with Matter's
Node Operational Certificates (NOC). An AURA–Matter bridge would allow a user's AURA identity to
authorize pairing of Matter IoT devices: tap your phone to an AURA-unaware device → it receives
your identity certificate → device grants access based on AURA-verified identity.

See: [github.com/project-chip/connectedhomeip] — Matter SDK, reference implementation.
See: [csa-iot.org/developer-resource/specifications-download-request/] — Matter specification.
Matter NOC format, fabric structure, and PASE (Passcode-Authenticated Session Establishment) are
the key integration points.

- [R&D] Assess Matter NOC compatibility with AURA identity keys:
  Matter NOC requires P-256 ECDSA (not Ed25519); AURA's identity key IS P-256; assess whether
  AURA can issue a NOC-compatible certificate for a Matter device using the existing identity key
- [R&D] `MatterIdentityBridge.kt` prototype: derive a Matter-format NOC from AURA identity key;
  use PASE to commission a Matter device with the NOC; revoke by issuing new NOC on key rotation
- [R&D] Privacy: Matter fabric ID and NOC are shared with every paired device; AURA should use
  a derived fabric-specific key (HKDF from identity key + fabric ID) rather than the identity key directly
- Trigger: implement when Matter ecosystem adoption reaches mainstream (50%+ of new smart home
  devices shipped with Matter support) and user demand for AURA-authenticated IoT pairing is demonstrated

---

### R&D-N — AI-Powered Contact Import (OCR + On-Device LLM)

**Goal:** Import contact information from a photographed business card, screenshot, or document
using on-device OCR and an on-device LLM to structure extracted text into a `ContactProfile`.
No data leaves the device. Uses Android ML Kit Text Recognition v2 (on-device) + Gemini Nano
(Android 14+ via `AICore`) for structured extraction.

See: [developers.google.com/ml-kit/vision/text-recognition/android] — ML Kit Text Recognition v2;
on-device, no network, Latin and CJK script support.
See: [developer.android.com/ai/aicore] — Android AICore; Gemini Nano on-device inference API;
available on Pixel 8 Pro+, Galaxy S24+; degrades gracefully on unsupported hardware.

- [R&D] Prototype `BusinessCardImporter.kt`:
  - Camera capture: `CameraX` image capture → `TextRecognizer.process(inputImage)` → raw text blocks
  - Structured extraction: feed raw text to Gemini Nano via `GeminiInferenceSession.executeAsync()`:
    prompt: "Extract name, email, phone, company, title, and website from this business card text.
    Return JSON only. Text: {raw_text}" — keep prompt minimal for Nano's context window
  - Map Gemini output JSON → `ContactProfile` fields; validate email format and phone format;
    show user a review/confirm screen before saving
  - Fallback on non-Nano devices: regex-based extraction for email + phone; leave name/company blank
    for user to fill manually
- [R&D] Privacy verification: confirm Gemini Nano inference runs entirely on-device with no
  network calls; verify `AICore` manifest declaration does not require Google account sign-in
- [R&D] Accuracy benchmark: test on 50 business cards (corporate, handwritten, multilingual);
  target field extraction accuracy > 90% for email and phone on Latin-script cards
- Trigger: implement when Gemini Nano (or equivalent) is available on > 40% of active Android devices;
  ship as opt-in feature with explicit "processed on-device" disclosure in the import UI

---

## What This Roadmap Delivers

With Tasks 1–44 shipped, AURA is a production-grade, privacy-first, gesture-authenticated
identity exchange system with NFC bootstrapping, multi-transport FOSS support, post-quantum crypto,
and multi-party room sessions. The next wave of tasks extends AURA across three dimensions:

*Platform security hardening (Tasks 48–49, 55–56):* Android 16 Identity Check and Advanced
Protection integration bring AURA into alignment with the strongest security posture Android
offers. These tasks are primarily defensive — no new features, just ensuring AURA is the
hardest-to-compromise app on any given device.

*Protocol completeness (Tasks 45, 51, 54):* SPQR Triple Ratchet extends post-quantum protection
to every individual message within a session, not just the session key. The Noise Protocol IK
handshake formalizes the BLE transport handshake with provable security properties matching
WireGuard's. MLS RFC 9420 replaces the star-topology Room exchange with cryptographic group key
agreement — no party, including the host, has plaintext access to other parties' cards.

*Identity ecosystem integration (Tasks 46, 47, 52, 53):* W3C Verifiable Credentials export
makes AURA a first-class citizen in the emerging VC ecosystem. ISO 18013-5 mDL verification
accepts government-issued digital identity alongside self-asserted contact cards. FIDO2 passkey
backup solves the device-loss problem without seed phrase complexity. ZK selective disclosure
enables attribute proofs without full card revelation.

*Privacy-maximizing architecture (R&D-A, B, C, L):* The R&D pipeline tracks the leading edge
of privacy-preserving identity: Tor + ZK-blind relays for transport anonymity, Merkle-log
blocklists with DH-PSI queries for contact safety without data leakage, ISO 18013-7 online
presentation for remote verified identity exchange, and DID full integration for
decentralized, self-sovereign identity without any AURA server dependency.

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
| v3.3.0 | 2026-05-26 | Tasks 1–44 complete — full transport stack, PQ crypto, MLS room, SPQR foundation, analytics |

---

*Last updated: 2026-05-26 — Full roadmap rewrite. Removed Tasks 1–44 (all shipped). Added Tasks
45–57 covering SPQR Triple Ratchet, W3C VC export, Android Digital Credentials + ISO 18013-5 mDL,
Android 16 Identity Check + Advanced Protection, BLE 6.2 SCI, Noise IK handshake, ZK selective
disclosure, FIDO2 passkey backup, MLS RFC 9420, OS integrity verification, API 36 compliance,
granular contact picker. Enriched R&D-A through R&D-K with full architecture + research citations.
Added R&D-L (ISO 18013-7 online mDL), R&D-M (Matter/Thread IoT bridge), R&D-N (AI contact import).*
