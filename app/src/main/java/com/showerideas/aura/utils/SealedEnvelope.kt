package com.showerideas.aura.utils

/**
 * Phase 8.2 — Sealed sender envelope for profile payloads.
 *
 * Provides traffic analysis resistance by padding all profile payloads to a
 * fixed size before encryption. A relay or passive observer cannot distinguish
 * a "minimal" profile (name only) from a "full card" profile by ciphertext length.
 *
 * ## Format
 * ```
 * [ 2B payload_length (big-endian) ][ payload_bytes... ][ zero_padding... ]
 * ```
 * Total size is always padded to the nearest 256-byte block, up to MAX_SIZE (4096 bytes).
 *
 * ## Wire protocol version
 * Sealed envelopes are introduced in wire protocol v7 (Phase 8.2). Peers
 * advertising v6 or lower receive unpadded payloads for backwards compatibility.
 *
 * @see HybridKEMUtils.WIRE_PROTOCOL_VERSION for the current version.
 */
object SealedEnvelope {

    /** Maximum sealed envelope size in bytes (including header + padding). */
    const val MAX_SIZE = 4096

    /** Minimum block size for padding alignment. */
    private const val BLOCK_SIZE = 256

    /** Wire protocol version that introduced sealed envelopes. */
    const val WIRE_PROTOCOL_VERSION = 7

    /**
     * Wrap a profile payload in a sealed envelope with PKCS#7-style padding.
     *
     * @param payload  Raw profile bytes (must be <= [MAX_SIZE] - 2 bytes)
     * @return         Padded envelope of fixed size (nearest BLOCK_SIZE multiple, max [MAX_SIZE])
     * @throws IllegalArgumentException if payload is too large
     */
    fun wrap(payload: ByteArray): ByteArray {
        val payloadLen = payload.size
        require(payloadLen <= MAX_SIZE - 2) {
            "Payload too large: $payloadLen bytes (max ${MAX_SIZE - 2})"
        }
        // Total = 2 (length header) + payload + padding to next BLOCK_SIZE boundary
        val contentSize = 2 + payloadLen
        val paddedSize  = ((contentSize + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
        val capped      = minOf(paddedSize, MAX_SIZE)

        val envelope = ByteArray(capped)
        // Write 2-byte big-endian length header
        envelope[0] = ((payloadLen shr 8) and 0xFF).toByte()
        envelope[1] = (payloadLen and 0xFF).toByte()
        // Copy payload
        System.arraycopy(payload, 0, envelope, 2, payloadLen)
        // Remaining bytes are zero (ByteArray default) — this is the padding
        return envelope
    }

    /**
     * Unwrap a sealed envelope to extract the original payload.
     *
     * @param envelope  Sealed envelope bytes (from [wrap] or received from peer)
     * @return          Original payload bytes without padding
     * @throws IllegalArgumentException if the envelope frame is corrupt
     */
    fun unwrap(envelope: ByteArray): ByteArray {
        require(envelope.size >= 2) { "Envelope too short: ${envelope.size} bytes" }
        val payloadLen = ((envelope[0].toInt() and 0xFF) shl 8) or (envelope[1].toInt() and 0xFF)
        require(payloadLen >= 0) { "Negative payload length in envelope header" }
        require(payloadLen + 2 <= envelope.size) {
            "Envelope header claims $payloadLen bytes but envelope is only ${envelope.size} bytes"
        }
        return envelope.slice(2 until 2 + payloadLen).toByteArray()
    }
}
