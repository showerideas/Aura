// Sources/AuraCore/SasVerifier.swift
// Phase E1 — Short Authentication String (SAS) verifier for iOS.
//
// Mirrors Android SasVerifier exactly: derives a 6-digit decimal code from
// the HKDF-SHA256 output keying material so both devices can cross-check
// without transmitting the shared secret.

import Foundation
import CryptoKit

/// Generates and verifies the Short Authentication String cross-check code.
///
/// Both Alice and Bob derive the same `code` from their shared session key.
/// The user reads the code aloud to the other party — a match confirms that
/// no MITM substituted keys during the ECDH exchange.
///
/// SAS construction (matches Android `SasVerifier`):
///   1. Derive 4 bytes from the session key via HKDF-SHA256,
///      info = `"AURA-SAS-v1"`, salt = nil (CryptoKit default = zero salt).
///   2. Interpret the 4 bytes as a big-endian UInt32.
///   3. Take `value % 1_000_000` → 6-digit decimal string (zero-padded).
public struct SasVerifier {

    public static let sasInfo = "AURA-SAS-v1".data(using: .utf8)!

    /// Derive the 6-digit SAS code from a 32-byte session key.
    ///
    /// - Parameter sessionKey: The AES-256-GCM session key derived during ECDH.
    /// - Returns: A zero-padded 6-digit decimal string, e.g. `"042817"`.
    public static func code(from sessionKey: SymmetricKey) -> String {
        // Derive 4 SAS bytes from the session key material
        let sasKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: sessionKey,
            info: sasInfo,
            outputByteCount: 4
        )

        let bytes = sasKey.withUnsafeBytes { Array($0) }
        let value = UInt32(bytes[0]) << 24
                  | UInt32(bytes[1]) << 16
                  | UInt32(bytes[2]) << 8
                  | UInt32(bytes[3])

        let sixDigit = value % 1_000_000
        return String(format: "%06d", sixDigit)
    }

    /// Constant-time comparison of two SAS codes (prevents timing attacks).
    ///
    /// - Returns: `true` if both codes are identical.
    public static func codesMatch(_ a: String, _ b: String) -> Bool {
        guard a.count == b.count else { return false }
        var result: UInt8 = 0
        for (ca, cb) in zip(a.utf8, b.utf8) {
            result |= ca ^ cb
        }
        return result == 0
    }
}
