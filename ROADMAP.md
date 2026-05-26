# AURA — Project Roadmap

> **Reading guide:**
> ✅ = Fully shipped and on `main`
> 🔧 = Implementation exists; small close-out task remains
> 📋 = Planned — not yet started
> 🔭 = Long-horizon — spec not written yet

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | `main` — v2.0.0 + Phase 10.x sprint; 9bdb1da |
| Phases shipped | 2, 3, 4, 5.1/3/4/6/7/8, 6.1–6.11, 7.1–7.5, 8.1–8.4, 9.1–9.4, 10.1–10.4 |
| Crypto stack | Hybrid KEM ML-KEM-768+X25519 (v6) · Sealed sender (v7) · DoubleRatchet · SAS · TOFU · PBKDF2+AES-256-GCM backup · Runtime SPKI pinning |
| Transport | NearbyConnections (gms) · WifiDirect (foss) · NFC HCE + NDEF tap · QR relay (HTTPS+Tor) |
| Platforms | Android phone · Wear OS tile · Android Auto · iOS companion scaffold |
| Distribution | GitHub Releases (signed splits arm64+armeabi-v7a) · F-Droid metadata wired |
| Localization | 262 strings × 7 locales — 100% coverage, CI-enforced |
| Test suite | Unit: 23+ files · 274+ methods · Instrumented: 13 files · 55 methods · 0 failures |
| CI | Green — unit + JaCoCo (60% branch floor) + lint + assembleRelease + APK size gate |
| Security | Wave 3 audit resolved (A1–A15) · NSC pinning · runtime SPKI pinning · blocklist transparency |

---

## ✅ COMPLETED PHASES

| Phase | Description |
|---|---|
| 2 | Localization — 262 strings × 7 locales, CI-enforced 100% coverage |
| 3 | Test coverage hardening — 274 unit + 55 instrumented, 0 failures |
| 4 | QR relay — AES-256-GCM profile POST/GET over HTTPS relay |
| 5.1 | SAS dialog hardening — countdown, haptic, identicon dual-channel |
| 5.3 | Accessibility — contentDescription audit, Espresso contracts |
| 5.4 | Volume button reliability — AuraAccessibilityService triple-press |
| 5.6 | QR relay self-hosting guide — docs/qr-relay-setup.md |
| 5.7 | Cert pinning — network_security_config.xml NSC + runtime SPKI SpkiPinTrustManager |
| 5.8 | MediaPipe model bundled offline — SHA-256 verified, FOSS-clean |
| 6.1 | NFC tap-to-exchange — NfcExchangeHelper, foreground dispatch, MainActivity wiring |
| 6.2 | Transport injection — gms/foss Hilt flavors, NearbyTransport interface |
| 6.3 | Contact deduplication — ContactRepository.saveDeduped(), ContactMergeBottomSheet |
| 6.4 | Multiple profiles — PERSONAL/WORK/CUSTOM, ProfileSwitcherBottomSheet, Room v6 |
| 6.5 | Identity key rotation — RotationCertificate, Room v7, KnownPeer.rotation_cert |
| 6.6 | Exchange audit log UI — AuditFragment, CSV export |
| 6.7 | Profile versioning — auto-increment version, KnownPeer.lastSeenProfileVersion, Snackbar |
| 6.8 | Share deeplink — AndroidManifest intent-filter + autoVerify, MainActivity handler |
| 6.9 | Identicon for unknown peers — IdenticonGenerator, SAS dialog, BlockedDevicesAdapter |
| 6.10 | Encrypted backup/restore — AES-256-GCM + PBKDF2, SAF file picker, BackupUtils |
| 6.11 | QS tile + home screen shortcut — AuraQsTileService, shortcuts.xml |
| 7.1 | iOS companion scaffold — WireProtocol.swift, MultipeerTransport.swift, CryptoKit ECDH |
| 7.2 | Wear OS tile — AuraTileService, WearPhoneBridge ChannelClient, WearStateStore |
| 7.3 | Android Auto — AuraCarAppService, 4 screens (Idle/Advertising/SAS/Completed) |
| 7.4 | Enterprise/MDM — EnterprisePolicy, RestrictionsManager, app_restrictions.xml |
| 7.5 | F-Droid reproducible builds — fdroid/com.showerideas.aura.yml, reproducible_build_test.sh |
| 8.1 | Post-quantum hybrid KEM — ML-KEM-768 + X25519, wire v6, HybridKEMTest |
| 8.2 | Sealed sender — SealedEnvelope, fixed-size 4096-byte padded frames, wire v7 |
| 8.3 | Tor/Orbot relay anonymization — SOCKS5 proxy toggle in RelayClient + Settings |
| 8.4 | Remote blocklist transparency — Ed25519 Merkle log, BloomFilter, WorkManager refresh |
| 9.1 | Smart share presets — SharePreset Room entity, preset picker sheet |
| 9.2 | Analytics dashboard — AnalyticsFragment, weekly chart, CSV export |
| 9.3 | Gesture library manager — GestureLibraryFragment, multi-slot enrollment |
| 9.4 | On-device gesture classifier — TFLite binary classifier, cosine+classifier dual gate |
| 10.1 | NFC HCE expansion — AuraHceService ISO 7816-4, aura_apdu_service.xml, public NDEF APIs |
| 10.2 | RelayClient cert pinning hardening — SpkiPinTrustManager, SPKI BuildConfig fields |
| 10.3 | MediaPipe model optimization — unified SHA-256 constant, GestureModelLoader singleton |
| 10.4 | Backup/restore polish — passphrase confirm, date filename, progress overlay, i18n strings |

---

## 🔧 NEARLY COMPLETE

