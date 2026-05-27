# Exchange flow

> An exchange begins when both users open AURA and tap Exchange, the gesture/biometric gate has cleared on each side, and the two phones are within Nearby Connections range. The sequence below walks every byte sent in a successful direct exchange.
>
> AURA supports three exchange paths: Nearby Connections (primary), QR relay (fallback), and DIDComm v2 (identity-layer).
> See §4 below for the DIDComm exchange flow and ISO 18013-7 async mDL presentation path.

---

## 1. The happy path — direct (1-to-1) exchange

```mermaid
sequenceDiagram
    autonumber
    actor A as User A
    actor B as User B
    participant AppA as A: AURA
    participant NCA as A: NearbyExchangeService
    participant NCB as B: NearbyExchangeService
    participant AppB as B: AURA

    A->>AppA: open app → tap Exchange
    B->>AppB: open app → tap Exchange
    AppA->>AppA: gesture / biometric gate ✅
    AppB->>AppB: gesture / biometric gate ✅
    AppA->>NCA: start()
    AppB->>NCB: start()

    par advertise + discover
        NCA-->>NCB: advertise SERVICE_ID
        NCB-->>NCA: advertise SERVICE_ID
    end

    NCA->>NCB: requestConnection(endpointName)
    NCB-->>NCA: acceptConnection()
    NCA-->>NCB: acceptConnection()

    Note over NCA,NCB: Channel is now encrypted by<br/>Nearby Connections itself (ECDH+AES-GCM)<br/>but we layer our own crypto on top.

    rect rgb(245,243,255)
    note over NCA,NCB: Phase 1 — PQ-hybrid KEM (ML-KEM-768 + X25519)
    NCA->>NCB: MSG_TYPE_PUBLIC_KEY ‖ HelloPayload(x25519_pub ‖ mlkem768_pub)
    NCB->>NCB: kemSession = responderSession(HelloPayload)
    NCB->>NCA: MSG_TYPE_PUBLIC_KEY ‖ HelloAckPayload(x25519_eph_pub ‖ mlkem768_ciphertext)
    NCA->>NCA: sharedKey = completeInitiatorSession(HelloAckPayload) → 32-byte KEM secret → AES-256
    NCB->>NCB: sharedKey already set from responderSession() → AES-256
    end

    rect rgb(254,242,242)
    note over NCA,NCB: Phase 2 — identity challenge
    NCA->>NCB: MSG_TYPE_CHALLENGE ‖ idPubA ‖ nonce_a
    NCB->>NCB: sign(nonce_a, idPrivB)
    NCB->>NCA: MSG_TYPE_CHALLENGE_RESPONSE ‖ idPubB ‖ sig_b
    NCA->>NCA: ECDSA verify(sig_b, nonce_a, idPubB) ✅
    NCB->>NCA: MSG_TYPE_CHALLENGE ‖ idPubB ‖ nonce_b
    NCA->>NCA: sign(nonce_b, idPrivA)
    NCA->>NCB: MSG_TYPE_CHALLENGE_RESPONSE ‖ idPubA ‖ sig_a
    NCB->>NCB: ECDSA verify(sig_a, nonce_b, idPubA) ✅
    end

    rect rgb(240,253,244)
    note over NCA,NCB: Phase 3 — encrypted profile + replay window
    NCA->>NCB: MSG_TYPE_PROFILE ‖ AES-GCM(profileJSON_A + _ts + _nonce)
    NCB->>NCB: PayloadValidator: _ts recency check + nonce not in dedup set
    NCB->>NCB: store(contact_A)
    NCB->>NCA: MSG_TYPE_PROFILE ‖ AES-GCM(profileJSON_B + _ts + _nonce)
    NCA->>NCA: PayloadValidator: _ts recency check + nonce not in dedup set
    NCA->>NCA: store(contact_B)
    end

    rect rgb(255,251,235)
    note over NCA,NCB: Phase 4 — optional avatar
    NCA-->>NCB: MSG_TYPE_AVATAR ‖ idPubA ‖ streamId
    NCA-->>NCB: STREAM payload (avatar bytes)
    NCB-->>NCA: MSG_TYPE_AVATAR ‖ idPubB ‖ streamId
    NCB-->>NCA: STREAM payload (avatar bytes)
    end

    NCA->>AppA: ExchangeSession.State.Completed
    NCB->>AppB: ExchangeSession.State.Completed
    AppA-->>A: ExchangeSuccessBottomSheet (contact name, phone, email)
    AppB-->>B: ExchangeSuccessBottomSheet (contact name, phone, email)
```

