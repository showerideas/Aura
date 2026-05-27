package com.showerideas.aura.zk

/**
 * Task 90 — Result of a ZK-SNARK gesture proof operation.
 *
 * A [ZkProofResult] wraps the raw proof bytes with verification metadata.
 * Used by [GestureZkProver] and [AuditExportWorker] to include ZK proofs
 * in enterprise audit exports without revealing biometric template data.
 *
 * @param proof       Raw Groth16 proof bytes (~192 bytes).
 * @param isMatch     Result of verifyProof — true if live descriptor matched enrolled.
 * @param proofSizeBytes  Actual proof size for benchmark/audit.
 */
data class ZkProofResult(
    val proof: ByteArray,
    val isMatch: Boolean,
    val proofSizeBytes: Int = proof.size
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ZkProofResult) return false
        return proof.contentEquals(other.proof) && isMatch == other.isMatch
    }
    override fun hashCode(): Int = 31 * proof.contentHashCode() + isMatch.hashCode()
}
