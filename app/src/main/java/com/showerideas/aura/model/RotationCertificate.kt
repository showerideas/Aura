package com.showerideas.aura.model

/**
 * Task 30 — Identity key rotation certificate.
 *
 * Proves that [newPublicKeyBytes] is authorized by the holder of [oldPublicKeyBytes].
 * The [signature] is ECDSA-SHA256(oldPrivateKey, oldPublicKeyBytes || newPublicKeyBytes).
 *
 * Wire format:
 * ```
 * [version(1)] [oldPubLen(2 BE)] [oldPubBytes] [newPubLen(2 BE)] [newPubBytes] [sigLen(2 BE)] [sigBytes]
 * ```
 */
data class RotationCertificate(
    val version           : Byte = 0x01,
    val oldPublicKeyBytes : ByteArray,
    val newPublicKeyBytes : ByteArray,
    val signature         : ByteArray,
    val rotatedAtMs       : Long = System.currentTimeMillis()
) {
    fun serialize(): ByteArray {
        val oldLen = oldPublicKeyBytes.size
        val newLen = newPublicKeyBytes.size
        val sigLen = signature.size
        return ByteArray(1 + 2 + oldLen + 2 + newLen + 2 + sigLen).also { buf ->
            var pos = 0
            buf[pos++] = version
            buf[pos++] = (oldLen shr 8).toByte(); buf[pos++] = (oldLen and 0xFF).toByte()
            oldPublicKeyBytes.copyInto(buf, pos); pos += oldLen
            buf[pos++] = (newLen shr 8).toByte(); buf[pos++] = (newLen and 0xFF).toByte()
            newPublicKeyBytes.copyInto(buf, pos); pos += newLen
            buf[pos++] = (sigLen shr 8).toByte(); buf[pos++] = (sigLen and 0xFF).toByte()
            signature.copyInto(buf, pos)
        }
    }

    companion object {
        fun deserialize(bytes: ByteArray): RotationCertificate {
            require(bytes.size >= 7) { "RotationCertificate too short: ${bytes.size} bytes" }
            var pos = 0
            val version = bytes[pos++]
            val oldLen  = ((bytes[pos++].toInt() and 0xFF) shl 8) or (bytes[pos++].toInt() and 0xFF)
            val oldPub  = bytes.copyOfRange(pos, pos + oldLen); pos += oldLen
            val newLen  = ((bytes[pos++].toInt() and 0xFF) shl 8) or (bytes[pos++].toInt() and 0xFF)
            val newPub  = bytes.copyOfRange(pos, pos + newLen); pos += newLen
            val sigLen  = ((bytes[pos++].toInt() and 0xFF) shl 8) or (bytes[pos++].toInt() and 0xFF)
            val sig     = bytes.copyOfRange(pos, pos + sigLen)
            return RotationCertificate(version, oldPub, newPub, sig)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RotationCertificate &&
        oldPublicKeyBytes.contentEquals(other.oldPublicKeyBytes) &&
        newPublicKeyBytes.contentEquals(other.newPublicKeyBytes) &&
        signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        oldPublicKeyBytes.contentHashCode() * 31 + newPublicKeyBytes.contentHashCode()
}
