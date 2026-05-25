package com.showerideas.aura

import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [NearbyExchangeService] companion-object state machine and
 * constant contracts.
 *
 * These tests do NOT instantiate the Android [Service] (which requires Hilt +
 * a running foreground context). Instead they target:
 *  - The companion's Intent-action constants — renames are caught immediately.
 *  - The [NearbyExchangeService.injectTestSessionState] backdoor used by Espresso.
 *  - The [NearbyExchangeService.sessionState] StateFlow reactive contract
 *    (cold start, value propagation, null reset).
 *  - The [ExchangeSession] data class structural invariants.
 *
 * Higher-level integration paths (onConnectionInitiated → blocklist → accept)
 * are exercised via Espresso instrumented tests using [FakeNearbyTransport].
 */
class NearbyExchangeServiceUnitTest {

    @Before
    fun resetState() {
        NearbyExchangeService.injectTestSessionState(null)
    }

    @After
    fun cleanUp() {
        NearbyExchangeService.injectTestSessionState(null)
    }

    // -------------------------------------------------------------------------
    // Intent-action constant contracts
    // -------------------------------------------------------------------------

    @Test
    fun `ACTION_START has expected value`() {
        assertEquals("com.showerideas.aura.nearby.START", NearbyExchangeService.ACTION_START)
    }

    @Test
    fun `ACTION_STOP has expected value`() {
        assertEquals("com.showerideas.aura.nearby.STOP", NearbyExchangeService.ACTION_STOP)
    }

    @Test
    fun `ACTION_CONFIRM_SAS has expected value`() {
        assertEquals("com.showerideas.aura.nearby.CONFIRM_SAS", NearbyExchangeService.ACTION_CONFIRM_SAS)
    }

    @Test
    fun `ACTION_ABORT_SAS has expected value`() {
        assertEquals("com.showerideas.aura.nearby.ABORT_SAS", NearbyExchangeService.ACTION_ABORT_SAS)
    }

    @Test
    fun `ACTION_GESTURE_VERIFIED has expected value`() {
        assertEquals(
            "com.showerideas.aura.nearby.GESTURE_VERIFIED",
            NearbyExchangeService.ACTION_GESTURE_VERIFIED
        )
    }

    @Test
    fun `all action constants are distinct`() {
        val actions = listOf(
            NearbyExchangeService.ACTION_START,
            NearbyExchangeService.ACTION_STOP,
            NearbyExchangeService.ACTION_START_ROOM_HOST,
            NearbyExchangeService.ACTION_START_ROOM_GUEST,
            NearbyExchangeService.ACTION_STATE_UPDATE,
            NearbyExchangeService.ACTION_CONFIRM_SAS,
            NearbyExchangeService.ACTION_ABORT_SAS,
            NearbyExchangeService.ACTION_GESTURE_VERIFIED
        )
        assertEquals("All action constants must be distinct", actions.size, actions.toSet().size)
    }

    @Test
    fun `all action constants share the aura package prefix`() {
        val prefix = "com.showerideas.aura.nearby."
        listOf(
            NearbyExchangeService.ACTION_START,
            NearbyExchangeService.ACTION_STOP,
            NearbyExchangeService.ACTION_START_ROOM_HOST,
            NearbyExchangeService.ACTION_START_ROOM_GUEST,
            NearbyExchangeService.ACTION_CONFIRM_SAS,
            NearbyExchangeService.ACTION_ABORT_SAS,
            NearbyExchangeService.ACTION_GESTURE_VERIFIED
        ).forEach { action ->
            assertTrue("'$action' must start with '$prefix'", action.startsWith(prefix))
        }
    }

    // -------------------------------------------------------------------------
    // sessionState StateFlow — reactive contract
    // -------------------------------------------------------------------------

    @Test
    fun `sessionState is null at startup`() = runBlocking {
        assertNull(NearbyExchangeService.sessionState.value)
    }

    @Test
    fun `injectTestSessionState sets session synchronously`() {
        val session = ExchangeSession(
            sessionId = "unit-test-session",
            state     = ExchangeSession.State.ADVERTISING
        )
        NearbyExchangeService.injectTestSessionState(session)
        assertEquals(session, NearbyExchangeService.sessionState.value)
    }

