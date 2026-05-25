package com.showerideas.aura.service

import com.showerideas.aura.data.KnownPeerRepository
import com.showerideas.aura.data.local.KnownPeerDao
import com.showerideas.aura.model.KnownPeer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for Phase 6.7 profile version tracking.
 *
 * Verifies the [KnownPeerRepository] version-bump helpers and the logic that
 * decides whether [ExchangeSession.profileVersionBumped] should be set:
 *
 *   - storedVersion == 0  → first exchange, bumped = false (no false positives)
 *   - storedVersion < incoming → peer updated their card, bumped = true
 *   - storedVersion == incoming → no change, bumped = false
 *   - storedVersion > incoming → stale/replay, bumped = false
 *   - version == 0 on incoming → update skipped (unversioned profile)
 */
class ProfileVersioningTest {

    private lateinit var dao: KnownPeerDao
    private lateinit var repo: KnownPeerRepository

    private fun makeKnownPeer(
        endpointId: String = "ep-1",
        lastSeenProfileVersion: Int = 0,
        identityPublicKeyBase64: String = "AAAA"
    ) = KnownPeer(
        endpointId = endpointId,
        identityPublicKeyBase64 = identityPublicKeyBase64,
        firstSeenAt = 1_000L,
        lastSeenAt = 2_000L,
        lastSeenProfileVersion = lastSeenProfileVersion
    )

    @Before
    fun setUp() {
        dao = mock()
    }

    // -------------------------------------------------------------------------
    // getLastSeenProfileVersion
    // -------------------------------------------------------------------------

    @Test
    fun `getLastSeenProfileVersion returns 0 when endpoint unknown`() = runTest {
        whenever(dao.get("ep-new")).thenReturn(null)
        repo = KnownPeerRepository(dao)

        val result = repo.getLastSeenProfileVersion("ep-new")

        assertEquals(0, result)
    }

    @Test
    fun `getLastSeenProfileVersion returns stored value`() = runTest {
        whenever(dao.get("ep-1")).thenReturn(makeKnownPeer(lastSeenProfileVersion = 7))
        repo = KnownPeerRepository(dao)

        val result = repo.getLastSeenProfileVersion("ep-1")

        assertEquals(7, result)
    }

    @Test
    fun `getLastSeenProfileVersion returns 0 when stored version is 0`() = runTest {
        whenever(dao.get("ep-1")).thenReturn(makeKnownPeer(lastSeenProfileVersion = 0))
        repo = KnownPeerRepository(dao)

        val result = repo.getLastSeenProfileVersion("ep-1")

        assertEquals(0, result)
    }

    // -------------------------------------------------------------------------
    // updateLastSeenProfileVersion
    // -------------------------------------------------------------------------

    @Test
    fun `updateLastSeenProfileVersion delegates to dao`() = runTest {
        repo = KnownPeerRepository(dao)

        repo.updateLastSeenProfileVersion("ep-1", 5)

        verify(dao).updateLastSeenProfileVersion("ep-1", 5)
    }

    @Test
    fun `updateLastSeenProfileVersion with version 0 still delegates`() = runTest {
        repo = KnownPeerRepository(dao)

        repo.updateLastSeenProfileVersion("ep-1", 0)

        verify(dao).updateLastSeenProfileVersion("ep-1", 0)
    }

    // -------------------------------------------------------------------------
    // profileVersionBumped logic (simulated in-test — mirrors NearbyExchangeService)
    //
    // The service logic is:
    //   val storedVersion = repo.getLastSeenProfileVersion(endpointId)
    //   val bumped = storedVersion > 0 && contact.profileVersion > storedVersion
    //   if (contact.profileVersion > 0) repo.updateLastSeenProfileVersion(...)
    // -------------------------------------------------------------------------

    private fun computeBumped(storedVersion: Int, incomingVersion: Int): Boolean =
        storedVersion > 0 && incomingVersion > storedVersion

    private fun shouldUpdate(incomingVersion: Int): Boolean =
        incomingVersion > 0

    @Test
    fun `first exchange (stored=0) never triggers bump regardless of incoming version`() {
        assertFalse(computeBumped(storedVersion = 0, incomingVersion = 1))
        assertFalse(computeBumped(storedVersion = 0, incomingVersion = 99))
        assertFalse(computeBumped(storedVersion = 0, incomingVersion = 0))
    }

    @Test
    fun `incoming version higher than stored triggers bump`() {
        assertTrue(computeBumped(storedVersion = 1, incomingVersion = 2))
        assertTrue(computeBumped(storedVersion = 3, incomingVersion = 100))
        assertTrue(computeBumped(storedVersion = 9, incomingVersion = 10))
    }

    @Test
    fun `same version does not trigger bump`() {
        assertFalse(computeBumped(storedVersion = 5, incomingVersion = 5))
        assertFalse(computeBumped(storedVersion = 1, incomingVersion = 1))
    }

    @Test
    fun `downgrade (incoming lower than stored) does not trigger bump`() {
        assertFalse(computeBumped(storedVersion = 10, incomingVersion = 9))
        assertFalse(computeBumped(storedVersion = 5, incomingVersion = 3))
    }

    @Test
    fun `incoming version 0 means unversioned profile — no update persisted`() {
        assertFalse(shouldUpdate(incomingVersion = 0))
    }

    @Test
    fun `incoming version greater than 0 means update should be persisted`() {
        assertTrue(shouldUpdate(incomingVersion = 1))
        assertTrue(shouldUpdate(incomingVersion = 42))
    }

    @Test
    fun `bump flag is false when incoming is 0 even if stored is nonzero`() {
        // storedVersion > 0 but incomingVersion == 0 → not a bump
        assertFalse(computeBumped(storedVersion = 5, incomingVersion = 0))
    }

    @Test
    fun `round trip — bump true then update — next exchange no bump for same version`() = runTest {
        // Simulate first meeting (stored=0), then an update (stored=1, incoming=2),
        // then a repeat at same version (stored=2, incoming=2).

        // Step 1: first exchange
        assertFalse(computeBumped(storedVersion = 0, incomingVersion = 1))
        // storedVersion updated to 1

        // Step 2: peer bumped their card
        assertTrue(computeBumped(storedVersion = 1, incomingVersion = 2))
        // storedVersion updated to 2

        // Step 3: same card again
        assertFalse(computeBumped(storedVersion = 2, incomingVersion = 2))
    }
}
