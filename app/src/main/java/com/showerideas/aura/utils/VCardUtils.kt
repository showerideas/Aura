package com.showerideas.aura.utils

import com.showerideas.aura.model.Contact

/**
 * vCard 3.0 serialisation for AURA contacts. Spec: RFC 2426.
 *
 * Field mapping:
 *  - FN      — formatted name (displayName)
 *  - N       — structured name with displayName in the family slot
 *  - TEL     — phone (TYPE=CELL)
 *  - EMAIL   — email (TYPE=INTERNET)
 *  - ORG     — company
 *  - TITLE   — job title
 *  - URL     — website
 *  - NOTE    — bio + notes concatenated
 *
 * Blank fields are omitted entirely; no "TEL:" lines with empty values.
 * Lines are CRLF-terminated per RFC.
 */
private const val CRLF = "\r\n"

private fun StringBuilder.appendField(name: String, value: String?) {
    if (value.isNullOrBlank()) return
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace(",", "\\,")
        .replace(";", "\\;")
    append(name).append(':').append(escaped).append(CRLF)
}

/** Serialise this [Contact] to a single vCard 3.0 record. */
fun Contact.toVCard(): String = buildString {
    append("BEGIN:VCARD").append(CRLF)
    append("VERSION:3.0").append(CRLF)
    appendField("FN", displayName)
    if (displayName.isNotBlank()) {
        // Whole display name goes into the surname slot for clients that
        // parse N but ignore FN. Remaining components are empty.
        appendField("N", "$displayName;;;;")
    }
    appendField("TEL;TYPE=CELL", phone)
    appendField("EMAIL;TYPE=INTERNET", email)
    appendField("ORG", company)
    appendField("TITLE", title)
    appendField("URL", website)

    val note = buildString {
        if (bio.isNotBlank()) append(bio)
        if (bio.isNotBlank() && notes.isNotBlank()) append("\n\n")
        if (notes.isNotBlank()) append(notes)
    }
    appendField("NOTE", note)

    append("END:VCARD").append(CRLF)
}

/**
 * Concatenate multiple contacts into a single vCard bundle that most
 * address-book apps can import in one shot.
 */
fun List<Contact>.toVCardBundle(): String = joinToString(separator = "") { it.toVCard() }
