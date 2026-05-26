package com.showerideas.aura.wear

import com.google.android.gms.wearable.Channel
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.InputStream

/**
 * Phase 7.2 — Wear OS side of the phone↔watch data bridge.
 *
 * The paired phone's [com.showerideas.aura.wear.PhoneWearSender] opens a
 * Wearable Data Layer channel on path [CHANNEL_PATH] and streams a single
 * [ExchangeState] ordinal byte whenever the exchange state changes.  This
 * service receives that byte, updates [WearStateStore], and asks the system
 * to refresh the [AuraTileService] tile.
 *
 * ## Data format (phone → watch)
 * Each message is a single byte whose value is the [ExchangeState.ordinal].
 * Fixed-width messages allow partial reads to be detected and discarded.
 */
class WearPhoneBridge : WearableListenerService() {

    companion object {
        /** Channel path agreed between phone sender and watch receiver. */
        const val CHANNEL_PATH = "/aura/exchange-state"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // WearableListenerService callbacks
    // -------------------------------------------------------------------------

    override fun onChannelOpened(channel: Channel) {
        Timber.d("WearPhoneBridge: channel opened from ${channel.nodeId} path=${channel.path}")
        if (channel.path != CHANNEL_PATH) return

        val client: ChannelClient = com.google.android.gms.wearable.Wearable.getChannelClient(this)
        scope.launch {
            try {
                val task = client.getInputStream(channel)
                // Blocking .await() via Guava adapter
                val inputStream: InputStream = awaitTask(task)
                readStateLoop(inputStream, channel)
            } catch (e: Exception) {
                Timber.e(e, "WearPhoneBridge: failed to open input stream")
            }
        }
    }

    override fun onChannelClosed(channel: Channel, closeReason: Int, appSpecificErrorCode: Int) {
        Timber.d("WearPhoneBridge: channel closed (reason=$closeReason)")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads state bytes from the phone until the channel is closed or an error
     * occurs.  Each byte maps to a [ExchangeState] ordinal.
     */
    private fun readStateLoop(stream: InputStream, channel: Channel) {
        stream.use { input ->
            val buf = ByteArray(1)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break          // channel closed by phone
                if (n == 0) continue        // spurious empty read

                val ordinal = buf[0].toInt() and 0xFF
                val state = ExchangeState.entries.getOrElse(ordinal) { ExchangeState.IDLE }
                Timber.d("WearPhoneBridge: received state $state (ordinal=$ordinal)")

                // Persist for tile rendering
                WearStateStore.update(applicationContext, state)

                // Ask the system to refresh the tile immediately
                AuraTileService.requestUpdate(applicationContext)
            }
        }
        Timber.d("WearPhoneBridge: stream ended for channel ${channel.nodeId}")
    }

    /** Suspending wrapper around a Guava [com.google.android.gms.tasks.Task]. */
    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        kotlinx.coroutines.tasks.await(task)
}
