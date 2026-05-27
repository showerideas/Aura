package com.showerideas.aura.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Lightweight state cache for the current exchange status on the
 * Wear OS device.
 *
 * [WearPhoneBridge] writes here when it receives a state update from the phone.
 * [AuraTileService] reads here when building the tile layout.
 *
 * Uses [SharedPreferences] (synchronous writes are fine for a single byte value
 * shared between two services on the same process).
 */
object WearStateStore {

    private const val PREFS_NAME = "aura_wear_state"
    private const val KEY_STATE  = "exchange_state_ordinal"

    fun currentState(context: Context): ExchangeState {
        val prefs = prefs(context)
        val ordinal = prefs.getInt(KEY_STATE, ExchangeState.IDLE.ordinal)
        return ExchangeState.entries.getOrElse(ordinal) { ExchangeState.IDLE }
    }

    fun update(context: Context, state: ExchangeState) {
        prefs(context).edit { putInt(KEY_STATE, state.ordinal) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Exchange states surfaced on the Wear OS tile.
 *
 * Ordinals are part of the wire protocol between phone and watch — do NOT
 * reorder entries without bumping the protocol version in [WearPhoneBridge].
 */
enum class ExchangeState(
    val displayText: String,
    val accentColor: Int        // ARGB
) {
    IDLE       ("Ready",        0xFF_00_BCD4.toInt()),
    BROADCASTING("Broadcasting", 0xFF_7C_4DFF.toInt()),
    VERIFYING  ("Verifying…",  0xFF_FF_AB_00.toInt()),
    COMPLETED  ("Done ✓",      0xFF_00_E5_76.toInt()),
    ERROR      ("Failed",      0xFF_FF_53_53.toInt()),
}
