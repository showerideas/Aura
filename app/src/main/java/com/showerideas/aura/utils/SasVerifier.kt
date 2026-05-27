package com.showerideas.aura.utils

import java.security.MessageDigest
import java.security.PublicKey

/**
 * Short Authentication String (SAS) derivation for first-meet MITM protection.
 *
 * Problem
 * AURA's ECDSA challenge/response catches key-substitution attacks against *known* peers
 * (TOFU registry detects the mismatch). But on the very first exchange between two devices,
 * neither has seen the other's identity key before — a MITM who can intercept the Nearby
 * session could substitute their own key and neither party would notice.
 *
 * Mitigation
 * Both parties display the same 6-digit SAS derived from their ephemeral ECDH public keys.
 * A MITM who substitutes their own key produces a different SAS, which the users can compare
 * verbally before confirming the exchange.
 *
 * Derivation
 * ```
 * SAS = big_endian_24bit(SHA-256(canonical_key_a || canonical_key_b)) % 1_000_000
 * ```
 * - `canonical_key_x` = SPKI-encoded (X.509) public key bytes
 * - The two keys are sorted lexicographically before concatenation so both parties
 *   compute the same hash regardless of which key they consider "ours" vs "theirs".
 * - The first 3 bytes (24 bits) of the 256-bit hash are taken as a big-endian integer.
 * - The result is reduced modulo 10^6 (range 0..999_999) and zero-padded to 6 digits.
 *
 * Security note
 * A 6-digit SAS has ~20 bits of entropy — enough to make a brute-force key-substitution
 * attack impractical in the short window of a physical face-to-face exchange. It does NOT
 * provide cryptographic security against a patient offline attacker; that is handled by the
 * ECDSA identity-key layer and the TOFU registry for all subsequent sessions.
 *
 * UI integration
 * Show the SAS to both parties *before* the profile payload is exchanged. If both users
 * confirm the numbers match, proceed. If they differ, abort with [ExchangeSession.State.ERROR].
 * UI integration is tracked in the v1.2 milestone.
 */
object SasVerifier {

    /**
     * The number of decimal digits in the SAS. 6 gives ~20 bits of entropy.
     * Must not be changed without updating the format string below.
     */
    const val SAS_DIGITS = 6

    /** Exclusive upper bound: 10^[SAS_DIGITS]. */
    private val SAS_MODULUS = Math.pow(10.0, SAS_DIGITS.toDouble()).toLong()

    /**
     * Derive the SAS from two ephemeral ECDH public keys.
     *
     * @param keyA SPKI-encoded EC public key (our ephemeral ECDH key).
     * @param keyB SPKI-encoded EC public key (the peer's ephemeral ECDH key).
     * @return A zero-padded 6-digit decimal string, e.g. `"042731"`.
     */
    fun derive(keyA: PublicKey, keyB: PublicKey): String {
        return deriveFromBytes(keyA.encoded, keyB.encoded)
    }

    /**
     * Derive the SAS directly from raw SPKI-encoded key bytes.
     * Exposed for testing without needing to construct [PublicKey] objects.
     */
    fun deriveFromBytes(keyBytesA: ByteArray, keyBytesB: ByteArray): String {
        // Canonical ordering: sort so that derive(a, b) == derive(b, a).
        val (first, second) = if (lexicographicCompare(keyBytesA, keyBytesB) <= 0) {
            keyBytesA to keyBytesB
        } else {
            keyBytesB to keyBytesA
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()

        // Take the first 3 bytes as a big-endian unsigned 24-bit integer.
        val top24bits = ((hash[0].toInt() and 0xFF) shl 16) or
                        ((hash[1].toInt() and 0xFF) shl 8)  or
                         (hash[2].toInt() and 0xFF)

        val sasValue = top24bits.toLong() % SAS_MODULUS
        return sasValue.toString().padStart(SAS_DIGITS, '0')
    }

    /**
     * Verify that a SAS string produced by one party matches this device's own computation.
     *
     * Uses [MessageDigest.isEqual] for a constant-time byte comparison so that
     * timing differences cannot leak how many leading digits match — preventing a
     * theoretical incremental-digit oracle on a local timing channel.
     *
     * @param expected The SAS displayed on the peer's screen (entered manually or scanned).
     * @param keyA Our ephemeral ECDH public key.
     * @param keyB The peer's ephemeral ECDH public key.
     * @return `true` if the SAS matches; `false` if there is a mismatch (possible MITM).
     */
    fun verify(expected: String, keyA: PublicKey, keyB: PublicKey): Boolean {
        val actual = derive(keyA, keyB)
        // Constant-time comparison — MessageDigest.isEqual pads both arrays to the same
        // length and XORs all bytes before returning, so no early exit leaks timing info.
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Derive the SAS directly from a KEM shared secret.
     *
     * Used when the session key is established via a post-quantum hybrid KEM (ML-KEM-768 +
     * X25519) rather than a classical ECDH exchange. Both parties derive the same shared
     * secret from the KEM handshake; hashing it gives a deterministic SAS without needing
     * to exchange additional public keys for the comparison.
     *
     * Derivation:
     * ```
     * SAS = big_endian_24bit(SHA-256(sharedSecret)) % 1_000_000
     * ```
     *
     * @param sharedSecret 32-byte KEM shared secret from [HybridKemEngine.KemSession.sharedSecret].
     * @return A zero-padded 6-digit decimal string, e.g. `"042731"`.
     */
    fun deriveFromSharedSecret(sharedSecret: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val top24bits = ((hash[0].toInt() and 0xFF) shl 16) or
                        ((hash[1].toInt() and 0xFF) shl  8) or
                         (hash[2].toInt() and 0xFF)
        val sasValue = top24bits.toLong() % SAS_MODULUS
        return sasValue.toString().padStart(SAS_DIGITS, '0')
    }

    // Internal helpers

    /**
     * Lexicographic comparison of two byte arrays.
     * Returns negative if [a] < [b], zero if equal, positive if [a] > [b].
     */
    private fun lexicographicCompare(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