### Notes on the phases

- **Phase 1** runs a **post-quantum hybrid KEM** (ML-KEM-768 + X25519) *on top of* the encryption Nearby Connections already provides. The initiator sends a `HelloPayload` (X25519 pub + ML-KEM-768 pub); the responder encapsulates against both, sends back a `HelloAckPayload` (X25519 ephemeral pub + ML-KEM-768 ciphertext). Both derive a 32-byte shared secret via `HKDF-SHA256(mlkem_ss ‖ x25519_ss)`. The session key breaks only if *both* algorithms are broken simultaneously. NFC-bootstrapped sessions fall back to classical ECDH for the bootstrap step only.
- **Phase 2** binds the session to each side's Android-Keystore **ML-DSA-65 + ECDSA P-256 hybrid** identity key. The nonce is 32 cryptographically random bytes; the signature is over `nonce ‖ idPub` to prevent cross-protocol misuse.
- **Phase 3** wraps the JSON profile in AES-GCM using the Phase-1 derived key. Two replay-protection fields are stamped into the plaintext envelope by `PayloadValidator.stamp()`: `_ts` (current epoch ms) and `_nonce` (random UUID). On receipt, `PayloadValidator.validate()` checks `_ts` is within the allowed recency window and that `_nonce` has not been seen before — a bounded `ConcurrentHashSet` (max 1,000 entries, purged every 5 min) acts as the dedup store.
- **Phase 4** is optional. The avatar travels as a Nearby Connections `STREAM` payload (not `BYTES`), so multi-megabyte images don't block the small text messages.

---

## 2. Room mode (1 host : N guests, )

```mermaid
sequenceDiagram
    autonumber
    actor H as Host
    actor G1 as Guest 1
    actor G2 as Guest 2
    participant NCH as Host: NearbyExchangeService
    participant NCG1 as Guest 1: NearbyExchangeService
    participant NCG2 as Guest 2: NearbyExchangeService

    H->>NCH: startRoomHost()
    NCH-->>NCH: advertise (P2P_STAR)
    G1->>NCG1: startRoomGuest()
    G2->>NCG2: startRoomGuest()
    NCG1-->>NCH: discovery → requestConnection
    NCG2-->>NCH: discovery → requestConnection
    NCH->>NCG1: accept
    NCH->>NCG2: accept

    par per-guest exchange
        NCH<<->>NCG1: Phases 1–4 (as in §1)
    and
        NCH<<->>NCG2: Phases 1–4 (as in §1)
    end

    NCH-->>H: "2 guests onboarded"
```

In room mode the host runs `P2P_STAR` so multiple guests can connect simultaneously; each guest still does its own ECDH and challenge so the host receives **N** independent secure sessions, not a broadcast.

---

## 3. QR fallback

When BLE / Wi-Fi P2P is blocked (some corporate venues, locker-room metal cages), the user can switch to QR mode:

```mermaid
sequenceDiagram
    actor A
    actor B
    participant A_App as A: QRExchangeFragment
    participant B_App as B: QRExchangeFragment

    A->>A_App: open QR mode
    A_App->>A_App: encode profile JSON → QR
    B->>B_App: open QR mode → camera
    B_App->>B_App: scan QR → parse JSON
    B_App->>B_App: PayloadValidator.validate()
    B_App->>B_App: save Contact
    B-->>A: shows their QR
    A_App->>A_App: scan → save
```

The QR payload is **not** encrypted on the wire (a camera in line of sight is the channel), but it still passes through `PayloadValidator` to reject oversized fields, foreign keys, and embedded HTML.

---

## 4. Failure / abort paths

