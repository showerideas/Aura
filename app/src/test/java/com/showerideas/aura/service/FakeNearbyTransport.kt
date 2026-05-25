package com.showerideas.aura.service

/**
 * In-memory fake implementation of [NearbyTransport] for unit testing.
 *
 * Allows tests to:
 *  - Control what payloads appear to arrive from a peer
 *  - Inspect what bytes the system-under-test sent
 *  - Drive connection/disconnection callbacks deterministically
 *
 * ## Usage
 * ```kotlin
 * val transport = FakeNearbyTransport()
 * // Wire up SUT that depends on NearbyTransport …
 * transport.simulateEndpointFound("peer-1", "Alice")
 * transport.simulateConnectionInitiated("peer-1", "Alice")
 * transport.simulateConnected("peer-1", "Alice", isIncoming = false)
 * transport.simulatePayloadReceived("peer-1", myPayloadBytes)
 * val sent = transport.sentPayloads["peer-1"] ?: emptyList()
 * ```
 */
class FakeNearbyTransport : NearbyTransport {

    // -------------------------------------------------------------------------
    // NearbyTransport callbacks (wired by the system-under-test)
    // -------------------------------------------------------------------------

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Captured calls — inspected by tests
    // -------------------------------------------------------------------------

    /** Sequence of (localName, serviceId) pairs passed to startAdvertising. */
    val advertisingStartedWith = mutableListOf<Pair<String, String>>()

    /** True once stopAdvertising has been called. */
    var advertisingStopped = false

    /** Sequence of serviceIds passed to startDiscovery. */
    val discoveryStartedWith = mutableListOf<String>()

    /** True once stopDiscovery has been called. */
    var discoveryStopped = false

    /** Sequence of (localName, endpointId) pairs passed to requestConnection. */
    val connectionRequestedWith = mutableListOf<Pair<String, String>>()

    /** Sequence of endpointIds passed to acceptConnection. */
    val acceptedConnections = mutableListOf<String>()

    /** Sequence of endpointIds passed to rejectConnection. */
    val rejectedConnections = mutableListOf<String>()

    /** All payloads sent, keyed by endpointId. Each entry is a list of byte arrays. */
    val sentPayloads = mutableMapOf<String, MutableList<ByteArray>>()

    /** True once stopAllEndpoints has been called. */
    var allEndpointsStopped = false

    // -------------------------------------------------------------------------
    // NearbyTransport — implementation
    // -------------------------------------------------------------------------

    override fun startAdvertising(localName: String, serviceId: String) {
        advertisingStartedWith.add(Pair(localName, serviceId))
    }

    override fun startDiscovery(serviceId: String) {
        discoveryStartedWith.add(serviceId)
    }

    override fun requestConnection(localName: String, endpointId: String) {
        connectionRequestedWith.add(Pair(localName, endpointId))
    }

    override fun acceptConnection(endpointId: String) {
        acceptedConnections.add(endpointId)
    }

    override fun rejectConnection(endpointId: String) {
        rejectedConnections.add(endpointId)
    }

    override fun stopAdvertising() {
        advertisingStopped = true
    }

    override fun stopDiscovery() {
        discoveryStopped = true
    }

    override fun stopAllEndpoints() {
        allEndpointsStopped = true
    }

    override fun sendBytes(endpointId: String, data: ByteArray) {
        sentPayloads.getOrPut(endpointId) { mutableListOf() }.add(data)
    }

    // -------------------------------------------------------------------------
    // Simulation helpers — called by tests to drive state
    // -------------------------------------------------------------------------

    /** Simulate the transport discovering a remote endpoint. */
    fun simulateEndpointFound(endpointId: String, remoteName: String) {
        onEndpointFound?.invoke(endpointId, remoteName)
    }

    /**
     * Simulate an incoming connection being initiated (pre-accept/reject).
     * Call this before [simulateConnected] when testing the blocklist-check path.
     */
    fun simulateConnectionInitiated(endpointId: String, remoteName: String) {
        onConnectionInitiated?.invoke(endpointId, remoteName)
    }

    /** Simulate a connection being established (incoming or outgoing). */
    fun simulateConnected(endpointId: String, remoteName: String, isIncoming: Boolean) {
        onConnected?.invoke(endpointId, remoteName, isIncoming)
    }

    /** Simulate a payload arriving from a remote endpoint. */
    fun simulatePayloadReceived(endpointId: String, data: ByteArray) {
        onPayloadReceived?.invoke(endpointId, data)
    }

    /** Simulate the remote endpoint disconnecting. */
    fun simulateDisconnected(endpointId: String) {
        onDisconnected?.invoke(endpointId)
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    /** The last payload sent to [endpointId], or null if none. */
    fun lastSentTo(endpointId: String): ByteArray? =
        sentPayloads[endpointId]?.lastOrNull()

    /** The total number of payloads sent to all endpoints combined. */
    val totalSentCount: Int
        get() = sentPayloads.values.sumOf { it.size }
}
