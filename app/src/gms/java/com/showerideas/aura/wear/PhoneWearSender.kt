package com.showerideas.aura.wear

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase F1 — Phone-side Wearable Data Layer sender.
 *
 * Discovers all connected Wear OS nodes and opens a [ChannelClient] channel at
 * [CHANNEL_PATH] ("/aura/exchange-state") to each one. When the AURA exchange
 * state changes, [sendState] writes a single ordinal byte to every open channel.
 * The watch-side [WearPhoneBridge] (in the `:wearos` module) receives the byte
 * and updates the [AuraTileService] tile.
 *
 * ## Lifecycle
 * Instantiate once (Hilt @Singleton). Call [sendState] from wherever the
 * [NearbyExchangeService] emits a new [ExchangeState]. Call [close] when the
 * application is shutting down.
 *
 * ## Robustness
 * If a channel write fails (watch disconnected, channel torn down), the error is
 * logged and the channel is removed from the active set. The next [sendState]
 * call automatically rediscovers connected nodes.
 */
@Singleton
class PhoneWearSender @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Channel path agreed between phone sender and watch receiver. */
        const val CHANNEL_PATH = "/aura/exchange-state"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Ordinal byte values for each exchange state. Mirrored in WearPhoneBridge. */
    enum class ExchangeState { IDLE, ADVERTISING, CONNECTING, VERIFYING, COMPLETED, ERROR }

    // Currently open output streams keyed by node ID.
    private val streams = mutableMapOf<String, java.io.OutputStream>()

    /**
     * Discover connected Wear OS nodes and return their IDs.
     * Returns an empty list if no watch is paired or Wearable API is unavailable.
     */
    suspend fun connectedNodes(): List<Node> {
        return try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (e: Exception) {
            Timber.w(e, "PhoneWearSender: failed to list connected nodes")
            emptyList()
        }
    }

    /**
     * Send [state] to all connected Wear OS watches.
     *
     * Opens a new channel to any node that doesn't have one yet; writes the state
     * byte ordinal (0–5) to each open channel. Silently no-ops if no watch is
     * connected.
     */
    fun sendState(state: ExchangeState) {
        scope.launch {
            val nodes = connectedNodes()
            if (nodes.isEmpty()) {
                Timber.d("PhoneWearSender: no connected nodes — skipping state send")
                return@launch
            }

            val channelClient = Wearable.getChannelClient(context)
            for (node in nodes) {
                val stream = streams[node.id] ?: openChannel(channelClient, node)
                if (stream != null) {
                    try {
                        stream.write(state.ordinal)
                        stream.flush()
                        Timber.d("PhoneWearSender: sent state %s to node %s", state, node.displayName)
                    } catch (e: Exception) {
                        Timber.w(e, "PhoneWearSender: write failed for node %s — removing channel", node.id)
                        streams.remove(node.id)
                    }
                }
            }
        }
    }

    private suspend fun openChannel(
        client: ChannelClient,
        node: Node
    ): java.io.OutputStream? {
        return try {
            val channel: ChannelClient.Channel = client.openChannel(node.id, CHANNEL_PATH).await()
            val stream = client.getOutputStream(channel).await()
            streams[node.id] = stream
            Timber.i("PhoneWearSender: opened channel to %s (%s)", node.displayName, node.id)
            stream
        } catch (e: Exception) {
            Timber.w(e, "PhoneWearSender: failed to open channel to node %s", node.id)
            null
        }
    }

    fun close() {
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        scope.cancel()
    }
}