```mermaid
flowchart LR
    Start([exchange initiated]) --> Gate{gesture/biometric?}
    Gate -- fail x3 --> Abort1[Cancelled: gesture]
    Gate -- pass --> Discover{peer found in<br/>30 s?}
    Discover -- no --> Abort2[Cancelled: no peer]
    Discover -- yes --> Block{endpoint in blocklist?}
    Block -- yes --> Abort3[Auto-rejected]
    Block -- no --> Challenge{challenge sig valid?}
    Challenge -- no --> Abort4[Aborted: impersonation]
    Challenge -- yes --> Replay{counter ≥ stored?}
    Replay -- no --> Abort5[Aborted: replay]
    Replay -- yes --> Save[(persist contact)]
    Save --> Done([Completed ✅])

    classDef abort fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d
    classDef ok fill:#dcfce7,stroke:#15803d,color:#14532d
    class Abort1,Abort2,Abort3,Abort4,Abort5 abort
    class Done,Save ok
```

Each `Aborted` branch surfaces in the UI as a localized message (see [`features/01-gesture-gate.md`](features/01-gesture-gate.md), [`features/13-device-challenge.md`](features/13-device-challenge.md), [`features/14-blocklist.md`](features/14-blocklist.md), [`features/15-replay-protection.md`](features/15-replay-protection.md)) and is logged via Timber on debug builds only.

---

## 5. State machine inside `NearbyExchangeService`

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Advertising: ACTION_START
    Idle --> RoomHost: ACTION_START_ROOM_HOST
    Idle --> RoomGuest: ACTION_START_ROOM_GUEST
    Advertising --> Connecting: onEndpointFound
    RoomHost --> Connecting: onEndpointFound
    RoomGuest --> Connecting: onEndpointFound
    Connecting --> Connected: onConnectionResult(OK)
    Connecting --> Idle: onConnectionResult(FAIL)
    Connected --> Keying: send our ephPub
    Keying --> Identified: both pubs in
    Identified --> ChallengeOut: send challenge
    ChallengeOut --> ChallengeReplied: response received & verified
    ChallengeReplied --> ProfileSent: encrypted profile out
    ProfileSent --> ProfileReceived: peer profile in & counter valid
    ProfileReceived --> AvatarPhase: announce avatar (optional)
    AvatarPhase --> Completed: STREAM finished / no avatar
    Completed --> Idle
    state "Aborted" as Aborted
    ChallengeOut --> Aborted: bad signature
    ProfileSent --> Aborted: replay window
    Aborted --> Idle
```

This is the same finite-state machine drawn at a coarser level in [`ARCHITECTURE.md`](ARCHITECTURE.md#3-class-level-overview-of-the-exchange-service); the version above includes the explicit abort transitions.

---

## 4. DIDComm v2 exchange path

The DIDComm exchange path enables asynchronous, store-and-forward contact exchange
with any DIDComm v2-compatible wallet (including enterprise identity wallets).

```
Enterprise Wallet                    AURA (recipient)
     │                                      │
     │── DIDComm authcrypt envelope ─────→  │  DIDCommTransport.receive()
     │   type: aura.exchange.v1/request     │
     │   body: { vc, nonce, requesterDid }  │  → DIDCommInboxFragment shows consent dialog
     │                                      │
     │                                      │  User performs enrolled gesture
     │                                      │  GestureVerificationEngine.verify()
     │                                      │
     │  ←─── DIDComm authcrypt envelope ──  │  DIDCommTransport.send()
     │   type: aura.exchange.v1/response    │
     │   body: { vc, nonce_ack }            │
     │                                      │
     │  OR if declined:                     │
     │  ←─── type: report-problem ────────  │
     │        code: e.p.req.declined        │
```

Messages are routed via the AURA relay (`RelayClient`) using the `did:peer:2`
pairwise DID as the inbox slot identifier.

### ISO 18013-7 async mDL presentation

ISO 18013-7 extends ISO 18013-5 proximity presentation to an online (async) path
using OpenID4VP as the transport. The AURA verifier accepts `vp_token` responses
from any mDL wallet via `MdocDocument.fromOid4vpResponse()`.

```
Verifier (AURA)            mDL Wallet (remote)
     │                            │
     │── OpenID4VP AuthRequest ─→ │  response_type=vp_token
     │   nonce, presentation_def  │  presentation_definition: id.aura.contact.1
     │                            │
     │ ←─── vp_token (Base64url ─ │  DeviceResponse CBOR
     │        DeviceResponse)     │
     │                            │
     │  MdocDocument.fromOid4vpResponse(vpTokenJson, nonce)
     │  → MdocDocument with verified elements
```
