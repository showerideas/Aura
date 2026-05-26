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
| Codebase | `main` — v2.0.1 + Stage 1/2 sprint (13 PRs #88–#100); open PRs pending merge |
| Phases shipped | 2, 3, 4, 5.1/3/4/6/7/8, 6.1–6.11, 7.1–7.5, 8.1–8.4, 9.1–9.4, 10.1–10.4, A1–A3, B1–B8, C1, D1–D2, E1–E2, F1, G1–G2, H1, I1–I2, J1–J2 |
| Crypto stack | Hybrid KEM ML-KEM-768+X25519 (v6) · Sealed sender (v7) · DoubleRatchet · SAS · TOFU · PBKDF2+AES-256-GCM backup · Runtime SPKI pinning |
| Transport | NearbyConnections (gms) · WifiDirect (foss) · NFC HCE + NDEF tap · QR relay (HTTPS+Tor) |
| Platforms | Android phone · Wear OS tile + pairing UI · Android Auto (voice+biometric) · iOS companion (full AuraCore) |
| Distribution | GitHub Releases (signed splits arm64+armeabi-v7a) · F-Droid metadata + reproducible build script · submission guide |
| Localization | 313 strings × 7 locales — 100% coverage, human-reviewed (B1–B7), CI-enforced |
| Test suite | Unit: 27+ files · 300+ methods · iOS: 15 AuraCoreTests · Instrumented: 13 files · 55 methods · 0 failures |
| CI | Green — unit + JaCoCo (60% branch floor) + lint + assembleRelease + APK size gate + iOS build/test |
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
| A1–A3 | JaCoCo coverage gate — floor raised to 60% (already on main); A2 relay-state tests (PR #88) |
| B1–B8 | Localization human review — 9 untranslated strings fixed across 7 locales (PR #95); strings_review_log.md complete (PR #96) |
| C1 | Deeplink → pre-filled Add Contact sheet — DeeplinkContactSheet, ContactsViewModel.saveDeeplinkContact (PR #89) |
| D1 | docs/WIRE_PROTOCOL.md — complete v1–v7 byte-layout spec (on main) |
| D2 | Cross-platform ECDH test vectors — Android JVM + iOS Swift (PR #90) |
| E1 | iOS companion — AuraCore (ContactProfile vCard 3.0, SasVerifier), AuraExchangeCoordinator, 15 unit tests (PR #97) |
| E2 | iOS CI — cache, coverage, workflow_dispatch, 20-min timeout (PR #98) |
| F1 | Wear OS companion pairing — WearPairingViewModel, WearPairingBottomSheet, PhoneWearSender (PR #93) |
| G1+G2 | Android Auto — voice action (AuraVoiceActivity) + biometric auth gate (AuraBiometricAutoActivity) (PR #92) |
| H1 | Enterprise audit retention — AuditRetentionWorker WorkManager cleanup job (PR #91) |
| I1 | F-Droid reproducible build verification — fdroid/reproducible_build_test.sh (PR #99) |
| I2 | F-Droid submission — docs/FDROID_SUBMISSION.md + v2.0.1 metadata entry (PR #100) |
| J1 | Gesture classifier A/B test — GestureClassifierABTest 4 cases (PR #94) |
| J2 | Gesture FAR/FRR analysis — GESTURE_CLASSIFIER_AB_TEST.md + CONFIDENCE_GATE recommendation (PR #96) |

---

## ✅ COMPLETED — Stage 1 + Stage 2 (PRs #88–#100, 2026-05-26)

All roadmap Stage 1 and Stage 2 items are implemented and have open PRs:

| Item | PR | Description |
|---|---|---|
| A1 | (main) | JaCoCo floor already at 60% — no change needed |
| A2 | #88 | QRExchangeViewModel relay-state tests with FakeRelayClient |
| A3 | (main) | JaCoCo 60% already on main |
| B1–B7 | #95 | 9 untranslated strings fixed across all 7 locales |
| B8 | #96 | strings_review_log.md — all locales marked complete |
| C1 | #89 | Deeplink → pre-filled Add Contact bottom sheet |
| D1 | (main) | docs/WIRE_PROTOCOL.md — complete byte-layout spec |
| D2 | #90 | Cross-platform ECDH test vectors (Android JVM + iOS Swift) |
| E1 | #97 | iOS companion — AuraCore (ContactProfile, SasVerifier) + AuraExchangeCoordinator + 15 tests |
| E2 | #98 | iOS CI — cache, coverage, workflow_dispatch, 20-min timeout |
| F1 | #93 | Wear OS pairing flow — WearPairingViewModel + BottomSheet + PhoneWearSender |
| G1+G2 | #92 | Android Auto voice action + biometric auth gate |
| H1 | #91 | Enterprise WorkManager audit-log retention cleanup job |
| I1 | #99 | F-Droid reproducible build verification script |
| I2 | #100 | F-Droid submission guide + v2.0.1 metadata entry |
| J1 | #94 | Gesture classifier A/B test (FAR/FRR cosine-only vs full classifier) |
| J2 | #96 | docs/GESTURE_CLASSIFIER_AB_TEST.md — FAR/FRR analysis + gate recommendations |

---

## 📋 STAGE 3 — Milestone tags (pending PR merges)

### Stage 3 — Milestone gates

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  TAG  v2.1.0  — merge #88 (A2) + #95 (B1-B7) + #96 (B8/J2) + #89 (C1)     │
│               (coverage 60%, localization reviewed, deeplink complete)        │
│                                                                               │
│  TAG  v3.0.0  — merge #97 (E1) + #98 (E2)                                    │
│               (iOS companion full app, cross-platform CI)                     │
│                                                                               │
│  TAG  v3.1.0  — merge #93 (F1) + #92 (G1+G2)                                 │
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
| v2.1.0 | pending merge | A2+B1-B8+C1 — JaCoCo 60%, l10n human review (313 strings), deeplink Add Contact sheet |
| v3.0.0 | pending merge | E1+E2 — iOS AuraCore companion (vCard 3.0, SAS, ECDH), iOS CI with cache + coverage |
| v3.1.0 | pending merge | F1+G1+G2 — Wear OS pairing UI, Android Auto voice + biometric gate |
| v3.2.0 | pending merge | H1+I1+I2 — Enterprise audit retention, F-Droid reproducible build + submission guide |

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

*Last updated: 2026-05-26 — Stage 1+2 sprint complete (PRs #88–#100). All roadmap items implemented and in review. Next milestones: v2.1.0 (merge #88+#89+#95+#96), v3.0.0 (merge #97+#98), v3.1.0 (merge #92+#93), v3.2.0 (merge #91+#99+#100).*
