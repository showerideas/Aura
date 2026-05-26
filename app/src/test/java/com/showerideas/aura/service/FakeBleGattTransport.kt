package com.showerideas.aura.service

/**
 * In-memory test double for [NearbyTransport] — mirrors FakeWifiDirectTransport pattern.
 *
 * Wire two instances together: bytes sent by Alice are delivered synchronously to Bob's
 * [onPayloadReceived] callback, and vice versa.
 */
class FakeBleGattTransport : NearbyTransport {

    override var onPayloadReceived     : ((String, ByteArray) -> Unit)? = null
    override var onConnected           : ((String, String, Boolean) -> Unit)? = null
    override var onDisconnected        : ((String) -> Unit)? = null
    override var onEndpointFound       : ((String, String) -> Unit)? = null
    override var onConnectionInitiated : ((String, String) -> Unit)? = null

    private var peer      : FakeBleGattTransport? = null
    private var connected : Boolean = false

    /** Simulate a successful BLE GATT connection. Fires onConnected on both sides. */
    fun connect() {
        val p = peer ?: return
        connected   = true
        p.connected = true
        onConnected?.invoke(PEER_ENDPOINT_ID, "fake-ble-peer", false)
        p.onConnected?.invoke(PEER_ENDPOINT_ID, "fake-ble-local", true)
    }

    /** Simulate peer disconnect. Fires onDisconnected on both sides. */
    fun disconnect() {
        val p = peer ?: return
        connected   = false
        p.connected = false
        onDisconnected?.invoke(PEER_ENDPOINT_ID)
        p.onDisconnected?.invoke(PEER_ENDPOINT_ID)
    }

    override fun sendBytes(endpointId: String, data: ByteArray) {
        check(connected) { "FakeBleGattTransport: not connected" }
        peer?.onPayloadReceived?.invoke(PEER_ENDPOINT_ID, data)
    }

    override fun startAdvertising(localName: String, serviceId: String) {}
    override fun startDiscovery(serviceId: String) {}
    override fun requestConnection(localName: String, endpointId: String) {}
    override fun acceptConnection(endpointId: String) {}
    override fun rejectConnection(endpointId: String) {}
    override fun stopAdvertising() {}
    override fun stopDiscovery() {}
    override fun stopAllEndpoints() { connected = false }

    companion object {
        const val PEER_ENDPOINT_ID = "fake-ble-peer-endpoint"

        fun createPair(): Pair<FakeBleGattTransport, FakeBleGattTransport> {
            val a = FakeBleGattTransport(); val b = FakeBleGattTransport()
            a.peer = b; b.peer = a
            return a to b
        }
    }
}
