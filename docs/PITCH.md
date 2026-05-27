# AURA — Funding Pitch

> Two phones. One gesture. Zero servers.
> Post-quantum contact exchange for the physical world.

---

## The Problem

Contact exchange is still broken in 2026.

QR codes require a working camera and lighting. NFC taps miss constantly and need proximity you can't always get. Typing phone numbers by hand is error-prone and exposes the number mid-exchange. AirDrop and Nearby Share work only within ecosystems — Android to iPhone is dead. Every method that does work routes your identity through a cloud server you don't control.

Worse: every existing method is cryptographically fragile. X25519 key exchanges and ECDSA signatures are broken by a sufficiently powerful quantum computer. NIST finalized post-quantum standards in 2024. The infrastructure to harvest-now-decrypt-later is already operating. Contact data you exchange today with classical crypto can be decrypted in the future.

**The gap:** a secure, offline, cross-platform contact exchange that works in the physical world, with no ecosystem lock-in, no cloud dependency, and cryptography that survives the quantum transition.

---

## The Solution — What AURA Does

AURA is a gesture-authenticated contact exchange app. Two people open AURA, perform their agreed gesture, and their contact cards are exchanged — directly, device to device, over an encrypted channel that classical and quantum computers cannot break.

No account. No internet. No server sees your data.

---

## For the Investor / Non-Technical Reader

### The User Experience

**Setup (once, under 60 seconds)**

1. Install AURA and create your profile — name, phone, email, photo.
2. Record your personal unlock gesture. You hold your hand in front of the camera, make an open-palm anchor position, then perform any motion you choose — a wave, a swipe, a custom shape. AURA captures it. That gesture is now your key.
3. Optionally bind a backup fingerprint unlock.

**Every exchange after that is three taps**

1. Open AURA, tap Exchange.
2. Perform your gesture in front of the camera. AURA verifies it matches your enrollment.
3. Hold your phone near the other person's. A success sheet appears with the received contact card. Tap to save.

**What the user never sees**

A post-quantum key exchange executes in the background — ML-KEM-768 and ML-DSA-65, the same standards being adopted by NIST, NSA, and enterprise security vendors right now. The channel is end-to-end encrypted with an ephemeral key that is destroyed after the exchange. The relay, if used, never sees plaintext.

### Why This Matters

| The old way | AURA |
|---|---|
| QR scan — needs lighting, camera focus, ecosystem match | Gesture + BLE/Wi-Fi — works offline, cross-platform |
| Data routed through a server | Direct device-to-device — server never sees your identity |
| Classical crypto (breakable by quantum computers) | Post-quantum hybrid KEM — quantum-resistant by design |
| One profile, one identity | Multi-profile: Personal, Work, Custom per exchange |
| Passive — anyone can request your contact | Active — your gesture is the gate; you decide when to exchange |

### Who Uses This

- **Enterprises and regulated industries** — financial services, healthcare, defense. MDM policy enforcement, zero-touch enrollment, audit log export, differential privacy analytics. Your org controls the policy; AURA enforces it.
- **Privacy-conscious consumers** — no account, no cloud sync, no data to breach. F-Droid distributed — no Play Store surveillance layer.
- **Government and law enforcement** — post-quantum crypto already mandatory under NSM-10 for national security systems. AURA is architected to that standard today.
- **Events and networking** — conference halls, trade shows, anywhere QR fatigue is real and you need 50 reliable exchanges per hour.

---

## For the Technical Reader — How It Works

### Transport Layer

AURA negotiates the best available channel at exchange time:

```
Priority 1: NFC tap          — ISO 7816-4 APDU over HCE, <200ms handshake
Priority 2: BLE GATT         — MTU 517, direct GATT service, no internet
Priority 3: Wi-Fi Direct P2P — higher throughput for large payloads
Priority 4: QR relay         — HTTPS + OHTTP; relay sees only AES-256-GCM ciphertext
```

The fallback chain is automatic and transparent to the user. FOSS builds (F-Droid) use Wi-Fi Direct; GMS builds (sideload) use Nearby Connections. No Google dependency required.

### Cryptographic Stack

```
Identity layer:     ML-DSA-65 (CRYSTALS-Dilithium, NIST FIPS 204)
                    did:key anchors, SPKI certificate pinning
                    Runtime certificate chain validation + replay deduplication

Key exchange:       PQXDH — Post-Quantum Extended Diffie-Hellman
                    = ML-KEM-768 (CRYSTALS-Kyber, NIST FIPS 203) + X25519 hybrid
                    Ephemeral key pair per exchange — forward secrecy guaranteed

Channel:            Noise_XX handshake protocol
                    Double Ratchet (Signal-spec) for ratcheted forward secrecy
                    SPQR upgrade path for post-quantum ratchet

Envelope:           Sealed sender — recipient cannot determine sender identity from wire
                    AES-256-GCM with HKDF-SHA-256 derived keys
                    2048-byte maximum frame size with APDU chaining

Group (Room mode):  MLS (Messaging Layer Security, RFC 9420) key agreement
                    Star topology with designated room host
                    Store-and-forward mesh routing for offline members
```

The classical X25519 component provides backward compatibility and defense-in-depth. The ML-KEM-768 component provides quantum resistance. Both must be broken simultaneously to compromise the session — this is the harvest-now-decrypt-later mitigation.

### Authentication Layer

AURA uses a three-tier auth stack:

**Tier 1 — Gesture authentication (primary)**
MediaPipe hand landmark model detects an open-palm anchor position at capture start. If the anchor is missing, the capture fails immediately — no partial captures. Once the anchor is detected, AURA records a 2-second window at 30fps (~60 frames). Two overlapping bone graph descriptors are computed from that window:

