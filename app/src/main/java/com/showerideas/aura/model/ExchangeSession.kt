package com.showerideas.aura.model

/**
 * Represents a single live exchange session lifecycle.
 *
 * Created when the user activates AURA (triple-press), destroyed when the
 * session ends (timeout, success, or cancellation).
 */
data class ExchangeSession(
    val sessionId: String,
    val state: State = State.ADVERTISING,
    /** Which exchange topology this session is running under (PR-09). */
    val mode: ExchangeMode = ExchangeMode.PEER_TO_PEER,
    val discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    val connectedEndpointId: String? = null,
    val receivedContact: Contact? = null,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis()
) {
    enum class State {
        /** Broadcasting presence via Nearby Connections */
        ADVERTISING,
        /** Scanning for nearby AURA peers */
        DISCOVERING,
        /** Negotiating connection with a peer */
        CONNECTING,
        /** Authenticated — exchanging profile data */
        EXCHANGING,
        /** Exchange completed successfully */
        COMPLETED,
        /** User cancelled or timed out */
        CANCELLED,
        /** An unrecoverable error occurred */
        ERROR
        // FIX-4: ROOM_HOST and ROOM_GUEST removed. These described exchange topology,
        // not exchange stage — mixing them with stage values (ADVERTISING, CONNECTING,
        // COMPLETED, …) made when/else branches ambiguous and tests hard to reason about.
        // Topology is now expressed exclusively via [ExchangeMode]. To check whether a
        // session is in room-host mode use: session.mode == ExchangeMode.ROOM_HOST
    }

    /** Topology selector for the exchange — PR-09. */
    enum class ExchangeMode {
        PEER_TO_PEER,
        ROOM_HOST,
        ROOM_GUEST
    }

    data class DiscoveredEndpoint(
        val endpointId: String,
        val endpointName: String,
        val serviceId: String
    )
}
