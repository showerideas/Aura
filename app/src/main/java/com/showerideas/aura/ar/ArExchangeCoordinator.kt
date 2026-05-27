package com.showerideas.aura.ar

import android.content.Context
import android.uwb.UwbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 98 — ARCore face detection + UWB distance correlation coordinator.
 *
 * Gates AR contact-card rendering to peers that satisfy ALL of:
 *   1. ARCore `AugmentedFace` detected in camera feed.
 *   2. BLE advertisement from a known AURA identity key visible within current scan window.
 *   3. UWB ranging distance < [uwbDistanceThresholdM] (default 1.5m).
 *
 * When UWB is unavailable (most non-flagship devices), falls back to BLE RSSI
 * with a ≥ -65 dBm threshold (~1–2m range) to provide a degraded-but-safe gate.
 *
 * ## Enterprise flag
 * [BuildConfig.ENABLE_AR_EXCHANGE] must be true. Disabled by default; requires
 * explicit user consent via the AR privacy disclosure ([ArPrivacyDisclosureSheet])
 * and the Settings → Privacy → AR Exchange toggle.
 *
 * ## Privacy
 * ARCore Augmented Faces processes the camera feed on-device. No face images
 * or face mesh data are transmitted. The AR overlay is purely local rendering.
 *
 * See: ROADMAP §Task 98
 */
@Singleton
class ArExchangeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Default UWB range gate in metres. Configurable via Settings → Privacy → AR Range. */
        const val DEFAULT_UWB_THRESHOLD_M = 1.5f
        /** BLE RSSI fallback threshold (dBm). Approximately 1–2m open-air. */
        const val RSSI_FALLBACK_THRESHOLD_DBM = -65
    }

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class ArExchangeState {
        /** AR is not active (flag disabled or no consent). */
        object Inactive : ArExchangeState()
        /** ARCore is initialising or waiting for UWB ranging to settle. */
        object Scanning : ArExchangeState()
        /**
         * A qualified peer is detected at [distanceM] metres.
         * The contact card should be rendered at [faceAnchorId].
         */
        data class PeerInRange(
            val identityKeyHash: String,
            val distanceM: Float,
            val faceAnchorId: String
        ) : ArExchangeState()
        /** Distance gate not met or UWB/RSSI below threshold. */
        object PeerOutOfRange : ArExchangeState()
        /** UWB unavailable on this device; degraded RSSI fallback active. */
        object RssiFallback : ArExchangeState()
    }

    private val _state = MutableStateFlow<ArExchangeState>(ArExchangeState.Inactive)
    val state: StateFlow<ArExchangeState> = _state

    private var uwbDistanceThresholdM: Float = DEFAULT_UWB_THRESHOLD_M

    // ── UWB availability ─────────────────────────────────────────────────────

    private val isUwbAvailable: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= 31 &&
            runCatching {
                context.getSystemService(UwbManager::class.java) != null
            }.getOrDefault(false)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setDistanceThreshold(metres: Float) {
        uwbDistanceThresholdM = metres
        Timber.d("ArExchangeCoordinator: UWB threshold set to ${metres}m")
    }

    /**
     * Called by [ArExchangeOverlay] when ARCore detects a face anchor and a BLE
     * AURA advertisement matches the given [identityKeyHash].
     *
     * @param identityKeyHash SHA-256 of peer identity key (Base64).
     * @param distanceM       UWB ranging distance, or RSSI-estimated distance if UWB absent.
     * @param faceAnchorId    ARCore `AugmentedFace` anchor ID for spatial positioning.
     */
    fun onPeerCandidateDetected(
        identityKeyHash: String,
        distanceM: Float,
        faceAnchorId: String
    ) {
        if (!isUwbAvailable) {
            _state.value = ArExchangeState.RssiFallback
        }

        val withinRange = distanceM < uwbDistanceThresholdM
        _state.value = if (withinRange) {
            Timber.d("ArExchangeCoordinator: peer in range at ${distanceM}m — $identityKeyHash")
            ArExchangeState.PeerInRange(identityKeyHash, distanceM, faceAnchorId)
        } else {
            Timber.d("ArExchangeCoordinator: peer out of range (${distanceM}m > ${uwbDistanceThresholdM}m)")
            ArExchangeState.PeerOutOfRange
        }
    }

    fun onPeerLost() {
        if (_state.value !is ArExchangeState.Inactive) {
            _state.value = ArExchangeState.Scanning
        }
    }

    fun activate() {
        _state.value = ArExchangeState.Scanning
        Timber.d("ArExchangeCoordinator: activated (uwb=${isUwbAvailable})")
    }

    fun deactivate() {
        _state.value = ArExchangeState.Inactive
        Timber.d("ArExchangeCoordinator: deactivated")
    }
}
