# Security policy

Thanks for helping keep AURA and its users safe.

## Supported versions

| Version | Status |
|---|---|
| `1.x` | ✅ Actively supported |
| `< 1.0` | ❌ Pre-release, unsupported |

## Reporting a vulnerability

**Please do _not_ open a public issue for security problems.** Instead:

1. Open a private [GitHub Security Advisory](https://github.com/showerideas/Aura/security/advisories/new), **or**
2. Email the maintainers at `security@showerideas.app` with subject `[AURA security]`.

Include:

- A description of the issue and its impact.
- A minimal reproduction (device model, AURA version, steps).
- Whether you'd like public credit when the fix ships.

We aim to acknowledge within **48 hours** and to ship a patch (or publish a workaround) within **14 days** for confirmed reports.

## Scope

In scope:

- The Android app published in [Releases](https://github.com/showerideas/Aura/releases).
- The ECDH key exchange, AES-256-GCM payload encryption, ECDSA challenge-response, and replay window described in [`docs/SECURITY.md`](docs/SECURITY.md).
- The gesture / biometric authentication gate.
- Local data-at-rest (Room database, `EncryptedSharedPreferences`, Android Keystore usage).

Out of scope:

- Issues that require root, ADB access, or a debugger attached to the user's device (AURA's threat model assumes a non-compromised device — see `docs/SECURITY.md` §"Out of scope").
- The unsigned validation APK published from CI — only signed releases are subject to the SLA above.
- Bugs in third-party libraries unless AURA's usage materially worsens them.

## What we do _not_ defend against (explicit non-goals)

These are documented in [`docs/SECURITY.md`](docs/SECURITY.md) and we won't accept security reports for them as bugs:

- Physical phone capture by a sophisticated attacker.
- An attacker who has installed a malicious accessibility service on the victim's phone.
- A signal-level RF jam attack on the BLE/Wi-Fi-P2P radio.

## Coordinated disclosure

After the patch is merged we will:

1. Publish a GitHub Security Advisory describing the issue, impact, and the fixed version.
2. Credit the reporter (with consent) in the advisory and in the release notes.
