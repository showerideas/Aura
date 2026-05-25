# AURA Wire Protocol

Version: 6 (current, Phase 8.1 hybrid KEM)
Previous: 5 (ECDH-only)

## Overview

The AURA wire protocol is a binary framing protocol used for peer-to-peer
contact exchange over Nearby Connections (Android) and MultipeerConnectivity (iOS).

## Frame format

```
[ 1B version ] [ 1B message_type ] [ 2B payload_length (big-endian) ] [ payload_bytes... ]
```

- **version**: Protocol version (5 = ECDH-only, 6 = hybrid KEM, 7 = sealed sender)
- **message_type**: See enum below
- **payload_length**: Length of `payload_bytes` in bytes (0–65535)
- **payload_bytes**: Message-specific payload

## Message types

| Value | Name           | Direction       | Description                                      |
|-------|----------------|-----------------|--------------------------------------------------|
| 0x01  | HELLO          | Initiator→Peer  | Version negotiation + ephemeral public keys       |
| 0x02  | HELLO_ACK      | Peer→Initiator  | Version confirmation + peer's public keys         |
| 0x03  | PROFILE        | Both            | AES-256-GCM encrypted profile payload            |
| 0x04  | SAS_CONFIRM    | Both            | SAS verification result (confirm/reject)          |
| 0x05  | BYE            | Both            | Graceful session termination                      |

## HELLO frame payload (v6)

```
[ 65B ecdh_public_key (uncompressed P-256) ]
[ 2B kem_public_key_length ]
[ kem_public_key_bytes... ]
[ 16B session_uuid (ASCII hex) ]
```

## PROFILE frame payload

When wire protocol >= 7 (Phase 8.2), profile bytes are wrapped in `SealedEnvelope`
before AES-256-GCM encryption. See `SealedEnvelope.kt` for the padding format.

## Maximum payload sizes

| Message type | Max payload (bytes) |
|--------------|---------------------|
| HELLO        | 256                 |
| HELLO_ACK    | 256                 |
| PROFILE      | 4096 (sealed)       |
| SAS_CONFIRM  | 1                   |
| BYE          | 0                   |

## Cross-platform ECDH test vector

Fixed private key (P-256, big-endian hex):
```
c9af10cf3e857e4dc71d6e02e536e5db35cd71e61b8f01bfe3da57f58c04d4c1
```
Fixed peer public key (uncompressed, 65 bytes hex):
```
04
b5a5c47e8a4e5f9c3c3e4b3d5a8e7c5f9d2e4b3a5c47e8a4e5f9c3c3e4b3d5a8
e7c5f9d2e4b3a5c47e8a4e5f9c3c3e4b3d5a8e7c5f9d2e4b3a5c47e8a4e5f9c3
```
Expected ECDH shared secret (HKDF-SHA256 output, 32 bytes):
```
# Computed with P-256 ECDH then HKDF-SHA256(IKM=ecdh_raw, info="AURA-v6-hybrid-kem")
# Run: HybridKEMTest.verifyTestVector() (to be added in CI)
```
