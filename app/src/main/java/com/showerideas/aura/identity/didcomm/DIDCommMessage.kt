package com.showerideas.aura.identity.didcomm

import java.time.Instant

/**
 * Task 106/107 — DIDComm v2 message model.
 *
 * Models the core DIDComm v2 message envelope and AURA-specific
 * message types per the DIDComm Messaging v2 specification.
 *
 * ## Message structure
 * DIDComm v2 messages are JSON objects with a standardized header set.
 * After authcrypt/anoncrypt unwrapping, the plaintext body contains:
 *   - `id`: unique message ID (UUID)
 *   - `type`: the message type URI
 *   - `from`: sender's DID (null for anoncrypt)
 *   - `to`: list of recipient DIDs
 *   - `created_time`: Unix seconds
 *   - `expires_time`: Unix seconds (optional)
 *   - `body`: type-specific payload
 *
 * ## AURA message types
 * - [TYPE_EXCHANGE_REQUEST]  — `aura.exchange.v1/request`
 * - [TYPE_EXCHANGE_RESPONSE] — `aura.exchange.v1/response`
 * - [TYPE_PROBLEM_REPORT]    — standard DIDComm problem-report
 *
 * ## Encryption
 * - `anoncrypt`: ECDH-ES + X25519 + AES-256-GCM (no sender auth)
 * - `authcrypt`: ECDH-1PU + X25519 + A256CBC-HS512 (sender authenticated)
 * AURA uses `authcrypt` for exchange requests (proves sender identity)
 * and `anoncrypt` for initial discovery messages.
 *
 * See: identity.foundation/didcomm-messaging/spec
 * See: ROADMAP §Task 106
 */
data class DIDCommMessage(
    val id: String,
    val type: String,
    val from: String?,         // sender DID; null for anoncrypt
    val to: List<String>,      // recipient DIDs
    val createdTime: Instant,
    val expiresTime: Instant?,
    val body: Map<String, Any>
) {

    companion object {
        // ── AURA message type URIs ─────────────────────────────────────────
        const val TYPE_EXCHANGE_REQUEST  = "https://aura.showerideas.com/didcomm/exchange/1.0/request"
        const val TYPE_EXCHANGE_RESPONSE = "https://aura.showerideas.com/didcomm/exchange/1.0/response"
        const val TYPE_PROBLEM_REPORT    = "https://didcomm.org/report-problem/2.0/problem-report"

        // ── Encryption modes ──────────────────────────────────────────────
        const val CRYPT_ANONCRYPT = "anoncrypt"
        const val CRYPT_AUTHCRYPT = "authcrypt"

        // ── Exchange request body keys ────────────────────────────────────
        const val BODY_VC          = "verifiable_credential"
        const val BODY_NONCE       = "nonce"
        const val BODY_REQUESTER   = "requester_did"
        const val BODY_GESTURE_HINT = "gesture_required"   // boolean — does recipient need gesture?

        // ── Problem report descriptor codes ──────────────────────────────
        const val PROBLEM_DECLINED     = "e.p.req.declined"
        const val PROBLEM_UNAUTHORIZED = "e.p.req.unauthorized"
        const val PROBLEM_EXPIRED      = "e.p.msg.expired"
    }

    /** True if this message has passed its expiry time. */
    fun isExpired(): Boolean = expiresTime?.let { Instant.now().isAfter(it) } ?: false

    /** Build a serialisable map representation of this message (simplified; no JWE wrapping). */
    fun toMap(): Map<String, Any?> = mapOf(
        "id"           to id,
        "type"         to type,
        "from"         to from,
        "to"           to to,
        "created_time" to createdTime.epochSecond,
        "expires_time" to expiresTime?.epochSecond,
        "body"         to body
    )
}

/**
 * Task 107 — AURA exchange request body builder.
 *
 * Constructs the `body` map for a [DIDCommMessage.TYPE_EXCHANGE_REQUEST] message.
 * The recipient's AURA app shows a consent dialog and responds with
 * [DIDCommMessage.TYPE_EXCHANGE_RESPONSE] containing their VC if accepted,
 * or [DIDCommMessage.TYPE_PROBLEM_REPORT] with code [DIDCommMessage.PROBLEM_DECLINED]
 * if rejected.
 *
 * @param requesterDid DID of the requesting party.
 * @param vcJson       The requester's Verifiable Credential as a JSON string.
 * @param nonce        Fresh random nonce — prevents replay attacks.
 * @param gestureRequired True if the recipient must perform their enrolled gesture to accept.
 */
data class ExchangeRequestBody(
    val requesterDid: String,
    val vcJson: String,
    val nonce: String,
    val gestureRequired: Boolean = true
) {
    fun toBodyMap(): Map<String, Any> = mapOf(
        DIDCommMessage.BODY_REQUESTER    to requesterDid,
        DIDCommMessage.BODY_VC          to vcJson,
        DIDCommMessage.BODY_NONCE       to nonce,
        DIDCommMessage.BODY_GESTURE_HINT to gestureRequired
    )
}