    @Test
    fun `injectTestSessionState with null clears the session`() {
        NearbyExchangeService.injectTestSessionState(
            ExchangeSession(sessionId = "tmp", state = ExchangeSession.State.CONNECTING)
        )
        NearbyExchangeService.injectTestSessionState(null)
        assertNull(NearbyExchangeService.sessionState.value)
    }

    @Test
    fun `sessionState flow emits injected value to new collectors`() = runBlocking {
        val session = ExchangeSession(
            sessionId = "flow-test",
            state     = ExchangeSession.State.VERIFYING,
            sasPin    = "123456"
        )
        NearbyExchangeService.injectTestSessionState(session)
        // StateFlow.first() returns the current value synchronously for hot flows
        val emitted = NearbyExchangeService.sessionState.first()
        assertEquals(session, emitted)
    }

    // -------------------------------------------------------------------------
    // ExchangeSession data class invariants
    // -------------------------------------------------------------------------

    @Test
    fun `ExchangeSession defaults to ADVERTISING state and PEER_TO_PEER mode`() {
        val s = ExchangeSession(sessionId = "s1")
        assertEquals(ExchangeSession.State.ADVERTISING, s.state)
        assertEquals(ExchangeSession.ExchangeMode.PEER_TO_PEER, s.mode)
    }

    @Test
    fun `ExchangeSession copy preserves all unchanged fields`() {
        val original = ExchangeSession(
            sessionId = "orig",
            state     = ExchangeSession.State.CONNECTING,
            sasPin    = "999000",
            errorMessage = null
        )
        val updated = original.copy(state = ExchangeSession.State.VERIFYING)
        assertEquals("orig",              updated.sessionId)
        assertEquals("999000",            updated.sasPin)
        assertEquals(ExchangeSession.State.VERIFYING, updated.state)
    }

    @Test
    fun `ExchangeSession VERIFYING state holds a non-null sasPin`() {
        val session = ExchangeSession(
            sessionId = "sas-test",
            state     = ExchangeSession.State.VERIFYING,
            sasPin    = "042567"
        )
        assertNotNull(
            "sasPin must be non-null in VERIFYING state",
            session.sasPin
        )
        assertEquals("042567", session.sasPin)
    }

    @Test
    fun `all ExchangeSession State values are enumerable`() {
        // Guard: if a new state is added without updating when/else branches,
        // the enum count will change and this test will catch it.
        val expected = setOf(
            "ADVERTISING", "DISCOVERING", "CONNECTING",
            "VERIFYING", "EXCHANGING", "COMPLETED", "CANCELLED", "ERROR"
        )
        val actual = ExchangeSession.State.values().map { it.name }.toSet()
        assertEquals(
            "ExchangeSession.State enum members changed — update all when/else branches",
            expected,
            actual
        )
    }

    @Test
    fun `ExchangeSession COMPLETED with mergeEvent is properly structured`() {
        val contact = com.showerideas.aura.model.Contact(
            id = "c1", displayName = "Alice", email = "alice@test.com",
            phone = "", company = "", title = "", website = "", bio = "",
            identityKeyHash = "hash", sourceEndpointId = "ep1",
            avatarUri = "", profileVersion = 1
        )
        val mergeEvent = com.showerideas.aura.model.MergeEvent(
            preserved = contact.copy(email = "new@test.com"),
            previous  = contact,
            diffs     = listOf(
                com.showerideas.aura.model.ContactFieldDiff(
                    field = "email", label = "Email",
                    oldValue = "alice@test.com", newValue = "new@test.com"
                )
            )
        )
        val session = ExchangeSession(
            sessionId  = "merge-test",
            state      = ExchangeSession.State.COMPLETED,
            mergeEvent = mergeEvent
        )
        assertTrue("mergeEvent.hasChanges should be true", session.mergeEvent!!.hasChanges)
        assertEquals("new@test.com",   session.mergeEvent!!.preserved.email)
        assertEquals("alice@test.com", session.mergeEvent!!.previous.email)
    }
}
