package com.showerideas.aura.utils

import com.showerideas.aura.utils.BackupUtils.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.10 — BackupUtils roundtrip and error-handling unit tests.
 */
class BackupUtilsTest {

    private val sampleContacts = """[{"id":"c1","displayName":"Alice","email":"alice@test.com"}]"""
    private val samplePeers    = """[{"id":"p1","endpointId":"ep1"}]"""
    private val passphrase     = "correct-horse-battery-staple"

    @Test
    fun export_producesNonEmptyBytes() {
        val bytes = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        assertTrue("Export must produce non-empty output", bytes.isNotEmpty())
        assertTrue("Export must be larger than headers", bytes.size > 36)
    }

    @Test
    fun roundtrip_exactContactAndPeerCounts() {
        val bytes = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        val result = BackupUtils.restore(bytes, passphrase)
        assertTrue("Restore must succeed", result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals("Should restore 1 contact", 1, success.contactsRestored)
        assertEquals("Should restore 1 known peer", 1, success.peersRestored)
    }

    @Test
    fun wrongPassphrase_returnsWrongPassphraseResult() {
        val bytes = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        val result = BackupUtils.restore(bytes, "wrong-passphrase")
        assertTrue("Wrong passphrase must return WrongPassphrase result",
                   result is RestoreResult.WrongPassphrase)
    }

    @Test
    fun corruptBytes_returnsCorruptBackupResult() {
        val result = BackupUtils.restore(ByteArray(10) { 0xFF.toByte() }, passphrase)
        assertTrue("Corrupt bytes must return CorruptBackup result",
                   result is RestoreResult.CorruptBackup)
    }

    @Test
    fun export_startsWith_AURA_magic() {
        val bytes = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        assertEquals("Magic byte 0 should be 0x41 (A)", 0x41.toByte(), bytes[0])
        assertEquals("Magic byte 1 should be 0x55 (U)", 0x55.toByte(), bytes[1])
        assertEquals("Magic byte 2 should be 0x52 (R)", 0x52.toByte(), bytes[2])
        assertEquals("Magic byte 3 should be 0x41 (A)", 0x41.toByte(), bytes[3])
    }

    @Test
    fun twoExports_samePassphrase_produceDifferentCiphertext() {
        val bytes1 = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        val bytes2 = BackupUtils.export(sampleContacts, samplePeers, passphrase)
        // Salt and IV are random — two exports must not be identical
        assertTrue("Two exports must differ (random salt+IV)", !bytes1.contentEquals(bytes2))
    }
}
