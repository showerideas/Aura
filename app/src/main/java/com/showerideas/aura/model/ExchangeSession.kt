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
        ERROR,
        /** Room mode: hosting a multi-guest collection session (PR-09) */
        ROOM_HOST,
        /** Room mode: joined as a guest, awaiting host card (PR-09) */
        ROOM_GUEST
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
