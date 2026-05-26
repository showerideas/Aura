package com.showerideas.aura.network

/**
 * Fake [RelayClient] for JVM unit tests.
 *
 * Callers pre-load slot data via [enqueueSlot] before the test runs;
 * [getSlot] returns those bytes the first time the matching endpoint is
 * polled, then null thereafter (simulating a one-shot relay store).
 *
 * [postSlot] records the call in [postedSlots] and returns [postOk] (default true).
 */
class FakeRelayClient : RelayClient() {

    /** Set to false to make every postSlot() return false (network failure). */
    @Volatile var postOk: Boolean = true

    /** Queued bytes to return for a given endpoint on the first getSlot() call. */
    private val queued = mutableMapOf<String, ByteArray>()

    /** All (endpoint, bytes) pairs that were posted via postSlot(). */
    val postedSlots = mutableListOf<Pair<String, ByteArray>>()

    /** Number of getSlot() calls made, keyed by endpoint. */
    val getCallCount = mutableMapOf<String, Int>()

    /** Pre-load an endpoint so the next getSlot() call for it returns [bytes]. */
    fun enqueueSlot(endpoint: String, bytes: ByteArray) {
        queued[endpoint] = bytes
    }

    override fun postSlot(baseUrl: String, endpoint: String, encryptedBytes: ByteArray): Boolean {
        postedSlots.add(endpoint to encryptedBytes)
        return postOk
    }

    override fun getSlot(baseUrl: String, endpoint: String): ByteArray? {
        getCallCount[endpoint] = (getCallCount[endpoint] ?: 0) + 1
        return queued.remove(endpoint)
    }
}
