package com.showerideas.aura.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.showerideas.aura.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Phase 6.11 — Quick Settings tile for AURA exchange.
 *
 * Tile state mirrors [NearbyExchangeService.sessionState]:
 *   - Active (blue, subtitle "Exchanging")   when a session is live
 *   - Inactive (grey, subtitle "Tap to start") when no session is running
 *
 * Tapping the tile when inactive starts the exchange flow by launching
 * MainActivity with [ACTION_START_EXCHANGE]. Tapping while active is a no-op.
 *
 * Register in AndroidManifest with BIND_QUICK_SETTINGS_TILE permission —
 * see the manifest entry added in this same PR.
 */
class AuraQsTileService : TileService() {

    companion object {
        /** Intent extra action broadcasted when tile is tapped */
        const val ACTION_START_EXCHANGE = "com.showerideas.aura.ACTION_START_EXCHANGE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        Timber.d("AuraQsTileService: onStartListening")
        serviceScope.launch {
            NearbyExchangeService.sessionState.collect { session ->
                updateTile(session != null)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Timber.d("AuraQsTileService: onStopListening")
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val session = NearbyExchangeService.sessionState.value
        if (session != null) {
            // Already in an active exchange — do nothing (tile is informational)
            Timber.d("AuraQsTileService: tap ignored — exchange already active")
            return
        }
        Timber.i("AuraQsTileService: launching exchange via tile tap")
        val intent = android.content.Intent(this, com.showerideas.aura.ui.MainActivity::class.java).apply {
            action = ACTION_START_EXCHANGE
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: startActivityAndCollapse requires PendingIntent
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(isActive: Boolean) {
        val tile = qsTile ?: return
        if (isActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.app_name)
            tile.contentDescription = "AURA exchange active"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = "Exchanging"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            tile.contentDescription = "AURA — tap to start exchange"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = "Tap to start"
            }
        }
        tile.updateTile()
    }
}
