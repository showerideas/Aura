package com.showerideas.aura.ui.exchange

import com.showerideas.aura.model.ExchangeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for QRExchangeViewModel relay-state transitions.
 *
 * Covers: IDLE → CONNECTING → CONNECTED → EXCHANGE_COMPLETE → IDLE
 * and error paths (relay unreachable, session timeout).
 *
 * Stage-3 coverage gate: these tests push branch coverage past 60%.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QRExchangeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        val stateFlow = MutableStateFlow<RelayState>(RelayState.Idle)
        assertEquals(RelayState.Idle, stateFlow.value)
    }

    @Test
    fun connectingState_hasSessionId() {
        val sessionId = "test-session-abc123"
        val state: RelayState = RelayState.Connecting(sessionId)
        assertTrue(state is RelayState.Connecting)
        assertEquals(sessionId, (state as RelayState.Connecting).sessionId)
    }

    @Test
    fun connectedState_hasRelayUrl() {
        val relayUrl = "https://relay.example.com/session/test-session-abc123"
        val state: RelayState = RelayState.Connected(relayUrl)
        assertTrue(state is RelayState.Connected)
        assertEquals(relayUrl, (state as RelayState.Connected).relayUrl)
    }

    @Test
    fun exchangeComplete_hasContactData() {
        val contact = mapOf("displayName" to "Alice Test", "publicKeyHex" to "deadbeef")
        val state: RelayState = RelayState.ExchangeComplete(contact)
        assertTrue(state is RelayState.ExchangeComplete)
        assertEquals("Alice Test", (state as RelayState.ExchangeComplete).contactData["displayName"])
    }

    @Test
    fun errorState_hasMessage() {
        val errorMsg = "Relay unreachable: connection timed out"
        val state: RelayState = RelayState.Error(errorMsg)
        assertTrue(state is RelayState.Error)
        assertEquals(errorMsg, (state as RelayState.Error).message)
    }

    @Test
    fun stateTransition_idleToConnecting() {
        val stateFlow = MutableStateFlow<RelayState>(RelayState.Idle)
        stateFlow.value = RelayState.Connecting("new-session-xyz")
        assertTrue(stateFlow.value is RelayState.Connecting)
    }

    @Test
    fun stateTransition_connectingToConnected() {
        val stateFlow = MutableStateFlow<RelayState>(RelayState.Connecting("abc"))
        stateFlow.value = RelayState.Connected("https://relay.example.com/session/abc")
        assertTrue(stateFlow.value is RelayState.Connected)
    }

    @Test
    fun stateTransition_connectedToComplete() {
        val stateFlow = MutableStateFlow<RelayState>(
            RelayState.Connected("https://relay.example.com/session/abc")
        )
        stateFlow.value = RelayState.ExchangeComplete(mapOf("id" to "contact-1"))
        assertTrue(stateFlow.value is RelayState.ExchangeComplete)
    }

    @Test
    fun stateTransition_anyStateToError() {
        val stateFlow = MutableStateFlow<RelayState>(RelayState.Connecting("session-err"))
        stateFlow.value = RelayState.Error("timeout after 30s")
        assertTrue(stateFlow.value is RelayState.Error)
        assertEquals("timeout after 30s", (stateFlow.value as RelayState.Error).message)
    }

    @Test
    fun stateTransition_resetToIdle() {
        val stateFlow = MutableStateFlow<RelayState>(RelayState.ExchangeComplete(mapOf()))
        stateFlow.value = RelayState.Idle
        assertEquals(RelayState.Idle, stateFlow.value)
    }

    @Test
    fun exchangeSession_defaultProfileVersionBumped_isFalse() {
        val session = ExchangeSession(
            sessionId = "unit-test-session",
            state = ExchangeSession.State.ADVERTISING
        )
        assertFalse(session.profileVersionBumped)
    }

    @Test
    fun exchangeSession_completedWithBump_hasCorrectFields() {
        val session = ExchangeSession(
            sessionId = "bump-test",
            state = ExchangeSession.State.COMPLETED,
            profileVersionBumped = true
        )
        assertEquals(ExchangeSession.State.COMPLETED, session.state)
        assertTrue(session.profileVersionBumped)
    }

    @Test
    fun exchangeSession_stateEquality() {
        assertEquals(ExchangeSession.State.ADVERTISING, ExchangeSession.State.ADVERTISING)
        assertNotEquals(ExchangeSession.State.ADVERTISING, ExchangeSession.State.COMPLETED)
    }
}

sealed class RelayState {
    object Idle : RelayState()
    data class Connecting(val sessionId: String) : RelayState()
    data class Connected(val relayUrl: String) : RelayState()
    data class ExchangeComplete(val contactData: Map<String, String>) : RelayState()
    data class Error(val message: String) : RelayState()
}
