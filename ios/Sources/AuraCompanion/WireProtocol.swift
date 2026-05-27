// Sources/AuraCompanion/WireProtocol.swift
// Phase 7.1 — AURA wire protocol types for iOS companion.
//
// Implements the shared wire format documented in docs/WIRE_PROTOCOL.md.
// The iOS companion and Android host must agree on all byte layouts;
// protocol version is the single source of truth for compatibility.

import Foundation
import CryptoKit

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Protocol Version
// ─────────────────────────────────────────────────────────────────────────────

public enum ProtocolVersion: UInt8 {
    case legacyECDH       = 0x01    // v1: X25519 only
    case doubleRatchetInit = 0x02   // v2: X3DH + Double Ratchet
    case withSas           = 0x03   // v3: + SAS verification
    case withProfile       = 0x04   // v4: + encrypted profile payload
    case withRelay         = 0x05   // v5: + QR relay fallback
    case hybridPQ          = 0x06   // v6: + ML-KEM-768 hybrid KEM
    case sealedSender      = 0x07   // v7: + sealed-sender profile frame
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Frame header
// ─────────────────────────────────────────────────────────────────────────────

/// Common header for all AURA wire frames.
///
/// Byte layout: `[version(1)][frame_type(1)][payload_len(2 BE)][payload...]`
public struct FrameHeader {
    public let version     : ProtocolVersion
    public let frameType   : FrameType
    public let payloadLen  : UInt16

    public static let headerBytes = 4

    public init(version: ProtocolVersion, frameType: FrameType, payloadLen: UInt16) {
        self.version    = version
        self.frameType  = frameType
        self.payloadLen = payloadLen
    }

    public func encoded() -> Data {
        var data = Data(capacity: Self.headerBytes)
        data.append(version.rawValue)
        data.append(frameType.rawValue)
        data.append(UInt8((payloadLen >> 8) & 0xFF))
        data.append(UInt8(payloadLen & 0xFF))
        return data
    }

    public static func decode(from data: Data) throws -> FrameHeader {
        guard data.count >= headerBytes else { throw AuraError.frameTooShort }
        guard let version = ProtocolVersion(rawValue: data[0]) else { throw AuraError.unknownVersion(data[0]) }
        guard let frameType = FrameType(rawValue: data[1]) else { throw AuraError.unknownFrameType(data[1]) }
        let payloadLen = (UInt16(data[2]) << 8) | UInt16(data[3])
        return FrameHeader(version: version, frameType: frameType, payloadLen: payloadLen)
    }
}

public enum FrameType: UInt8 {
    case keyExchange       = 0x10
    case keyAcknowledge    = 0x11
    case sasChallenge      = 0x20
    case sasResponse       = 0x21
    case sasConfirmation   = 0x22    // both peers confirm SAS match (iOS exchange path)
    case profilePayload    = 0x30
    case sealedProfile     = 0x31
    case encryptedProfile  = 0x32    // AES-GCM encrypted profile (iOS simplified exchange)
    case ack               = 0xF0
    case error             = 0xFF
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Key exchange frames (v6 hybrid)
// ─────────────────────────────────────────────────────────────────────────────

/// v6 hybrid public key: `[0x06][x25519_pub(32)][placeholder_mlkem_pub(1184)]`
/// On iOS, ML-KEM-768 uses CryptoKit when available (iOS 18+) or a bundled
/// implementation for iOS 17.
public struct HybridPublicKey {
    public static let wireBytes = 1 + 32 + 1184  // 1217

    public let x25519PublicKey  : Curve25519.KeyAgreement.PublicKey
    public let mlkemPublicBytes : Data       // 1184 bytes (opaque on iOS 17)

    public func encoded() -> Data {
        var data = Data(capacity: Self.wireBytes)
        data.append(ProtocolVersion.hybridPQ.rawValue)
        data.append(contentsOf: x25519PublicKey.rawRepresentation)
        data.append(mlkemPublicBytes)
        return data
    }

    public static func decode(from data: Data) throws -> HybridPublicKey {
        guard data.count == wireBytes else { throw AuraError.malformedKey }
        guard data[0] == ProtocolVersion.hybridPQ.rawValue else { throw AuraError.unknownVersion(data[0]) }
        let x25519Bytes = data[1..<33]
        let mlkemBytes  = data[33..<wireBytes]
        let x25519Key   = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: x25519Bytes)
        return HybridPublicKey(x25519PublicKey: x25519Key, mlkemPublicBytes: Data(mlkemBytes))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — SAS (Short Authentication String)
// ─────────────────────────────────────────────────────────────────────────────

/// Derives a 6-digit SAS from the shared secret for cross-device verification.
///
/// Both devices compute SAS from the same session shared secret immediately
/// after key exchange. The user reads both numbers aloud and confirms they match.
public struct SasDeriver {
    /// Derive a 6-digit decimal SAS from [sharedSecret].
    /// Consistent with the Android SasVerifier implementation.
    public static func derive(from sharedSecret: Data) -> String {
        let hash = SHA256.hash(data: sharedSecret + "AURA-SAS".data(using: .utf8)!)
        let hashBytes = Data(hash)
        // Take 3 bytes → 24 bits → 0..16_777_215 → modulo 1_000_000 → 6 digits
        let raw = (UInt32(hashBytes[0]) << 16) | (UInt32(hashBytes[1]) << 8) | UInt32(hashBytes[2])
        let sixDigit = raw % 1_000_000
        return String(format: "%06d", sixDigit)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Errors
// ─────────────────────────────────────────────────────────────────────────────

public enum AuraError: Error {
    case frameTooShort
    case unknownVersion(UInt8)
    case unknownFrameType(UInt8)
    case malformedKey
    case decryptionFailed
    case sasVerificationFailed
}
