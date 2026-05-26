// Tests/AuraCompanionTests/AuraCryptoTests.swift
// Stage-1 / D2 — Cross-platform ECDH test vectors for iOS companion.
//
// Parallel to CrossPlatformECDHVectorTest.kt on the Android side.
// Both suites verify:
//   1. ECDH commutativity: ECDH(privA, pubB) == ECDH(privB, pubA)
//   2. Derived key is exactly 32 bytes (AES-256)
//   3. Wire-format round-trip: X.509 public key encodes/decodes losslessly
//   4. Full wire-format exchange produces identical keys on both sides

import XCTest
import CryptoKit
@testable import AuraCompanion

final class AuraCryptoTests: XCTestCase {

    // MARK: — Round-trip tests

    /// ECDH commutativity: both sides must derive the same raw shared secret.
    func testECDH_isCommutative() throws {
        let privA = P256.KeyAgreement.PrivateKey()
        let privB = P256.KeyAgreement.PrivateKey()

        // Raw ECDH (before HKDF) must be identical from both perspectives
        let secretFromA = try privA.sharedSecretFromKeyAgreement(with: privB.publicKey)
        let secretFromB = try privB.sharedSecretFromKeyAgreement(with: privA.publicKey)

        let bytesA = secretFromA.withUnsafeBytes { Data($0) }
        let bytesB = secretFromB.withUnsafeBytes { Data($0) }

        XCTAssertEqual(bytesA, bytesB,
            "Raw ECDH shared secret must be identical from both perspectives")
    }

    /// Full HKDF-derived key commutativity using AuraCrypto.deriveKey.
    func testDerivedKey_isCommutative() throws {
        let privA = AuraCrypto.generateEphemeralKeyPair()
        let privB = AuraCrypto.generateEphemeralKeyPair()

        let info = WireProtocol.info

        let secretA = try privA.sharedSecretFromKeyAgreement(with: privB.publicKey)
        let secretB = try privB.sharedSecretFromKeyAgreement(with: privA.publicKey)

        let ikmA = secretA.withUnsafeBytes { Data($0) }
        let ikmB = secretB.withUnsafeBytes { Data($0) }

        let keyA = AuraCrypto.deriveKey(ikm: ikmA, info: info)
        let keyB = AuraCrypto.deriveKey(ikm: ikmB, info: info)

        XCTAssertEqual(keyA, keyB,
            "HKDF-derived AES key must be identical from both sides")
    }

    /// Derived key must be exactly 32 bytes (AES-256).
    func testDerivedKey_is256Bits() throws {
        let privA = AuraCrypto.generateEphemeralKeyPair()
        let privB = AuraCrypto.generateEphemeralKeyPair()

        let secret = try privA.sharedSecretFromKeyAgreement(with: privB.publicKey)
        let ikm = secret.withUnsafeBytes { Data($0) }
        let key = AuraCrypto.deriveKey(ikm: ikm, info: WireProtocol.info)

        XCTAssertEqual(key.bitCount, 256, "Key must be AES-256 (32 bytes / 256 bits)")
    }

    /// Different peers must produce different shared keys (probabilistic).
    func testDifferentPeers_produceDifferentKeys() throws {
        let privA = AuraCrypto.generateEphemeralKeyPair()
        let privB = AuraCrypto.generateEphemeralKeyPair()
        let privC = AuraCrypto.generateEphemeralKeyPair()

        let secretAB = try privA.sharedSecretFromKeyAgreement(with: privB.publicKey)
        let secretAC = try privA.sharedSecretFromKeyAgreement(with: privC.publicKey)

        let ikmAB = secretAB.withUnsafeBytes { Data($0) }
        let ikmAC = secretAC.withUnsafeBytes { Data($0) }

        let keyAB = AuraCrypto.deriveKey(ikm: ikmAB, info: WireProtocol.info)
        let keyAC = AuraCrypto.deriveKey(ikm: ikmAC, info: WireProtocol.info)

        XCTAssertNotEqual(keyAB, keyAC,
            "Different peer public keys must produce different shared secrets")
    }

    // MARK: — Wire-format round-trip

    /// X9.63 uncompressed public key (65 bytes) encodes and decodes losslessly.
    func testPublicKey_wireFormatRoundTrip() throws {
        let kp  = AuraCrypto.generateEphemeralKeyPair()
        let pub = kp.publicKey

        // Encode as X9.63 uncompressed (04 || x || y), 65 bytes — matches Android X.509 inner point
        let wireData = pub.x963Representation
        XCTAssertEqual(wireData.count, 65,
            "P-256 uncompressed public key must be 65 bytes")
        XCTAssertEqual(wireData[0], 0x04,
            "Uncompressed point must start with 0x04")

        // Decode back
        let decoded = try P256.KeyAgreement.PublicKey(x963Representation: wireData)
        XCTAssertEqual(decoded.x963Representation, wireData,
            "Round-tripped public key must be identical")
    }

