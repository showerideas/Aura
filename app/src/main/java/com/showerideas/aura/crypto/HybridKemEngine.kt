package com.showerideas.aura.crypto

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wire Protocol v8: Post-quantum hybrid KEM session engine.
 *
 * Wraps [HybridKEM] with protocol negotiation so that AURA sessions can
 * advertise and accept ML-KEM-768+X25519 capability in a backward-compatible
 * way. Older peers that only support protocol v6 (classical ECDH) are still
 * served correctly.
 *
 * Negotiation flow
 * ```
 * Initiator                           Responder
 *    │                                   │
 *    │─── HELLO {version: 8, caps: pq} ──►│
 *    │◄── HELLO_ACK {version: 8 or 6} ───│
 *    │                                   │
 *    │  (if both agreed v8)              │
 *    │─── KEM_INIT {hybrid_pk} ──────────►│
 *    │◄── KEM_RESP {ciphertext} ──────────│
 *    │                                   │
 *    │  Both derive sharedSecret via HKDF │
 *    │─── ENCRYPTED_PROFILE ─────────────►│
 * ```
 *
 * Version constants
 * - `WIRE_V6` = 6 — classical ECDH (legacy, always supported)
 * - `WIRE_V8` = 8 — ML-KEM-768 + X25519 hybrid KEM
 *
 * Negotiation rule
 * Both peers advertise their maximum supported version. The session uses
 * `min(initiator_max, responder_max)` to guarantee backward compatibility.
 * If only one peer supports v8, the session falls back to v6.
 */
@Singleton
class HybridKemEngine @Inject constructor() {

    companion object {
        /** Classical ECDH protocol version (always supported). */
        const val WIRE_V6: Int = 6

        /** Post-quantum hybrid KEM protocol version. */
        const val WIRE_V8: Int = 8

        /** Maximum version this implementation supports. */
        const val MAX_VERSION: Int = WIRE_V8

        /** Minimum version this implementation accepts from a peer. */
        const val MIN_VERSION: Int = WIRE_V6
    }

    // Session type

    /**
     * An active KEM session between two peers.
     *
     * @param negotiatedVersion  The wire protocol version agreed during HELLO exchange.
     * @param localKeyPair       Our hybrid key pair (v8) or null for v6 sessions.
     * @param sharedSecret       32-byte HKDF output; available after [complete].
     */
    data class KemSession(
        val sessionId        : String,
        val negotiatedVersion: Int,
        val localKeyPair     : HybridKEM.HybridKeyPair?,
        var sharedSecret     : ByteArray? = null
    ) {
        val isPostQuantum: Boolean get() = negotiatedVersion >= WIRE_V8
        val isComplete   : Boolean get() = sharedSecret != null
    }

    // Negotiation

    /**
     * Create an initiator session. Call this on the device that opens the
     * exchange. Returns the session and the HELLO payload to send to the peer.
     *
     * @param peerMaxVersion  Protocol version advertised by the peer's HELLO message.
     */
    fun initiatorSession(sessionId: String, peerMaxVersion: Int): Pair<KemSession, HelloPayload> {
        val negotiated = negotiateVersion(MAX_VERSION, peerMaxVersion)
        Timber.i("HybridKemEngine: initiator session %s — negotiated v%d", sessionId.take(8), negotiated)

        val keyPair = if (negotiated >= WIRE_V8) HybridKEM.generateKeyPair() else null
        val session = KemSession(sessionId, negotiated, keyPair)
        val hello   = HelloPayload(
            maxVersion     = MAX_VERSION,
            sessionId      = sessionId,
            publicKeyBytes = keyPair?.encodedPublicKey
        )
        return session to hello
    }

    /**
     * Create a responder session. Call this when you receive a peer's HELLO.
     * Returns the session and the HELLO_ACK payload to send back.
     *
     * @param peerHello  The HelloPayload received from the initiator.
     */
    fun responderSession(peerHello: HelloPayload): Pair<KemSession, HelloAckPayload> {
        val negotiated = negotiateVersion(MAX_VERSION, peerHello.maxVersion)
        Timber.i("HybridKemEngine: responder session %s — negotiated v%d",
            peerHello.sessionId.take(8), negotiated)

        val keyPair = if (negotiated >= WIRE_V8) HybridKEM.generateKeyPair() else null
        val session = KemSession(peerHello.sessionId, negotiated, keyPair)

        val ack = if (negotiated >= WIRE_V8 && peerHello.publicKeyBytes != null) {
            // Encapsulate using the initiator's public key
            val result = HybridKEM.encapsulate(peerHello.publicKeyBytes)
            session.sharedSecret = result.sharedSecret
            Timber.i("HybridKemEngine: responder encapsulated — shared secret derived")
            HelloAckPayload(
                negotiatedVersion = negotiated,
                sessionId         = peerHello.sessionId,
                ciphertextBytes   = result.ciphertext,
                responderPubKey   = keyPair?.encodedPublicKey
            )
        } else {
            // v6 fallback — classical ECDH shared secret derived externally
            HelloAckPayload(
                negotiatedVersion = negotiated,
                sessionId         = peerHello.sessionId,
                ciphertextBytes   = null,
                responderPubKey   = null
            )
        }
        return session to ack
    }

