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
    /** Which exchange topology this session is running under. */
    val mode: ExchangeMode = ExchangeMode.PEER_TO_PEER,
    val discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    val connectedEndpointId: String? = null,
    val receivedContact: Contact? = null,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    /**
     * Short Authentication String for first-meet MITM protection.
     * Both peers display this 6-digit code; users verbally confirm they match
     * before the profile payload is transmitted. Non-null only while the session
     * is in [State.VERIFYING]. Derived via SHA-256 over both ephemeral ECDH public
     * keys — a MITM who substitutes their own key produces a different code.
     */
    val sasPin: String? = null,
    /**
     * Non-null when state == [State.COMPLETED] and the peer was a returning contact
     * whose visible fields changed since the last exchange. The UI should show
     * [com.showerideas.aura.ui.contacts.ContactMergeBottomSheet] to let the user
     * review what changed (Phase 6.3 / Phase 6.7).
     */
    val mergeEvent: MergeEvent? = null,
    /**
     * True when state == [State.COMPLETED] and the peer's [Contact.profileVersion]
     * is higher than the value stored in [KnownPeer.lastSeenProfileVersion].
     * The UI shows a "Card updated" Snackbar to inform the user that the peer
     * updated their contact details since the last exchange (Phase 6.7).
     */
    val profileVersionBumped: Boolean = false
) {
    enum class State {
        // State flow (all states emitted at the correct point):
        //   ADVERTISING → DISCOVERING → CONNECTING → VERIFYING → EXCHANGING → COMPLETED | CANCELLED | ERROR
        //
        //   ADVERTISING:  emitted when startAdvertisingAndDiscovery() begins.
        //   DISCOVERING:  emitted after both startAdvertising AND startDiscovery succeed.
        //   CONNECTING:   emitted in onConnectionResult on success.
        //   VERIFYING:    emitted after ECDH — user must confirm SAS before profile is sent.
        //   EXCHANGING:   emitted at the start of sendProfile() and handleIncomingProfile().
        //   COMPLETED:    emitted when contact is saved and session succeeds.
        //   CANCELLED:    emitted on user cancel or session timeout.
        //   ERROR:        emitted on any unrecoverable failure.

        /** Broadcasting presence via Nearby Connections */
        ADVERTISING,
        /** Both advertising and discovery are active — scanning for peers */
        DISCOVERING,
        /** Negotiating connection with a peer */
        CONNECTING,
        /**
         * ECDH handshake complete — waiting for the user to verify the SAS PIN
         * with their peer before the profile payload is transmitted.
         * [ExchangeSession.sasPin] holds the 6-digit code to display.
         */
        VERIFYING,
        /** SAS confirmed — currently exchanging profile data */
        EXCHANGING,
        /** Exchange completed successfully */
        COMPLETED,
        /** User cancelled or timed out */
        CANCELLED,
        /** An unrecoverable error occurred */
        ERROR
        // ROOM_HOST and ROOM_GUEST removed. These described exchange topology,
        // not exchange stage — mixing them with stage values (ADVERTISING, CONNECTING,
        // COMPLETED, …) made when/else branches ambiguous and tests hard to reason about.
        // Topology is now expressed exclusively via [ExchangeMode]. To check whether a
        // session is in room-host mode use: session.mode == ExchangeMode.ROOM_HOST
    }

    /** Topology selector for the exchange — . */
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
