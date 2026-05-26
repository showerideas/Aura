package com.showerideas.aura.wear

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import timber.log.Timber

/**
 * T35 — Wear OS SAS PIN confirmation activity.
 *
 * When the phone enters the VERIFYING state during an AURA exchange,
 * [WearPhoneBridge] relays the 6-digit SAS code and the exchange state byte
 * to the watch. This activity is launched on the watch so the user can
 * compare the codes and confirm or reject without looking at the phone.
 *
 * ## Launch contract
 * The phone side sends a Wearable Data Layer message on path
 * [CHANNEL_PATH_SAS] with the following payload:
 *   [6-byte ASCII SAS digits] [1-byte action: 0=confirm, 1=reject]
 *
 * The watch displays the SAS code and two large-touch-target buttons.
 * The result is sent back via a Data Layer message on [CHANNEL_PATH_SAS_ACK].
 *
 * ## Wear OS UI constraints
 * - No XML layouts — inline [View] construction for :wearos module isolation.
 * - Large circular buttons (56 dp) for glanceability and fat-finger accuracy.
 * - Ambient mode: activity finishes if not confirmed within [TIMEOUT_MS].
 */
class SasPinActivity : ComponentActivity() {

    companion object {
        /** Data Layer message path carrying the SAS code from phone to watch. */
        const val CHANNEL_PATH_SAS     = "/aura/sas-code"

        /** Data Layer ack path: watch → phone result. */
        const val CHANNEL_PATH_SAS_ACK = "/aura/sas-ack"

        /** Intent extra: the 6-digit SAS string. */
        const val EXTRA_SAS_CODE       = "sas_code"

        /** Ack byte values. */
        const val ACK_CONFIRMED: Byte = 0x01
        const val ACK_REJECTED : Byte = 0x00

        /** Auto-dismiss timeout — activity finishes if user doesn't respond. */
        const val TIMEOUT_MS = 30_000L
    }

    private var sasCode: String = "------"
    private val timeoutRunnable = Runnable {
        Timber.w("SasPinActivity: timed out — rejecting SAS")
        sendAck(ACK_REJECTED)
        finish()
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sasCode = intent.getStringExtra(EXTRA_SAS_CODE) ?: "------"
        setContentView(buildContentView())
        Timber.i("SasPinActivity: showing SAS code %s", sasCode)

        // Auto-reject after TIMEOUT_MS
        window.decorView.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(timeoutRunnable)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildContentView(): View {
        // Simple vertical layout: SAS label + code + Confirm + Reject
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }

        val labelView = TextView(this).apply {
            text    = getString(R.string.sas_wear_prompt)
            gravity = android.view.Gravity.CENTER
        }

        val codeView = TextView(this).apply {
            text      = sasCode
            textSize  = 28f
            gravity   = android.view.Gravity.CENTER
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }

        val confirmBtn = buildActionButton(
            label      = getString(R.string.sas_wear_confirm),
            colorResId = android.R.color.holo_green_light
        ) {
            sendAck(ACK_CONFIRMED)
            finish()
        }

        val rejectBtn = buildActionButton(
            label      = getString(R.string.sas_wear_reject),
            colorResId = android.R.color.holo_red_light
        ) {
            sendAck(ACK_REJECTED)
            finish()
        }

        layout.addView(labelView)
        layout.addView(codeView)
        layout.addView(confirmBtn)
        layout.addView(rejectBtn)
        return layout
    }

    private fun buildActionButton(label: String, colorResId: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setBackgroundColor(getColor(colorResId))
            setOnClickListener { onClick() }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
            ).apply { topMargin = 12 }
            layoutParams = lp
        }

    // -------------------------------------------------------------------------
    // Ack transmission
    // -------------------------------------------------------------------------

    private fun sendAck(ack: Byte) {
        // In production: send via Wearable.getMessageClient(this).sendMessage(...)
        // Stub for now — the phone listens on CHANNEL_PATH_SAS_ACK
        Timber.i("SasPinActivity: sending ack=%d for SAS %s", ack.toInt(), sasCode)
    }
}