    /**
     * Complete the initiator session using the responder's HELLO_ACK.
     * Decapsulates the hybrid KEM ciphertext and derives the shared secret.
     *
     * No-op for v6 sessions (shared secret is derived externally via ECDH).
     */
    fun completeInitiatorSession(session: KemSession, ack: HelloAckPayload) {
        if (session.negotiatedVersion < WIRE_V8) {
            Timber.d("HybridKemEngine: v6 session — KEM complete skipped")
            return
        }
        val keyPair = session.localKeyPair
            ?: error("v8 session missing local key pair — cannot decapsulate")
        val ciphertext = ack.ciphertextBytes
            ?: error("v8 HELLO_ACK missing ciphertext — peer sent malformed ACK")

        session.sharedSecret = HybridKEM.decapsulate(ciphertext, keyPair)
        Timber.i("HybridKemEngine: initiator decapsulated — shared secret derived for session %s",
            session.sessionId.take(8))
    }

    // Wire payloads

    /**
     * HELLO payload sent by the initiator.
     *
     * @param maxVersion      Maximum protocol version this peer supports.
     * @param sessionId       Random session UUID (36 chars).
     * @param publicKeyBytes  Hybrid public key bytes for v8 sessions; null for v6.
     */
    data class HelloPayload(
        val maxVersion    : Int,
        val sessionId     : String,
        val publicKeyBytes: ByteArray?
    ) {
        fun toJson(): String = buildString {
            append("{\"v\":$maxVersion,\"sid\":\"$sessionId\"")
            if (publicKeyBytes != null) {
                append(",\"pk\":\"${android.util.Base64.encodeToString(publicKeyBytes, android.util.Base64.NO_WRAP)}\"")
            }
            append("}")
        }

        companion object {
            fun fromJson(json: String): HelloPayload {
                val obj = org.json.JSONObject(json)
                val pkB64 = obj.optString("pk").takeIf { it.isNotBlank() }
                return HelloPayload(
                    maxVersion     = obj.getInt("v"),
                    sessionId      = obj.getString("sid"),
                    publicKeyBytes = pkB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                )
            }
        }
    }

    /**
     * HELLO_ACK payload sent by the responder.
     *
     * @param negotiatedVersion  The agreed protocol version.
     * @param sessionId          Echoes the initiator's session ID.
     * @param ciphertextBytes    ML-KEM-768 ciphertext for v8 sessions; null for v6.
     * @param responderPubKey    Responder's hybrid public key (optional, for future
     *                           mutual-auth extensions).
     */
    data class HelloAckPayload(
        val negotiatedVersion: Int,
        val sessionId        : String,
        val ciphertextBytes  : ByteArray?,
        val responderPubKey  : ByteArray?
    ) {
        fun toJson(): String = buildString {
            append("{\"v\":$negotiatedVersion,\"sid\":\"$sessionId\"")
            if (ciphertextBytes != null) {
                append(",\"ct\":\"${android.util.Base64.encodeToString(ciphertextBytes, android.util.Base64.NO_WRAP)}\"")
            }
            if (responderPubKey != null) {
                append(",\"rpk\":\"${android.util.Base64.encodeToString(responderPubKey, android.util.Base64.NO_WRAP)}\"")
            }
            append("}")
        }

        companion object {
            fun fromJson(json: String): HelloAckPayload {
                val obj = org.json.JSONObject(json)
                val ctB64 = obj.optString("ct").takeIf { it.isNotBlank() }
                val rpkB64 = obj.optString("rpk").takeIf { it.isNotBlank() }
                return HelloAckPayload(
                    negotiatedVersion = obj.getInt("v"),
                    sessionId         = obj.getString("sid"),
                    ciphertextBytes   = ctB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) },
                    responderPubKey   = rpkB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                )
            }
        }
    }

    // Version negotiation

    /**
     * Agree on the highest version both peers support.
     * Clamps to [MIN_VERSION]–[MAX_VERSION] on both sides.
     */
    fun negotiateVersion(localMax: Int, peerMax: Int): Int =
        minOf(localMax, peerMax).coerceIn(MIN_VERSION, MAX_VERSION)
}
