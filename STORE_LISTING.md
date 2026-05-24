# AURA — Google Play Store Listing

Source-of-truth for the Play Console submission. Update the placeholders marked **TODO** before publishing.

---

## App identity
- **Package name:** `com.showerideas.aura`
- **App name:** AURA
- **Default language:** English (en-US)
- **Category:** Communication
- **Tags:** contacts, networking, productivity, offline, privacy
- **Pricing:** Free, no in-app purchases, no ads

---

## Short description (max 80 characters)
> Trade contacts in a single gesture — offline, encrypted, no account required.

*(Current length: 75 chars.)*

---

## Full description (max 4000 characters)

> AURA is the fastest, most private way to swap contact details with someone right in front of you.
>
> Tap, perform your gesture, and your profile flies across the room — encrypted end-to-end, sent only over a direct local Bluetooth or Wi-Fi link between the two phones. No server. No account. No login. No analytics. No tracking. AURA can't see your contacts because it never has the chance to.
>
> **How it works**
> • Set up your profile once: name, phone, email, company, title, website, bio, avatar.
> • Choose which fields you want to share (and change it any time).
> • Record a custom unlock gesture, or use your fingerprint / face unlock instead.
> • Tap Activate. Both phones light up. Perform your gesture. Done.
>
> **Why people use AURA**
> • **Faster than typing a number.** A real exchange takes about two seconds.
> • **No business cards.** No more pockets full of card-stock you'll throw out.
> • **No "find me on LinkedIn" awkwardness.** AURA gives the other person *your* fields, the way *you* curated them.
> • **Works in airplane mode.** Bluetooth and Wi-Fi P2P paths are fully offline — designed for conferences, weddings, networking events, and signal-dead venues. An optional QR relay path uses the internet only when Bluetooth and Wi-Fi P2P are unavailable; it sees only end-to-end encrypted ciphertext and no plaintext ever leaves your device.
>
> **Built for privacy**
> • No plaintext ever leaves your device. The optional QR relay transmits only AES-256-GCM ciphertext; the relay server is stateless and sees nothing readable.
> • No account creation. No email or phone number required.
> • Your profile data lives only in your phone's encrypted app storage.
> • Your gesture is stored as an encrypted feature vector — the original recording is discarded.
> • The exchange itself is signed with a per-device key in the Android Keystore, so you can detect impersonation.
> • Endpoint blocklist: if you don't want to swap with someone again, block them and they can never reconnect.
>
> **Extra features**
> • QR-code fallback for environments where Bluetooth or Wi-Fi P2P is blocked.
> • Room mode: one host, many guests — perfect for meetups and small events.
> • vCard export: send your contacts back into your phone's address book or anywhere you like.
> • Favourites and notes: jot a private note next to the people who matter.
> • Full accessibility audit: TalkBack, large fonts, high-contrast theme.
> • Multilingual: English, Hindi, Spanish, French, German, Japanese, Korean, Simplified Chinese.
>
> Built by Shower Ideas. Privacy policy: https://showerideas.app/aura/privacy

*(Replace the URL placeholder with the live policy once hosted.)*

---

## Graphic assets (TODO — designer hand-off)

| Asset | Spec | File |
| --- | --- | --- |
| App icon | 512×512 PNG, 32-bit RGBA | TODO |
| Feature graphic | 1024×500 PNG/JPG, no transparency | TODO |
| Phone screenshots (min 2, max 8) | 16:9 or 9:16, 320–3840 px on each side | TODO |
| 7" tablet screenshots *(optional)* | same constraints | TODO |
| 10" tablet screenshots *(optional)* | same constraints | TODO |
| Promo video *(optional)* | YouTube URL | TODO |

Required minimum: **icon + feature graphic + 2 phone screenshots**.

---

## Content rating questionnaire

Submit through the IARC tool in Play Console. Expected answers:

| Question | Answer |
| --- | --- |
| Violence | None |
| Sexuality / nudity | None |
| Profanity / crude humour | None |
| Controlled substances | None |
| Gambling | None |
| User-generated content shared with others | **Yes** — users share their own profile fields with peers over local Bluetooth/Wi-Fi |
| Location sharing | **No** — `ACCESS_FINE_LOCATION` is requested only as an OS prerequisite for BLE scanning; AURA never reads or transmits geographic location |
| Personal info sharing | **Yes** — the user's own profile fields, only when they tap Activate |
| Interaction between users | **Yes** — peer-to-peer, no central server |

Expected rating: **Everyone** / **PEGI 3**.

---

## Data safety form

> All answers below derive from the actual app behaviour. See `PRIVACY_POLICY.md` for the full breakdown.

**Does your app collect or share any of the required user data types?** **No.**

If Play Console insists on individual data-type answers:

| Data type | Collected? | Shared? | Notes |
| --- | --- | --- | --- |
| Name | No | No | Stored only on-device, never sent to us |
| Email address | No | No | Stored only on-device |
| Phone number | No | No | Stored only on-device |
| Photos | No | No | Avatar stored only on-device |
| Contacts | No | No | Imported/exported only on explicit user action |
| Location (approximate / precise) | No | No | Permission is BLE-scan prerequisite only |
| App activity / interactions | No | No | No analytics SDK |
| Device or other IDs | No | No | No advertising ID, no install ID |

**Security practices**
- ✅ Data is encrypted in transit (Nearby Connections AES-GCM).
- ✅ Data is encrypted at rest where sensitive (EncryptedSharedPreferences for gesture vector, Android Keystore for identity key).
- ✅ User can request data deletion: **Settings → Clear all contacts**, or uninstall.
- ✅ Independent security review: TODO (internal threat-model doc only at this stage).

**Data deletion mechanism:** In-app (Settings → Clear all contacts) and via uninstall (`android:hasFragileUserData="true"`).

---

## Release notes (first release)

> First public release of AURA.
> • Tap-and-gesture contact exchange over local Bluetooth / Wi-Fi.
> • Biometric unlock alternative.
> • QR fallback, room mode, vCard export.
> • Endpoint blocklist + replay-attack protection.
> • Eight languages, full accessibility audit.

---

## Submission checklist

- [ ] Replace every **TODO** above.
- [x] ~~Host `PRIVACY_POLICY.md` at https://showerideas.app/aura/privacy.~~ ✅ Deployed via `gh-pages.yml` (v1.3.0).
- [ ] Upload assets in Play Console.
- [ ] Run `./gradlew assembleRelease` with the four `KEYSTORE_*` env vars set.
- [ ] Upload signed AAB to the internal testing track first.
- [ ] Promote to closed → open → production after at least one round of internal QA.
