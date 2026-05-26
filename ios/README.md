# AURA iOS Companion — Phase 7.1 Scaffold

Swift Package Manager project for the AURA iOS companion application.

## Architecture

```
Sources/AuraCompanion/
  WireProtocol.swift         — Frame types, hybrid key encoding, SAS derivation
  MultipeerTransport.swift   — MultipeerConnectivity peer discovery + data channel
```

## Wire Protocol Compatibility

The iOS companion targets wire protocol **v7** (sealed-sender profile frames),
but negotiates downward to v6 with Android hosts that haven't updated.

See [`docs/WIRE_PROTOCOL.md`](../../docs/WIRE_PROTOCOL.md) for the full spec.

## Building

```bash
swift build
swift test
```

## Requirements

| Dependency        | Version  | Source              |
|-------------------|----------|---------------------|
| Swift             | 5.9+     | Xcode 15+           |
| iOS               | 17+      | Deployment target   |
| MultipeerConnectivity | system | iOS framework    |
| CryptoKit         | system   | iOS 13+ framework   |

## Status

Phase 7.1 scaffold — core wire protocol types and MultipeerConnectivity
transport are implemented. Key exchange coordinator, UI, and CoreData
profile persistence are planned for Phase 7.1-b.
