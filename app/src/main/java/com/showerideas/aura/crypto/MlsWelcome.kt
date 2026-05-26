package com.showerideas.aura.crypto

/**
 * Task 57 — MLS Welcome message.
 *
 * Carries the epoch secret wrapped to a specific new joiner. The joiner uses their
 * private key to unwrap the epoch secret and derive the same application key as the
 * rest of the group, without ever seeing prior epoch keys (forward secrecy guarantee).
 *
 * In full MLS RFC 9420, the wrapping uses HPKE (Hybrid Public Key Encryption). AURA
 * uses HKDF-based wrapping (sufficient for session lifetimes ≤10 min). Full HPKE
 * integration is tracked as an enhancement once the `:protocol` KMP module exports
 * an HPKE primitive.
 *
 * @param roomId             AURA room ID — must match the recipient's room context.
 * @param epoch              Current epoch number at the time this Welcome was issued.
 * @param joinerId           Identity fingerprint of the intended recipient.
 * @param wrappedEpochSecret Epoch secret wrapped (XOR with HKDF key) to the joiner's public key.
 * @param memberSnapshot     Set of member IDs in the group at this epoch.
 * @param confirmationTag    Epoch confirmation tag — recipients verify before accepting epoch.
 */
data class MlsWelcome(
    val roomId: String,
    val epoch: Long,
    val joinerId: String,
    val wrappedEpochSecret: ByteArray,
    val memberSnapshot: Set<String>,
    val confirmationTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MlsWelcome) return false
        return roomId == other.roomId &&
               epoch == other.epoch &&
               joinerId == other.joinerId &&
               wrappedEpochSecret.contentEquals(other.wrappedEpochSecret) &&
               memberSnapshot == other.memberSnapshot &&
               confirmationTag.contentEquals(other.confirmationTag)
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + joinerId.hashCode()
        result = 31 * result + wrappedEpochSecret.contentHashCode()
        result = 31 * result + memberSnapshot.hashCode()
        result = 31 * result + confirmationTag.contentHashCode()
        return result
    }
}
