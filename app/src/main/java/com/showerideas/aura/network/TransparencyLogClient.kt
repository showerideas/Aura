package com.showerideas.aura.network

import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 8.4 — Transparency log client for the AURA remote blocklist.
 *
 * Protocol:
 * 1. Submit a peer identity hash to flag it as abusive (opt-in).
 * 2. Fetch the current Bloom filter for fast local lookups.
 * 3. Verify a Merkle proof for a specific hash (audit trail).
 *
 * All operations are opt-in. No data is submitted without explicit user consent.
 * The transparency log server spec is in docs/BLOCKLIST_TRANSPARENCY.md.
 */
@Singleton
class TransparencyLogClient @Inject constructor() {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 30_000
        // Placeholder base URL — configure via BuildConfig.TRANSPARENCY_LOG_URL
        private const val DEFAULT_BASE_URL   = "https://transparency.aura.app/v1"
    }

    /**
     * Phase 8.4 — Submit a peer identity hash to the transparency log.
     * Only called when the user explicitly taps "Submit this peer as abusive".
     *
     * @param identityKeyHash  SHA-256 of the peer identity public key (hex string)
     * @param baseUrl          Transparency log API base URL
     * @return true on HTTP 2xx, false on error
     */
    fun submitHash(identityKeyHash: String, baseUrl: String = DEFAULT_BASE_URL): Boolean {
        return try {
            val conn = openConnection("$baseUrl/report", "POST")
            val body = """{"hash":"$identityKeyHash"}""".toByteArray()
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Length", body.size.toString())
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            Timber.i("TransparencyLogClient submit hash → HTTP %d", code)
            code in 200..204
        } catch (e: IOException) {
            Timber.e(e, "TransparencyLogClient submitHash failed")
            false
        }
    }

    /**
     * Phase 8.4 — Fetch the current Bloom filter bytes for local lookups.
     * The Bloom filter is used to quickly check if a peer has been reported
     * without revealing which hashes are being queried.
     *
     * @return Raw Bloom filter bytes, or null on error
     */
    fun fetchBloomFilter(baseUrl: String = DEFAULT_BASE_URL): ByteArray? {
        return try {
            val conn = openConnection("$baseUrl/bloom", "GET")
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { it.readBytes() }
                    .also { Timber.i("TransparencyLogClient bloom filter: %dB", it.size) }
                    .also { conn.disconnect() }
            } else {
                conn.disconnect()
                Timber.w("TransparencyLogClient bloom filter → HTTP %d", code)
                null
            }
        } catch (e: IOException) {
            Timber.e(e, "TransparencyLogClient fetchBloomFilter failed")
            null
        }
    }

    /**
     * Phase 8.4 — Verify a Merkle inclusion proof for a hash.
     * Returns true if the hash is provably in the log, false otherwise.
     *
     * @param identityKeyHash  Hash to verify
     * @param proofBytes       Serialized Merkle proof from the server
     * @param rootHash         Expected Merkle root hash
     */
    fun verifyMerkleProof(
        identityKeyHash: String,
        proofBytes: ByteArray,
        rootHash: ByteArray
    ): Boolean {
        // Simplified Merkle verification: hash the entry and walk up the proof
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var current = digest.digest(identityKeyHash.toByteArray(Charsets.UTF_8))
            var offset = 0
            while (offset + 32 < proofBytes.size) {
                val sibling = proofBytes.slice(offset until offset + 32).toByteArray()
                val side = proofBytes[offset + 32]
                current = if (side == 0.toByte()) {
                    digest.digest(current + sibling)
                } else {
                    digest.digest(sibling + current)
                }
                offset += 33
            }
            current.contentEquals(rootHash)
        } catch (e: Exception) {
            Timber.e(e, "Merkle proof verification failed")
            false
        }
    }

    /**
     * Phase 8.4 — Check if a peer hash is in the local Bloom filter.
     * Fast O(k) lookup — no network required.
     *
     * @param identityKeyHash  Hash to check
     * @param bloomFilterBytes Raw Bloom filter bytes (from [fetchBloomFilter])
     * @return true if the hash is probably in the filter (may have false positives)
     */
    fun isInBloomFilter(identityKeyHash: String, bloomFilterBytes: ByteArray): Boolean {
        if (bloomFilterBytes.isEmpty()) return false
        val bitCount = bloomFilterBytes.size * 8
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(identityKeyHash.toByteArray())
        // 3 hash functions for the Bloom filter
        val indices = (0 until 3).map { i ->
            val seed = ((hash[i * 4].toInt() and 0xFF) shl 24) or
                       ((hash[i * 4 + 1].toInt() and 0xFF) shl 16) or
                       ((hash[i * 4 + 2].toInt() and 0xFF) shl 8) or
                       (hash[i * 4 + 3].toInt() and 0xFF)
            Math.abs(seed) % bitCount
        }
        return indices.all { bitIndex ->
            val byteIdx = bitIndex / 8
            val bitIdx  = bitIndex % 8
            (bloomFilterBytes[byteIdx].toInt() and (1 shl bitIdx)) != 0
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
        }
}
