# AURA — Project Roadmap

> **This is the canonical planning document for AURA.**
> All milestones, tasks, and future work live here. Reference it at the start of every session.
> Keep it current — strike through completed items, add blockers inline.

---

## Current status snapshot

| Area | State |
|---|---|
| Codebase | Production-ready on `main` — all 22 PRs merged |
| Security fixes | All 9 fixes merged (FIX-1 → FIX-7, FIX-9) |
| Localization | 162 strings across 7 languages (DE, ES, FR, HI, JA, KO, ZH-CN) shipped |
| Test suite | 50 tests passing — 32 unit + 4 instrumented (0 failures) |
| CI pipeline | Green — unit tests, lint, `assembleRelease`, instrumented emulator runner |
| Play Store pipeline | `upload-to-play` job wired in `ci.yml` — blocked only on 5 GitHub secrets |
| Privacy policy | Hosted via GitHub Pages at `https://showerideas.github.io/Aura/privacy` |
| AUDIT.md | Every item struck through — nothing outstanding |
| **Blocker to launch** | **5 GitHub repo secrets not yet set — this is the only gate** |

---

## PHASE 1 — Play Store Launch (primary objective)

> Everything in this phase is required before AURA ships to real users.
> Tasks are ordered by dependency. Work top-to-bottom.

---

### 1.1 Configure signing and Play Console secrets

The `upload-to-play` CI job is fully wired. The moment these 5 secrets are set, the next
push to `main` will build a signed AAB and upload it to the Play Console internal track
automatically — no manual steps required.

**Prerequisite: generate a release keystore (if not already done)**

```bash
keytool -genkey -v \
  -keystore aura-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias aura
```

Keep `aura-release.jks` somewhere safe and backed up. This file signs every release forever.
If you lose it, you cannot update the app on the Play Store.

**Prerequisite: create a Play Console service account**

1. Go to Play Console → Setup → API access
2. Link to a Google Cloud project (or create one)
3. Create a service account with the role **Release manager** (minimum scope needed)
4. Download the JSON key file

**Set these 5 secrets in GitHub → Settings → Secrets and variables → Actions:**

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 aura-release.jks` (full base64 output, no newlines) |
| `KEYSTORE_STORE_PASSWORD` | Password you chose for the keystore |
| `KEYSTORE_KEY_ALIAS` | `aura` (or whatever alias you used above) |
| `KEYSTORE_KEY_PASSWORD` | Password for the key inside the keystore |
| `GOOGLE_PLAY_JSON_KEY` | Full contents of the service account JSON file |

**Verification after setting secrets:**

- [ ] Trigger the `upload-to-play` workflow manually from GitHub → Actions
- [ ] Confirm the signed AAB appears in Play Console → Internal testing → Releases
- [ ] Download the AAB artifact from the CI run and verify it installs cleanly on a physical device
- [ ] Verify `apksigner verify --verbose your-app-release.aab` shows signature is valid

---

### 1.2 Play Console — App listing setup

These items must be complete before Google will accept the app for review.

**Store listing (Main store page)**

- [ ] App name: `AURA`
- [ ] Short description (≤ 80 chars): write final copy based on `STORE_LISTING.md`
- [ ] Full description (≤ 4000 chars): final copy from `STORE_LISTING.md` — review and confirm
- [ ] App icon: 512×512 PNG, no alpha channel — upload to Play Console
- [ ] Feature graphic: 1024×500 PNG — required for store display (currently missing)
- [ ] Screenshots — minimum 2 required per device type, recommended 4-8:
  - [ ] Phone screenshots (min 2): capture on a real device or emulator
    - Home screen with activation tile
    - Profile setup screen
    - Exchange in progress (gesture animation)
    - Contacts list with received card
  - [ ] 7-inch tablet screenshots (optional but improves discoverability)

**App content**

- [ ] Privacy policy URL: confirm `https://showerideas.github.io/Aura/privacy` resolves and renders
- [ ] Content rating questionnaire: fill out IARC questionnaire in Play Console
  - AURA has no user-generated content, no violence, no ads, no location data shared
  - Expected rating: Everyone / PEGI 3
- [ ] Target audience: confirm 18+ or All ages (no child-directed content)
- [ ] Data safety form: complete the Data safety section honestly
  - Data collected: none (all data stays on-device)
  - Data shared: none (no network calls, no backend)
  - Security practices: data encrypted in transit (Bluetooth/Wi-Fi P2P, AES-256-GCM), data encrypted at rest (Android Keystore)

**App categorization**

- [ ] Category: `Tools` or `Social` — decide and set
- [ ] Tags: contact exchange, offline, privacy, gesture, bluetooth
- [ ] Country availability: set distribution (all countries or restricted)
- [ ] Pricing: Free

---

### 1.3 Internal testing track

Once the signed AAB is uploaded via CI:

- [ ] Publish to internal testing track in Play Console
- [ ] Add internal testers (your own Google accounts + any trusted testers)
- [ ] Install via Play Store on a real device — confirm the Play-distributed build:
  - [ ] App installs without sideload warnings
  - [ ] Gesture recording works end-to-end
  - [ ] BLE exchange completes between two physical devices
  - [ ] QR fallback works
  - [ ] vCard export works
  - [ ] Blocklist persists across app restarts
  - [ ] All 7 locales display correctly (switch device language and reopen)
  - [ ] Biometric unlock triggers correctly
  - [ ] Volume button triple-press activates from background
- [ ] Run through the AUDIT.md headline claims H1-H17 manually on the Play-distributed build
- [ ] Confirm no crash on fresh install (no Room migration issues from null state)

