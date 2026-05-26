// Tests/AuraCoreTests/ContactProfileTests.swift
// Phase E1 — Additional ContactProfile edge-case tests.

import XCTest
@testable import AuraCore

final class ContactProfileTests: XCTestCase {

    func testVCard3_specialCharactersEscaped() {
        let profile = ContactProfile(
            name    : "O'Brien, Patrick",
            company : "Smith; Jones & Co.",
            bio     : "Line 1\nLine 2"
        )
        let vcard = profile.toVCard3()
        // Commas and semicolons must be backslash-escaped in vCard 3.0
        XCTAssertTrue(vcard.contains("O'Brien\\, Patrick"),
            "Commas in FN must be escaped as \\,")
        XCTAssertTrue(vcard.contains("Smith\\; Jones & Co."),
            "Semicolons in ORG must be escaped as \\;")
        XCTAssertTrue(vcard.contains("Line 1\\nLine 2"),
            "Newlines in NOTE must be escaped as \\n")
    }

    func testVCard3_unescapeRoundTrip_withSpecialChars() {
        let profile = ContactProfile(
            name    : "Ada, Byron",
            company : "Science; Tech",
            bio     : "First\nSecond line"
        )
        let vcard   = profile.toVCard3()
        let rebuilt = ContactProfile.fromVCard3(vcard)

        XCTAssertEqual(rebuilt?.name,    "Ada, Byron")
        XCTAssertEqual(rebuilt?.company, "Science; Tech")
        XCTAssertEqual(rebuilt?.bio,     "First\nSecond line")
    }

    func testVCard3_missingName_returnsNil() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD\r\n"
        XCTAssertNil(ContactProfile.fromVCard3(vcard), "vCard without FN must return nil")
    }

    func testContactProfile_equatable() {
        let a = ContactProfile(name: "Eve", email: "eve@example.com")
        let b = ContactProfile(name: "Eve", email: "eve@example.com")
        let c = ContactProfile(name: "Eve", email: "other@example.com")

        XCTAssertEqual(a, b, "Profiles with same fields must be equal")
        XCTAssertNotEqual(a, c, "Profiles with different fields must not be equal")
    }

    func testVCard3_crlfLineEndings() {
        let profile = ContactProfile(name: "Test")
        let vcard   = profile.toVCard3()
        // RFC 2426 requires CRLF line endings
        XCTAssertTrue(vcard.contains("\r\n"), "vCard must use CRLF line endings per RFC 2426")
    }
}
