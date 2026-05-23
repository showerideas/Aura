# Security policy

Thanks for helping keep AURA and its users safe.

## Supported versions

| Version | Status |
|---|---|
| `1.x` | ✅ Actively supported |
| `< 1.0` | ❌ Pre-release, unsupported |

## Reporting a vulnerability

**Please do _not_ open a public issue for security problems.** Instead:

1. Open a private [GitHub Security Advisory](https://github.com/showerideas/Aura/security/advisories/new).

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

- Issues that require root, ADB access, or a debugger attached to the user's device (AURA's threat model assumes a non-compromised device — see `docs/SECURITY.md` §3 "Threats we don't defend against").
- The unsigned validation APK published from CI — only signed releases are subject to the SLA above.
- Bugs in third-party libraries unless AURA's usage materially worsens them.
- Volume-button activation failures on OEM-skinned Android (Samsung One UI, MIUI, ColorOS) — documented in [docs/VOLUME_BUTTON_RELIABILITY.md](docs/VOLUME_BUTTON_RELIABILITY.md) as a known limitation, not a security issue.

## Known limitations (not security vulnerabilities)

The following are documented design trade-offs. We won't accept reports for these as vulnerabilities, but we welcome discussion:

- **Gesture FAR:** The MediaPipe cosine-similarity gate has a 30–70% false-accept rate for same-gesture cross-person pairs. This is an ergonomic gate, not a biometric credential — the ECDSA identity key is the real security anchor. See [docs/GESTURE_AUTH.md §6](docs/GESTURE_AUTH.md).
- **TOFU first-meet gap:** On the very first exchange between two devices, a proximity MITM could substitute their own identity key. The `SasVerifier` 6-digit display is the mitigation; UI integration is in progress for v1.2. See [docs/SECURITY.md §6](docs/SECURITY.md).
- **`gestureVerified` process-wide scope:** In the unlikely scenario of AURA installed in both a personal and work Android profile, `markGestureVerified()` on one profile opens the gate for the other. Tracked for v1.3 refactor.

## What we do _not_ defend against (explicit non-goals)

These are documented in [`docs/SECURITY.md`](docs/SECURITY.md) and we won't accept security reports for them as bugs:

- Physical phone capture by a sophisticated attacker.
- An attacker who has installed a malicious accessibility service on the victim's phone.
- A signal-level RF jam attack on the BLE/Wi-Fi-P2P radio.

## Coordinated disclosure

After the patch is merged we will:

1. Publish a GitHub Security Advisory describing the issue, impact, and the fixed version.
2. Credit the reporter (with consent) in the advisory and in the release notes.
