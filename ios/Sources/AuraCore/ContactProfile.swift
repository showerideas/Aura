// Sources/AuraCore/ContactProfile.swift
// Phase E1 — Contact profile model with vCard 3.0 and JSON wire encoding.
//
// JSON wire format matches the Android ContactProfile serialisation so that
// profiles exchanged over the wire are compatible between platforms.

import Foundation

/// The contact card exchanged during an AURA session.
///
/// Fields mirror the Android `Contact` Room entity. All fields except `name`
/// are optional — the sharing owner controls which fields are included.
public struct ContactProfile: Codable, Equatable {
    public var name    : String
    public var phone   : String?
    public var email   : String?
    public var company : String?
    public var title   : String?
    public var website : String?
    public var bio     : String?

    public init(
        name    : String,
        phone   : String? = nil,
        email   : String? = nil,
        company : String? = nil,
        title   : String? = nil,
        website : String? = nil,
        bio     : String? = nil
    ) {
        self.name    = name
        self.phone   = phone
        self.email   = email
        self.company = company
        self.title   = title
        self.website = website
        self.bio     = bio
    }

    // -------------------------------------------------------------------------
    // MARK: — JSON wire encoding (matches Android ContactProfile wire format)
    // -------------------------------------------------------------------------

    /// Encode to the AURA wire JSON format.
    /// Keys are camelCase to match Android's `@SerializedName` defaults.
    public func toJSON() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(self)
    }

    /// Decode from AURA wire JSON.
    public static func fromJSON(_ data: Data) throws -> ContactProfile {
        return try JSONDecoder().decode(ContactProfile.self, from: data)
    }

    // -------------------------------------------------------------------------
    // MARK: — vCard 3.0 export
    // -------------------------------------------------------------------------

    /// Export as a vCard 3.0 string suitable for import into iOS Contacts.
    ///
    /// Spec: RFC 2426 (vCard 3.0). All non-ASCII characters are included as
    /// UTF-8 per the `CHARSET=UTF-8` content type parameter.
    public func toVCard3() -> String {
        var lines: [String] = [
            "BEGIN:VCARD",
            "VERSION:3.0",
            "FN:\(vCardEscaped(name))",
        ]

        // Structured name: Family;Given;Additional;Prefix;Suffix
        // AURA uses display name only — split on first space for a best-effort split.
        let nameParts = name.split(separator: " ", maxSplits: 1)
        let given  = nameParts.first.map(String.init) ?? name
        let family = nameParts.count > 1 ? String(nameParts[1]) : ""
        lines.append("N:\(vCardEscaped(family));\(vCardEscaped(given));;;")

        if let phone   = phone   { lines.append("TEL;TYPE=CELL:\(vCardEscaped(phone))") }
        if let email   = email   { lines.append("EMAIL:\(vCardEscaped(email))") }
        if let company = company { lines.append("ORG:\(vCardEscaped(company))") }
        if let title   = title   { lines.append("TITLE:\(vCardEscaped(title))") }
        if let website = website { lines.append("URL:\(vCardEscaped(website))") }
        if let bio     = bio     { lines.append("NOTE:\(vCardEscaped(bio))") }

        // Source attribution
        lines.append("X-AURA-SOURCE:AURA-Exchange")
        lines.append("END:VCARD")

        return lines.joined(separator: "\r\n") + "\r\n"
    }

    /// Parse the fields we care about from a vCard 3.0 string.
    /// Not a full RFC 2426 parser — handles the subset produced by `toVCard3()`.
    public static func fromVCard3(_ vcard: String) -> ContactProfile? {
        var name    : String?
        var phone   : String?
        var email   : String?
        var company : String?
        var title   : String?
        var website : String?
        var bio     : String?

        let lines = vcard.components(separatedBy: .newlines)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("FN:") {
                name = vCardUnescaped(String(trimmed.dropFirst(3)))
            } else if trimmed.hasPrefix("TEL") {
                if let colon = trimmed.firstIndex(of: ":") {
                    phone = vCardUnescaped(String(trimmed[trimmed.index(after: colon)...]))
                }
            } else if trimmed.hasPrefix("EMAIL:") {
                email = vCardUnescaped(String(trimmed.dropFirst(6)))
            } else if trimmed.hasPrefix("ORG:") {
                company = vCardUnescaped(String(trimmed.dropFirst(4)))
            } else if trimmed.hasPrefix("TITLE:") {
                title = vCardUnescaped(String(trimmed.dropFirst(6)))
            } else if trimmed.hasPrefix("URL:") {
                website = vCardUnescaped(String(trimmed.dropFirst(4)))
            } else if trimmed.hasPrefix("NOTE:") {
                bio = vCardUnescaped(String(trimmed.dropFirst(5)))
            }
        }

        guard let resolvedName = name, !resolvedName.isEmpty else { return nil }
        return ContactProfile(
            name: resolvedName, phone: phone, email: email,
            company: company, title: title, website: website, bio: bio
        )
    }

    // -------------------------------------------------------------------------
    // MARK: — vCard escaping helpers (RFC 2426 §5.8.4)
    // -------------------------------------------------------------------------

    private func vCardEscaped(_ s: String) -> String {
        return s
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: ",",  with: "\\,")
            .replacingOccurrences(of: ";",  with: "\\;")
            .replacingOccurrences(of: "\n", with: "\\n")
    }

    private static func vCardUnescaped(_ s: String) -> String {
        return s
            .replacingOccurrences(of: "\\n", with: "\n")
            .replacingOccurrences(of: "\\;", with: ";")
            .replacingOccurrences(of: "\\,", with: ",")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }
}