### Phase 5.2 — Coverage gate
- [ ] Raise `minimum` in `build.gradle.kts`: 50% → 55% (1-line change, safe now)
- [ ] Add `QRExchangeViewModel` relay-state unit tests (POST → pending → GET → success/timeout) using fake `RelayClient`
- [ ] Raise floor 55% → 60% once tests land

### Phase 6.8 — Deeplink "Add contact" sheet
- [ ] `DeeplinkUtils.decodeShareUrl()` → pre-filled contact save dialog in `MainActivity.onNewIntent()`
  *(Manifest filter + autoVerify already in; handler skeleton exists)*

---

## 📋 REMAINING WORK — Execution graph

> Maximal parallelism. Each stage can begin once all its listed prerequisites are ✅.
> Items within a stage are independent and can be parallelised freely.

---

### Stage 1 — All independent, start immediately

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  A1  Raise JaCoCo floor → 55%              (build.gradle.kts 1-liner)       │
│  A2  QRExchangeViewModel relay-state tests  (fake RelayClient, JVM)          │
│                                                                               │
│  B1  Localization review: DE               ┐                                 │
│  B2  Localization review: ES               │                                 │
│  B3  Localization review: FR               │ one PR per locale               │
│  B4  Localization review: HI               │ all 7 fully independent         │
│  B5  Localization review: JA               │                                 │
│  B6  Localization review: KO               │                                 │
│  B7  Localization review: ZH-CN            ┘                                 │
│                                                                               │
│  C1  Deeplink decode → pre-filled "Add contact" sheet (MainActivity)         │
│                                                                               │
│  D1  docs/WIRE_PROTOCOL.md — full byte-layout spec v1–v7 extracted           │
│  D2  Cross-platform ECDH test vector (fixed keys → expected shared secret)   │
│                                                                               │
│  F1  Wear OS: Settings → "Wear OS" companion pairing flow (phone-side)       │
│                                                                               │
│  G1  Android Auto: voice action registration in AndroidManifest              │
│  G2  Android Auto: biometric-only auth gate when camera unavailable          │
│                                                                               │
│  H1  Enterprise: WorkManager audit-log retention cleanup job                 │
│      (periodic Room purge keyed on audit_log_retention_days MDM policy)      │
│                                                                               │
│  I1  F-Droid: fdroidserver local build verification                           │
│                                                                               │
│  J1  Gesture classifier A/B test                                              │
│      (100 genuine + 100 impostor; FAR/FRR cosine-only vs cosine+classifier)  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Prerequisites:** none

---

### Stage 2 — Depends on Stage 1 items as noted

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  A3  Raise JaCoCo floor → 60%              after A2                          │
│                                                                               │
│  B8  docs/strings_review_log.md            after ALL of B1–B7                │
│                                                                               │
│  E1  iOS SwiftUI full app                  after D1 + D2                     │
│      (MultipeerConnectivity, P-256 ECDH, AES-GCM, SAS, vCard 3.0)           │
│  E2  GitHub Actions macos-latest iOS build after D1                          │
│                                                                               │
│  I2  Submit fdroid/com.showerideas.aura.yml to F-Droid data repo  after I1  │
│                                                                               │
│  J2  Document FAR/FRR results; adjust CONFIDENCE_GATE if needed   after J1  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Prerequisites:** Stage 1 items as noted above

---

### Stage 3 — Milestone gates

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  TAG  v2.1.0  — after A3 + B8 + C1                                           │
│               (coverage 60%, localization reviewed, deeplink complete)        │
│                                                                               │
│  TAG  v3.0.0  — after E1 + E2                                                 │
│               (iOS companion full app, cross-platform CI)                     │
│                                                                               │
│  TAG  v3.1.0  — after F1 + G1 + G2                                           │
│               (Wear OS pairing UI, Auto voice + biometric)                   │
│                                                                               │
│  TAG  v3.2.0  — after H1 + I2                                                 │
│               (Enterprise retention cleanup, F-Droid live)                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Version history

| Version | Released | Key changes |
|---|---|---|
| v1.0.0 | 2026-05-23 | Gesture gate, ECDH+HKDF, room exchange, QR fallback, blocklist, biometric |
| v1.1.0 | 2026-05-24 | QR relay, 7 locales 100%, 259 unit + 51 instrumented tests, signed APK splits |
| v2.0.0 | 2026-05-25 | 22 PRs: transport injection, NFC, profiles, identity rotation, audit log, backup, QS tile, post-quantum, sealed sender, Tor, iOS/Wear/Auto/MDM/F-Droid scaffolds |
| v2.0.1 | 2026-05-26 | 4 PRs: NFC HCE full impl, SPKI runtime pinning, MediaPipe model unification, backup/restore polish |
| v2.1.0 | planned | JaCoCo 60%, localization human review, deeplink Add Contact sheet |
| v3.0.0 | planned | iOS companion full SwiftUI app, cross-platform ECDH CI |
| v3.1.0 | planned | Wear OS pairing UI, Android Auto voice + biometric |
| v3.2.0 | planned | Enterprise audit retention cleanup, F-Droid submission live |

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions |
| [`docs/WIRE_PROTOCOL.md`](docs/WIRE_PROTOCOL.md) | Wire protocol v1–v7 byte layouts |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step exchange sequence |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Room schema (v9), migration history |
| [`docs/qr-relay-setup.md`](docs/qr-relay-setup.md) | Firebase Realtime Database relay setup |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-26 — v2.0.1 sprint complete (PRs #84–#87). Phases 10.1–10.4 merged. Next milestone: v2.1.0 (coverage gate, l10n review, deeplink).*
