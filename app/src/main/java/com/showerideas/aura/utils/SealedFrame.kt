package com.showerideas.aura.utils

import timber.log.Timber

/**
 * Fixed-length wire frame for traffic analysis resistance.
 *
 * All AURA sealed envelopes are padded to [WIRE_FRAME_SIZE] bytes before transmission.
 * A passive observer sees identical-length packets regardless of profile size.
 *
 * Format
 * ```
 * [actual_len(2 BE)] [envelope_bytes] [random_padding to WIRE_FRAME_SIZE]
 * ```
 * Total: always [WIRE_FRAME_SIZE] bytes on the wire.
 *
 * The padding bytes are cryptographically random (not zero) to prevent
 * boundary-length leakage.
 */
object SealedFrame {

    /** Fixed outer wire frame size in bytes. */
    const val WIRE_FRAME_SIZE = 2048

    /** Maximum envelope that fits in one frame: WIRE_FRAME_SIZE − 2 (length prefix). */
    const val MAX_ENVELOPE_BYTES = WIRE_FRAME_SIZE - 2  // 2046

    private val rng = java.security.SecureRandom()

    /**
     * Wrap a sealed envelope in a fixed-length frame.
     *
     * @param envelope  Bytes from SealedEnvelope.wrap() — must be ≤ [MAX_ENVELOPE_BYTES].
     * @return [WIRE_FRAME_SIZE] bytes ready for transmission.
     */
    fun wrap(envelope: ByteArray): ByteArray {
        require(envelope.size <= MAX_ENVELOPE_BYTES) {
            "Sealed envelope too large for fixed frame: ${envelope.size} > $MAX_ENVELOPE_BYTES"
        }
        // Start with random bytes so padding is indistinguishable from ciphertext
        val frame = ByteArray(WIRE_FRAME_SIZE).also { rng.nextBytes(it) }
        frame[0] = (envelope.size shr 8).toByte()
        frame[1] = (envelope.size and 0xFF).toByte()
        envelope.copyInto(frame, 2)
        Timber.d("SealedFrame: wrapped ${envelope.size}b → ${WIRE_FRAME_SIZE}b frame")
        return frame
    }

    /**
     * Extract the sealed envelope from a fixed-length frame.
     *
     * @param frame  [WIRE_FRAME_SIZE] bytes received from the network.
     * @return The actual sealed envelope bytes.
     */
    fun unwrap(frame: ByteArray): ByteArray {
        require(frame.size >= WIRE_FRAME_SIZE) {
            "Frame too short: ${frame.size} < $WIRE_FRAME_SIZE"
        }
        val actualLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        require(actualLen in 1..MAX_ENVELOPE_BYTES) {
            "Invalid envelope length in frame: $actualLen"
        }
        Timber.d("SealedFrame: unwrapped ${actualLen}b from ${WIRE_FRAME_SIZE}b frame")
        return frame.copyOfRange(2, 2 + actualLen)
    }
}
