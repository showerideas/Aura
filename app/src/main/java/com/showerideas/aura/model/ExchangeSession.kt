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
    }

    data class DiscoveredEndpoint(
        val endpointId: String,
        val endpointName: String,
        val serviceId: String
    )
}
