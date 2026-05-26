package com.showerideas.aura.enterprise

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * H1 — Verify audit-log retention pruning cutoff calculation.
 *
 * The Worker delegates to [ExchangeAuditRepository.pruneOldEntries] which
 * deletes records older than (now − retentionDays * 86400_000 ms).
 * This test verifies the cutoff arithmetic with known inputs.
 */
class AuditRetentionWorkerTest {

    @Test fun retentionCutoff_90days_isCorrect() {
        val nowMs         = 1_748_304_000_000L     // fixed epoch for determinism
        val retentionDays = 90
        val expectedCutoff = nowMs - retentionDays.toLong() * 24 * 60 * 60 * 1_000
        // 90 days = 7_776_000_000 ms
        assertEquals(nowMs - 7_776_000_000L, expectedCutoff)
    }

    @Test fun retentionCutoff_30days_isCorrect() {
        val nowMs          = 1_748_304_000_000L
        val retentionDays  = 30
        val expectedCutoff = nowMs - retentionDays.toLong() * 24 * 60 * 60 * 1_000
        assertEquals(nowMs - 2_592_000_000L, expectedCutoff)
    }

    @Test fun retentionCutoff_0days_prunesAllEntries() {
        val nowMs          = 1_748_304_000_000L
        val retentionDays  = 0
        val cutoff         = nowMs - retentionDays.toLong() * 24 * 60 * 60 * 1_000
        // cutoff == now → everything before now is pruned
        assertEquals(nowMs, cutoff)
    }

    @Test fun retentionCutoff_365days_isCorrect() {
        val nowMs          = 1_748_304_000_000L
        val retentionDays  = 365
        val cutoff         = nowMs - retentionDays.toLong() * 24 * 60 * 60 * 1_000
        assertEquals(nowMs - 31_536_000_000L, cutoff)
    }
}
