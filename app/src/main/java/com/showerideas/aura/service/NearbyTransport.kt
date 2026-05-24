package com.showerideas.aura.service

/**
 * Minimal transport interface that abstracts Google Nearby Connections
 * from the wire-protocol logic.
 *
 * Motivations:
 *  1. [NearbyExchangeService] depends directly on [com.google.android.gms.nearby.connection.ConnectionsClient].
 *     That class is a Play Services singleton — impossible to instantiate in JVM unit tests.
 *  2. Extracting send/receive to this interface lets a [FakeNearbyTransport] wire two
 *     in-process "endpoints" together, enabling full wire-protocol JVM tests without
 *     real BLE/Wi-Fi or an Android device.
 *
 * Scope: BYTES payloads only — STREAM payloads (avatar) are out-of-scope for this
 * interface version. Avatar tests belong in instrumented tests where the ParcelFileDescriptor
 * infrastructure is available.
 *
 * This interface intentionally has NO imports from com.google.android.gms or android.*
 * so it is usable in pure-JVM (src/test/) test code.
 */
interface NearbyTransport {

    /**
     * Send a raw bytes payload to [endpointId].
     * Delivery is best-effort. On the real transport, failures are logged
     * via [com.google.android.gms.tasks.Task] failure listeners; the fake
     * delivers synchronously.
     */
    fun sendBytes(endpointId: String, data: ByteArray)

    /**
     * Register a callback invoked when bytes arrive from any connected endpoint.
     * Must be set before any connections are accepted.
     */
    var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)?

    /**
     * Register a callback invoked when an endpoint connects and is accepted.
     * Parameters: (endpointId, remoteName, isIncoming)
     */
    var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)?

    /**
     * Register a callback invoked when an endpoint disconnects.
     */
    var onDisconnected: ((endpointId: String) -> Unit)?

    /**
     * Register a callback invoked when a new remote endpoint is discovered
     * (discovery mode only). Parameters: (endpointId, remoteName)
     */
    var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)?

    /** Start advertising under [localName] and [serviceId]. */
    fun startAdvertising(localName: String, serviceId: String)

    /** Start discovering endpoints advertising [serviceId]. */
    fun startDiscovery(serviceId: String)

    /** Request a connection to [endpointId] found via discovery. */
    fun requestConnection(localName: String, endpointId: String)

    /** Accept a pending incoming connection from [endpointId]. */
    fun acceptConnection(endpointId: String)

    /** Reject a pending incoming connection from [endpointId]. */
    fun rejectConnection(endpointId: String)

    /** Stop advertising. */
    fun stopAdvertising()

    /** Stop discovery. */
    fun stopDiscovery()

    /** Disconnect all endpoints and clean up. */
    fun stopAllEndpoints()
}
