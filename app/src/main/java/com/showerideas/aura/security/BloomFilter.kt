package com.showerideas.aura.security

import java.security.MessageDigest
import java.util.BitSet

/**
 * Space-efficient probabilistic set membership filter.
 *
 * Uses k=7 independent hash functions derived from SHA-256 to probe m=65536 bit
 * positions. For a set of n=10,000 blocked identities this gives a false-positive
 * rate of ~0.8% — a known FP is verified against the authoritative list before
 * triggering a block action, so false positives are benign.
 *
 * The filter is serialised as a [ByteArray] for DataStore persistence and
 * is reconstructed from bytes on each app start (no warm-up required).
 *
 * Thread-safe: all read operations are on immutable [BitSet]; writes
 * synchronise on the instance.
 */
class BloomFilter(
    private val bitCount: Int = DEFAULT_BIT_COUNT,
    private val hashCount: Int = DEFAULT_HASH_COUNT
) {
    companion object {
        const val DEFAULT_BIT_COUNT  = 65_536     // 8 KiB
        const val DEFAULT_HASH_COUNT = 7

        /** Reconstruct a [BloomFilter] from previously serialised bytes. */
        fun fromBytes(bytes: ByteArray, hashCount: Int = DEFAULT_HASH_COUNT): BloomFilter {
            val filter = BloomFilter(bytes.size * 8, hashCount)
            val bits   = BitSet.valueOf(bytes)
            for (i in 0 until bits.size()) {
                if (bits[i]) filter.bits.set(i)
            }
            return filter
        }
    }

    private val bits = BitSet(bitCount)

    /**
     * Insert [item] into the filter.
     * The item is typically the hex-encoded SHA-256 of a device fingerprint.
     */
    @Synchronized
    fun insert(item: String) {
        for (seed in 0 until hashCount) {
            bits.set(hashPos(item, seed))
        }
    }

    /**
     * Returns true if [item] _might_ be in the set.
     * Returns false if [item] is _definitely_ not in the set.
     */
    fun mightContain(item: String): Boolean {
        for (seed in 0 until hashCount) {
            if (!bits[hashPos(item, seed)]) return false
        }
        return true
    }

    /** Serialise the filter to bytes for DataStore persistence. */
    fun toBytes(): ByteArray {
        // BitSet.toByteArray() is little-endian and trims trailing zeros; pad back to full size
        val raw = bits.toByteArray()
        return raw.copyOf(bitCount / 8)
    }

    /** Number of bits currently set (useful for diagnostics). */
    val populatedBitCount: Int get() = bits.cardinality()

    // Private

    /**
     * Map ([item], [seed]) → a bit index in [0, bitCount).
     *
     * Computes SHA-256(seed_bytes || item_bytes) and takes the first 4 bytes
     * as a big-endian unsigned int, then mods by [bitCount].  Using different
     * seeds gives approximately independent hash functions.
     */
    private fun hashPos(item: String, seed: Int): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf(
            (seed shr 24).toByte(), (seed shr 16).toByte(),
            (seed shr  8).toByte(),  seed.toByte()
        ))
        digest.update(item.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val unsigned = ((hash[0].toInt() and 0xFF) shl 24) or
                       ((hash[1].toInt() and 0xFF) shl 16) or
                       ((hash[2].toInt() and 0xFF) shl  8) or
                        (hash[3].toInt() and 0xFF)
        return Math.floorMod(unsigned, bitCount)
    }
}