---

### 1.4 Closed testing (optional but recommended before production)

- [ ] Create a closed testing track with a small group (5-20 people)
- [ ] Collect feedback on gesture sensitivity, onboarding clarity, and BLE reliability
- [ ] Fix any P1/P2 bugs found before promoting to production
- [ ] Minimum 1 week in closed testing before production submission

---

### 1.5 Production submission

- [ ] Promote the internal/closed testing AAB to the production track
- [ ] Set a staged rollout percentage (recommended: start at 10%, then 50%, then 100%)
- [ ] Submit for Google Play review
- [ ] Expected review time: 1-3 days for a new app
- [ ] Address any policy rejection reasons immediately
- [ ] Tag the production release in git: `git tag v1.0.0-play && git push origin v1.0.0-play`
- [ ] Update `README.md` badge/link to point to the Play Store listing URL

---

### 1.6 Post-launch monitoring (first 2 weeks)

- [ ] Watch Play Console → Android vitals for ANRs and crashes
- [ ] Watch Play Console → Ratings and reviews daily for the first week
- [ ] Set up an alert or check-in schedule for review responses
- [ ] Confirm the GitHub Actions `upload-to-play` job continues to succeed on new pushes
- [ ] Confirm GitHub Pages privacy policy URL remains live

---

## PHASE 2 — Localization completion

> Currently: 162 high-impact strings localized across 7 languages (stubs).
> Goal: full string coverage — every string in `strings.xml` translated.

- [ ] Audit `values/strings.xml` — count total strings vs. translated strings per locale
- [ ] Identify untranslated strings falling back to English in each `values-xx/` folder
- [ ] Commission or produce translations for the remaining strings in each locale:
  - `values-de/` — German
  - `values-es/` — Spanish
  - `values-fr/` — French
  - `values-hi/` — Hindi
  - `values-ja/` — Japanese
  - `values-ko/` — Korean
  - `values-zh-rCN/` — Simplified Chinese
- [ ] Review translated strings with a native speaker or translation tool for each locale
- [ ] Add a CI lint step that fails if any string key exists in `values/` but is missing in any `values-xx/`
- [ ] Ship as v1.1.1 patch release

---

## PHASE 3 — Test coverage hardening

> Current: 50 tests, 0 failures. Some coverage gaps remain (see AUDIT.md §2 yellow items).

- [ ] Biometric unlock — add instrumented test (currently 🟡 — wired but untested)
  - Mock `BiometricPrompt` or use a test-only stub
- [ ] Permission-rationale sheet — add Espresso test (currently 🟡)
- [ ] Room exchange (host/guest) — add instrumented test (currently manual QA only)
- [ ] Avatar streaming — add instrumented test (currently manual QA only)
- [ ] Onboarding flow — add Espresso smoke test (currently manual QA only)
- [ ] Settings screen — add Espresso test covering blocked device filter
- [ ] Target: 0 yellow items in AUDIT.md §2 (all green)
- [ ] Ship as v1.2.x

---

## PHASE 4 — Future features (v2.x)

> These are ideas for after the Play Store launch is stable. No detailed specs yet.
> Promote to a full phase with task breakdown when the time comes.

### Connectivity

- NFC tap as an alternative activation method (no volume button needed)
- Wi-Fi Direct as a transport alternative to Nearby Connections
- Bluetooth Classic fallback for older devices

### Exchange UX

- Contact deduplication — detect if the received card already exists in Room
- Merge prompt when receiving an updated version of an existing contact
- "Share AURA" link — deeplink or web fallback for non-AURA users

### Profile

- Multiple profiles (personal / work) with per-profile gesture
- Profile versioning — let the other person know when your card has changed since last exchange
- Custom fields beyond the standard vCard fields

### Security

- Key rotation — allow the user to regenerate their long-lived Keystore identity key
- Audit log — local-only log of every exchange (who, when, what was sent)
- Remote wipe of sent data (requires opt-in by recipient — complex UX, explore carefully)

### Platform

- iOS companion app (SwiftUI) — not in scope yet but architecturally possible at the crypto layer
- Android Auto profile — read out received contact details via TTS
- Wear OS glance tile

### Distribution

- F-Droid listing (requires fully reproducible build and no proprietary deps — Nearby Connections is a blocker)
- Enterprise MDM distribution via managed Google Play

### Quality

- Crashlytics or a self-hosted crash reporter (evaluate privacy implications carefully — AURA's privacy promise is strict)
- A/B testing for onboarding flow copy
- Automated screenshot generation for Play Store listing via Screengrab / Fastlane

---

## Reference

| Document | Purpose |
|---|---|
| [`docs/AUDIT.md`](docs/AUDIT.md) | Intent-fulfilment audit — every feature claim vs. code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Package map, threading model, dependency rules |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, crypto decisions, NIST SP 800-56A compliance |
| [`docs/EXCHANGE_FLOW.md`](docs/EXCHANGE_FLOW.md) | Step-by-step sequence diagram for a complete exchange |
| [`docs/GESTURE_AUTH.md`](docs/GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, 42-float embedding, cosine-similarity matching, stability gate |
| [`STORE_LISTING.md`](STORE_LISTING.md) | Play Store copy — short description, full description, keywords |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy source (hosted via GitHub Pages) |
| [`SECURITY.md`](SECURITY.md) | Responsible disclosure policy |

---

*Last updated: 2026-05-23 — codebase fully consolidated on `main`, all roadmap items through v1.3.0 complete, Play Store launch pending secrets configuration.*
