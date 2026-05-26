package com.showerideas.aura.crypto

/**
 * Task 48 — ML-DSA (FIPS 204) hybrid identity signature.
 *
 * A hybrid signature pairs a classical P-256/ECDSA signature with a
 * post-quantum ML-DSA-65 (CRYSTALS-Dilithium, FIPS 204) signature.
 * Both signatures cover the same data. Verification requires both to
 * be valid — an adversary must break BOTH P-256 and ML-DSA-65 to forge
 * a signature.
 *
 * ## Wire layout (v9 TLV `HYBRID_SIG_V9`)
 * ```
 * [2 bytes] p256Len   — length of p256Sig  (typically 64 for raw R||S)
 * [p256Len bytes]     — P-256 signature bytes
 * [4 bytes] mlDsaLen  — length of mlDsaSig (typically 3293 for ML-DSA-65)
 * [mlDsaLen bytes]    — ML-DSA-65 signature bytes
 * ```
 *
 * ## Size budget
 * P-256 raw signature:  64 bytes
 * ML-DSA-65 signature:  3293 bytes
 * Total per signed payload: ~3357 bytes — acceptable for identity exchange.
 *
 * @param p256Sig   Raw R||S encoding (64 bytes) of the P-256/ECDSA signature.
 * @param mlDsaSig  ML-DSA-65 signature bytes (3293 bytes for Dilithium mode 3).
 */
data class HybridSignature(
    val p256Sig: ByteArray,
    val mlDsaSig: ByteArray
) {
    /** Serialise to wire bytes: [2B p256Len][p256Sig][4B mlDsaLen][mlDsaSig] */
    fun encode(): ByteArray {
        val buf = ByteArray(2 + p256Sig.size + 4 + mlDsaSig.size)
        var offset = 0
        buf[offset++] = (p256Sig.size shr 8).toByte()
        buf[offset++] = (p256Sig.size and 0xFF).toByte()
        p256Sig.copyInto(buf, offset); offset += p256Sig.size
        buf[offset++] = (mlDsaSig.size shr 24).toByte()
        buf[offset++] = (mlDsaSig.size shr 16).toByte()
        buf[offset++] = (mlDsaSig.size shr 8).toByte()
        buf[offset++] = (mlDsaSig.size and 0xFF).toByte()
        mlDsaSig.copyInto(buf, offset)
        return buf
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HybridSignature) return false
        return p256Sig.contentEquals(other.p256Sig) && mlDsaSig.contentEquals(other.mlDsaSig)
    }

    override fun hashCode(): Int = 31 * p256Sig.contentHashCode() + mlDsaSig.contentHashCode()

    companion object {
        /** Deserialise from wire bytes produced by [encode]. */
        fun decode(bytes: ByteArray): HybridSignature {
            var offset = 0
            val p256Len = ((bytes[offset++].toInt() and 0xFF) shl 8) or
                           (bytes[offset++].toInt() and 0xFF)
            val p256Sig = bytes.copyOfRange(offset, offset + p256Len); offset += p256Len
            val mlDsaLen = ((bytes[offset++].toInt() and 0xFF) shl 24) or
                           ((bytes[offset++].toInt() and 0xFF) shl 16) or
                           ((bytes[offset++].toInt() and 0xFF) shl 8)  or
                            (bytes[offset++].toInt() and 0xFF)
            val mlDsaSig = bytes.copyOfRange(offset, offset + mlDsaLen)
            return HybridSignature(p256Sig, mlDsaSig)
        }
    }
}
