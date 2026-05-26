package com.showerideas.aura.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.car.app.connection.CarConnection
import timber.log.Timber

/**
 * Phase G1 — Transparent trampoline Activity for Android Auto voice actions.
 *
 * The Android Assistant routes voice commands (e.g. "start an AURA exchange")
 * to this Activity via an ACTION_SEARCH intent. On receipt we detect whether
 * the device is currently projected to a head unit and, if so, forward the
 * intent to [AuraCarAppService] via the Car App Library session API.
 *
 * If no projection is active (e.g. the user invoked this via the phone launcher),
 * we simply finish immediately — the main app handles exchange initiation directly.
 *
 * ## Voice command registration
 * The intent-filter in AndroidManifest.xml registers this Activity for:
 *   android.intent.action.SEARCH — standard Google Assistant query action
 *
 * Future: register a custom voice shortcut via the Google Assistant App Actions
 * BII (Built-In Intent) `actions.intent.OPEN_APP_FEATURE` with the featureReference
 * set to "exchange" for tighter Assistant integration.
 */
class AuraVoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        Timber.i("AuraVoiceActivity: received action=%s", action)

        when (action) {
            Intent.ACTION_SEARCH,
            Intent.ACTION_MAIN -> handleVoiceTrigger()
            else -> {
                Timber.w("AuraVoiceActivity: unrecognised action %s — finishing", action)
                finish()
            }
        }
    }

    private fun handleVoiceTrigger() {
        // Check if the phone is currently projected to a head unit.
        // CarConnection.TYPE_PROJECTION == 1 means Projection (Android Auto).
        // CarConnection.TYPE_NOT_CONNECTED == 0 means no head unit.
        CarConnection(this).type.observe(this) { connectionType ->
            if (connectionType == CarConnection.CONNECTION_TYPE_PROJECTION) {
                Timber.i("AuraVoiceActivity: projected to head unit — routing to car session")
                // The Car App Library session is already running in AuraCarAppService.
                // Sending a broadcast lets the session know to navigate to AdvertisingScreen.
                sendBroadcast(Intent(ACTION_VOICE_START_EXCHANGE).apply {
                    setPackage(packageName)
                })
            } else {
                Timber.i("AuraVoiceActivity: not projected — finishing (phone handles exchange)")
            }
            finish()
        }
    }

    companion object {
        /** Broadcast action sent to the Car App session to trigger exchange. */
        const val ACTION_VOICE_START_EXCHANGE = "com.showerideas.aura.auto.VOICE_START_EXCHANGE"
    }
}