```
Window A: frames 0 → 1.5s   (45 frames)
Window B: frames 0.5s → 2.0s (45 frames, 15-frame overlap)
```

Both descriptors must independently match the enrollment template within configured Euclidean distance thresholds. Both must pass — one match is insufficient. This dual-descriptor design catches enrollment-verification drift and provides confidence without requiring the user to perform the gesture twice.

**Tier 2 — Liveness guard (anti-spoofing)**
A two-layer temporal liveness check runs in parallel:
- Layer 1: motion flow analysis — static images and screenshots fail here
- Layer 2: challenge-response — the system issues a random micro-challenge gesture mid-capture (e.g., slight wrist rotation) and verifies it appears in the landmark stream

**Tier 3 — Continuous behavioral auth (background)**
`ContinuousAuthMonitor` samples a 36-dimensional behavioral feature vector (touch dynamics, motion patterns, session cadence) on a 30-second interval throughout the session. A local TFLite model scores the session; anomaly detection triggers a re-auth prompt. The model trains on the device from enrollment data and never leaves the device.

Biometric fallback (fingerprint / face) is available as an alternative to gesture unlock. The gesture is the cryptographic gate — biometric is the UX fallback. Both produce identical session tokens downstream.

### Identity and Privacy

```
W3C Verifiable Credentials    — VC-JWT signed with ML-DSA-65 identity key
ISO 18013-5 mdoc / mDL        — mobile driver's license credential presentation
OpenID4VP                     — verifiable presentation over QR/NFC
PSI contact discovery         — Private Set Intersection; server never learns your contacts
did:key anchors               — decentralized identity, no registrar required
Differential privacy ε=1.0   — analytics telemetry with formal privacy budget
```

### Codebase State

```
Version:          4.0.0 (shipped 2026-05-26, tag 90e3cbb)
Kotlin files:     291 across app / wearos / automotive / desktop / ios modules
Test files:       75 unit + 16 instrumented + 36 iOS AuraCore
Coverage floor:   JaCoCo 60% branch coverage enforced in CI
Localization:     365 strings × 7 languages
Distribution:     F-Droid reproducible build (PR #74 merged), signed AAB sideload
CI:               GitHub Actions — build / lint / test / sign / release pipeline
```

### Platform Surface

| Platform | Status | Key features |
|---|---|---|
| Android (main) | Shipped v4.0.0 | Full exchange flow, all transports |
| Wear OS | Shipped | Glance tile, wrist-raise trigger, SAS PIN, HRV liveness |
| Android Auto | Shipped | 4-screen voice-gated exchange flow |
| iOS AuraCore | Scaffold shipped | NFC + TOFU + PQXDH, 36 tests |
| Desktop (Compose) | Scaffold shipped | Relay-based exchange for desktop |

---

## Market Opportunity

**Immediate — Enterprise identity and access**
The enterprise contact and identity management market is $14B growing to $22B by 2029 (MarketsandMarkets 2024). Every regulated enterprise faces a quantum migration mandate under NSM-10, PQC Cybersecurity Guidance (CISA), and the EU's NIS2 post-quantum roadmap. AURA is already built to those standards.

**Adjacent — Physical-world identity**
Tap-to-pay normalized NFC exchange for payments. The same pattern — phone proximity + biometric gate — is the natural UX for identity. The hardware is already in every phone. AURA is the software layer.

**Long-term — Post-quantum identity infrastructure**
Harvest-now-decrypt-later attacks are already in progress against government and enterprise targets (NSA reporting, 2024). Every classical key exchange happening today is a liability. AURA's crypto stack is the answer.

---

## Traction

- v4.0.0 shipped with 66 implementation tasks complete across 291 Kotlin source files
- Full post-quantum cryptographic stack — ML-KEM-768, ML-DSA-65, PQXDH, Double Ratchet, MLS — all production-wired, not stubs
- F-Droid reproducible build verified and submitted
- CI/CD pipeline with signed APK/AAB release artifacts on every tag
- 16-item R&D pipeline already specified and backlog-ready (gesture enrollment system, NFC expansion, behavioral model training, federated learning scaffold)
- Zero Play Store dependency — distribution is fully self-sovereign

---

## The Ask

**Funding goal:** [amount]

**Use of funds:**

| Allocation | Purpose |
|---|---|
| 40% Engineering | Complete R&D pipeline: user-defined gesture enrollment, NFC channel expansion, behavioral model training, federated gradient, QUIC transport hardening |
| 25% Security audit | Third-party cryptographic audit of PQ stack, protocol review, penetration test |
| 20% Enterprise GTM | MDM integration depth, enterprise SSO, compliance documentation (FedRAMP, SOC 2, ISO 27001) |
| 15% Platform | iOS full parity, Wear OS standalone mode, Android Auto certification |

**What we are not building:**
A cloud platform, a social network, or a surveillance product. AURA is infrastructure. The business model is enterprise licensing and white-label deployment — the privacy architecture is the product, not the users.

---

## Why Now

1. NIST finalized ML-KEM and ML-DSA in August 2024. The quantum transition is not theoretical — it is an active compliance mandate.
2. Every major enterprise security vendor is scrambling to retrofit post-quantum crypto onto classical stacks. AURA is built on PQ from day one.
3. Physical-world identity is unsolved. Digital identity wallets (EU eIDAS 2.0, mDL rollout) are creating demand for a secure offline presentation layer. AURA implements the exact standards those wallets require.
4. The codebase is done and shipping. This is not a pitch for a prototype — it is a pitch for scale.

---

*AURA — MIT licensed, open source, fully auditable.*
*Source: https://github.com/showerideas/Aura*
