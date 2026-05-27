package com.showerideas.aura.security

import java.security.MessageDigest

/**
 * Binary Merkle tree proof verifier (SHA-256).
 *
 * Verifies that a given [leaf] value is a member of a Merkle tree whose
 * root hash is [knownRoot], using an inclusion proof supplied by the
 * transparency log server.
 *
 * Proof format
 * The server supplies an ordered list of sibling hashes. The [proofPath]
 * alternates direction based on [leafIndex]: at each level, if the current
 * index is even the sibling is on the right; if odd the sibling is on the
 * left.
 *
 * Node hashing
 * Each internal node is H(left || right) where H = SHA-256.
 * Leaf nodes are pre-hashed by the caller: H(item_bytes).
 */
object MerkleVerifier {

    /**
     * Verify a Merkle inclusion proof.
     *
     * @param leaf       SHA-256 hash of the item to prove (32 bytes as hex string).
     * @param leafIndex  0-based index of the leaf in the tree.
     * @param proofPath  Ordered sibling hashes from [leaf] level to root.
     * @param knownRoot  Expected Merkle root (hex string from signed server response).
     * @return true if the proof is valid and [leaf] is in the tree.
     */
    fun verify(
        leaf       : String,
        leafIndex  : Int,
        proofPath  : List<String>,
        knownRoot  : String
    ): Boolean {
        if (leaf.length != 64 || knownRoot.length != 64) return false
        if (proofPath.any { it.length != 64 }) return false

        var currentHash = leaf
        var index = leafIndex

        for (sibling in proofPath) {
            currentHash = if (index % 2 == 0) {
                // Current node is left child; sibling is on the right
                combineHashes(currentHash, sibling)
            } else {
                // Current node is right child; sibling is on the left
                combineHashes(sibling, currentHash)
            }
            index = index shr 1
        }

        return currentHash.equals(knownRoot, ignoreCase = true)
    }

    /**
     * Compute the SHA-256 hash of a raw item and return as lowercase hex.
     * Use this to produce the [leaf] argument for [verify].
     */
    fun leafHash(itemBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(itemBytes).joinToString("") { "%02x".format(it) }
    }

    // Private

    /** SHA-256([left_hex || right_hex]) → hex string. */
    private fun combineHashes(left: String, right: String): String {
        val input  = hexToBytes(left) + hexToBytes(right)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

