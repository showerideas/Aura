// Tests/AuraCoreTests/AuraCryptoTests.swift
// Phase E1 — AuraCore unit tests for P-256 ECDH, SAS, and ContactProfile.
// Runs on macOS CI without iOS simulator (pure Swift, no UIKit).

import XCTest
import CryptoKit
@testable import AuraCore

final class AuraCryptoTests: XCTestCase {

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — P-256 ECDH commutativity
    // ─────────────────────────────────────────────────────────────────────────

    func testECDH_commutativity() throws {
        let alicePriv = AuraCrypto.generateEphemeralKeyPair()
        let bobPriv   = AuraCrypto.generateEphemeralKeyPair()

        let aliceShared = try alicePriv.sharedSecretFromKeyAgreement(with: bobPriv.publicKey)
        let bobShared   = try bobPriv.sharedSecretFromKeyAgreement(with: alicePriv.publicKey)

        let aliceKey = aliceShared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data(), sharedInfo: WireProtocol.info, outputByteCount: 32)
        let bobKey   = bobShared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data(), sharedInfo: WireProtocol.info, outputByteCount: 32)

        XCTAssertEqual(
            aliceKey.withUnsafeBytes { Data($0) },
            bobKey.withUnsafeBytes   { Data($0) },
            "ECDH must be commutative: Alice(Bob.pub) == Bob(Alice.pub)"
        )
    }

    func testDerivedKey_is32Bytes() throws {
        let ikm = Data(repeating: 0xAB, count: 32)
        let key = AuraCrypto.deriveKey(ikm: ikm, info: WireProtocol.info)
        let keyBytes = key.withUnsafeBytes { Data($0) }
        XCTAssertEqual(keyBytes.count, 32, "Session key must be 256-bit (32 bytes)")
    }

    func testEncryptDecrypt_roundTrip() throws {
        let key       = SymmetricKey(size: .bits256)
        let plaintext = "Hello AURA".data(using: .utf8)!

        let ciphertext = try AuraCrypto.encrypt(key: key, plaintext: plaintext)
        let decrypted  = try AuraCrypto.decrypt(key: key, ciphertext: ciphertext)

        XCTAssertEqual(decrypted, plaintext, "Decrypt(Encrypt(m)) must equal m")
    }

    func testDecrypt_withWrongKey_throws() {
        let key1 = SymmetricKey(size: .bits256)
        let key2 = SymmetricKey(size: .bits256)
        let plaintext = "secret".data(using: .utf8)!

        guard let ciphertext = try? AuraCrypto.encrypt(key: key1, plaintext: plaintext) else {
            XCTFail("Encryption failed")
            return
        }

        XCTAssertThrowsError(
            try AuraCrypto.decrypt(key: key2, ciphertext: ciphertext),
            "Decrypting with wrong key must throw"
        )
    }

    func testPublicKey_x963Representation_is65Bytes() {
        let priv   = AuraCrypto.generateEphemeralKeyPair()
        let x963   = priv.publicKey.x963Representation
        XCTAssertEqual(x963.count, 65, "P-256 uncompressed x963 must be 65 bytes (0x04 || x || y)")
        XCTAssertEqual(x963[0], 0x04, "First byte must be 0x04 (uncompressed point)")
    }

    func testX963_roundTrip() throws {
        let priv    = AuraCrypto.generateEphemeralKeyPair()
        let x963    = priv.publicKey.x963Representation
        let rebuilt = try P256.KeyAgreement.PublicKey(x963Representation: x963)
        XCTAssertEqual(
            rebuilt.x963Representation, x963,
            "x963 round-trip must be identity"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — SAS verifier
    // ─────────────────────────────────────────────────────────────────────────

    func testSas_isSixDigit() {
        let key  = SymmetricKey(size: .bits256)
        let code = SasVerifier.code(from: key)
        XCTAssertEqual(code.count, 6, "SAS code must be exactly 6 characters")
        XCTAssertTrue(code.allSatisfy { $0.isNumber }, "SAS code must be all digits")
    }

    func testSas_commutativity() throws {
        let alicePriv = AuraCrypto.generateEphemeralKeyPair()
        let bobPriv   = AuraCrypto.generateEphemeralKeyPair()

        let aliceShared = try alicePriv.sharedSecretFromKeyAgreement(with: bobPriv.publicKey)
        let bobShared   = try bobPriv.sharedSecretFromKeyAgreement(with: alicePriv.publicKey)

        let aliceKey = aliceShared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data(), sharedInfo: WireProtocol.info, outputByteCount: 32)
        let bobKey   = bobShared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data(), sharedInfo: WireProtocol.info, outputByteCount: 32)

        let aliceCode = SasVerifier.code(from: aliceKey)
        let bobCode   = SasVerifier.code(from: bobKey)

        XCTAssertTrue(SasVerifier.codesMatch(aliceCode, bobCode),
            "SAS codes derived from the same session key must match: \(aliceCode) vs \(bobCode)")
    }

    func testSas_differentKeys_differentCodes() {
        let key1  = SymmetricKey(size: .bits256)
        let key2  = SymmetricKey(size: .bits256)
        let code1 = SasVerifier.code(from: key1)
        let code2 = SasVerifier.code(from: key2)
        // Probabilistically certain to differ (1 in 1,000,000 chance of false failure)
        XCTAssertFalse(SasVerifier.codesMatch(code1, code2),
            "SAS codes from different keys should differ")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — ContactProfile vCard 3.0
    // ─────────────────────────────────────────────────────────────────────────

    func testVCard3_roundTrip_fullProfile() {
        let profile = ContactProfile(
            name    : "Alice Nakamura",
            phone   : "+1-555-0100",
            email   : "alice@example.com",
            company : "AURA Inc.",
            title   : "iOS Engineer",
            website : "https://aura.app",
            bio     : "Privacy-first contact exchange"
        )

        let vcard    = profile.toVCard3()
        let rebuilt  = ContactProfile.fromVCard3(vcard)

        XCTAssertNotNil(rebuilt, "fromVCard3 must succeed on a valid vCard")
        XCTAssertEqual(rebuilt?.name,    profile.name)
        XCTAssertEqual(rebuilt?.phone,   profile.phone)
        XCTAssertEqual(rebuilt?.email,   profile.email)
        XCTAssertEqual(rebuilt?.company, profile.company)
        XCTAssertEqual(rebuilt?.title,   profile.title)
        XCTAssertEqual(rebuilt?.website, profile.website)
        XCTAssertEqual(rebuilt?.bio,     profile.bio)
    }

    func testVCard3_nameOnly_roundTrip() {
        let profile = ContactProfile(name: "Bob")
        let vcard   = profile.toVCard3()
        let rebuilt = ContactProfile.fromVCard3(vcard)

        XCTAssertEqual(rebuilt?.name, "Bob")
        XCTAssertNil(rebuilt?.phone)
        XCTAssertNil(rebuilt?.email)
    }

    func testVCard3_hasRequiredHeaders() {
        let profile = ContactProfile(name: "Test")
        let vcard   = profile.toVCard3()

        XCTAssertTrue(vcard.contains("BEGIN:VCARD"),  "Must start with BEGIN:VCARD")
        XCTAssertTrue(vcard.contains("VERSION:3.0"),  "Must declare VERSION:3.0")
        XCTAssertTrue(vcard.contains("END:VCARD"),    "Must end with END:VCARD")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — ContactProfile JSON wire encoding
    // ─────────────────────────────────────────────────────────────────────────

    func testJSON_roundTrip() throws {
        let profile = ContactProfile(
            name    : "Carol",
            phone   : "+44 20 7946 0958",
            email   : "carol@example.co.uk",
            company : "Widgets Ltd",
            title   : nil,
            website : nil,
            bio     : nil
        )

        let data    = try profile.toJSON()
        let rebuilt = try ContactProfile.fromJSON(data)

        XCTAssertEqual(rebuilt.name,    profile.name)
        XCTAssertEqual(rebuilt.phone,   profile.phone)
        XCTAssertEqual(rebuilt.email,   profile.email)
        XCTAssertEqual(rebuilt.company, profile.company)
        XCTAssertNil(rebuilt.title)
    }

    func testJSON_invalidData_throws() {
        XCTAssertThrowsError(
            try ContactProfile.fromJSON("not json".data(using: .utf8)!),
            "Parsing invalid JSON must throw"
        )
    }
}
