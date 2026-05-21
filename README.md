# AURA

**Gesture-authenticated offline contact exchange for Android**

AURA lets two people exchange contact cards face-to-face — no internet, no QR code, no NFC tap required. Just the right gesture and proximity.

---

## How it works

```
Triple-press volume ↓  →  AURA activates
                         ↓
         Perform your recorded gesture
                         ↓
         Nearby Connections P2P link forms
                         ↓
     ECDH key exchange (ephemeral per session)
                         ↓
    Profiles encrypted with AES-256-GCM, exchanged
                         ↓
         Contact saved locally — offline, always
```

---

## Architecture

```
app/
├── model/          — Profile, Contact, ExchangeSession, GesturePattern
├── data/
│   ├── local/      — Room database (ContactDao, ProfileDao, AppDatabase)
│   ├── ContactRepository.kt
│   └── ProfileRepository.kt
├── auth/
│   └── GestureAuthManager.kt   — Sensor recording + DTW matching
├── service/
│   ├── VolumeButtonListenerService.kt   — Triple-press trigger
│   └── NearbyExchangeService.kt         — P2P exchange state machine
├── ui/
│   ├── MainActivity.kt
│   ├── home/        — Activate button + stats
│   ├── profile/     — Edit profile + record gesture
│   ├── exchange/    — Live exchange status
│   └── contacts/    — Received contacts list + search
├── utils/
│   ├── CryptoUtils.kt     — ECDH + AES-GCM
│   └── Extensions.kt      — Haptics, views
└── di/
    └── DatabaseModule.kt  — Hilt DI
```

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0 |
| UI | Fragments + ViewBinding + Navigation Component |
| DI | Hilt |
| Local DB | Room (SQLite) |
| P2P transport | Google Nearby Connections API |
| Crypto | Android Keystore + ECDH + AES-256-GCM |
| Gesture auth | Accelerometer + DTW matching |
| Activation | Volume-button triple-press (foreground service) |
| Build | Gradle 8.4 with Version Catalogs |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

---

## Security model

- **Offline-first** — no server, no cloud sync, no account required
- **Ephemeral ECDH** — fresh keypair per session, shared secret never stored
- **AES-256-GCM** — authenticated encryption on all profile payloads
- **Gesture gate** — DTW-matched sensor signature required before exchange
- **Android Keystore** — long-lived device identity key hardware-backed
- **EncryptedSharedPreferences** — gesture feature vector stored encrypted
- **No backup** — Room DB and gesture prefs excluded from cloud backup

---

## Getting started

1. Clone the repo
2. Open in Android Studio Ladybug (2024.2+)
3. Build & run on a device (emulator won't have BLE)
4. Set up your profile → record your gesture → triple-press to exchange

---

## Roadmap

- [ ] vCard / contact book export
- [ ] Gesture pattern strength indicator
- [ ] Batch exchange (1-to-many in a room)
- [ ] QR fallback for non-BLE environments
- [ ] Avatar image sharing via Nearby Connections STREAM payload
