package com.showerideas.aura.utils

import com.showerideas.aura.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Phase 6.10 — Unit tests for [BackupUtils].
 *
 * Verifies:
 * 1. Round-trip: export then restore produces byte-for-byte identical contacts.
 * 2. Wrong passphrase → [BackupUtils.BackupException].
 * 3. Corrupted ciphertext → [BackupUtils.BackupException].
 * 4. Magic mismatch → [BackupUtils.BackupException].
 * 5. Nullable [Contact.identityKeyHash] survives round-trip.
 * 6. Two exports with the same passphrase produce different ciphertext (random IV/salt).
 */
class BackupUtilsTest {

    private val passphrase = "correct-horse-battery-staple".toCharArray()
    private val wrongPassphrase = "wrong-password".toCharArray()

    private val sampleContacts = listOf(
        Contact(
            id          = "contact-001",
            displayName = "Alice Smith",
            phone       = "+1-555-0100",
            email       = "alice@example.com",
            company     = "Acme Corp",
            title       = "Engineer",
            website     = "https://alice.example.com",
            bio         = "Hello, AURA world!",
            isFavorite  = true,
            identityKeyHash = "abc123deadbeef"
        ),
        Contact(
            id          = "contact-002",
            displayName = "Bob Jones",
            identityKeyHash = null  // nullable survives round-trip
        )
    )

    // -------------------------------------------------------------------------
    // Round-trip
    // -------------------------------------------------------------------------

    @Test
    fun roundTrip_restoresContactsExactly() {
        val buf = ByteArrayOutputStream()
        BackupUtils.export(sampleContacts, passphrase, buf)
        val restored = BackupUtils.restore(passphrase, ByteArrayInputStream(buf.toByteArray()))

        assertEquals("Should restore same number of contacts", sampleContacts.size, restored.size)

        val alice = restored.first { it.id == "contact-001" }
        assertEquals("Alice", "Alice Smith", alice.displayName)
        assertEquals("+1-555-0100", alice.phone)
        assertEquals("alice@example.com", alice.email)
        assertEquals("Acme Corp", alice.company)
        assertEquals("Engineer", alice.title)
        assertEquals("https://alice.example.com", alice.website)
        assertEquals("Hello, AURA world!", alice.bio)
        assertEquals(true, alice.isFavorite)
        assertEquals("abc123deadbeef", alice.identityKeyHash)

        val bob = restored.first { it.id == "contact-002" }
        assertEquals("Bob Jones", bob.displayName)
        assertNull("Null identityKeyHash must survive round-trip", bob.identityKeyHash)
    }

    // -------------------------------------------------------------------------
    // Wrong passphrase
    // -------------------------------------------------------------------------

    @Test(expected = BackupUtils.BackupException::class)
    fun wrongPassphrase_throwsBackupException() {
        val buf = ByteArrayOutputStream()
        BackupUtils.export(sampleContacts, passphrase, buf)
        BackupUtils.restore(wrongPassphrase, ByteArrayInputStream(buf.toByteArray()))
    }

    // -------------------------------------------------------------------------
    // Corrupted ciphertext
    // -------------------------------------------------------------------------

    @Test(expected = BackupUtils.BackupException::class)
    fun corruptedCiphertext_throwsBackupException() {
        val buf = ByteArrayOutputStream()
        BackupUtils.export(sampleContacts, passphrase, buf)
        val bytes = buf.toByteArray()
        // Flip a byte in the ciphertext region (after 4+1+16+12 = 33 byte header)
        bytes[33] = bytes[33].xor(0xFF.toByte())
        BackupUtils.restore(passphrase, ByteArrayInputStream(bytes))
    }

    // -------------------------------------------------------------------------
    // Magic mismatch
    // -------------------------------------------------------------------------

    @Test(expected = BackupUtils.BackupException::class)
    fun wrongMagic_throwsBackupException() {
        val garbage = "not an AURA backup file at all".toByteArray()
        BackupUtils.restore(passphrase, ByteArrayInputStream(garbage))
    }

    // -------------------------------------------------------------------------
    // Random IV/salt guarantees ciphertext uniqueness
    // -------------------------------------------------------------------------

    @Test
    fun twoExports_produceDifferentCiphertext() {
        val buf1 = ByteArrayOutputStream()
        val buf2 = ByteArrayOutputStream()
        BackupUtils.export(sampleContacts, passphrase, buf1)
        BackupUtils.export(sampleContacts, passphrase, buf2)
        assertNotEquals(
            "Two exports with same passphrase must differ (random IV/salt)",
            buf1.toByteArray().toList(),
            buf2.toByteArray().toList()
        )
    }

    // -------------------------------------------------------------------------
    // Empty contacts guard
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun emptyContacts_throwsIllegalArgument() {
        BackupUtils.export(emptyList(), passphrase, ByteArrayOutputStream())
    }
}
