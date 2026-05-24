package com.showerideas.aura.utils

import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric-ratchet state for AURA exchange sessions.
 *
 * ## What this provides
 * Forward secrecy within an exchange session. Every payload (profile data, avatar,
 * challenge bytes) is encrypted with a unique one-time key derived from a shared
 * chain key. After the key is consumed the chain advances, making it impossible to
 * rederive past keys even if a later chain state is exposed.
 *
 * ## Relationship to the full Signal Double Ratchet
 * This is the *symmetric ratchet* half of the Signal Double Ratchet (Section 2.2
 * in the Signal specification). The other half — the DH ratchet for break-in
 * recovery — is omitted intentionally: AURA's single-round-trip exchange model
 * has no async round-trip available to carry new DH keys. The symmetric ratchet
 * alone provides forward secrecy for all messages in the session.
 *
 * ## Key derivation functions (KDF chain)
 * ```
 * messageKey = HMAC-SHA256(chainKey, "AURA-MSG-KEY\x01")
 * nextChain  = HMAC-SHA256(chainKey, "AURA-CHAIN-ADV\x02")
 * ```
 * Domain-separated labels ensure the message key and the next chain key are
 * cryptographically independent (they cannot be derived from each other).
 *
 * ## Integration
 * 1. Both peers derive the same ECDH session key via [CryptoUtils.deriveSharedAESKey].
 * 2. Each side calls [DoubleRatchetState.from] with that session key.
 * 3. For every encrypted payload: call [nextMessageKey] → encrypt with AES-GCM via
 *    [CryptoUtils.encrypt] → transmit. The receiver calls [nextMessageKey] first to
 *    get the same key, then decrypts.
 * 4. Because AURA's single exchange is fully ordered, the counter never gets out of sync.
 *
 * Thread safety: [nextMessageKey] and [nextMessageKeyIndexed] are @Synchronized.
 * No external dependencies — pure javax.crypto.
 */
class DoubleRatchetState private constructor(private var chainKey: ByteArray) {

    companion object {
        private val MSG_KEY_LABEL   = "AURA-MSG-KEY\u0001".toByteArray(Charsets.UTF_8)
        private val CHAIN_ADV_LABEL = "AURA-CHAIN-ADV\u0002".toByteArray(Charsets.UTF_8)
        private const val HMAC_ALGO = "HmacSHA256"
        private const val KEY_ALGO  = "AES"

        /**
         * Initialise a ratchet from the shared ECDH session key.
         * Both peers must call this with the exact same [sessionKey] bytes for the
         * ratchet to stay in sync. The session key is never used directly for
         * encryption — it only seeds the chain.
         */
        fun from(sessionKey: SecretKey): DoubleRatchetState =
            DoubleRatchetState(sessionKey.encoded.copyOf())

        /**
         * Initialise from raw key bytes (for deserialization or testing).
         */
        fun fromBytes(chainKeyBytes: ByteArray): DoubleRatchetState =
            DoubleRatchetState(chainKeyBytes.copyOf())
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var _messageIndex = 0

    /** Number of message keys derived so far (useful for debugging / logging). */
    val messageIndex: Int get() = _messageIndex

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Derive the next one-time AES-256 message key and advance the chain.
     *
     * Each call consumes one ratchet step — the previous chain state is zeroed
     * and overwritten with the next state, so previous keys cannot be re-derived.
     *
     * **Both peers must call this in the same order, once per payload.**
     *
     * @return A fresh 256-bit [SecretKey] for AES-GCM encryption of one payload.
     */
    @Synchronized
    fun nextMessageKey(): SecretKey {
        val msgKeyBytes  = hmac(chainKey, MSG_KEY_LABEL)
        val nextChainKey = hmac(chainKey, CHAIN_ADV_LABEL)

        // Overwrite the old chain key before letting the GC see it
        chainKey.fill(0)
        chainKey = nextChainKey
        _messageIndex++

        return SecretKeySpec(msgKeyBytes, KEY_ALGO)
    }

    /**
     * Same as [nextMessageKey] but also returns the current message index.
     * Useful for log statements and test assertions.
     *
     * @return Pair of (message key, 1-based message index).
     */
    @Synchronized
    fun nextMessageKeyIndexed(): Pair<SecretKey, Int> {
        val key   = nextMessageKey()
        return key to _messageIndex
    }

    /**
     * Expose the current chain key bytes for serialization / persistence.
     * The returned array is a defensive copy — mutating it does NOT affect the ratchet.
     *
     * Only use this if you need to resume a ratchet across process restarts.
     * In normal single-session use this is not needed.
     */
    fun exportChainKey(): ByteArray = chainKey.copyOf()

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(key, HMAC_ALGO))
        return mac.doFinal(data)
    }
}
