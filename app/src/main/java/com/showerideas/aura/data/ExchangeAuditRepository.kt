package com.showerideas.aura.data

import com.showerideas.aura.data.local.ExchangeAuditDao
import com.showerideas.aura.model.ExchangeAuditEntry
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the local exchange audit log.
 *
 * Wraps [ExchangeAuditDao] and provides convenience factory methods for
 * logging common exchange outcomes without callers needing to construct
 * [ExchangeAuditEntry] objects directly.
 */
@Singleton
class ExchangeAuditRepository @Inject constructor(
    private val auditDao: ExchangeAuditDao
) {

    val allEntries: Flow<List<ExchangeAuditEntry>> = auditDao.observeAll()

    fun recentEntries(limit: Int = 20): Flow<List<ExchangeAuditEntry>> =
        auditDao.observeRecent(limit)

    fun entriesForPeer(identityKeyHash: String): Flow<List<ExchangeAuditEntry>> =
        auditDao.observeForPeer(identityKeyHash)

    suspend fun logSuccess(
        peerIdentityKeyHash: String?,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY,
        direction: String = ExchangeAuditEntry.DIR_BOTH
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = ExchangeAuditEntry.OUTCOME_SUCCESS,
            channel = channel,
            direction = direction
        )
    )

    suspend fun logFailure(
        peerIdentityKeyHash: String? = null,
        errorCode: String? = null,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = ExchangeAuditEntry.OUTCOME_FAILED,
            errorCode = errorCode,
            channel = channel
        )
    )

    suspend fun logBlocked(
        peerIdentityKeyHash: String? = null,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = ExchangeAuditEntry.OUTCOME_BLOCKED,
            channel = channel
        )
    )

    suspend fun logSpoofDetected(
        peerIdentityKeyHash: String? = null,
        errorCode: String = ExchangeAuditEntry.ERR_LIVENESS_FAIL,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = ExchangeAuditEntry.OUTCOME_SPOOF,
            errorCode = errorCode,
            channel = channel
        )
    )

    suspend fun logTimeout(
        peerIdentityKeyHash: String? = null,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = ExchangeAuditEntry.OUTCOME_TIMEOUT,
            channel = channel
        )
    )


    /**
     * Log a SAS (Short Authentication String) confirmation or rejection event.
     *
     * This is a separate entry from the final exchange outcome — it records
     * the precise moment the user made their verbal code comparison decision,
     * independent of whether the profile exchange that follows succeeds.
     *
     * [confirmed] = true  → "Match ✓" pressed — user verified codes match.
     * [confirmed] = false → "Mismatch" pressed or timed out — possible MITM.
     */
    suspend fun logSasEvent(
        confirmed: Boolean,
        peerIdentityKeyHash: String? = null,
        channel: String = ExchangeAuditEntry.CHANNEL_NEARBY
    ) = auditDao.insert(
        ExchangeAuditEntry(
            id = UUID.randomUUID().toString(),
            peerIdentityKeyHash = peerIdentityKeyHash,
            outcome = if (confirmed) "SAS_CONFIRMED" else ExchangeAuditEntry.OUTCOME_FAILED,
            errorCode = if (confirmed) null else ExchangeAuditEntry.ERR_SAS_MISMATCH,
            channel = channel
        )
    )

    suspend fun countFailuresForPeer(hash: String, windowMs: Long = 3_600_000L): Int =
        auditDao.countFailuresForPeer(hash, System.currentTimeMillis() - windowMs)

    suspend fun clearAll() = auditDao.deleteAll()

    /** Prune entries older than [retentionDays] (default 90 days). */
    suspend fun pruneOldEntries(retentionDays: Int = 90) {
        val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        auditDao.deleteOlderThan(cutoff)
    }
}

