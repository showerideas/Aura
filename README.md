<div align="center">

# ✦ AURA ✦

### Gesture-authenticated offline contact exchange for Android

*Two phones. One gesture. Zero servers.*

[![CI](https://github.com/showerideas/Aura/actions/workflows/ci.yml/badge.svg)](https://github.com/showerideas/Aura/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/showerideas/Aura?color=6E56CF&label=release)](https://github.com/showerideas/Aura/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](#license)
[![Min SDK](https://img.shields.io/badge/min%20SDK-26%20(Android%208.0)-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/target%20SDK-35%20(Android%2015)-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/15)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Offline-first](https://img.shields.io/badge/network-zero%20outbound-1f1f1f)](docs/SECURITY.md)

</div>

---

## What is AURA?

**AURA** lets two people swap contact cards face-to-face — **no internet, no QR code, no NFC tap required**. You set up your profile once, record a personal unlock gesture (or bind it to your fingerprint), and from then on a single motion is enough to push your details to the phone in front of you. The exchange flies over a direct Bluetooth-LE / Wi-Fi-P2P link encrypted end-to-end with ECDH + AES-256-GCM.

> AURA has **no backend**. There is nothing to sign up for, nothing to sync, and nothing for a server operator to leak — because there is no server operator.

<div align="center">

```
   📱  ─── triple-press vol ▼ ──▶  ✋ gesture  ──▶  🔐 ECDH  ──▶  📇  📱
```

</div>

---

## ⚡ Quick links

| | |
|---|---|
| 📥 **Get the APK** | [Latest release](https://github.com/showerideas/Aura/releases/latest) |
| 📖 **Read the docs** | [`/docs`](docs/README.md) — architecture, security, every feature |
| 🧭 **Architecture diagrams** | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 🔐 **Security model** | [`docs/SECURITY.md`](docs/SECURITY.md) |
| 🧪 **Audit / intent fulfilment** | [`docs/AUDIT.md`](docs/AUDIT.md) |
| 🛠 **Build it yourself** | [`docs/BUILD.md`](docs/BUILD.md) |
| 📜 **Privacy policy** | [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) |
| 🛍 **Play Store listing** | [`STORE_LISTING.md`](STORE_LISTING.md) |

---

## How it works (in one diagram)

```mermaid
flowchart LR
    U1([User A]) -- "triple-press vol ▼" --> S1[VolumeButtonListenerService]
    S1 -- "Activate intent" --> EX1[ExchangeFragment]
    EX1 -- "perform gesture" --> GA1{Gesture<br/>matches?}
    GA1 -- ✅ --> NB1[NearbyExchangeService]
    GA1 -- ❌ --> CX1[Cancel]
    NB1 -- "advertise + discover" --> NB2[NearbyExchangeService]
    NB2 -- "advertise + discover" --> NB1
    NB1 -- "ECDH pub keys" --> NB2
    NB2 -- "ECDH pub keys" --> NB1
    NB1 -- "AES-GCM(profile)" --> NB2
    NB2 -- "AES-GCM(profile)" --> NB1
    NB1 --> DB1[(Room)]
    NB2 --> DB2[(Room)]
    U2([User B]) -- "triple-press vol ▼" --> S2[VolumeButtonListenerService]
    S2 -.-> NB2

    classDef user fill:#6E56CF,color:#fff,stroke:#4d3a99
    classDef service fill:#0EA5E9,color:#fff,stroke:#075985
    classDef db fill:#10B981,color:#fff,stroke:#065f46
    classDef gate fill:#F59E0B,color:#fff,stroke:#92400e
    class U1,U2 user
    class S1,S2,NB1,NB2,EX1 service
    class DB1,DB2 db
    class GA1 gate
```

The full sequence — including ECDH key derivation, challenge–response identity proof, replay-counter window, and avatar streaming — lives in [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md).

---

## ✨ Feature highlights

<table>
<tr>
<td width="33%" valign="top">

### 🔐 Privacy by construction
- Zero outbound network calls
- No account, no email, no cloud
- ECDH per-session keys
- AES-256-GCM payload encryption
- Android Keystore identity key
- Endpoint blocklist + replay window

</td>
<td width="33%" valign="top">

### 🎯 Frictionless UX
- Triple-press volume ▼ to activate
- Custom gesture **or** biometric unlock
- One-shot Room mode (1 host : N guests)
- QR fallback for BLE-hostile venues
- Avatar streamed alongside profile
- Favourites + private notes per contact

</td>
<td width="33%" valign="top">

### ♿️ Production polish
- Full accessibility audit (TalkBack, large fonts, contrast)
- Onboarding tutorial
- Pulsing-activation animation
- Settings + Blocked Devices screens
- Room schema migrations (`v1 → v2`)
- vCard / contact-book export

</td>
</tr>
</table>

Detailed write-up of every single PR/feature is in [`docs/features/`](docs/features/).

---

## 🧱 Architecture at a glance

```mermaid
flowchart TB
    subgraph UI["UI layer (Fragments + ViewBinding)"]
        HF[HomeFragment]
        PF[ProfileFragment]
        EF[ExchangeFragment]
        CF[ContactsFragment]
        OF[OnboardingFragment]
        QF[QRExchangeFragment]
        RF[RoomExchangeFragment]
        SF[SettingsFragment]
        BF[BlockedDevicesFragment]
    end

    subgraph VM["ViewModels (Hilt-injected)"]
        VMs[(Home / Profile / Exchange<br/>Contacts / Settings / Room / QR ViewModels)]
    end

    subgraph DOMAIN["Domain / services"]
        GA[GestureAuthManager<br/>sensor + DTW]
        BA[BiometricAuthHelper]
        VB[VolumeButtonListenerService<br/>foreground]
        NX[NearbyExchangeService<br/>foreground]
        CR[ContactRepository]
        PR[ProfileRepository]
        BR[BlocklistRepository]
    end

    subgraph DATA["Data (Room v2)"]
        CD[(ContactDao)]
        PD[(ProfileDao)]
        BD[(BlockedEndpointDao)]
        DS[(DataStore<br/>auth + onboarding prefs)]
        ESP[(EncryptedSharedPreferences<br/>gesture vector)]
        KS[(Android Keystore<br/>identity EC key)]
    end

    UI --> VM
    VM --> GA
    VM --> BA
    VM --> CR
    VM --> PR
    VM --> BR
    VM -.starts.-> NX
    VM -.starts.-> VB
    GA --> ESP
    NX --> KS
    NX --> CR
    NX --> BR
    CR --> CD
    PR --> PD
    BR --> BD

    classDef ui fill:#fde68a,stroke:#b45309
    classDef vm fill:#bfdbfe,stroke:#1e40af
    classDef dom fill:#bbf7d0,stroke:#065f46
    classDef data fill:#fecaca,stroke:#991b1b
    class HF,PF,EF,CF,OF,QF,RF,SF,BF ui
    class VMs vm
    class GA,BA,VB,NX,CR,PR,BR dom
    class CD,PD,BD,DS,ESP,KS data
```

---

## 🧰 Tech stack

| Layer | Choice |
|---|---|
| Language | **Kotlin 2.0** (JVM 17) |
| UI | Fragments + ViewBinding + Navigation Component |
| DI | Hilt 2.51 |
| Persistence | Room 2.6 (exported schemas, v2 with migration) |
| Async | Kotlinx Coroutines 1.8 |
| P2P transport | Google **Nearby Connections** |
| Crypto | Android Keystore + ECDH (EC-256) + AES-256-GCM + ECDSA |
| Gesture auth | Accelerometer + **Dynamic Time Warping** matching |
| Biometric | `androidx.biometric` (fingerprint / face) |
| QR | ZXing-embedded 4.3 |
| Preferences | DataStore + `EncryptedSharedPreferences` |
| Build | Gradle 8.4 (Kotlin DSL) + Version Catalogs |
| Min / Target SDK | 26 / 35 |
| CI | GitHub Actions — unit tests + Lint + `assembleRelease` + APK artifact |

---

## 🚀 Get started in 60 seconds

1. **Install** the APK from [Releases](https://github.com/showerideas/Aura/releases/latest).
2. **Set up your profile** — name, phone, email, company, title, website, bio, avatar.
3. **Record your gesture** (hold the record button, perform the move once). You can also bind unlock to your fingerprint instead.
4. **Tap Activate** (or triple-press vol ▼ from anywhere). Both phones light up.
5. **Perform the gesture**. Done — the other person now has your card.

Want to build from source? → [`docs/BUILD.md`](docs/BUILD.md).

---

## 🛡 Security in one paragraph

Each exchange opens a fresh ECDH key pair (never reused), derives a 256-bit AES key, and wraps the profile JSON in AES-GCM before the bytes leave the device. A long-lived Android-Keystore EC key signs a challenge so each side can detect impersonation. Replay attempts are rejected by a monotonically advancing counter window. Blocked endpoints are remembered as fingerprints in Room. The full threat model and crypto walkthrough is in [`docs/SECURITY.md`](docs/SECURITY.md).

---

## 🗺 Roadmap

- [x] Gesture gate, ECDH, room exchange, QR fallback, blocklist, replay protection, biometric, accessibility, settings — see [audit](docs/AUDIT.md)
- [ ] Ship translated `values-xx/` resource bundles (currently scaffolded only)
- [ ] Wire Espresso UI tests into a CI emulator job
- [ ] Signed Play Store build + Play Integrity attestation
- [ ] Cross-platform "AURA Lite" iOS receiver (read-only via QR)

---

## 🤝 Contributing

Pull requests welcome. Please read [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) before opening one — it covers branch naming, the per-PR commit style this repo uses, and the test gates each PR must pass.

---

## License

MIT — see [`LICENSE`](LICENSE) (to be added; the repo is currently under an implicit "all rights reserved" until that file lands).

---

<div align="center">

*Built by [Shower Ideas](https://github.com/showerideas) — privacy-first software for the offline moments that matter.*

</div>
