# AURA — Documentation

Welcome. This folder is the single source of truth for **how AURA works, why it was built that way, and what every PR contributed**. The top-level [`README.md`](../README.md) is the marketing front-face; the files below are the engineering record.

---

## 📚 Table of contents

### Core docs

| Doc | What's inside |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module map, package graph, dependency-direction rules, all top-level Mermaid diagrams |
| [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) | End-to-end sequence diagram of a successful exchange — gesture, ECDH, challenge–response, payload, avatar, replay window |
| [`SECURITY.md`](SECURITY.md) | Threat model, crypto primitives, key lifecycle, replay protection, blocklist, what AURA explicitly does **not** defend against |
| [`GESTURE_AUTH.md`](GESTURE_AUTH.md) | Accelerometer pipeline, Dynamic-Time-Warping matcher, variance check, strength meter, storage |
| [`DATA_MODEL.md`](DATA_MODEL.md) | Room schema (v1 → v2), entity diagram, DAO surface, migrations, what lives outside Room |
| [`BUILD.md`](BUILD.md) | Toolchain, env vars, common Gradle targets, CI parity, release signing |
| [`AUDIT.md`](AUDIT.md) | **Intent fulfilment audit** — every promise made in the README / Store listing scored against the codebase as it stands today |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Branch / PR conventions, commit style, required checks |

### Feature dossiers

One file per PR. Each dossier explains *what the user sees*, *what the code does*, and *what tests cover it*.

| # | Feature | Doc |
|---|---|---|
| PR-01 | Gesture-gate enforcement before exchange | [`features/01-gesture-gate.md`](features/01-gesture-gate.md) |
| PR-02 | ECDH race-condition fix | [`features/02-ecdh-race-fix.md`](features/02-ecdh-race-fix.md) |
| PR-03 | Permission-rationale bottom sheet | [`features/03-permission-rationale.md`](features/03-permission-rationale.md) |
| PR-04 | Room schema migrations | [`features/04-room-migrations.md`](features/04-room-migrations.md) |
| PR-05 | First-launch onboarding | [`features/05-onboarding.md`](features/05-onboarding.md) |
| PR-06 | Gesture-variance gate | [`features/06-gesture-variance.md`](features/06-gesture-variance.md) |
| PR-07 | vCard export | [`features/07-vcard-export.md`](features/07-vcard-export.md) |
| PR-08 | QR-code fallback exchange | [`features/08-qr-fallback.md`](features/08-qr-fallback.md) |
| PR-09 | Room mode (1 host : N guests) | [`features/09-room-exchange.md`](features/09-room-exchange.md) |
| PR-10 | Avatar STREAM sharing | [`features/10-avatar-sharing.md`](features/10-avatar-sharing.md) |
| PR-11 | Gesture-strength indicator | [`features/11-gesture-strength.md`](features/11-gesture-strength.md) |
| PR-12 | Favourites + notes | [`features/12-favorites-notes.md`](features/12-favorites-notes.md) |
| PR-13 | Device-identity challenge | [`features/13-device-challenge.md`](features/13-device-challenge.md) |
| PR-14 | Endpoint blocklist (DB v2) | [`features/14-blocklist.md`](features/14-blocklist.md) |
| PR-15 | Replay-attack protection | [`features/15-replay-protection.md`](features/15-replay-protection.md) |
| PR-16 | Biometric unlock | [`features/16-biometric.md`](features/16-biometric.md) |
| PR-17 | Accessibility audit | [`features/17-accessibility.md`](features/17-accessibility.md) |
| PR-18 | Pulsing-activation animation | [`features/18-pulse-animation.md`](features/18-pulse-animation.md) |
| PR-19 | Settings + Blocked Devices screens | [`features/19-settings.md`](features/19-settings.md) |
| PR-20 | Localisation scaffolding | [`features/20-localization.md`](features/20-localization.md) |
| PR-21 | Test-suite finisher | [`features/21-tests.md`](features/21-tests.md) |
| PR-22 | Release config + ProGuard + CI | [`features/22-release-ci.md`](features/22-release-ci.md) |

---

## 🧭 How to navigate

If you are…

- **Trying the app** → start with the top-level [`README.md`](../README.md) and the [latest release](https://github.com/showerideas/Aura/releases/latest).
- **Reviewing security** → [`SECURITY.md`](SECURITY.md) and [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md), then [`features/13-device-challenge.md`](features/13-device-challenge.md) and [`features/15-replay-protection.md`](features/15-replay-protection.md).
- **Onboarding as a contributor** → [`ARCHITECTURE.md`](ARCHITECTURE.md), [`BUILD.md`](BUILD.md), [`CONTRIBUTING.md`](CONTRIBUTING.md).
- **Auditing whether the README promises match the code** → [`AUDIT.md`](AUDIT.md).
- **Understanding a single feature** → pick the matching `features/NN-*.md`.

---

*All diagrams in these docs use [Mermaid](https://mermaid-js.github.io), which GitHub renders natively. If you are reading offline, copy a code block beginning with ` ```mermaid ` into <https://mermaid.live> to view it.*
