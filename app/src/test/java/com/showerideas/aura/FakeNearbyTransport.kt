package com.showerideas.aura

import com.showerideas.aura.service.NearbyTransport
import java.util.concurrent.LinkedBlockingQueue

/**
 * In-process fake implementation of [NearbyTransport].
 *
 * Wires two logical "endpoints" together so that a byte payload sent by
 * endpoint A arrives at endpoint B's [onPayloadReceived] callback and vice-
 * versa. This allows full wire-protocol JVM tests with no BLE, no Wi-Fi, and
 * no Play Services.
 *
 * Usage:
 * ```kotlin
 * val (aliceTransport, bobTransport) = FakeNearbyTransport.pairedPair("alice", "bob")
 * aliceTransport.simulateConnect()   // fires onConnected on both sides
 * aliceTransport.sendBytes("bob", byteArrayOf(0x01, 0x02))
 * // → bobTransport.onPayloadReceived("alice", [0x01, 0x02]) fires synchronously
 * ```
 *
 * Thread-safety: callbacks are invoked synchronously on the calling thread.
 * Tests that need async dispatch can wrap send calls in a coroutine or Thread.
 */
class FakeNearbyTransport(
    val localId: String,
) : NearbyTransport {

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null

    /** Paired remote transport — set by [pairedPair]. */
    var peer: FakeNearbyTransport? = null

    private var advertising = false
    private var discovering = false
    private val connectedPeers: MutableSet<String> = mutableSetOf()

    val sentPayloads: LinkedBlockingQueue<Pair<String, ByteArray>> = LinkedBlockingQueue()

    // -------------------------------------------------------------------------
    // NearbyTransport implementation
    // -------------------------------------------------------------------------

    override fun sendBytes(endpointId: String, data: ByteArray) {
        sentPayloads.add(endpointId to data.copyOf())
        val remote = peer ?: error("FakeNearbyTransport: no peer wired for $localId")
        // Deliver synchronously to the peer's callback.
        remote.onPayloadReceived?.invoke(localId, data.copyOf())
    }

    override fun startAdvertising(localName: String, serviceId: String) {
        advertising = true
        // Simulate discovery: if the peer is already discovering, notify them.
        val remote = peer ?: return
        if (remote.discovering) {
            remote.onEndpointFound?.invoke(localId, localName)
        }
    }

    override fun startDiscovery(serviceId: String) {
        discovering = true
        // Simulate discovery: if the peer is already advertising, notify us.
        val remote = peer ?: return
        if (remote.advertising) {
            onEndpointFound?.invoke(remote.localId, remote.localId)
        }
    }

    override fun requestConnection(localName: String, endpointId: String) {
        val remote = peer ?: error("No peer for $localId")
        // Auto-notify both sides that a connection is initiated (always accepted by fake).
        remote.onConnected?.invoke(localId, localName, true)
        connectedPeers.add(endpointId)
        onConnected?.invoke(endpointId, remote.localId, false)
    }

    override fun acceptConnection(endpointId: String) {
        // In the fake, acceptance is implicit in requestConnection.
        connectedPeers.add(endpointId)
    }

    override fun rejectConnection(endpointId: String) {
        connectedPeers.remove(endpointId)
    }

    override fun stopAdvertising() { advertising = false }
    override fun stopDiscovery() { discovering = false }

    override fun stopAllEndpoints() {
        val peers = connectedPeers.toList()
        connectedPeers.clear()
        peers.forEach { id ->
            peer?.onDisconnected?.invoke(localId)
            onDisconnected?.invoke(id)
        }
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Simulate a connection being established from this side.
     * Fires [onConnected] on both sides without going through the
     * advertise/discover flow.
     */
    fun simulateConnect(remoteName: String = peer?.localId ?: "remote") {
        val remote = peer ?: error("No peer for $localId")
        connectedPeers.add(remote.localId)
        remote.connectedPeers.add(localId)
        onConnected?.invoke(remote.localId, remoteName, false)
        remote.onConnected?.invoke(localId, localId, true)
    }

    /**
     * Simulate disconnection. Fires [onDisconnected] on both sides.
     */
    fun simulateDisconnect() {
        val remote = peer ?: return
        connectedPeers.remove(remote.localId)
        remote.connectedPeers.remove(localId)
        onDisconnected?.invoke(remote.localId)
        remote.onDisconnected?.invoke(localId)
    }

    companion object {
        /**
         * Create two paired [FakeNearbyTransport] instances that route bytes
         * between each other. The first is "alice" (the initiating side);
         * the second is "bob" (the responding side).
         */
        fun pairedPair(
            aliceId: String = "alice",
            bobId: String = "bob",
        ): Pair<FakeNearbyTransport, FakeNearbyTransport> {
            val alice = FakeNearbyTransport(aliceId)
            val bob = FakeNearbyTransport(bobId)
            alice.peer = bob
            bob.peer = alice
            return alice to bob
        }
    }
}
