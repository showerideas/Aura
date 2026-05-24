# AURA Privacy Policy

**Effective date:** 2026-05-22
**Application:** AURA — Contact Exchange (package `com.showerideas.aura`)
**Publisher:** Shower Ideas

**Hosted at:** <https://showerideas.app/aura/privacy> (deployed via GitHub Pages — see `.github/workflows/gh-pages.yml`)

---

## 1. The short version

AURA is an **offline, local-only** contact-exchange app. Your data never leaves your device unless **you** explicitly share it with another nearby user during an exchange, and even then it travels only over a direct device-to-device channel (Bluetooth Low Energy / Wi-Fi P2P / Wi-Fi hotspot) — never through our servers, because we don't operate any.

- **No data ever leaves the device** except over the local peer-to-peer channels you authorise.
- **No account, no login, no email required.**
- **No analytics, no advertising SDKs, no third-party trackers.**
- **No outbound network calls.** The app's network security config explicitly disables cleartext traffic and AURA itself never opens an HTTP(S) connection.

---

## 2. What data the app handles

### 2.1 Data stored locally on your device
- **Your profile** — display name, phone, email, company, title, website, bio, optional avatar. Stored in the app's private Room database.
- **Contacts you exchange** — the profile fields the other user chose to share with you. Stored in the same Room database.
- **Gesture pattern** — a numeric feature vector derived from the unlock gesture you record during onboarding. Stored encrypted via `EncryptedSharedPreferences` (Android Keystore-backed). The raw gesture is never persisted.
- **Identity key** — a non-extractable EC256 key pair generated on first run and held in the Android Keystore. Used to sign exchange challenges so a peer can detect impersonation.
- **Endpoint blocklist** — fingerprints of peers you have explicitly blocked.
- **App preferences** — your chosen unlock method (gesture or biometric), share-field toggles, locale, etc.

### 2.2 Data transmitted during an exchange
When you tap **Activate** (or trigger the volume-button hotword) and complete the gesture / biometric gate, AURA opens a **local** Nearby Connections session. Over that session it sends, **only**:
- The profile fields you have marked as shareable.
- A signed challenge containing your public identity key.
- The avatar bytes (if you chose to share an avatar).

The session is encrypted end-to-end by Google's Nearby Connections layer (ECDH key agreement + AES-GCM). No part of the exchange touches Shower Ideas servers.

### 2.3 Data we (the publisher) collect
**None.** We operate no servers and run no analytics. We literally cannot see your contacts, profile, gestures, or exchange history.

---

## 3. Permissions we request and why

| Permission | Why AURA needs it |
| --- | --- |
| `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Local peer discovery via BLE. |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scanning on older API levels. We do **not** read your geographic location; the permission is never used to determine where you are. |
| `NEARBY_WIFI_DEVICES` | Wi-Fi P2P transport fallback for Nearby Connections. |
| `CAMERA` | QR-code fallback when BLE/Wi-Fi is unavailable. The camera is only opened on the QR screen. |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Importing/exporting contacts to your system address book, **only** when you explicitly tap "Add to phone contacts" or perform a vCard export. |
| `USE_BIOMETRIC` | Optional alternative to the gesture unlock. |
| `POST_NOTIFICATIONS` | Foreground-service status during an active exchange. |

None of these permissions are used for any purpose other than the one listed.

---

## 4. Data sharing with third parties

We do not share data with third parties because we do not have your data. The only "third parties" involved at all are:
- **Google Play Services / Nearby Connections** — system-level component on your device that brokers the local BLE / Wi-Fi P2P session. It is part of Android, not Shower Ideas.
- **Android Keystore** — for the identity key and encrypted preferences. Same caveat.

---

## 5. Data retention and deletion

- All data lives on your device. There is nothing for us to retain.
- **Delete a single contact:** open the contact, tap the overflow menu, **Delete**.
- **Delete all contacts:** **Settings → Clear all contacts**.
- **Delete everything:** uninstall AURA. Because we set `android:hasFragileUserData="true"`, Android will explicitly warn you that this removes all AURA data, and then it does.

---

## 6. Children's privacy

AURA is not directed at children under 13. It does not knowingly process data from children.

---

## 7. Changes to this policy

If we ever change this policy we will update the **Effective date** at the top and publish the new version at <https://showerideas.app/aura/privacy>. Substantive changes will also be announced in the app's release notes on Google Play.

---

## 8. Contact

Questions about this policy: open an issue on the [GitHub repository](https://github.com/showerideas/Aura/issues) or open a private [GitHub Security Advisory](https://github.com/showerideas/Aura/security/advisories/new) for sensitive matters.
