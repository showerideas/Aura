<div align="center">

# ✦ AURA ✦

### Gesture-authenticated offline contact exchange for Android

*Two phones. One gesture. Local-first. Post-quantum.*

[![CI](https://github.com/showerideas/Aura/actions/workflows/ci.yml/badge.svg)](https://github.com/showerideas/Aura/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/showerideas/Aura?color=6E56CF&label=release)](https://github.com/showerideas/Aura/releases/latest)
[![Download APK](https://img.shields.io/badge/download-APK-3DDC84?logo=android&logoColor=white)](https://github.com/showerideas/Aura/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/min%20SDK-26%20%E2%80%A2%20Android%208.0-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/target%20SDK-35%20%E2%80%A2%20Android%2015-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/15)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Post-Quantum](https://img.shields.io/badge/crypto-post--quantum-6E56CF)](docs/SECURITY.md)
[![Local-first](https://img.shields.io/badge/network-local--first-1f1f1f)](docs/SECURITY.md)

</div>

---

## What is AURA?

**AURA** lets two people swap contact cards face-to-face — **no internet, no QR scan, no NFC tap required**. You set up your profile once, record a personal unlock gesture (or bind it to your fingerprint), and from then on a single motion is enough to push your details to the phone in front of you. The exchange flies over a direct Bluetooth-LE / Wi-Fi-P2P / NFC link protected by a post-quantum hybrid KEM (ML-KEM-768+X25519) and ML-DSA-65 identity signatures.

> **BLE/Wi-Fi-P2P exchange is fully offline** — no account, no cloud sync, nothing for a server to leak. The optional QR relay path uses a short-lived relay slot (HTTPS, AES-256-GCM ciphertext only) for environments where direct radio contact is unavailable; the relay never sees plaintext.

<div align="center">

```text
  📱   ── triple-press vol ▼ ──▶   ✋ gesture   ──▶   🔐 PQ-KEM   ──▶   📇   📱
```

</div>

---

## ⚡ Front desk

| | |
|---|---|
| 📥 **Install** | [`Releases → latest`](https://github.com/showerideas/Aura/releases/latest) — side-load the APK |
| 📖 **Docs hub** | [`/docs`](docs/README.md) — engineering record |
| 🏛 **Architecture** | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 🔐 **Security model** | [`docs/SECURITY.md`](docs/SECURITY.md) |
| 🔄 **Exchange flow** | [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) |
| 🔌 **Wire protocol** | [`docs/WIRE_PROTOCOL.md`](docs/WIRE_PROTOCOL.md) — v9 frame spec |
| ✋ **Gesture auth** | [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) |
| 🧪 **Audit** (intent fulfilment) | [`docs/AUDIT.md`](docs/AUDIT.md) |
| 📸 **Showcase** (screenshots + demo) | [`docs/SHOWCASE.md`](docs/SHOWCASE.md) |
| 🛠 **Build locally** | [`docs/BUILD.md`](docs/BUILD.md) |
| 📜 **Privacy policy** | [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) |
| 📜 **License** | [MIT](LICENSE) |

---

## ✨ Every feature in v4.0.0

| 🔐 Post-quantum crypto | 🌐 Transport | 🎯 Auth & UX |
|---|---|---|
| Hybrid KEM ML-KEM-768+X25519 | Nearby Connections (GMS) | MediaPipe gesture gate |
| ML-DSA-65 identity signatures | Wi-Fi Direct (FOSS/F-Droid) | Temporal liveness (2-layer) |
| PQXDH full prekey bundle | BLE GATT MTU 517 + SCI | Biometric unlock fallback |
| Noise_XX encrypted channel | NFC HCE ISO 7816-4 | Triple-press vol ▼ wake¹ |
| Double Ratchet + SPQR | QR relay (HTTPS + OHTTP) | SAS first-meet PIN |
| MLS group key agreement | QUIC/HTTP3 (Cronet) | Multi-profile Personal/Work |
| Sealed sender envelopes | Tor SOCKS5 (Orbot) | Room mode (star topology) |
| SPKI certificate pinning | LoRa via Meshtastic (opt-in) | Wear OS 7 Glance tile |

| 🪪 Identity & privacy | 📊 Enterprise & analytics | 🧪 Quality |
|---|---|---|
| W3C Verifiable Credentials | 6 MDM restriction keys | 623+ unit test methods |
| ISO 18013-5 mdoc/mDL | Zero-touch enrollment | 72 instrumented tests |
| OpenID4VP presentation | Advanced Protection API | 36 iOS AuraCore tests |
| PSI contact discovery | Differential privacy ε=1.0 | JaCoCo 60% branch floor |
| did:key identity anchors | Signed audit export CSV | TalkBack + AA contrast pass |
| StrongBox key migration | Android Auto voice gate | 365 strings × 7 locales |
| Replay + nonce dedup window | Wear OS Health Connect HRV | F-Droid reproducible build |
| No PII ever logged | PDF analytics export | vCard / Contacts export |

---

## 🔄 How it works (one diagram)

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569',
  'secondaryColor':'#F1F5F9',
  'tertiaryColor':'#FAFAF9',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':40,'rankSpacing':50,'padding':14}}}%%
flowchart LR
    subgraph A["📱 Phone&nbsp;A"]
        direction TB
        UA[["User&nbsp;A"]]:::user
        VA["Volume<br/>Listener"]:::service
        GA{"Gesture<br/>match?"}:::gate
        NA["Nearby<br/>Exchange"]:::service
        DA[("Room&nbsp;v2")]:::data
        UA --> VA --> GA
        GA -- "✅" --> NA
        GA -- "❌" --> XA["Cancel"]:::warn
        NA --> DA
    end
    subgraph B["📱 Phone&nbsp;B"]
        direction TB
        UB[["User&nbsp;B"]]:::user
        VB["Volume<br/>Listener"]:::service
        GB{"Gesture<br/>match?"}:::gate
        NB["Nearby<br/>Exchange"]:::service
        DB[("Room&nbsp;v2")]:::data
        UB --> VB --> GB
        GB -- "✅" --> NB
        GB -- "❌" --> XB["Cancel"]:::warn
        NB --> DB
    end
    NA <== "PQ-KEM&nbsp;+&nbsp;Noise_XX&nbsp;+&nbsp;AES-GCM" ==> NB

    classDef user fill:#6E56CF,color:#FFFFFF,stroke:#3D2C7A,stroke-width:2px
    classDef service fill:#0EA5E9,color:#FFFFFF,stroke:#075985,stroke-width:2px
    classDef data fill:#10B981,color:#FFFFFF,stroke:#065F46,stroke-width:2px
    classDef gate fill:#F59E0B,color:#1F2937,stroke:#92400E,stroke-width:2px
    classDef warn fill:#EF4444,color:#FFFFFF,stroke:#991B1B,stroke-width:2px
```

The full step-by-step sequence (PQ-KEM handshake, ML-DSA-65 identity proof, Noise_XX channel setup, replay window, avatar streaming) is in [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md).

---

## 🧱 Architecture at a glance

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569',
  'secondaryColor':'#F1F5F9',
  'tertiaryColor':'#FAFAF9',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':28,'rankSpacing':36,'padding':10}}}%%
flowchart TB
    subgraph UI["🎨&nbsp;UI"]
        direction LR
        HF["Home"]:::ui
        PF["Profile"]:::ui
        EF["Exchange"]:::ui
        CF["Contacts"]:::ui
        QF["QR"]:::ui
        RF["Room"]:::ui
        SF["Settings"]:::ui
        AUF["Audit"]:::ui
        ENT["Enterprise"]:::ui
    end
    subgraph VM["🧠&nbsp;ViewModels"]
        direction LR
        V1["Hilt-injected<br/>ViewModels"]:::vm
    end
    subgraph DOM["⚙️&nbsp;Domain&nbsp;/&nbsp;Services"]
        direction LR
        GA["Gesture<br/>Auth"]:::service
        BA["Biometric<br/>Helper"]:::service
        VB["Volume<br/>Service"]:::service
        NX["Exchange<br/>Service"]:::service
        CR["Contact<br/>Repo"]:::service
        PR["Profile<br/>Repo"]:::service
        BR["Blocklist<br/>Repo"]:::service
        AR["Audit<br/>Repo"]:::service
    end
    subgraph CRY["🔐&nbsp;Crypto"]
        direction LR
        KEM["HybridKEM<br/>(ML-KEM-768+X25519)"]:::crypto
        SIG["HybridSig<br/>(ML-DSA-65)"]:::crypto
        NSE["Noise_XX"]:::crypto
        DR["Double Ratchet<br/>+SPQR"]:::crypto
        MLS["MLS<br/>RFC 9420"]:::crypto
    end
    subgraph IDL["🪪&nbsp;Identity"]
        direction LR
        VC["VcIssuer<br/>(did:key)"]:::id
        MDOC["mdoc<br/>ISO 18013-5"]:::id
        VP["OpenID4VP"]:::id
    end
    subgraph DAT["💾&nbsp;Data"]
        direction LR
        CD[("Contact<br/>Dao")]:::data
        PD[("Profile<br/>Dao")]:::data
        BD[("Blocked<br/>Dao")]:::data
        AuD[("Audit<br/>Dao")]:::data
        DS[("Data<br/>Store")]:::data
        ESP[("Encrypted<br/>Prefs")]:::data
        KS[("Android<br/>Keystore")]:::data
    end

    UI --> VM
    VM --> GA & BA & CR & PR & BR & AR
    VM -.-> NX & VB
    GA --> ESP
    NX --> KEM & NSE & KS & CR & BR
    NX --> VC & MDOC
    SIG --> KS
    CR --> CD
    PR --> PD
    BR --> BD
    AR --> AuD

    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef vm fill:#F472B6,color:#1F2937,stroke:#9D174D,stroke-width:2px
    classDef service fill:#0EA5E9,color:#FFFFFF,stroke:#075985,stroke-width:2px
    classDef crypto fill:#EC4899,color:#FFFFFF,stroke:#9D174D,stroke-width:2px
    classDef id fill:#F59E0B,color:#1F2937,stroke:#92400E,stroke-width:2px
    classDef data fill:#10B981,color:#FFFFFF,stroke:#065F46,stroke-width:2px
```

More detail (package map, dependency direction rules, threading) in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## 🧰 Tech stack

| Layer | Choice |
|---|---|
| Language | **Kotlin 2.0** (JVM 17) |
| UI | Fragments + ViewBinding + Navigation Component + Material 3 |
| DI | Hilt 2.51.1 |
| Persistence | Room 2.6.1 (exported schemas, v2 + MIGRATION_1_2 through MIGRATION_4_5) |
| Async | Kotlinx Coroutines 1.8.1 |
| Primary transport (GMS) | Google **Nearby Connections** 19.1.0 |
| FOSS transport | Wi-Fi Direct (NSD/mDNS) |
| Additional transports | BLE GATT · NFC HCE (ISO 7816-4) · LoRa · UWB (FiRa 3.0) · Mesh |
| Session crypto | **ML-KEM-768 + X25519** hybrid KEM (BouncyCastle bcpqc-jdk18on) |
| Identity crypto | **ML-DSA-65 + ECDSA P-256** hybrid — Android Keystore |
| Protocol crypto | PQXDH · Noise_XX · MLS RFC 9420 · Double Ratchet + SPQR · AES-256-GCM |
| Gesture auth | CameraX + **MediaPipe GestureRecognizer** (21 landmarks) + LSTM temporal classifier + 2-layer liveness |
| Biometric | `androidx.biometric` (fingerprint / face) + `CryptoObject` KeyAgreement (API 36+) |
| QR | ZXing-embedded 4.3.0 |
| Preferences | DataStore 1.1.1 + `EncryptedSharedPreferences` |
| Build | AGP 8.13.2 (Kotlin DSL) + Version Catalogs |
| Min / Target SDK | **26** / **35** |
| Platforms | Android · Wear OS 7 · Android Auto · iOS (AuraCore) · Desktop (KMP) |
| CI | GitHub Actions — unit tests + JaCoCo (60% branch floor) + Lint + `assembleRelease` + APK size gate + MediaPipe class survival check + iOS build/test |

---

## 📱 Platform targets

| Platform | Status | Notes |
|---|---|---|
| **Android phone** | Production | Min SDK 26, Target 35 |
| **Wear OS 7** | Production | Glance tile, Health Connect HRV, SasPinActivity, WristRaiseTrigger |
| **Android Auto** | Production | Voice action, biometric gate, full screen library (Advertising / Idle / Completed / SAS) |
| **iOS companion** | Production | AuraCore — ContactProfile, SasVerifier, WireProtocol.swift, MultipeerTransport, 15 unit tests |
| **Desktop (KMP)** | Production | Kotlin Multiplatform companion — QR relay transport |

---

## 🔐 Cryptographic stack

| Layer | Primitive | Standard |
|---|---|---|
| Session key agreement | ML-KEM-768 + X25519 hybrid KEM | FIPS 203 + RFC 7748 |
| Shared secret derivation | HKDF-SHA256 over `mlkem_ss ‖ x25519_ss` | RFC 5869 |
| Identity signatures | ML-DSA-65 + ECDSA P-256 hybrid | FIPS 204 + NIST P-256 |
| Channel encryption | AES-256-GCM | NIST SP 800-38D |
| Noise channel | Noise_XX (X25519, AESGCM, SHA256) | Noise Protocol Framework |
| Async key exchange | PQXDH prekey bundle | Signal PQ extension |
| Session ratchet | Double Ratchet + SPQR post-quantum ratchet | DR spec |
| Group key agreement | MLS RFC 9420 | RFC 9420 |
| SAS verification | SHA-256(shared\_secret) mod 10⁶, 6 digits | — |
| Sealed sender | HKDF + AES-256-GCM two-phase unwrap | Signal sealed sender |
| Key storage | Android Keystore (StrongBox preferred) | Android Security |
| Gesture template | `EncryptedSharedPreferences` (AES-256 master key) | Jetpack Security |

The full wire frame specification (frame structure, key sizes, version history v1–v9) is in [`docs/WIRE_PROTOCOL.md`](docs/WIRE_PROTOCOL.md).

---

## 🚀 Get started in 60 seconds

1. **Install** the APK from [Releases](https://github.com/showerideas/Aura/releases/latest) (enable *Install unknown apps* for your browser/file-manager first).
2. **Set up your profile** — name, phone, email, company, title, website, bio, avatar.
3. **Record your gesture** (hold record, perform the motion once). You can bind unlock to your fingerprint instead.
4. **Activate** — tap the home tile *or* triple-press vol ▼ from anywhere. Both phones light up.
5. **Perform the gesture.** Done — the other person's card is on your phone.

Want to build from source? → [`docs/BUILD.md`](docs/BUILD.md).

---

## 🛡 Security in one paragraph

Each exchange opens a **fresh post-quantum hybrid KEM** (ML-KEM-768+X25519), derives a 256-bit AES key via HKDF-SHA256, and wraps the profile JSON in **AES-GCM** before the bytes leave the device. A long-lived **hybrid identity key** (P-256 + ML-DSA-65 FIPS 204) signs every payload; an adversary must break both classical and post-quantum signatures to forge identity. The session is wrapped in a **Noise_XX encrypted channel**. Replay attempts are rejected by a **timestamp + per-nonce dedup window**. Blocked endpoints are remembered as identity-key hashes in Room. For first-meet exchanges, `SasVerifier` produces a 6-digit Short Authentication String both parties can compare verbally. Multi-party Room sessions use **MLS RFC 9420 group key agreement**. The full threat model lives in [`docs/SECURITY.md`](docs/SECURITY.md).

---

## 🗺 Roadmap

- [x] **v1.0.0** — gesture gate (MediaPipe), ECDH+HKDF, room exchange, QR fallback, blocklist, replay protection (ts+nonce), biometric, accessibility, settings, R8-shrunk release APK
- [x] **v1.1.0** — QR relay, 7 locales 100% coverage (HI, ES, FR, DE, JA, KO, ZH-CN), 259 unit + 51 instrumented tests
- [x] **v2.0.0** — transport abstraction, NFC HCE ISO 7816-4, multi-profile, identity rotation, audit log, SPKI pinning, backup
- [x] **v3.0.0** — iOS AuraCore companion (ContactProfile, SasVerifier, ECDH, SAS), Wear OS pairing, Android Auto voice + biometric gate, F-Droid reproducible build
- [x] **v3.3.0** — full transport stack (BLE GATT + SCI, Wi-Fi Direct FOSS, NFC, LoRa opt-in), PQ crypto (ML-KEM-768, ML-DSA-65, PQXDH), differential privacy analytics, enterprise MDM, JaCoCo 60% floor
- [x] **v4.0.0** — Noise_XX channel, MLS RFC 9420 rooms, Double Ratchet + SPQR, OHTTP RFC 9458, QUIC/HTTP3, OpenID4VP, ISO 18013-5 mdoc, W3C Verifiable Credentials, UWB FiRa 3.0, BLE Channel Sounding, Advanced Protection API; 623+ unit / 72 instrumented / 36 iOS tests
- [ ] **R&D pipeline** — 16 research items: `did:peer`/`did:web` full DID wallet, ARCore exchange overlay, satellite fallback (SatelliteManager), DIDComm v2, FIDO2 credential provider, ZK-SNARK gesture privacy, MPC threshold audit signing, Android XR / Jetpack XR, Privacy Pass relay rate-limiting, Kotlin 2.2 Swift export

---

> ¹ Volume-button wake is unreliable on some OEM skins (Samsung One UI, Xiaomi MIUI, OPPO
> ColorOS) due to `MediaSession` limitations. Use the quick-settings tile as a reliable
> alternative. See [docs/VOLUME_BUTTON_RELIABILITY.md](docs/VOLUME_BUTTON_RELIABILITY.md).

---

## 🤝 Contributing

Pull requests welcome. Please read [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) before opening one — it covers branch naming, the per-PR commit style this repo uses, and the test gates each PR must pass.

---

## 📜 License

MIT — see [`LICENSE`](LICENSE).