    /// Full wire-format exchange: encode public key as x963 Data, decode on the
    /// other side, derive shared key — must match.
    func testWireFormatExchange_producesIdenticalSharedKey() throws {
        let privAlice = AuraCrypto.generateEphemeralKeyPair()
        let privBob   = AuraCrypto.generateEphemeralKeyPair()

        // Alice encodes her public key for the wire (x963 / uncompressed)
        let alicePubWire = privAlice.publicKey.x963Representation
        // Bob encodes his public key for the wire
        let bobPubWire   = privBob.publicKey.x963Representation

        // Alice decodes Bob's wire key and derives shared key
        let bobPubDecoded = try P256.KeyAgreement.PublicKey(x963Representation: bobPubWire)
        let secretFromAlice = try privAlice.sharedSecretFromKeyAgreement(with: bobPubDecoded)
        let ikmFromAlice = secretFromAlice.withUnsafeBytes { Data($0) }
        let keyFromAlice = AuraCrypto.deriveKey(ikm: ikmFromAlice, info: WireProtocol.info)

        // Bob decodes Alice's wire key and derives shared key
        let alicePubDecoded = try P256.KeyAgreement.PublicKey(x963Representation: alicePubWire)
        let secretFromBob = try privBob.sharedSecretFromKeyAgreement(with: alicePubDecoded)
        let ikmFromBob = secretFromBob.withUnsafeBytes { Data($0) }
        let keyFromBob = AuraCrypto.deriveKey(ikm: ikmFromBob, info: WireProtocol.info)

        XCTAssertEqual(keyFromAlice, keyFromBob,
            "Wire-format ECDH exchange must produce identical AES key on both sides")
    }

    // MARK: — Encrypt / Decrypt round-trip

    /// AES-256-GCM encrypt then decrypt must recover the original plaintext.
    func testEncryptDecrypt_roundTrip() throws {
        let key      = SymmetricKey(size: .bits256)
        let message  = "Hello, AURA cross-platform!".data(using: .utf8)!

        let ciphertext = try AuraCrypto.encrypt(key: key, plaintext: message)
        let recovered  = try AuraCrypto.decrypt(key: key, ciphertext: ciphertext)

        XCTAssertEqual(message, recovered, "Decrypted data must match original plaintext")
    }

    /// Decrypting with the wrong key must throw.
    func testDecrypt_wrongKey_throws() {
        let key1     = SymmetricKey(size: .bits256)
        let key2     = SymmetricKey(size: .bits256)
        let message  = "Secret message".data(using: .utf8)!

        XCTAssertThrowsError(try {
            let ciphertext = try AuraCrypto.encrypt(key: key1, plaintext: message)
            _ = try AuraCrypto.decrypt(key: key2, ciphertext: ciphertext)
        }(), "Decrypting with wrong key must throw an error")
    }

    // MARK: — Cross-platform vector documentation

    /**
     * Prints a cross-platform test vector to stdout.
     * Run with `swift test --filter testCrossPlatformVector_print` and embed
     * the output into docs/WIRE_PROTOCOL.md §D2 Cross-Platform ECDH Vectors.
     *
     * Expected output format matches CrossPlatformECDHVectorTest.kt on Android.
     */
    func testCrossPlatformVector_print() throws {
        let privA = AuraCrypto.generateEphemeralKeyPair()
        let privB = AuraCrypto.generateEphemeralKeyPair()

        let pubA_x963 = privA.publicKey.x963Representation.base64EncodedString()
        let pubB_x963 = privB.publicKey.x963Representation.base64EncodedString()

        let secretFromA = try privA.sharedSecretFromKeyAgreement(with: privB.publicKey)
        let ikmA = secretFromA.withUnsafeBytes { Data($0) }
        let derivedKey = AuraCrypto.deriveKey(ikm: ikmA, info: WireProtocol.info)
        let keyHex = derivedKey.withUnsafeBytes { bytes in
            bytes.map { String(format: "%02x", $0) }.joined()
        }

        print("=== AURA ECDH Cross-Platform Test Vector (iOS) ===")
        print("pubA (x963 b64): \(pubA_x963)")
        print("pubB (x963 b64): \(pubB_x963)")
        print("derivedKey (hex): \(keyHex)")
        print("HKDF info: \(String(data: WireProtocol.info, encoding: .utf8) ?? "?")")
        print("Curve: secp256r1 (P-256)")
        print("==================================================")

        XCTAssertEqual(keyHex.count, 64, "32-byte key must produce 64 hex chars")
        XCTAssertFalse(keyHex.allSatisfy { $0 == "0" }, "Key must not be all zeros")
    }
}
