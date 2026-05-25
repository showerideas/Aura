import Foundation
import CryptoKit

/// Phase 7.1 — AURA iOS Core: P-256 ECDH + HKDF-SHA256 cross-platform key agreement.
/// Matches the Android HybridKEMUtils wire protocol v6 construction.
public struct AuraCrypto {

    /// Generate an ephemeral P-256 key pair for ECDH
    public static func generateEphemeralKeyPair() -> P256.KeyAgreement.PrivateKey {
        return P256.KeyAgreement.PrivateKey()
    }

    /// Derive a 32-byte AES-256 key using HKDF-SHA256
    /// - Parameters:
    ///   - ikm: Input key material (raw ECDH shared secret bytes)
    ///   - info: Context info (must match Android: "AURA-v6-hybrid-kem")
    public static func deriveKey(ikm: Data, info: Data) -> SymmetricKey {
        let inputKey = SymmetricKey(data: ikm)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: inputKey,
            info: info,
            outputByteCount: 32
        )
    }

    /// AES-256-GCM encrypt
    public static func encrypt(key: SymmetricKey, plaintext: Data) throws -> Data {
        let sealed = try AES.GCM.seal(plaintext, using: key)
        return sealed.combined ?? Data()
    }

    /// AES-256-GCM decrypt
    public static func decrypt(key: SymmetricKey, ciphertext: Data) throws -> Data {
        let sealedBox = try AES.GCM.SealedBox(combined: ciphertext)
        return try AES.GCM.open(sealedBox, using: key)
    }
}

/// Wire protocol version constants (mirrors Android HybridKEMUtils)
public enum WireProtocol {
    public static let version: UInt8 = 6
    public static let versionV5: UInt8 = 5
    public static let info = "AURA-v6-hybrid-kem".data(using: .utf8)!
}
