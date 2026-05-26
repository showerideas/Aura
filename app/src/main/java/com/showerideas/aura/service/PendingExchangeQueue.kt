package com.showerideas.aura.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.showerideas.aura.security.BloomFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T37 — Delay-tolerant store-and-forward queue for AURA exchanges.
 *
 * When two devices cannot exchange directly (e.g. one is in airplane mode, they
 * missed each other, or the BLE range was insufficient), the initiator can "park"
 * their outgoing exchange payload in this queue. A background BLE advertisement
 * encodes a Bloom-filter hint so nearby AURA devices know a pending exchange is
 * waiting without revealing which specific peer it targets.
 *
 * ## Protocol
 * 1. Initiator calls [enqueue] with their encrypted profile payload.
 * 2. The local BLE advertiser reads [bloomFilterHint] and includes it in the AURA
 *    service data field (8 bytes) of the BLE advertisement.
 * 3. A passing AURA peer scans the hint, checks whether they are a potential
 *    recipient, and if so requests a full delivery session.
 * 4. On successful delivery the sender calls [dequeue] to remove the entry.
 *
 * ## Privacy
 * The Bloom filter hint uses the sender's identity key hash as the item. The
 * receiver can test whether a specific hash is in the filter (true positive ~99.2%)
 * but cannot enumerate all senders — the filter is a compact probabilistic set with
 * no reverse operation.
 *
 * ## Storage
 * Pending entries are persisted in [DataStore] as JSON-serialised [PendingEntry]
 * objects. DataStore provides atomic, transactional writes with crash safety.
 *
 * ## TTL
 * Entries expire after [ENTRY_TTL_MS] (24 hours default). Expired entries are
 * pruned automatically by [pruneExpired].
 */
@Singleton
class PendingExchangeQueue @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Time-to-live for a pending entry: 24 hours. */
        const val ENTRY_TTL_MS = 24 * 60 * 60 * 1_000L

        /** Maximum number of concurrent pending entries. */
        const val MAX_QUEUE_SIZE = 10

        private val QUEUE_KEY = stringPreferencesKey("pending_exchange_queue_v1")
        private val BLOOM_KEY = stringPreferencesKey("pending_exchange_bloom_v1")
    }

    private val Context.pendingExchangeDataStore: DataStore<Preferences>
            by preferencesDataStore(name = "pending_exchange")

    private val dataStore get() = context.pendingExchangeDataStore

    // -------------------------------------------------------------------------
    // Entry model
    // -------------------------------------------------------------------------

    /**
     * A single parked exchange.
     *
     * @param id             Unique entry ID (UUID).
     * @param enqueuedAtMs   Epoch ms when the entry was added.
     * @param senderKeyHash  SHA-256 of the sender's identity key (for Bloom filter).
     * @param encryptedPayload Base64-encoded AES-GCM ciphertext of the profile.
     */
    data class PendingEntry(
        val id: String,
        val enqueuedAtMs: Long,
        val senderKeyHash: String,
        val encryptedPayload: String
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - enqueuedAtMs > ENTRY_TTL_MS
    }

    // -------------------------------------------------------------------------
    // Queue operations
    // -------------------------------------------------------------------------

    /** Add a pending exchange to the queue. Returns the entry ID, or null if queue is full. */
    suspend fun enqueue(senderKeyHash: String, encryptedPayload: String): String? {
        val current = loadEntries().toMutableList()
        if (current.size >= MAX_QUEUE_SIZE) {
            Timber.w("PendingExchangeQueue: queue full ($MAX_QUEUE_SIZE entries)")
            return null
        }
        val entry = PendingEntry(
            id              = UUID.randomUUID().toString(),
            enqueuedAtMs    = System.currentTimeMillis(),
            senderKeyHash   = senderKeyHash,
            encryptedPayload = encryptedPayload
        )
        current.add(entry)
        saveEntries(current)
        rebuildBloomFilter(current)
        Timber.i("PendingExchangeQueue: enqueued ${entry.id} (sender hash prefix: ${senderKeyHash.take(8)}…)")
        return entry.id
    }

    /** Remove a delivered entry from the queue. */
    suspend fun dequeue(entryId: String) {
        val current = loadEntries().filter { it.id != entryId }
        saveEntries(current)
        rebuildBloomFilter(current)
        Timber.i("PendingExchangeQueue: dequeued $entryId")
    }

    /** Remove all expired entries. Call periodically (e.g. on app foreground). */
    suspend fun pruneExpired() {
        val before = loadEntries()
        val after  = before.filter { !it.isExpired() }
        if (after.size < before.size) {
            Timber.i("PendingExchangeQueue: pruned ${before.size - after.size} expired entries")
            saveEntries(after)
            rebuildBloomFilter(after)
        }
    }

    /** All non-expired pending entries. */
    suspend fun pendingEntries(): List<PendingEntry> =
        loadEntries().filter { !it.isExpired() }

    // -------------------------------------------------------------------------
    // Bloom filter hint for BLE advertising
    // -------------------------------------------------------------------------

    /**
     * 8-byte Bloom filter hint suitable for inclusion in a BLE service data field.
     *
     * Encodes the sender key hashes of all pending entries. A peer scanning the
     * advertisement can test whether their own identity key hash is in the filter
     * with ~99.2% true-positive rate and ~0.8% false-positive rate.
     *
     * Returns an 8-byte zeroed array if the queue is empty.
     */
    suspend fun bloomFilterHint(): ByteArray {
        val raw = dataStore.data.map { prefs -> prefs[BLOOM_KEY] }.first() ?: return ByteArray(8)
        return try {
            android.util.Base64.decode(raw, android.util.Base64.NO_WRAP).take(8).toByteArray()
                .let { if (it.size < 8) it + ByteArray(8 - it.size) else it }
        } catch (e: Exception) {
            ByteArray(8)
        }
    }

    /**
     * Test whether [identityKeyHash] may have a pending exchange in this queue.
     * Returns true with ~0.8% false-positive probability even when no match exists.
     */
    suspend fun mightHavePendingFor(identityKeyHash: String): Boolean {
        val raw = dataStore.data.map { prefs -> prefs[BLOOM_KEY] }.first() ?: return false
        return try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
            BloomFilter.fromBytes(bytes).mightContain(identityKeyHash)
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private suspend fun loadEntries(): List<PendingEntry> {
        val json = dataStore.data.map { prefs -> prefs[QUEUE_KEY] }.first() ?: return emptyList()
        return try {
            com.google.gson.Gson().fromJson(json, Array<PendingEntry>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "PendingExchangeQueue: failed to deserialise entries")
            emptyList()
        }
    }

    private suspend fun saveEntries(entries: List<PendingEntry>) {
        val json = com.google.gson.Gson().toJson(entries)
        dataStore.edit { prefs -> prefs[QUEUE_KEY] = json }
    }

    private suspend fun rebuildBloomFilter(entries: List<PendingEntry>) {
        val filter = BloomFilter()
        entries.forEach { filter.insert(it.senderKeyHash) }
        val encoded = android.util.Base64.encodeToString(filter.toBytes(), android.util.Base64.NO_WRAP)
        dataStore.edit { prefs -> prefs[BLOOM_KEY] = encoded }
    }
}
