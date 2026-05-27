package com.showerideas.aura.service

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-process test double for [WifiDirectTransport].
 *
 * Wires two [FakeWifiDirectTransport] instances together synchronously,
 * enabling full wire-protocol JVM unit tests without real Wi-Fi Direct,
 * Android devices, or emulators.
 *
 * Usage
 * ```kotlin
 * val alice = FakeWifiDirectTransport("alice")
 * val bob   = FakeWifiDirectTransport("bob")
 * alice.connectTo(bob)    // simulates a mutual connection
 *
 * alice.sendBytes("bob", payload)
 * // bob.onPayloadReceived is called synchronously
 * ```
 *
 * [connectTo] simulates both the GO TCP handshake and the [NearbyTransport.onConnected]
 * callbacks on both sides, matching the real [WifiDirectTransport] behaviour.
 *
 * [receivedFrom] collects all bytes received from each peer for assertion in tests.
 */
class FakeWifiDirectTransport(val localName: String) : NearbyTransport {

    // NearbyTransport callbacks

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null

    // Test introspection

    /** All bytes received from each remote, in order. Key = remoteLocalName. */
    val receivedFrom: MutableMap<String, ConcurrentLinkedQueue<ByteArray>> =
        mutableMapOf<String, ConcurrentLinkedQueue<ByteArray>>().also { it.toMutableMap() }

    /** Peers this fake is currently advertising. */
    val advertisedServiceId: String? get() = _advertisedServiceId

    /** Discovery calls recorded. */
    val discoveryServiceIds: List<String> get() = _discoveryServiceIds.toList()

    private var _advertisedServiceId: String? = null
    private val _discoveryServiceIds = mutableListOf<String>()
    private val connectedPeers = mutableMapOf<String, FakeWifiDirectTransport>()

    // Simulation helpers

    /**
     * Simulate a mutual Wi-Fi Direct connection between this fake and [peer].
     *
     * Fires [onConnectionInitiated] and then [onConnected] on both fakes:
     * - This fake is treated as the Group Owner (isIncoming = true from peer's view).
     * - [peer] is treated as the client (isIncoming = false).
     * Also fires [onEndpointFound] on both if they were in discovery mode.
     */
    fun connectTo(peer: FakeWifiDirectTransport) {
        connectedPeers[peer.localName] = peer
        peer.connectedPeers[localName] = this

        receivedFrom.getOrPut(peer.localName) { ConcurrentLinkedQueue() }
        peer.receivedFrom.getOrPut(localName) { ConcurrentLinkedQueue() }

        // Simulate endpoint discovery
        this.onEndpointFound?.invoke(peer.localName, peer.localName)
        peer.onEndpointFound?.invoke(localName, localName)

        // Simulate connection initiation (pre-accept) — mirrors WifiDirectTransport.onPeerSocketReady
        this.onConnectionInitiated?.invoke(peer.localName, peer.localName)
        peer.onConnectionInitiated?.invoke(localName, localName)

        // Simulate connection established (this = GO, peer = client — lexicographic rule)
        this.onConnected?.invoke(peer.localName, peer.localName, true)
        peer.onConnected?.invoke(localName, localName, false)
    }

    /**
     * Simulate a peer disconnect. Fires [onDisconnected] on both sides.
     */
    fun disconnectFrom(peer: FakeWifiDirectTransport) {
        connectedPeers.remove(peer.localName)
        peer.connectedPeers.remove(localName)
        this.onDisconnected?.invoke(peer.localName)
        peer.onDisconnected?.invoke(localName)
    }

    // NearbyTransport implementation

    override fun sendBytes(endpointId: String, data: ByteArray) {
        val peer = connectedPeers[endpointId]
            ?: error("FakeWifiDirectTransport: no peer connected with id=$endpointId")
        // Record for test assertion
        peer.receivedFrom.getOrPut(localName) { ConcurrentLinkedQueue() }.add(data.copyOf())
        // Deliver synchronously
        peer.onPayloadReceived?.invoke(localName, data.copyOf())
    }

    override fun startAdvertising(localName: String, serviceId: String) {
        _advertisedServiceId = serviceId
    }

    override fun startDiscovery(serviceId: String) {
        _discoveryServiceIds.add(serviceId)
    }

    override fun requestConnection(localName: String, endpointId: String) {
        // No-op in the fake — tests call connectTo() directly
    }

    override fun acceptConnection(endpointId: String) {
        // No-op — Wi-Fi Direct acceptance is implicit (OS-level)
    }

    override fun rejectConnection(endpointId: String) {
        connectedPeers[endpointId]?.let { disconnectFrom(it) }
    }

    override fun stopAdvertising() { _advertisedServiceId = null }

    override fun stopDiscovery() { _discoveryServiceIds.clear() }

    override fun stopAllEndpoints() {
        val snapshot = connectedPeers.values.toList()
        snapshot.forEach { disconnectFrom(it) }
    }
}
