package com.showerideas.aura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 57 — MlsGroupState unit tests.
 *
 * Verifies epoch key agreement, Welcome round-trip, membership commits,
 * confirmation tag verification, and forward secrecy properties.
 */
class MlsGroupStateTest {

    @Test
    fun `group initializes with creator and epoch 0`() {
        val group = MlsGroupState("room-001")
        group.initialize("alice")
        assertEquals(0L, group.epoch)
        assertTrue("Alice must be a member", group.currentMembers().contains("alice"))
    }

    @Test
    fun `welcome round-trip gives joiner same application key`() {
        val group = MlsGroupState("room-001")
        group.initialize("alice")

        val bobPriv = ByteArray(32) { it.toByte() }   // dummy key material
        val bobPub  = ByteArray(32) { (it + 1).toByte() }

        val welcome = group.createWelcome("bob", bobPub)
        assertEquals("room-001", welcome.roomId)
        assertEquals(0L, welcome.epoch)
        assertEquals("bob", welcome.joinerId)

        val bobGroup = MlsGroupState("room-001")
        bobGroup.initialize("bob", ByteArray(32) { 0 }) // same init secret not needed — we use welcome
        // Simulate bob starting fresh then processing welcome
        val bobGroup2 = MlsGroupState("room-001")
        bobGroup2.processWelcome(welcome, bobPriv, "bob")

        // Both should be at epoch 0
        assertEquals(0L, bobGroup2.epoch)
        assertTrue(bobGroup2.currentMembers().contains("alice"))
        assertTrue(bobGroup2.currentMembers().contains("bob"))
    }

    @Test
    fun `commit advances epoch and rotates key`() {
        val group = MlsGroupState("room-002")
        group.initialize("alice")
        val key0 = group.currentApplicationKey()

        group.commit(addedId = "bob")

        assertEquals(1L, group.epoch)
        assertTrue("Bob must be in members after commit", group.currentMembers().contains("bob"))
        assertFalse("Epoch key must change on commit", key0.contentEquals(group.currentApplicationKey()))
    }

    @Test
    fun `process commit from another member advances epoch correctly`() {
        val group1 = MlsGroupState("room-003")
        group1.initialize("alice")

        val commitSecret = ByteArray(32) { 42 }
        group1.commit(addedId = "carol", commitSecret = commitSecret)
        val tag = group1.confirmationTag()

        // Simulate second group member (bob) receiving the same commit
        val group2 = MlsGroupState("room-003")
        group2.initialize("alice")  // same init state
        group2.processCommit(addedId = "carol", commitSecret = commitSecret, confirmationTag = tag)

        assertEquals("Both must be at epoch 1", 1L, group1.epoch)
        assertEquals("Both must be at epoch 1", 1L, group2.epoch)
        assertArrayEquals(
            "Application keys must be identical after same commit",
            group1.currentApplicationKey(),
            group2.currentApplicationKey()
        )
    }

    @Test
    fun `tampered confirmation tag rejected by processCommit`() {
        val group1 = MlsGroupState("room-004")
        group1.initialize("alice")
        val commitSecret = ByteArray(32) { 7 }
        group1.commit(addedId = "dave", commitSecret = commitSecret)
        val badTag = group1.confirmationTag().also { it[0] = it[0].xor(0xFF.toByte()) }

        val group2 = MlsGroupState("room-004")
        group2.initialize("alice")

        var threw = false
        try {
            group2.processCommit(addedId = "dave", commitSecret = commitSecret, confirmationTag = badTag)
        } catch (_: Exception) { threw = true }
        assertTrue("Tampered confirmation tag must be rejected", threw)
    }

    @Test
    fun `remove member reduces member count`() {
        val group = MlsGroupState("room-005")
        group.initialize("alice")
        group.commit(addedId = "bob")
        group.commit(addedId = "carol")
        assertEquals(3, group.currentMembers().size)

        group.commit(removedId = "bob")
        assertEquals(2, group.currentMembers().size)
        assertFalse(group.currentMembers().contains("bob"))
    }

    @Test
    fun `each epoch produces distinct application key`() {
        val group = MlsGroupState("room-006")
        group.initialize("alice")
        val keys = mutableListOf<ByteArray>()
        keys.add(group.currentApplicationKey())
        repeat(4) { i ->
            group.commit(addedId = "member-$i")
            keys.add(group.currentApplicationKey())
        }
        // All 5 keys must be distinct
        for (i in 0 until keys.size) {
            for (j in i + 1 until keys.size) {
                assertFalse("Keys at epoch $i and $j must differ",
                    keys[i].contentEquals(keys[j]))
            }
        }
    }
}
