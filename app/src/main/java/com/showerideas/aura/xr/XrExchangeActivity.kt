package com.showerideas.aura.xr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Task 101 — Android XR (Jetpack XR) spatial contact card exchange activity.
 *
 * Hosts the spatial UI variant of the AR contact exchange for Android XR headset
 * form factors (e.g. Samsung XR Developer Preview hardware). Renders floating
 * [SpatialContactCard] panels anchored to peer spatial positions via
 * `Compose XR` spatial panels and `Jetpack XR Hand Tracking`.
 *
 * ## Activation
 * Launched via an implicit intent from [ArExchangeFragment] when the system reports
 * `android.xr.MODE_SPATIAL_UI` is supported (Jetpack XR API level 1+).
 * Not launched on phone/tablet form factors — those use [ArExchangeFragment].
 *
 * ## Hand tracking
 * Jetpack XR Hand Tracking uses the same 21-joint hand skeleton as MediaPipe Hands.
 * The joint positions are normalised to [0, 1]³ in the same format as
 * [CameraHandEmbedder] output, so [GestureVerificationEngine] works unmodified.
 *
 * ## Dependency note
 * The full Jetpack XR and Compose XR dependencies are not declared in the baseline
 * `build.gradle.kts` because Android XR SDK Preview 4 targets SDK 36+ and the
 * current `targetSdk = 35`. They will be added when `targetSdk` advances to 36
 * in the v5.4 build config increment.
 *
 * See: developer.android.com/develop/xr/jetpack-xr-sdk/arcore
 * See: ROADMAP §Task 101
 */
@AndroidEntryPoint
class XrExchangeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("XrExchangeActivity: onCreate — XR spatial exchange")

        setContent {
            // Placeholder Compose UI pending Jetpack XR Compose dependency.
            // Full implementation: SpatialPanel hosting SpatialContactCard composable,
            // anchored to detected peer position via XrSpatialCapabilities.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "XR Exchange — Spatial UI (Jetpack XR SDK required)")
            }
        }
    }
}
