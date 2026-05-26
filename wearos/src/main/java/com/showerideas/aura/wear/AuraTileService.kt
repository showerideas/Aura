package com.showerideas.aura.wear

import android.content.Context
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import timber.log.Timber

/**
 * Phase 7.2 — AURA exchange status tile for the Wear OS tile carousel.
 *
 * Displays:
 *   • "AURA" header in cyan
 *   • Current [ExchangeState] text (IDLE / BROADCASTING / VERIFYING / etc.)
 *   • A coloured dot whose tint matches the state accent colour
 *
 * [WearPhoneBridge] calls [requestUpdate] whenever the phone sends a new
 * exchange state byte, causing the system to re-call [onTileRequest].
 *
 * ## Tile refresh policy
 * The tile sets a 5-minute freshness hint so it stays current without
 * draining the watch battery.  Real-time updates arrive via [requestUpdate].
 */
class AuraTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        /** Tile freshness window — system may refresh up to this often without a push. */
        private const val FRESHNESS_MS = 5 * 60 * 1_000L

        /** Call from any context to ask the system to refresh the tile immediately. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(AuraTileService::class.java)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // TileService contract
    // -------------------------------------------------------------------------

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<Tile> = scope.future {
        val state = WearStateStore.currentState(applicationContext)
        Timber.d("AuraTileService: building tile for state=$state")
        buildTile(state)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Layout builder
    // -------------------------------------------------------------------------

    private fun buildTile(state: ExchangeState): Tile {
        val layout = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // Header: "AURA"
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("AURA")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(14f))
                            .setColor(ColorBuilders.argb(0xFF_00_BC_D4.toInt()))
                            .setBold(true)
                            .build()
                    )
                    .build()
            )
            // Spacer
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(8f))
                    .build()
            )
            // State dot — coloured circle matching the accent
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.dp(16f))
                    .setHeight(DimensionBuilders.dp(16f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(ColorBuilders.argb(state.accentColor))
                                    .setCorner(
                                        ModifiersBuilders.Corner.Builder()
                                            .setRadius(DimensionBuilders.dp(8f))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            // Spacer
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(8f))
                    .build()
            )
            // State label
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(state.displayText)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(12f))
                            .setColor(ColorBuilders.argb(0xFF_FF_FF_FF.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(layout)
                            .build()
                    )
                    .build()
            )
            .build()

        return Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .build()
    }
}
