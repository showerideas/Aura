package com.showerideas.aura.wearos

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable

/**
 * Phase 7.2 — Phone-side Wear OS bridge using ChannelClient for
 * bidirectional state sync between AURA phone app and Wear OS companion.
 */
class WearPhoneBridge(private val context: Context) {

    companion object {
        const val CHANNEL_PATH = "/aura/state"
    }

    private val channelClient: ChannelClient by lazy {
        Wearable.getChannelClient(context)
    }

    /** Send the current session state to connected Wear OS device */
    fun sendState(stateJson: String) {
        // Implementation: open channel to watch, write state bytes
        // Full implementation requires Wear OS module dependency + Hilt scope
    }
}
