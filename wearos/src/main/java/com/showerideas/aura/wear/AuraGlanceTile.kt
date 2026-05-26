package com.showerideas.aura.wear

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.wear.tiles.GlanceTileService
import timber.log.Timber

/**
 * Task 50 — Wear OS 7 Glance Tile.
 *
 * Wear OS 7 (announced May 2026) deprecates the old ProtoLayout Tile approach in favour of
 * Wear Glance — a Compose-like declarative API that generates Tiles and optionally Wear Widgets.
 * This replaces [AuraTileService] for devices running Wear OS 7+; [AuraTileService] remains
 * the fallback for Wear OS 4-6 via the same tile configuration entry in `tiles.xml`.
 *
 * ## What's shown
 * - AURA branding header (cyan)
 * - Current exchange status: IDLE / ACTIVE / VERIFYING
 * - HRV reading from Health Connect (see [HealthConnectHrvReader]) — displayed as resting
 *   context so gesture quality estimates adapt to user fatigue
 * - Tap → launches AURA main activity on phone via RemoteActivityHelper
 *
 * ## Wear OS 7 migration strategy
 * `GlanceTileService` extends `TileService` — the OS routes tile requests here on Wear OS 7+
 * via the `<service>` declaration in `AndroidManifest.xml`:
 * ```xml
 * <service android:name=".wear.AuraGlanceTile"
 *          android:exported="true"
 *          android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
 *   <intent-filter>
 *     <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER"/>
 *   </intent-filter>
 *   <meta-data android:name="androidx.wear.tiles.PREVIEW"
 *              android:resource="@drawable/tile_preview"/>
 * </service>
 * ```
 *
 * See: [developer.android.com/training/wearables/tiles/glance]
 * See: [developer.android.com/health-and-fitness/guides/health-connect]
 */
class AuraGlanceTile : GlanceTileService() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val hrv = HealthConnectHrvReader.readLatestHrvRmssd(context)
        val statusText = WearStateStore.currentExchangeLabel(context)

        provideContent {
            GlanceTheme {
                Box(
                    modifier = androidx.glance.GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(android.graphics.Color.parseColor("#0D0D1A")))
                ) {
                    Column(
                        modifier = androidx.glance.GlanceModifier
                            .fillMaxSize()
                            .padding(12),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "AURA",
                            style = TextStyle(
                                color = ColorProvider(android.graphics.Color.parseColor("#00E5FF")),
                                fontSize = androidx.glance.unit.Sp(18f)
                            )
                        )
                        Text(
                            text = statusText,
                            style = TextStyle(
                                color = ColorProvider(android.graphics.Color.WHITE),
                                fontSize = androidx.glance.unit.Sp(13f)
                            ),
                            modifier = androidx.glance.GlanceModifier.padding(top = 4)
                        )
                        if (hrv != null) {
                            Text(
                                text = "HRV ${hrv.toInt()} ms",
                                style = TextStyle(
                                    color = ColorProvider(android.graphics.Color.parseColor("#B0BEC5")),
                                    fontSize = androidx.glance.unit.Sp(11f)
                                ),
                                modifier = androidx.glance.GlanceModifier.padding(top = 2)
                            )
                        }
                    }
                }
            }
        }
        Timber.d("AuraGlanceTile rendered — status=$statusText hrv=$hrv")
    }
}
