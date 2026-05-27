# AURA Wire Protocol Reference

*Last updated: 2026-05-27 | Current version: v9 (Noise_XX overlay + ML-DSA-65 hybrid sigs)*

## Table of Contents

1. [Overview](#overview)
2. [Version History](#version-history)
3. [Frame Structure](#frame-structure)
4. [Key Exchange (v6 Hybrid KEM)](#key-exchange-v6-hybrid-kem)
5. [SAS Verification (v3+)](#sas-verification-v3)
6. [Profile Payload (v4)](#profile-payload-v4)
7. [Sealed Sender (v7)](#sealed-sender-v7)
8. [Relay Protocol (v5)](#relay-protocol-v5)
9. [Transport Channels](#transport-channels)
10. [Error Handling](#error-handling)
11. [Implementation Notes](#implementation-notes)

---

## Overview

AURA uses a custom binary protocol for proximity contact exchange. The protocol
combines:
- **Gesture biometrics** — mutual authentication via hand-landmark embeddings
- **Post-quantum hybrid KEM** — ML-KEM-768 + X25519 key agreement (v6+)
- **Double Ratchet** — per-message forward secrecy after key exchange
- **SAS** — human-verifiable Short Authentication String cross-check
- **Sealed sender** — sender identity hidden from passive observers (v7+)

All integers are **big-endian** unless noted. All lengths are in bytes.

---

## Version History

| Version | Byte | Feature added                                       | Status  |
|---------|------|-----------------------------------------------------|---------|
| v1      | 0x01 | X25519 ECDH key exchange                            | Legacy  |
| v2      | 0x02 | X3DH + Double Ratchet session init                  | Stable  |
| v3      | 0x03 | SAS 6-digit verification                            | Stable  |
| v4      | 0x04 | Encrypted profile payload                           | Stable  |
| v5      | 0x05 | QR relay fallback channel                           | Stable  |
| v6      | 0x06 | ML-KEM-768 + X25519 hybrid KEM                      | Stable  |
| v7      | 0x07 | Sealed-sender profile envelope                      | Stable  |
| v8      | 0x08 | ML-DSA-65 hybrid identity signatures + SPKI pinning | Stable  |
| v9      | 0x09 | Noise_XX channel overlay + identity rotation chain  | Current |

Implementations MUST reject frames with unknown version bytes.
Implementations SHOULD negotiate downward if the remote peer advertises a lower version.

---

## Frame Structure

Every frame starts with a 4-byte header:

```
Offset  Size  Field
──────  ────  ────────────────────────────────────────────
0       1     version (ProtocolVersion byte)
1       1     frame_type (FrameType byte)
2       2     payload_len (uint16, big-endian)
4       N     payload (payload_len bytes)
```

### Frame Types

| Type             | Byte | Direction   | Description                           |
|------------------|------|-------------|---------------------------------------|
| KEY_EXCHANGE     | 0x10 | bidirectional | Hybrid public key                   |
| KEY_ACKNOWLEDGE  | 0x11 | bidirectional | KEM ciphertext (responder → initiator)|
| SAS_CHALLENGE    | 0x20 | initiator → | SAS commitment                       |
| SAS_RESPONSE     | 0x21 | responder → | SAS confirmation                     |
| PROFILE_PAYLOAD  | 0x30 | bidirectional | Encrypted profile (v4)              |
| SEALED_PROFILE   | 0x31 | bidirectional | Sealed-sender profile (v7)           |
| ACK              | 0xF0 | bidirectional | Generic acknowledgement              |
| ERROR            | 0xFF | bidirectional | Error with reason byte               |

---

## Key Exchange (v6 Hybrid KEM)

### Initiator → Responder: KEY_EXCHANGE

```
Frame payload (1217 bytes):
  [version=0x06 (1)] [x25519_pub (32)] [mlkem768_pub (1184)]
```

### Responder → Initiator: KEY_ACKNOWLEDGE

```
Frame payload (1120 bytes):
  [x25519_ephemeral_pub (32)] [mlkem768_ciphertext (1088)]
```

### Shared Secret Derivation

```
ephemeral_ss = X25519(ephemeral_priv, initiator_x25519_pub)
mlkem_ss     = ML-KEM-768.Decaps(mlkem768_private, mlkem768_ciphertext)
shared_secret = HKDF-SHA256(
                  IKM  = ephemeral_ss || mlkem_ss,
                  salt = 0x00 × 32,
                  info = "AURA-v6-hybrid-kem",
                  len  = 32
                )
```

Both parties independently compute the same 32-byte `shared_secret`.

### Security

- Breaks only if **both** X25519 and ML-KEM-768 are broken simultaneously.
- ML-KEM-768 (FIPS 203) provides post-quantum security level 3 (~AES-192 equivalent).
- X25519 provides current-day forward secrecy.
- HKDF domain separation prevents cross-protocol attacks.

---

## SAS Verification (v3+)

After key exchange, both devices derive a 6-digit SAS for human verification:

```
sas_input = SHA-256(shared_secret || "AURA-SAS")
sas_value = big_endian_uint24(sas_input[0:3]) mod 1_000_000
sas_string = zero_padded_6_digit_decimal(sas_value)
```

### SAS_CHALLENGE frame (payload: 32 bytes)

```
[commitment = SHA-256(sas_string || nonce_32)]
```

### SAS_RESPONSE frame (payload: 65 bytes)

```
[sas_string (6 ASCII digits)] [nonce (32)] [accept_byte (1: 0x01=accept, 0x00=reject)]
```

Both devices MUST verify the remote SAS matches their locally derived value before
proceeding to profile exchange.

---

## Profile Payload (v4)

Encrypted with the session key derived from the Double Ratchet state:

```
PROFILE_PAYLOAD frame payload:
  [dr_chain_index (4, BE)] [nonce (12)] [AES-256-GCM-ciphertext (N+16)]

Inner plaintext:
  [schema_version (1)] [profile_json_utf8 (N bytes)]
```

Profile JSON schema:

```json
{
  "displayName"  : "Alice",
  "avatarSha256" : "hex...",
  "publicKey"    : "base64_x25519_pub",
  "version"      : 42,
  "timestamp"    : 1716825600
}
```

---

## Sealed Sender (v7)

Hides the sender's static identity from the transport layer.

```
SEALED_PROFILE frame payload:
  [eph_pub (32)] [iv (12)] [AES-256-GCM-ciphertext (FRAME_SIZE + 16)]

Inner plaintext (always FRAME_SIZE = 4096 bytes):
  [sender_static_pub (32)] [payload_len (4, BE)] [payload] [zero_padding]
```

### Key Derivation

```
ephemeral_ss = X25519(eph_priv, recipient_static_pub)
static_ss    = X25519(sender_static_priv, recipient_static_pub)

// Full key (wrap side):
envelope_key = HKDF-SHA256(eph_ss || static_ss, info="AURA-v7-sealed-sender")

// Trial key (unwrap phase 1 — before sender is known):
trial_key = HKDF-SHA256(eph_ss, info="AURA-v7-sealed-sender-trial")
```

Unwrap is a two-phase operation:
1. Decrypt with `trial_key` to read `sender_static_pub` from inner plaintext.
2. Re-derive `envelope_key` using sender's static key and re-verify the GCM tag.

This ensures the sender cannot be forged by a relay: the MAC covers both the
ephemeral and the sender static key material.

---

## Relay Protocol (v5)

When Bluetooth/Nearby direct connection is unavailable, the QR relay provides
an HTTPS-based out-of-band channel:

```
POST /relay/{session_id}
Content-Type: application/octet-stream
Body: raw AURA frame bytes (any version)
```

The relay is end-to-end encrypted (AURA frames are always encrypted before
reaching the relay). The relay stores frames for 60 seconds then deletes them.

Session ID derivation:
```
session_id = base64url(SHA-256(initiator_x25519_pub)[0:16])
```

---

## Transport Channels

| Channel                | Discovery                    | Range | Latency | FOSS |
|------------------------|------------------------------|-------|---------|------|
| Nearby Connections     | BLE advertisement + WiFi P2P | ~30 m | Low     | No   |
| Wi-Fi Direct           | mDNS / NSD                   | ~30 m | Low     | Yes  |
| QR Relay               | HTTPS relay server           | ∞     | Medium  | Yes  |
| NFC                    | ISO 14443-A tap              | <5 cm | Low     | Yes  |
| MultipeerConnectivity  | Bonjour (iOS companion)      | ~30 m | Low     | Yes  |

Channels are tried in preference order: Nearby > Wi-Fi Direct > QR Relay.
NFC is used for initial pairing only (not full profile exchange due to size limits).

---

## Error Handling

ERROR frame payload:

```
[reason_code (1)] [message_utf8_len (2, BE)] [message_utf8]
```

Reason codes:

| Code | Constant              | Meaning                           |
|------|-----------------------|-----------------------------------|
| 0x01 | PROTOCOL_MISMATCH     | Version negotiation failed        |
| 0x02 | KEY_EXCHANGE_FAILED   | KEM or ECDH error                 |
| 0x03 | SAS_REJECTED          | User rejected SAS mismatch        |
| 0x04 | DECRYPTION_FAILED     | AES-GCM tag verification failed   |
| 0x05 | BLOCKED_DEVICE        | Device is on the blocklist        |
| 0x06 | TIMEOUT               | Exchange timed out (>30 s)        |
| 0xFF | UNKNOWN               | Unspecified error                 |

---

## Implementation Notes

### Key sizes

| Parameter              | Size (bytes) |
|------------------------|-------------|
| X25519 public key      | 32          |
| X25519 private key     | 32          |
| ML-KEM-768 public key  | 1184        |
| ML-KEM-768 private key | 2400        |
| ML-KEM-768 ciphertext  | 1088        |
| v6 hybrid public key   | 1217 (1+32+1184) |
| v6 ciphertext          | 1120 (32+1088)   |
| v7 sealed frame inner  | 4096        |
| Session key            | 32          |
| SAS                    | 6 decimal digits |

### Cryptographic dependencies

- **X25519**: BouncyCastle (Android), CryptoKit (iOS)
- **ML-KEM-768**: BouncyCastle bcpqc-jdk18on (Android), CryptoKit (iOS 18+) / custom (iOS 17)
- **AES-256-GCM**: JCA (Android), CryptoKit (iOS)
- **HKDF-SHA256**: JCA HmacSHA256 (Android), CryptoKit (iOS)
- **Double Ratchet**: Custom (DoubleRatchetState.kt / planned iOS port)

### Padding rationale (v7)

The fixed 4096-byte FRAME_SIZE prevents traffic analysis from inferring profile
size (which correlates with profile completeness and user behaviour patterns).
Profiles up to 4060 bytes (4096 − 36 header bytes) are supported; larger
profiles should be chunked in future protocol versions.
