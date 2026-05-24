# AURA — Documentation hub

> The top-level [`README.md`](../README.md) is the front-face. **This folder is the engineering record** — what every layer does, why, and how it was built.

---

## Core docs

| Doc | What's inside |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module map, package graph, dependency-direction rules, runtime component diagram, navigation graph, DI wiring |
| [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) | End-to-end sequence: gesture → ECDH → challenge → AES-GCM → avatar → replay window |
| [`SECURITY.md`](SECURITY.md) | Threat model, crypto primitives, key lifecycle, what AURA does **not** defend against |
| [`GESTURE_AUTH.md`](GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, 63-float embedding (21 landmarks × x,y,z), cosine-similarity matching, stability gate, security properties |
| [`DATA_MODEL.md`](DATA_MODEL.md) | Room v5 schema, entity diagram (Contact, Profile, BlockedEndpoint, KnownPeer, ExchangeAuditEntry), DAO surface, migration history |
| [`BUILD.md`](BUILD.md) | Toolchain, env vars, Gradle targets, CI parity, release signing |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Branch / PR conventions, commit style, required checks |
| [`AUDIT.md`](AUDIT.md) | Intent fulfilment audit — every README claim cross-referenced to code |
| [`SHOWCASE.md`](SHOWCASE.md) | Screenshots and demo notes |
| [`MANUAL_QA_PASS.md`](MANUAL_QA_PASS.md) | Manual QA recipe — step-by-step device test script |
| [`VOLUME_BUTTON_RELIABILITY.md`](VOLUME_BUTTON_RELIABILITY.md) | Known OEM skin limitations of the volume-button wake mechanism |

---

## Navigation by reader type

| If you are… | Start here |
|---|---|
| 📱 Trying the app | Top-level [`README.md`](../README.md) + [latest release](https://github.com/showerideas/Aura/releases/latest) |
| 🔐 Reviewing security | [`SECURITY.md`](SECURITY.md) → [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) → [`GESTURE_AUTH.md`](GESTURE_AUTH.md) |
| 🛠 Contributing code | [`ARCHITECTURE.md`](ARCHITECTURE.md) → [`BUILD.md`](BUILD.md) → [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| 🧪 Auditing the project | [`AUDIT.md`](AUDIT.md) — every README claim cross-referenced to code |
| 💾 Understanding storage | [`DATA_MODEL.md`](DATA_MODEL.md) — full schema, migrations, backup exclusions |

---

*All diagrams use [Mermaid](https://mermaid-js.github.io), which GitHub renders natively. Offline? Paste any ` ```mermaid ` block into <https://mermaid.live>.*
