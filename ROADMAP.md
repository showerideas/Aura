# AURA — Engineering Roadmap

> Canonical planning document for the AURA gesture-biometric contact exchange system.
> Written as a strictly ordered sequence of engineering tasks for a software team.
> Tasks are sorted by dependency: each task may depend on those above it.
> References are cited inline where they are relevant — there is no separate reference table.
> Last rewrite: 2026-05-26

---

## How to Read This Document

This document is a **dependency-ordered implementation sequence**, not a feature wishlist.
Every task is placed where it is because something either upstream requires it, or completing it
unlocks the most subsequent work. Read it top to bottom before starting any task.

Status markers:
- `[DONE]` — shipped and merged to main
- `[ ]` — open, ready to implement
- `[R&D]` — design/research phase only; no code until explicitly moved to `[ ]`

Current baseline: **v3.2.0** on `main`. PRs #62–#101 all merged. iOS companion shipped.
Wear OS + Android Auto shipped. F-Droid pipeline live. JaCoCo 60% floor. CI green.

---

## Current System Snapshot

| Layer | State |
|---|---|
| Core app | v3.2.0 — production-ready |
| Gesture gate | MediaPipe Hands + cosine-similarity + A/B classifier test (PR #94) |
| Transport | Google Nearby Connections (GMS) + Wi-Fi Direct (FOSS) + NFC HCE + QR relay |
| NFC | HCE ISO 7816-4 full impl (v2.0.1) + NDEF tap |
| QR relay | AES-256-GCM HTTPS + Tor path wired |
| Crypto | Hybrid KEM ML-KEM-768+X25519 · Sealed sender · Double Ratchet · SAS · TOFU |
| Wire protocol | v7 — SPKI runtime pinning · identity rotation · replay protection |
| Multi-profile | Personal / Work — wired; enterprise MDM retention (PR #91) |
| Audit log | ExchangeAuditLog Room table + CSV export + AuditFragment UI |
| Localization | 313 strings × 7 locales — 100% coverage, human-reviewed (PR #95/#96) |
| Test suite | 300+ unit methods + 55 instrumented + 15 iOS AuraCoreTests — JaCoCo 60% floor |
| CI | Green — lint + unit + JaCoCo + assembleRelease + APK size gate + iOS build/test |
| Distribution | F-Droid reproducible build script (PR #99) + submission guide (PR #100) — live |
| Signing | PKCS12 keystore in GitHub Secrets — signed AAB confirmed |
| iOS | AuraCore companion — ContactProfile, SasVerifier, AuraExchangeCoordinator, 15 tests |
| Wear OS | Pairing flow — WearPairingViewModel + BottomSheet + PhoneWearSender (PR #93) |
| Android Auto | Voice action + biometric auth gate (PR #92) |
| Deeplink | Deeplink → pre-filled Add Contact bottom sheet (PR #89) |

---

---

## Task 1 — NFC HCE Service: Full APDU Exchange Implementation

**Why this is first:** NFC tap is the cryptographic bootstrapping primitive for AURA. It is the
mechanism that creates the initial shared secret from which all subsequent BLE/Wi-Fi/Room sessions
derive their keys. The volume button has >50% OEM failure rate on Samsung/MIUI devices. NFC is
hardware-guaranteed on all modern Android phones. Until this is complete, every other transport
sits on a weaker foundation.

`AuraHceService.kt` and `NfcExchangeHelper.kt` are scaffolded. The ISO 7816-4 APDU command
structure (delivered in v2.0.1) is wired. The remaining work is hardening the APDU chaining
for payloads >255 bytes and completing the session handoff to `NearbyExchangeService`.

**Architecture:** Android HCE (`HostApduService`) works by the OS routing NFC APDU frames to a
registered service based on an Application Identifier (AID). The initiating device sends a
`SELECT AID` command; `AuraHceService` responds and begins the ephemeral key exchange.
NFC carries only the key material (small) — bulk profile payload transfers over the already-
established Nearby/BLE session. This is the correct split: NFC for trust bootstrapping,
Nearby/BLE for bandwidth.

See: [Android HCE APDU docs — developer.android.com/develop/connectivity/nfc/hce] for AID
registration format, APDU framing rules, and the `HostApduService` lifecycle. APDU frames are
limited to 255 bytes; use APDU chaining for payloads exceeding this.

See: [github.com/oscarpfernandez/AndroidBridgeBluetooth] for the NFC-to-Bluetooth session
handoff pattern — exactly the architecture AURA uses (NFC for keying, BT for payload).

- [DONE] `AuraHceService` extending `HostApduService` — ISO 7816-4 full impl (v2.0.1)
- [DONE] AURA AID `F0 41 55 52 41 01` registered in `res/xml/apduservice.xml`
- [DONE] `NfcExchangeHelper` public API
- [ ] APDU chaining for payloads >255 bytes — frame reassembly on both sides
- [ ] After key exchange via NFC: pass `(sharedSecret, sessionNonce)` to `NearbyExchangeService`
- [ ] NFC bootstraps the session — large-payload crypto stays on Nearby/BLE transport
- [ ] Unit test: APDU framing/deframing for payloads 1–512 bytes
- [ ] Instrumented test: real NFC device pair — SELECT AID → key exchange → Nearby session handoff

---

## Task 2 — NFC Reader Mode: Initiator Path

**Why this follows Task 1:** Task 1 implements the HCE *responder* (the device receiving the tap).
This task implements the *initiator* (the device doing the tapping). Both roles must be complete
before NFC is a usable transport. The initiator path uses `NfcAdapter.enableReaderMode` which is
distinct from and does not interact with `HostApduService`.

**Architecture:** The reader-mode device calls `enableReaderMode` with `FLAG_READER_NFC_A` and a
`ReaderCallback`. When it detects an ISO-DEP tag (another phone running AURA HCE), it instantiates
an `IsoDep` object, calls `connect()`, then drives the APDU exchange defined in Task 1. After
`transceive()` returns the peer's ephemeral key, the initiator proceeds identically to the
responder: ECDH → session seed → hand off to Nearby/BLE.

- [ ] Implement `NfcAdapter.enableReaderMode` path in `ExchangeFragment` and/or `NfcExchangeHelper`
- [ ] Handle `IsoDep` tag discovered → `connect()` → send `SELECT AID` → receive peer ephemeral key
- [ ] ECDH derivation from received public key — same flow as HCE responder side
- [ ] Disable `enableReaderMode` in `onPause()` — foreground dispatch rules require this
- [ ] Fallback chain: if NFC unavailable or timed out → fall through to volume button → QR relay
- [ ] Integration test: two emulators using mock APDU — full initiator + responder round-trip

---

## Task 3 — NFC Session Token as Room Bootstrap Seed

**Why this follows Tasks 1 and 2:** The NFC tap now exchanges ephemeral ECDH keys. That shared
secret should do more than just seed a single bilateral exchange — it should be the root key for
a multi-party Room session (Task 8). This task formalises the session token concept so the Room
System has a clean input from NFC.

**Architecture:** After ECDH on the NFC channel, both devices independently derive an identical
32-byte `sessionToken = HKDF(ecdhShared || nonce32, info="aura-room-v1")`. This token is the
cryptographic identity of the exchange instance and later the Room. The nonce is contributed by
the initiator (sent in the `EPHEMERAL_KEY_REQUEST` APDU frame). Both devices end up with the
same `sessionToken` without any further communication — standard HKDF determinism.

- [ ] Add `sessionNonce: ByteArray` (32 bytes, random) field to `EPHEMERAL_KEY_REQUEST` APDU frame
- [ ] Both sides run `HKDF(ecdhShared || sessionNonce, salt=null, info="aura-room-v1")` → 32-byte token
- [ ] `NfcExchangeHelper.getSessionToken(): ByteArray` — exposes derived token to callers
- [ ] `NearbyExchangeService`: accept optional `sessionToken` — gates session as NFC-bootstrapped
- [ ] Token stored in memory only; cleared on session close or 10-minute TTL

---

## Task 4 — Wi-Fi Direct FOSS Transport (`WifiDirectTransport`)

**Why this is next:** The current `NearbyTransport` interface already exists. `WifiDirectTransport`
is the first concrete implementation that does not require Google Mobile Services. Without this,
AURA cannot form mesh networks (Tasks 16–18). The FOSS flavor already exists but needs a
production-grade Wi-Fi Direct implementation beneath it.

**Architecture:** Android `WifiP2pManager` provides peer-to-peer Wi-Fi without an access point.
Peer discovery is NOT used here for finding AURA users — NFC/gesture already does that. Wi-Fi
Direct is purely the data transport. The group owner is determined by lexicographic ordering of
ECDH public keys (already in the SAS logic — reuse it). The group owner listens on TCP port 8743.
The client connects to the group owner's IP (retrieved via `WifiP2pManager.requestConnectionInfo()`).
The existing `WireProtocol` framing runs over this TCP socket unchanged.

See: [github.com/localsend/localsend] — LocalSend uses a REST-over-LAN approach with mDNS
discovery. Study their chunked TCP transfer patterns and handling of partial writes. AURA's
discovery problem is already solved by NFC/gesture; the transfer layer is directly reusable.

See: [github.com/UstadMobile/Meshrabiya] — Meshrabiya uses Wi-Fi Direct with local-only hotspot
for a multi-hop Android mesh. Study their `VirtualNode` design now; it will be reused in Task 17.
Group formation and IP assignment in Meshrabiya are production-grade references.

See: [github.com/mayfourth/WiFi-Direct-File-Transfer] for a minimal working reference of
`WifiP2pManager` group owner + client TCP socket pattern on Android.

- [ ] Implement `WifiDirectTransport.kt` satisfying the `NearbyTransport` interface
- [ ] Use `WifiP2pManager` + `WifiP2pManager.Channel` for connection (not discovery)
- [ ] Group owner election: lexicographic ordering on ECDH public key bytes as tiebreaker
- [ ] Group owner: `ServerSocket(8743)` → `accept()` → `WireProtocol` framing over TCP
- [ ] Group client: `requestConnectionInfo()` → connect to `groupOwnerAddress:8743`
- [ ] TCP transfer: chunked writes with sequence numbers matching existing `WireProtocol` design
- [ ] Register `WIFI_P2P_STATE_CHANGED_ACTION` broadcast receiver in the transport
- [ ] Teardown: `removeGroup()` after session close; unregister broadcast receiver
- [ ] `FakeWifiDirectTransport` test double — implements `NearbyTransport`, backed by in-memory queues
- [ ] Unit tests via `FakeWifiDirectTransport`: connect, send, receive, disconnect, timeout

---

## Task 5 — Product Flavor Split: GMS vs FOSS

**Why this follows Task 4:** `WifiDirectTransport` now exists. The build system routes to it via
a product flavor dimension. F-Droid requires a flavor that compiles with zero GMS dependencies.

**Architecture:** Add a `transport` flavor dimension: `gms` uses `play-services-nearby`; `foss`
excludes all GMS entirely and binds `WifiDirectTransport`. The `NearbyTransport` interface is the
abstraction boundary — no code above it knows which transport is active. Hilt routes to the
correct implementation per flavor via a `@Provides` binding in `TransportModule.kt`.

- [ ] `flavorDimensions += "transport"` in `app/build.gradle.kts`
- [ ] `gms` flavor: includes `play-services-nearby`, binds `NearbyExchangeServiceImpl`
- [ ] `foss` flavor: excludes all `com.google.android.gms` dependencies, binds `WifiDirectTransport`
- [ ] `TransportModule.kt` (Hilt): `@Provides` binding per flavor — no runtime `if` checks
- [ ] `app-foss` variant: bundle MediaPipe `.task` file in `assets/` — no CDN download at runtime
- [ ] Update CI: build both `assembleGmsRelease` and `assembleFossRelease`
- [ ] Update APK size gate to apply to both variants independently

---

## Task 6 — F-Droid Reproducible Build

**Why this follows Task 5:** The F-Droid pipeline is wired (PR #74 + PR #99/100). This task
closes the remaining non-determinism in the build. F-Droid builds apps from source on their own
infrastructure — if the APK produced differs by even one byte, the build is rejected.

See: [briarproject.org] — Briar has been on F-Droid since 2017 with reproducible builds and is
the most mature reference for the full pipeline including metadata, build server configuration,
and the submission process.

See: [github.com/meshtastic/Meshtastic-Android] and [github.com/localsend/localsend] — both
F-Droid with reproducible build pipelines. Study their `build.gradle` and F-Droid metadata YAML.

- [DONE] F-Droid reproducible build verification script (PR #99)
- [DONE] F-Droid submission guide + v2.0.1 metadata entry (PR #100)
- [ ] Replace `BuildConfig.BUILD_TIME = System.currentTimeMillis()` with git commit timestamp
- [ ] Audit all `buildConfigField` entries — remove or determinize runtime-varying values
- [ ] Verify `res/` generated files are deterministic across clean builds on different machines
- [ ] `reproducible-build-check.sh` in CI: two independent builds → `zipdiff` unsigned APKs

---

## Task 7 — BLE GATT Direct Transport (`BleGattTransport`)

**Why here:** Wi-Fi Direct (Task 4) removes GMS for bulk transfer. BLE GATT direct gives us a
second FOSS transport path with lower power consumption and better range in crowded RF environments.
More importantly, BLE is required for the UWB out-of-band channel (Task 11) and for the mesh
networking store-and-forward advertisement layer (Task 14).

**Architecture:** AURA defines a custom GATT service with a fixed service UUID. The GATT server
advertises this service; the GATT client scans for it. Peer discovery is out-of-scope here —
NFC/gesture already pairs the devices. MTU negotiation is critical: request MTU=512 on connect
(Android 5.0+ supports this) to avoid excessive fragmentation.

See: [github.com/NordicSemiconductor/Android-BLE-Library] — Nordic's production Android BLE
library handles the deeply unreliable Android BLE stack: connection retries, MTU negotiation
edge cases, bonding, characteristic read/write queuing. Study their `BleManager` class before
implementing raw `BluetoothGattCallback`.

See: [github.com/weliem/blessed-android] — BLESSED wraps the Android GATT API in coroutines.
~3000 lines and directly copyable. Its `BluetoothCentralManager` + `BluetoothPeripheral` API
maps cleanly to the AURA transport interface.

- [ ] Define AURA GATT service UUID: `12345678-0000-1000-8000-AURA00000001`
  - Characteristic `EPHEMERAL_KEY` (write + read): 65-byte ECDH public key
  - Characteristic `PAYLOAD_CHUNK` (write with response): 512-byte chunks, sequence-numbered
  - Characteristic `SESSION_STATUS` (notify): CONNECTING / ACTIVE / COMPLETE / ERROR
- [ ] GATT server: `BluetoothGattServer` advertising AURA service UUID
- [ ] GATT client: `BluetoothLeScanner` filtering by AURA service UUID → `connectGatt()`
- [ ] `requestMtu(512)` immediately on `onConnectionStateChange(CONNECTED)` — wait for callback
- [ ] Chunked write: 2-byte sequence number + 2-byte total chunk count per chunk
- [ ] Implement `BleGattTransport.kt` satisfying `NearbyTransport`
- [ ] `FakeBleGattTransport` test double for unit tests
- [ ] Unit tests: MTU fragmentation at 23/185/512 bytes; chunk reassembly with gap; timeout

---

## Task 8 — Room Database Schema: Room & Member Entities

**Why now:** The Room System (Tasks 9–10) needs a stable data layer before any service or UI work
begins. Database schema migrations in Android Room are permanent — getting the schema right before
writing service code avoids painful migrations later. This task is purely data modeling.

**Architecture:** A `Room` entity represents a bounded cryptographic session. A `RoomMember` entity
is a participant with their identity key hash and a profile snapshot. The `roomKey` (32 bytes) is
stored encrypted via Android Keystore — never in plaintext in the Room DB. `RoomState` enum:
`OPEN` (accepting joins) | `ACTIVE` (exchange in progress) | `CLOSED` (completed or expired).
TTL is 10 minutes from creation — enforced by the repository layer.

- [ ] Add `Room` entity to Room DB (new migration):
  ```
  roomId: ByteArray (32 bytes, PRIMARY KEY)
  roomKey: ByteArray (32 bytes, encrypted via Android Keystore at-rest)
  createdAt: Long (epoch ms)
  expiresAt: Long (createdAt + 600_000)
  hostIdentityKeyHash: ByteArray
  state: RoomState (OPEN / ACTIVE / CLOSED)
  memberCount: Int
  ```
- [ ] Add `RoomMember` entity:
  ```
  memberId: Long (autoincrement, PK)
  roomId: ByteArray (FK -> Room.roomId)
  identityKeyHash: ByteArray
  nickname: String
  profileSnapshot: ByteArray (serialized vCard, encrypted to this member's public key)
  joinedAt: Long
  ```
- [ ] `RoomDao`: `createRoom`, `joinRoom`, `addMember`, `getActiveRoom()`, `closeRoom`, `deleteExpiredRooms`
- [ ] `RoomRepository`: wraps DAO, emits `StateFlow<RoomState>`, enforces 10-minute TTL
- [ ] Database migration test: assert previous schema version preserves all existing records

---

## Task 9 — Room Creation & Join UX

**Why this follows Task 8:** The data model exists. Now wire the creation, join, and member-list
flows. Three join paths must work: NFC tap (uses Task 3 session token), QR code, and BLE beacon.
NFC is fastest and most secure; QR is the fallback for cross-platform; BLE beacon serves discovery.

**Architecture:** The room host broadcasts a BLE advertisement containing `roomId` in
manufacturer-specific data. The QR code encodes `roomId + PIN-wrapped roomKey` as compact JSON.
The PIN is shown to the room creator; joiners scan and enter PIN to decrypt `roomKey`. Neither
QR nor BLE advertisement leaks `roomKey` in plaintext.

- [ ] "Create Room" action in `HomeFragment` — new FAB secondary action
- [ ] On create: generate `roomId` (32-byte secure random), derive `roomKey` (Task 3 derivation),
  persist via `RoomRepository.createRoom()`, start BLE advertisement
- [ ] Room host UI: QR code display + 6-digit PIN + live participant count via `StateFlow<RoomState>`
- [ ] QR join path: scanner decodes `roomId` + PIN-wrapped `roomKey`, user enters PIN → decrypt
- [ ] BLE scan join path: scan for AURA room advertisements → show room list → tap → enter PIN
- [ ] NFC join path: host serves `roomId + roomKey` via HCE APDU (reuses `AuraHceService` Task 1)
- [ ] Room auto-closes after 10 minutes: `RoomRepository` cancels BLE advertisement, sets CLOSED
- [ ] `RoomViewModel`: `roomState: StateFlow<RoomState>` + `members: StateFlow<List<RoomMember>>`

---

## Task 10 — Multi-Party Card Exchange Service (`RoomExchangeService`)

**Why this follows Task 9:** Bootstrap and join are working. This task implements the card
distribution logic. The room host orchestrates the exchange — members send cards to the host,
the host re-encrypts and forwards to all other members. Star topology by deliberate design: it is
simpler, auditable, and avoids requiring pairwise sessions between all members before cards flow.

**Architecture:** Every `Envelope` is standard `WireProtocol` framing — no new crypto primitives.
Outer encryption uses the recipient's ECDH public key (exchanged during join). The host re-encrypts
using proxy re-encryption without decrypting. No card is considered delivered until an ACK frame
is received (Briar discipline).

See: [code.briarproject.org] — Briar's `SyncProtocol` and message routing layer handle delivery
guarantees and retry logic in a multi-party P2P context. Study their message store and
transport-agnostic delivery model. AURA's star topology is simpler but should inherit Briar's
delivery acknowledgment discipline.

- [ ] `RoomExchangeService.kt`: manages multi-party card routing over the session transport
- [ ] Member → Host: `Envelope(myProfile, encryptedFor: hostPublicKey)`
- [ ] Host → Member: for each member M, forward all other envelopes re-encrypted as
  `Envelope(otherProfile, encryptedFor: M.publicKey)`
- [ ] Re-encryption: `ECDH(hostPrivKey, memberPublicKey)` → re-derive session key → re-wrap
- [ ] Delivery ACK: host emits `ACK(memberId, deliveredCount)` after forwarding completes
- [ ] On room close: all received profiles saved to `ContactDao`
- [ ] Unit tests: 2-member exchange, 5-member exchange, partial delivery with retry
- [ ] Extend `ExchangeAuditLog`: add `roomId: ByteArray?` column — `AuditFragment` groups room exchanges

---

## Task 11 — UWB Proximity Confirmation

**Why here:** With NFC bootstrapping (Tasks 1–3) and BLE GATT transport (Task 7) in place, UWB is
the proximity *confirmation* layer — it proves the person you exchanged with was physically at the
expected distance, not someone across the room who intercepted your BLE advertisement. Requires
BLE GATT as the out-of-band channel for UWB session establishment.

**Architecture:** The `androidx.core.uwb` library (`UwbManager` + `RangingSession`) is used.
The BLE GATT `SESSION_STATUS` characteristic carries UWB session parameters as part of connection
negotiation. Both devices start a UWB ranging session once BLE connects. If distance < 50 cm
AND SAS matches → auto-confirm. Silently disabled when `FEATURE_UWB` is absent.

See: [github.com/dustedrob/multi-platform-uwb] — KMP library covering the BLE → UWB OOB
handoff pattern. The `UwbSessionScope` and ranging callbacks are directly applicable. Study how
they encode `UwbAddress` and `UwbComplexChannel` in the BLE OOB payload.

See: [github.com/Estimote/Android-Estimote-UWB-SDK] for production UWB ranging patterns,
particularly handling `RangingResult` streaming and distance smoothing (UWB measurements are
noisy; apply a sliding-window average over the last 5 measurements).

- [ ] Gate entire feature behind `PackageManager.FEATURE_UWB` runtime check
- [ ] `UwbRangingManager.kt`: wraps `UwbManager.controleeSessionScope()`
- [ ] BLE GATT `EPHEMERAL_KEY` characteristic payload extended with UWB session parameters
- [ ] `ExchangeFragment`: show real-time distance badge ("34 cm") when UWB ranging is active
- [ ] Auto-confirm: `if (distance < 50cm && sasMatch) → skipManualConfirmation()`
- [ ] `ExchangeAuditLog.uwbDistanceCm: Int?` — null when UWB absent
- [ ] Unit test: mock `UwbManager` — verify auto-confirm triggers below threshold, not above

---

## Task 12 — Multi-Sample Gesture Enrollment

**Why now:** First of three sequential gesture intelligence upgrades (Tasks 12–14). Must come before
the LSTM model (Task 13) because the LSTM requires training data from the enrollment pipeline.
Also independently valuable: 5-sample enrollment ships better reliability to users immediately.

**Architecture:** `GestureAuthManager.enrollGesture()` signature changes from single
`LandmarkVector` to `List<LandmarkVector>`. Authentication becomes mean cosine similarity across
all enrolled samples. Enrollment quality = mean pairwise cosine similarity across samples.
Adopt wrist-relative, scale-normalized landmark coordinates from enrollment onward.

See: [github.com/kinivi/hand-gesture-recognition-mediapipe] — their MediaPipe + MLP pipeline
uses wrist-relative coordinates normalized by hand span before classification. AURA should adopt
this normalization to make gestures lighting- and scale-invariant. The normalization is ~30 lines;
port to Kotlin directly.

- [ ] Change `enrollGesture(vector: LandmarkVector)` → `enrollGesture(samples: List<LandmarkVector>)`
- [ ] Enrollment UI: capture 5 samples, show per-sample quality score after each
- [ ] Quality score: cosine similarity of new sample vs. running mean — reject below 0.80
- [ ] Overall enrollment quality: mean pairwise similarity across all 5 accepted samples
- [ ] Authentication: mean cosine similarity across all enrolled samples > threshold (default 0.87)
- [ ] Re-enrollment reminder: if 7-day auth failure rate exceeds 20%, surface in-app banner
- [ ] Migration: existing single-sample enrollments wrapped in a list of size 1 on read

---

## Task 13 — LSTM Temporal Gesture Classifier

**Why this follows Task 12:** Multi-sample enrollment improves the static matcher. This task
replaces the static matcher with a temporal model — the gesture becomes a motion, not a pose.
A single-frame landmark snapshot can be spoofed with a printed photo held up to the camera.
An LSTM over 30 sequential frames cannot. This is the core security upgrade to the gesture gate.

**Architecture:** Model input: `(30 frames × 63 float values)` — 21 landmarks × 3 coordinates
per frame, wrist-relative and scale-normalized (Task 12). Output: softmax over gesture classes.
The LSTM is trained per-user at enrollment time using MediaPipe Model Maker. The resulting `.task`
bundle replaces the cosine comparison in `GestureAuthManager`. Model size target: <500 KB
compressed (existing APK size gate). Inference latency target: <200 ms on Snapdragon 730G.

See: [github.com/ArminSmajlagic/Real-Time-Hand-Gesture-Recognition] — deep LSTM on MediaPipe
landmarks in Python. This is the direct architectural reference: two LSTM layers with dropout,
dense output head. Study their feature engineering and training loop before designing the Android
inference path.

See: [ai.google.dev/edge/mediapipe/solutions/customization/gesture_recognizer] — MediaPipe Model
Maker handles the MediaPipe backbone integration automatically. The training pipeline (Colab →
`.task` export) avoids implementing a custom TFLite pipeline entirely.

See: [developers.google.com/mediapipe/solutions] for `.task` bundle export, training loop, and
int8 quantization configuration.

- [ ] Create `tools/gesture-training/` in repo: Colab notebook + training script
- [ ] Model architecture: 2x LSTM(64 units) → Dropout(0.3) → Dense(gesture_count, softmax)
  Input shape: (30, 63) float32 after normalization
- [ ] Target: <500 KB `.task`, <200 ms inference on Snapdragon 730G-class hardware
- [ ] `GestureAuthManager`: replace cosine comparison with MediaPipe `GestureRecognizer` inference
  on 30-frame buffer; emit result when buffer is full (1 second at 30 fps)
- [ ] `RecordingState`: add `COLLECTING_SEQUENCE` — UI shows a 1-second countdown during capture
- [ ] CI gate: `downloadGestureModel` task validates `.task` bundle format

---

## Task 14 — Liveness Anti-Spoofing

**Why this follows Task 13:** With the LSTM model in place, liveness is a challenge layer added on
top. The LSTM already defeats static photo replay. Liveness adds a randomized challenge to defeat
any pre-recorded video replay attack.

**Architecture:** 300 ms before the capture window opens, `LivenessGuard` randomly selects a
challenge gesture from `["open hand", "fist", "two-finger point"]`. The challenge is displayed
on screen. The user must perform the challenge AND THEN their auth gesture. The 60-frame buffer
captures both: model output must show the challenge in the first 30 frames and the auth gesture
in the second 30. Inter-frame optical flow provides a secondary signal: zero optical flow = static
image = hard fail.

See: [github.com/suyashawari/deaf_speech] — Android app using MediaPipe + TensorFlow for ASL
gesture recognition. Study their multi-gesture pipeline and sequential classifier chaining.

- [ ] `LivenessGuard.kt`: randomly select challenge gesture from set of 3 at session start
- [ ] `ExchangeFragment`: display challenge instruction 300 ms before capture begins
- [ ] Expand buffer to 60 frames: first 30 = challenge, second 30 = auth gesture
- [ ] LSTM inference on challenge window: must match challenge class (confidence > 0.80)
- [ ] Optical flow check: mean frame-to-frame landmark displacement < 3 pixels → fail
- [ ] `livenessConfidence: Float` in auth result — stored in `ExchangeAuditLog`
- [ ] `AuthFailReason.LIVENESS_FAILED` — distinct from gesture mismatch in UI messaging
- [ ] Unit tests: mock landmark sequences with/without challenge compliance, zero-flow detection

---

## Task 15 — Profile Versioning and Update Notifications

**Why here:** Independent of gesture and transport work — can be parallelized with Tasks 12–14.
Must be in place before the contact dedup engine (Task 16) because dedup needs a version number
to determine which data is newer.

**Architecture:** `Profile.version: Int` is monotonically increasing, auto-incremented on any
field mutation. `KnownPeer.lastSeenProfileVersion: Int` tracks the version last received.
On each exchange: if `received.version > stored.lastSeenVersion` → surface "Card updated" banner
with a field-level diff.

- [ ] Add `version: Int` to `Profile` data class (default 1, auto-increment in setter)
- [ ] `ProfileRepository.updateField()`: increments `version` and persists in one transaction
- [ ] Add `lastSeenProfileVersion: Int` to `KnownPeer` entity (new migration)
- [ ] `NearbyExchangeService`: emit `ExchangeEvent.ProfileUpdated(peer, oldVersion, newVersion, changedFields)`
- [ ] `ExchangeFragment`: observe `ProfileUpdated` → non-blocking snackbar "Card updated — N fields changed"
- [ ] `ProfileDiffBottomSheet`: per-field accept/reject with Material 3 color tokens
  (green = added, yellow = changed, red = removed)

---

## Task 16 — Contact Deduplication Engine

**Why this follows Task 15:** Dedup needs version numbers (Task 15) to resolve conflicts
deterministically. Identity-key-anchored dedup prevents duplicate records for the same person
across multiple exchanges.

**Architecture:** After each exchange, look up `KnownPeer` by `identityKeyHash` — the durable
identity anchor (does not change when name or email changes). If a peer with the same
`identityKeyHash` already exists, diff the fields and surface a merge dialog. If not, insert directly.

- [ ] `ContactDao.findByIdentityKeyHash(hash: ByteArray): KnownPeer?`
- [ ] `ContactDao.upsertByIdentity(received: KnownPeer)`: transactional merge
- [ ] `DeduplicationEngine.kt`: emits `DeduplicationEvent` — MERGED, CONFLICT, NEW
- [ ] Conflict resolution UI: `ContactMergeDialog` — split view, old vs. new, per-field accept/reject
- [ ] Auto-merge rule: all changed fields non-empty in received AND version higher → auto-merge
- [ ] Unit tests: upsert on new identity, upsert with higher version, version conflict

---

## Task 17 — On-Device Exchange Analytics

**Why here:** `ExchangeAuditLog` already has all the data. Zero-network read-only aggregation.
Can be parallelized with Tasks 12–16. Reads from the schema stabilized by Tasks 10 and 15.

- [ ] `AnalyticsFragment`: reads `ExchangeAuditLog` only — no network, no new DB writes
- [ ] Metrics: total exchanges (week / month / all-time), transport breakdown (pie chart),
  exchange heatmap by day-of-week × hour-of-day, unique contacts count
- [ ] Add `transport: TransportType` column to `ExchangeAuditLog` (new migration)
- [ ] Export as PDF: local share sheet only — no cloud until user explicitly taps share
- [ ] Room exchanges (Task 10) grouped separately: "N room sessions, M cards total"

---

## Task 18 — Smart Share Presets

**Why here:** Independent UX work. Can be parallelized with any above tasks.

**Architecture:** A preset is a named `DataStore` entry containing `Map<ProfileField, Boolean>`.
Activating a preset sets the active field mask applied at serialization time in
`WireProtocol.serializeProfile()`. Transient — applies to the next exchange only, then resets
unless pinned.

- [ ] `SharePreset` data class: `id, name, fieldMask: Map<ProfileField, Boolean>`
- [ ] `PresetRepository`: CRUD via DataStore (up to 5 presets)
- [ ] `HomeFragment`: long-press exchange FAB → preset picker bottom sheet
- [ ] Quick-settings tile sub-action: cycle through presets from the notification shade
- [ ] [R&D] Context auto-detect: calendar event title contains preset name keywords → suggest preset

---

## Task 19 — Share AURA Deeplink

**Why here:** The deeplink → Add Contact flow already shipped (PR #89). This task extends it with
the static web landing page and full round-trip testing.

See: The vCard is in the URL fragment (`#`) which browsers do not send in HTTP requests — server
sees zero plaintext profile content.

- [DONE] Deeplink → pre-filled Add Contact bottom sheet (PR #89)
- [ ] `docs/deeplink-landing/` GitHub Pages: `index.html` + JS decodes URL fragment
  → triggers `text/vcard` download with correct `Content-Disposition` header
- [ ] `AndroidManifest.xml`: `<intent-filter>` for `https://aura.app/c/*` deeplink handling
- [ ] Unit tests: serialize → base64url → deserialize round-trip for all profile field types

---

## Task 20 — SAS Dialog Hardening

**Why here:** `SasVerifierDialog` exists and works. This polishes it to production-grade.

- [ ] 30-second countdown timer — progress indicator ring in dialog
- [ ] Auto-abort with `SasOutcome.TIMEOUT` if countdown expires
- [ ] `VibrationEffect.createOneShot(200ms, AMPLITUDE_MAX)` haptic when dialog appears
- [ ] `ExchangeAuditLog`: add `sasConfirmedAt: Long?` and `sasOutcome: SasOutcome` columns
- [ ] Wire SAS dialog into QR relay exchange path (currently Nearby-only)
- [ ] Espresso test: mock `NearbyExchangeService` broadcast → verify dialog + correct 6-digit code

---

## Task 21 — Test Coverage Hardening (Milestone-Gated)

Current floor: JaCoCo branch coverage 60% (achieved in v2.1.0).

- [DONE] JaCoCo floor at 60% (v2.1.0)
- [ ] Raise floor to 70% — target: at completion of Task 14 (Liveness)
  - `NearbyExchangeService` state transition tests using `FakeNearbyTransport`
  - `RoomExchangeService` multi-party tests: 2-party, 5-party, partial delivery + retry
  - `GestureAuthManager` LSTM inference path tests with mock `GestureRecognizer`
  - `LivenessGuard` optical flow failure detection unit tests
- [ ] Raise floor to 80% — target: at completion of Task 17 (Analytics)
  - `QRExchangeViewModel` relay state machine tests
  - `ContactDao` upsert + dedup transaction tests

---

## Task 22 — Accessibility Improvements

**Why here:** Accessibility is infrastructure. Hardened before any new platform additions so that
patterns are established and propagated to new platforms at creation time, not retrofitted.

- [ ] Automated Accessibility Scanner in CI: `AccessibilityChecks.enable()` — fails build on violations
- [ ] `contentDescription` audit: all icon-only elements across all fragments
- [ ] Touch-target size audit: all interactive elements >= 48x48 dp
- [ ] `accessibilityLiveRegion="polite"` on SAS dialog 6-digit code display
- [ ] `importantForAccessibility="no"` on decorative identicon thumbnails
- [ ] TalkBack navigation test: complete an exchange start-to-finish — documented in `docs/TESTING.md`

---

## Task 23 — Volume Button Reliability Remediation

- [ ] Audit `KeyEvent.KEYCODE_VOLUME_DOWN/UP` capture on 5 test devices:
  Pixel, Samsung One UI, MIUI, OxygenOS, stock AOSP emulator
- [ ] Evaluate `AccessibilityService` path and `MediaSession` + `MediaButtonReceiver` as alternatives
- [ ] Implement winning approach — gate behind `Settings -> Trigger -> Volume button (experimental)`
- [ ] `docs/VOLUME_BUTTON_OEM_MATRIX.md`: per-OEM status + recommended workaround

---

## Task 24 — Localization Quality Review

- [DONE] 9 untranslated strings fixed across all 7 locales (PR #95)
- [DONE] `strings_review_log.md` — all locales marked complete (PR #96)
- [ ] Second-pass native-speaker review focused on: gesture instruction text (precision critical),
  error messages (must be actionable), UI labels in narrow layouts (truncation risk)
- [ ] Screenshot automation via Screengrab / Fastlane for all locales — catch layout truncation

---

## Task 25 — QR Relay Self-Hosting Documentation

- [ ] `docs/qr-relay-setup.md`: step-by-step for two options:
  - Firebase Realtime Database (zero-ops, free tier) — POST/GET contract, slot TTL, CORS config
  - Cloudflare Workers (zero-ops, 100k req/day free) — complete worker JS in `tools/relay-worker/`
- [ ] `BuildConfig.QR_RELAY_BASE_URL`: configurable at build time

---

## Task 26 — Custom Gesture Training Pipeline

**Why this follows Task 13:** Task 13 built the LSTM pipeline. This externalizes it so users and
third-party forks can define custom gestures. Requires the `.task` bundle format and Model Maker
pipeline to be stable before documentation and tooling are published.

See: [ai.google.dev/edge/mediapipe/solutions/customization/gesture_recognizer] — official custom
gesture training pipeline. Images organized by `<label>/<image.*>` → Model Maker extracts
landmarks automatically, trains classification head, exports `.task` bundle.

See: [github.com/gitmax681/hand-gesture-classification] — Euclidean-normalized landmark features
with TensorFlow. Study feature engineering as a complement to the Model Maker path.

- [ ] `tools/gesture-training/` Colab notebook: end-to-end custom gesture training
- [ ] `tools/gesture-training/README.md`: "How to add a new gesture class to AURA"
- [ ] `BuildConfig.GESTURE_MODEL_URL`: overridable model registry URL for third-party builds
- [ ] CI `downloadGestureModel` task: validate `.task` bundle magic bytes and schema version

---

## Task 27 — Gesture Library: Multiple Gestures per User

**Why this follows Tasks 13 and 26:** LSTM supports multi-class output. Training pipeline can
produce N-class models. This wires classifier output into a per-user gesture library with
profile-switching by gesture.

- [ ] `GestureAuthManager`: support named gesture library — up to 5 enrolled gestures
- [ ] Each entry: `gestureId, name, enrolledAt, associatedProfile: ProfileType`
- [ ] On auth: infer all enrolled gestures in one model pass; winning class = highest-confidence
- [ ] Winning gesture maps to different profile than current → auto-switch + snackbar
- [ ] Settings → Gesture → Manage library: list view, add / delete / re-enroll / rename
- [ ] Deletion: zero-fill enrolled landmark data before removing (sensitive biometric data)

---

## Task 28 — Enterprise MDM: Managed Configuration

**Why here:** Enterprise features added after core product is stable and all distribution channels
working. Must be in place before the enterprise audit dashboard (Task 29).

**Architecture:** Android Managed Configuration (`app:managedConfigurations` in `AndroidManifest`)
allows EMM administrators to push JSON key-value configuration to managed devices.
`EnterpriseConfigRepository` reads this at startup via `RestrictionsManager` and enforces it
throughout the app. Configuration is read-only from the app's perspective.

See: [developer.android.com/work/managed-configurations] — official Managed Configuration docs.

- [DONE] Enterprise WorkManager audit-log retention cleanup job (PR #91)
- [ ] `res/xml/app_restrictions.xml`: declare configurable keys:
  `allowed_transports`, `enforce_gesture_only`, `audit_log_retention_days`,
  `disable_deeplink`, `require_sas_confirmation`, `qr_relay_base_url`
- [ ] `EnterpriseConfigRepository.kt`: `RestrictionsManager` at startup → `StateFlow<EnterpriseConfig>`
- [ ] `EnterpriseConfig` injected via Hilt into every service and ViewModel that enforces policy
- [ ] `IS_ENTERPRISE` build config flag gates enterprise-only Settings UI sections

---

## Task 29 — Zero-Touch Enrollment and Audit Export

**Why this follows Task 28:** Managed config exists. Extends it with EMM-driven profile
pre-provisioning and scheduled audit log export.

- [ ] On first launch with managed config present: read pre-provisioned profile fields from config —
  skip onboarding for managed fields
- [ ] Scheduled audit export: if `audit_export_endpoint` is set, POST CSV to that endpoint on schedule
- [ ] Export signed with device attestation key — receiving endpoint can verify authenticity
- [ ] `WorkManager` task: runs export, retries with exponential backoff on network failure

---

## Task 30 — Key Rotation Broadcasting

**Why here:** Key rotation storage was implemented in Phase 6.5 (v2.0.0). The rotation certificate
is generated and stored locally but never sent to existing contacts. This task closes that gap.

**Architecture:** The rotation certificate is included as a header field in `WireProtocol` framing
on the next exchange with any known peer. The peer verifies the certificate signature (must be
signed by the old key to prove key continuity) before updating their TOFU registry.

- [ ] `WireProtocol` v8 header: optional `ROTATION_CERT` field —
  `oldKeyHash || newPublicKey || signature`
- [ ] `NearbyExchangeService`: if local key was rotated since last exchange with this peer,
  include rotation cert in outbound frame header
- [ ] Receiver: verify `signature = Ed25519Sign(oldPrivKey, newPublicKey || oldKeyHash)`
  → `KnownPeerDao.applyRotationCert()` transaction
- [ ] If verification fails: reject rotation, flag in audit log as `KEY_ROTATION_REJECTED`
- [ ] Unit test: rotation cert round-trip — sign with old key, verify on receiver, assert TOFU updated

---

## Task 31 — Certificate Pinning Hardening

**Why here:** SPKI runtime pinning shipped in v2.0.1 (PR #85). This hardens operational aspects.

- [ ] Auto-pin expiry check: at startup, parse pinned certificate `notAfter` →
  if `notAfter - now < 30 days`, surface in-app banner
- [ ] Two-pin configuration: `SPKI_PIN_PRIMARY` and `SPKI_PIN_BACKUP` in `BuildConfig`
- [ ] Pin violation logging: on `SSLPeerUnverifiedException`, write `ExchangeAuditLog` entry
  with outcome `SPKI_MISMATCH` and observed certificate hash
- [ ] Document pin rotation procedure in `docs/SECURITY.md`

---

## Task 32 — Sealed Sender: Traffic Analysis Resistance

**Why here:** Protocol-level privacy hardening before the post-quantum work (Task 33). Sealed
sender is a simpler change and should ship first to give it time to soak.

**Architecture:** Current wire frames have variable length correlated to profile size — a passive
traffic observer can infer an exchange occurred and estimate profile size. Sealed sender pads all
payloads to a fixed 2048-byte ciphertext block. Inner plaintext includes a 2-byte actual-length
prefix; padding is random bytes. All AURA frames on-wire are identical length.

- [ ] `SealedEnvelope.kt`: wraps AES-256-GCM ciphertext in fixed-2048-byte outer envelope
- [ ] `WireProtocol.serializePayload()`: always wraps in `SealedEnvelope`
- [ ] `WireProtocol.deserializePayload()`: unwrap `SealedEnvelope`, trim padding using length prefix
- [ ] Unit test: 50-byte and 1900-byte payloads both produce identical-length sealed envelopes

---

## Task 33 — Post-Quantum Hybrid KEM (ML-KEM-768 + X25519)

**Why here:** Largest single protocol change in the roadmap. Must follow sealed sender (Task 32)
to keep change surfaces manageable and reviewable separately.

**Architecture:** Hybrid KEM uses both classical X25519 and ML-KEM-768 — session key derived from
both via HKDF. If either algorithm is broken, the session remains secure. This is the design used
by Signal (PQXDH) and Apple (IKEv2 + PQ). NIST finalized ML-KEM (CRYSTALS-Kyber, FIPS 203) in
August 2024. BouncyCastle 1.72+ has ML-KEM support for Android.

See: [github.com/MichaelsPlayground/PostQuantumCryptographyBc172] — BouncyCastle 1.72 ML-KEM
implementation with working Kotlin examples of key generation, encapsulation, and decapsulation.
BouncyCastle adds ~1.5 MB to the APK; gate behind enterprise flavor until R8 shrinking is confirmed.

See: [github.com/veorq/awesome-post-quantum] — curated PQ crypto reference list covering
finalized NIST standards, implementation libraries, and attack papers.

See: [github.com/signalapp/libsignal] — Signal's cross-platform crypto primitives and PQXDH
design for the hybrid approach rationale and protocol version negotiation patterns.

- [ ] Add `org.bouncycastle:bcprov-jdk18on:1.78+` to `pq` build variant
- [ ] `HybridKemEngine.kt`: `X25519 keypair || ML-KEM-768 keypair` → combined public key;
  session key: `HKDF(x25519_shared || mlkem_shared, salt=sessionNonce, info="aura-pq-v1")`
- [ ] `WireProtocolNegotiator.kt`: version handshake — advertise v8 (PQ); fall back to v7 if needed
- [ ] `WireProtocol` v8: extended public key field (65 bytes X25519 + 1184 bytes ML-KEM-768)
- [ ] APK size gate: allow +1.5 MB for `pq` variant only
- [ ] Wire protocol negotiation tests: v8 <-> v8 (PQ), v8 <-> v7 (fallback)

---

## Task 34 — iOS Companion App Full Expansion

**Why here:** iOS AuraCore companion shipped in v3.0.0 (PR #97/98). This task expands AuraCore
to a full exchange-capable app with NFC and MultipeerConnectivity transport.

Wire protocol must be fully stable (Tasks 30–33) before cross-platform work expands. Any
protocol change after iOS ships requires coordinated updates to two codebases.

`WireProtocol.kt` is the specification. iOS implements `WireProtocol.swift` — identical byte-for-
byte. Algorithms: P-256 ECDH (CryptoKit `P256.KeyAgreement`), HKDF-SHA256 (CryptoKit `HKDF`),
AES-256-GCM (CryptoKit `AES.GCM`). SAS verifier: same truncated-HMAC-SHA256 → same 6-digit code.

See: [github.com/signalapp/libsignal] — Signal's approach to maintaining algorithmic parity
across Swift, Kotlin, and Rust. Treat one implementation as the spec; the others are ports.

- [DONE] AuraCore — ContactProfile, SasVerifier, AuraExchangeCoordinator + 15 tests (PR #97)
- [DONE] iOS CI — cache, coverage, workflow_dispatch, 20-min timeout (PR #98)
- [ ] NFC: `NFCTagReaderSession` in reader mode → send SELECT AID → receive peer key
- [ ] MultipeerConnectivity transport for bulk payload — peer discovery via NFC handoff only
- [ ] vCard payload: identical field names and ordering to Android — round-trip test
- [ ] TOFU registry in CoreData — same logic as `KnownPeerDao` on Android
- [ ] Cross-platform integration test: Android initiator → iOS responder → contact saved correctly

---

## Task 35 — Wear OS Full Companion

**Why this follows Task 34:** Uses the same `WireProtocol` Kotlin code. Extracting the shared
`:protocol` KMP module is easier after iOS because the cross-platform boundary is already defined.

- [DONE] Wear OS pairing flow — WearPairingViewModel + BottomSheet + PhoneWearSender (PR #93)
- [ ] Extract `WireProtocol`, `SasVerifier`, crypto primitives into `:protocol` KMP module
- [ ] Wear OS `ExchangeTile`: "Ready" / "Active" states via Glance
- [ ] Tile tap → sends activation `Intent` to paired phone via `ChannelClient`
- [ ] Wrist-raise: `SensorManager` accelerometer → detect raise → trigger on phone (opt-in)
- [ ] SAS PIN display on watch face — receive PIN via `ChannelClient`

---

## Task 36 — Android Auto Full Integration

- [DONE] Android Auto voice action + biometric auth gate (PR #92)
- [ ] TTS announcement: "New contact received: [name], [title], [company]" via `CarContext`
- [ ] `CarAppService` screen: last 5 received contacts — tap to confirm save
- [ ] Explicit disable of gesture + NFC trigger when connected to Android Auto
- [ ] Manual test recipe in `docs/TESTING.md` for Auto mode

---

## Task 37 — Delay-Tolerant Store-and-Forward Exchange

**Why here:** First mesh networking task. Implemented on top of BLE GATT transport (Task 7) without
multi-hop routing. Store-and-forward is strictly simpler than mesh; this task sets up the
persistence and relay protocol; Task 38 adds routing.

**Architecture:** Alice creates an exchange packet encrypted to Bob's public key. Bob is not in
range. Alice's BLE GATT advertisement includes a bloom filter of `SHA256(targetIdentityKeyHash)`
values — pre-image resistant, no PII in advertisement. When Carol's device sees Alice's
advertisement and Carol has Bob in her TOFU registry, Carol downloads and stores the packet. When
Carol meets Bob, she delivers it. Carol never has plaintext access.

See: [code.briarproject.org] — Briar's `BrambleTransportProtocol` and message store-and-forward.
Their privacy-preserving advertisement mechanism (hash of recipient ID in BLE advertisement) is
exactly the model here. Briar's design specification is required reading before implementation.

- [ ] `PendingExchangeQueue` Room entity:
  `packetId`, `targetIdentityKeyHashBlind` (SHA256 of target hash), `encryptedPayload`,
  `createdAt`, `expiresAt` (TTL: 24 hours), `relayHopCount` (max 3)
- [ ] BLE GATT advertisement: bloom filter of `targetIdentityKeyHashBlind` in manufacturer data
  (Golomb-Rice bloom, ~64 bytes for 10 pending packets)
- [ ] On seeing advertisement: check TOFU registry against bloom filter → if hit → connect →
  download matching packets → store in `PendingExchangeQueue`
- [ ] On meeting a target: deliver pending packets before exchanging own card
- [ ] `WorkManager` task: purge expired `PendingExchangeQueue` entries every 6 hours
- [ ] Unit tests: bloom filter false-positive rate < 1%, packet relay to correct target

---

## Task 38 — Multi-Hop Wi-Fi Direct Mesh Routing

**Why this follows Task 37:** Store-and-forward is single-hop relay. This extends to multi-hop:
packets traverse a chain of AURA devices. Each hop decrements a TTL counter; TTL=0 drops.
All packets remain encrypted to the original recipient — intermediate nodes cannot decrypt.

**Architecture:** Each device maintains `MeshRoutingTable`:
`identityKeyHash -> (lastSeenAt, hopCount, nextHopAddress)`. When a packet arrives with TTL > 0,
look up target in routing table — deliver directly if in range, else forward to next-hop with
TTL decremented. Maximum 5 hops.

See: [github.com/UstadMobile/Meshrabiya] — WiFi Direct multi-hop mesh for Android. Their
`VirtualNode`, routing table, and group-owner IP assignment are directly applicable. Meshrabiya
uses local-only hotspot for the underlay network. Study before designing `MeshRoutingTable.kt`.

See: [github.com/andreas-mausch/MeshAndroid] — WiFi ad-hoc + OLSR routing on Android. Lighter
reference useful for understanding OLSR metric design.

See: [github.com/moarpepes/awesome-mesh] — curated survey of Android mesh implementations.

- [ ] `MeshRoutingTable.kt`: `Map<IdentityKeyHashBlind, RoutingEntry>` with TTL-based eviction
- [ ] Routing table updates on each BLE scan result and on each completed exchange
- [ ] Packet forwarding: receive → look up → forward to next hop → decrement TTL
- [ ] Local hotspot underlay (Meshrabiya pattern)
- [ ] `MeshMetrics` logged to `ExchangeAuditLog` with type `MESH_RELAY`
- [ ] Maximum path length: 5 hops — drop packet and log if TTL reaches 0

---

## Task 39 — LoRa Integration via Meshtastic

**Why this is last in the transport sequence:** Requires dedicated hardware (LoRa radio chip via
USB OTG or Bluetooth). Not a default transport — optional for festivals, hiking, disaster response.
Depends on all prior transport and mesh work being stable.

See: [github.com/meshtastic/Meshtastic-Android] — Meshtastic AIDL interface definition. Study
their KMP architecture (`core:ble`, `core:domain` in `commonMain`) — this is the long-term model
for AURA's cross-platform module split (Task 35 begins this).

See: [github.com/forresttindall/Meshtastic-LoRa-Radio] — LoRa hardware + Meshtastic protocol.

- [ ] [R&D] Bind to Meshtastic AIDL service — confirm API surface and message size constraints
- [ ] `LoRaTransport.kt`: sends via Meshtastic AIDL; LZ4-compresses before send;
  reassembles fragments on receive
- [ ] LZ4 target: AURA vCard < 256 bytes compressed (single Meshtastic message)
- [ ] Gate behind `BuildConfig.ENABLE_LORA = false` — off by default
- [ ] Settings → Advanced → Meshtastic integration (visible only if Meshtastic is installed)

---

## Task 40 — Desktop Companion (Kotlin Multiplatform)

**Why this is last:** Long-horizon. Requires the `:protocol` KMP module from Task 35 to be stable
and battle-tested on Android + iOS first. Desktop BLE is not available on most machines → QR
relay is the primary transport.

See: [github.com/meshtastic/Meshtastic-Android] — their KMP split (`commonMain` for domain,
platform targets for hardware APIs) is the direct architectural reference.

- [ ] [R&D] Compose Desktop: evaluate Compose Multiplatform 1.6+ for desktop target
- [ ] `jvmMain` (desktop): `QRRelayTransport` as primary transport
- [ ] `commonMain` already contains `WireProtocol`, `SasVerifier`, crypto (from Task 35)
- [ ] Desktop is a valid exchange peer — contact received from desktop AURA is indistinguishable

---

## Tasks in Research / Design Phase

The following are design-tracked. Each has an explicit trigger condition that moves it from
[R&D] to a scheduled implementation task.

### R&D-A — QR Relay Anonymisation

Route QR relay via Tor or zero-knowledge relay scheme.

See: [github.com/guardianproject/tor-android] for Android Tor integration.
ZK relay: client posts `H(sessionId)`, server stores ciphertext keyed by hash — receiver queries
by hash; server cannot link sender IP to receiver IP.

- [R&D] Evaluate `guardianproject/tor-android` for QR relay path
- Trigger: implement only if user research confirms real-world demand

### R&D-B — Remote Blocklist via Transparency Log

Privacy-preserving opt-in blocklist. Identity keys hashed with per-device pepper before any
submission. MUST be reviewed by an external cryptographer before any implementation.

See: [google/trillian] and [sigsum.org] for append-only log infrastructure.
See Signal's contact discovery for the PSI (Private Set Intersection) query pattern.

- [R&D] Review Trillian / Sigsum options
- Trigger: only after external cryptographic review is complete

### R&D-C — Contact Graph Privacy Analysis (PSI)

On-device detection of mutual contacts without revealing full lists.

See: [signal.org/blog] — Signal's contact discovery uses PSI.
See: Kales et al. "Private Contact Discovery" (2019).

- [R&D] Evaluate Diffie-Hellman PSI for AURA's identity key set cardinality

### R&D-D — Decentralized Identity (DID) Integration

Map the existing AURA P-256 identity key to a W3C `did:key` DID.
`did:key` requires no blockchain: `did:key:z<multibase(publicKey)>`.

See: [w3.org/TR/did-core] and [w3c-ccg.github.io/did-method-key].

- [R&D] Derive `did:key` from existing P-256 key — verify round-trip encoding
- Trigger: only if DID ecosystem integration adds user-visible value

### R&D-E — AI Gesture Coaching

After enrollment, report per-landmark variance to help the user improve consistency.
Uses the same LSTM landmark comparison engine (Task 13) in reporting mode.

- [R&D] `GestureCoach.kt`: per-landmark standard deviation across enrolled samples
- Trigger: schedule after gesture library (Task 27) has real-world usage data

### R&D-F — Wearable Biometric Fusion (HRV Second Factor)

Wear OS HRV as a second factor: `finalScore = 0.7 * gestureScore + 0.3 * hrvScore`.

See: [arXiv:1309.0073 — SilentSense] — behavioral biometrics combining touch, motion, and
physiological signals.
See: [github.com/BharathVishal/Biometric-Authentication-Android] — Jetpack Compose + Material 3
biometric API patterns.
See: [github.com/fmeum/WearAuthn] — Wear OS FIDO2 via BLE + NFC. `ChannelClient` pattern for
passing auth signals from Wear to phone is the implementation reference.

- [R&D] Review HRV uniqueness literature — is HRV sufficiently stable for biometric use?
- Trigger: only implement if HRV uniqueness confirmed with > 95% accuracy

### R&D-G — AR Exchange Overlay (ARCore)

Point phone at another AURA user → floating AR card appears → tap to confirm exchange.
Bilateral explicit consent required before any face detection activates.

- [R&D] ARCore face detection latency at exchange distances (1–3 m)
- Trigger: only as opt-in enterprise feature with privacy review

### R&D-H — Satellite Fallback

Route exchange packets via satellite SMS (Android 14+ `SatelliteManager`, Iridium, Garmin inReach).
AURA packet must compress to < 160 characters (one SMS unit).

- [R&D] Android 14+ `SatelliteManager` API — assess message size constraints and latency
- [R&D] LZ4 + base91 encoding — measure compressed size for typical profiles
- Trigger: only after LoRa integration (Task 39) ships and demand for longer-range paths exists

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

---

## What This Roadmap Delivers

By executing the tasks above in sequence, AURA evolves from a production-grade bilateral contact
exchange app into a comprehensive, secure, multi-platform identity exchange protocol.

*Physical layer:* NFC tap becomes a cryptographic hardware primitive — tap = identity-verified
session seed. Every exchange, room, and mesh relay is anchored to a physical touch event.
The NFC bootstrapping chain (Tasks 1–3) makes all subsequent transport work cryptographically
stronger by giving it a trust anchor that does not depend on software alone.

*Transport independence:* Three fully FOSS transport implementations (Wi-Fi Direct, BLE GATT,
LoRa via Meshtastic) give AURA zero dependency on Google Mobile Services. The app is F-Droid
distributable and runs on any Android device including de-Googled LineageOS installations.

*Room exchanges:* N people in the same space tap in once and all business cards flow to everyone
simultaneously. The conference table use case is fully covered with cryptographic isolation between
sessions and a 10-minute auto-expiry. The star-topology multi-party service (Task 10) handles
up to N members with delivery acknowledgment, no pairwise session bootstrapping required.

*Gesture authentication v2:* The gesture gate graduates from a single-frame cosine match to a
temporal LSTM classifier with liveness challenge-response. Spoofing requires intercepting a real
user's 60-frame motion sequence with correct challenge compliance in real time — practically
infeasible. The gesture library (Task 27) enables up to 5 distinct gestures per user, each
mapped to a different profile, enabling fluid persona switching via physical gesture alone.

*Offline resilience:* Through store-and-forward (Task 37) and multi-hop mesh routing (Task 38),
AURA exchanges work with no infrastructure. Two AURA devices meeting can relay cards for a third
device out of range. In festival, disaster, and censorship-resistance scenarios, AURA is a
self-contained identity exchange network. LoRa integration (Task 39) extends this to kilometre-
scale ranges using commodity Meshtastic hardware.

*Post-quantum security:* The hybrid KEM (X25519 + ML-KEM-768) in Task 33 is harvest-now-decrypt-
later resistant. An adversary who records AURA exchange ciphertext today cannot decrypt it with a
future quantum computer because breaking the session requires breaking both classical and
post-quantum algorithms simultaneously. Combined with sealed sender (Task 32), which pads all
frames to a fixed 2048-byte length, AURA is resistant to both computational and traffic-analysis
attacks at the transport layer.

*Enterprise readiness:* MDM-administered managed configuration, pre-provisioned profiles,
scheduled signed audit export, and transport restriction policies (Tasks 28–29) make AURA
deployable in regulated environments — corporate directories, healthcare staff introductions,
government ID exchange — without any cloud dependency or Play Store requirement.

*Cross-platform completeness:* iOS (Task 34), Wear OS (Task 35), Android Auto (Task 36), and
desktop (Task 40) implementations share the same wire protocol. A contact exchanged between an
iPhone and an Android device is cryptographically identical to one exchanged between two Android
phones. The `:protocol` KMP module (Task 35) is the single source of truth for all platforms.

---

*Last updated: 2026-05-26 — Full rewrite from phase-based to dependency-ordered task sequence.
v3.2.0 stable on main. PRs #62–#101 merged. Next implementation target: Task 1 NFC APDU chaining,
Task 4 Wi-Fi Direct transport, Task 8 Room schema.*
