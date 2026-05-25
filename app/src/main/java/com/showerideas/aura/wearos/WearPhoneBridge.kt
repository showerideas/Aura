package com.showerideas.aura.wearos

import android.content.Context

/**
 * Phase 7.2 — Phone-side Wear OS bridge for bidirectional state sync.
 *
 * Full implementation requires GMS play-services-wearable dependency and
 * a companion Wear OS module. This scaffold provides the interface contract
 * so the phone-side code compiles in both GMS and FOSS flavors.
 *
 * TODO (Phase 7.2.2): inject into WearOS module once wearos/ Gradle module is created.
 */
class WearPhoneBridge(private val context: Context) {

    companion object {
        const val CHANNEL_PATH = "/aura/state"
    }

    /**
     * Send the current session state to connected Wear OS device.
     * No-op until the Wear OS companion module is wired up.
     */
    fun sendState(stateJson: String) {
        // Full implementation: open ChannelClient channel to watch, write state bytes.
        // Requires: gmsImplementation("com.google.android.gms:play-services-wearable:18.0.0")
        // in the wearos submodule only — not in the main app module (FOSS compatibility).
        android.util.Log.d("WearPhoneBridge", "sendState (stub): ${stateJson.take(80)}")
    }
}
