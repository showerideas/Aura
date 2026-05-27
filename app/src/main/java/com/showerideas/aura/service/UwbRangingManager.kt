package com.showerideas.aura.service

import android.content.Context
import android.util.Log
import androidx.core.uwb.RangingCapabilities
import androidx.core.uwb.RangingMeasurement
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UWB proximity confirmation for AURA exchange sessions.
 *
 * Uses the [UwbManager] (Android 12+, Pixel 6+ and select OEM devices) to measure
 * the physical distance between two peers. When the measured distance drops below
 * [AUTO_CONFIRM_DISTANCE_CM], the SAS verification step is automatically confirmed
 * — physical proximity is itself a sufficient MITM defence at < 50 cm.
 *
 * Protocol integration
 * 1. Both peers exchange their [UwbAddress] and [UwbComplexChannel] via the existing
 *    wire-protocol BYTES payload (MSG_TYPE_UWB_OOB = 0x06) before ranging starts.
 * 2. The controller peer starts ranging; the controllee responds.
 * 3. [rangingState] emits [RangingState.Confirmed] when distance < 50 cm.
 * 4. [NearbyExchangeService] observes [rangingState] and auto-confirms SAS
 *    when Confirmed is emitted.
 *
 * Fallback
 * If the device does not support UWB ([isUwbAvailable] == false), the manager does
 * nothing. SAS confirmation falls back to the manual verbal PIN comparison path.
 *
 * Thread safety
 * All ranging callbacks are delivered on a background thread; [_rangingState] is
 * a [MutableStateFlow] (thread-safe).
 */
@Singleton
class UwbRangingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Below this distance the SAS step is auto-confirmed. Unit: centimetres. */
        const val AUTO_CONFIRM_DISTANCE_CM = 50.0

        private const val TAG = "UwbRangingManager"
        private const val UWB_CHANNEL = 9
        private const val UWB_PREAMBLE_INDEX = 11
    }

    // Ranging state

    sealed class RangingState {
        object Idle : RangingState()
        object Ranging : RangingState()
        /** Distance measured and below the auto-confirm threshold. */
        data class Confirmed(val distanceCm: Double) : RangingState()
        /** Distance measured but still too far. */
        data class TooFar(val distanceCm: Double) : RangingState()
        /** UWB not available on this device. */
        object Unavailable : RangingState()
        data class Error(val cause: Throwable) : RangingState()
    }

    private val _rangingState = MutableStateFlow<RangingState>(RangingState.Idle)
    val rangingState: StateFlow<RangingState> = _rangingState.asStateFlow()

    // Device capability

    /** True if the device hardware supports UWB ranging. */
    val isUwbAvailable: Boolean by lazy {
        try {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                context.packageManager.hasSystemFeature("android.hardware.uwb")
        } catch (e: Exception) {
            false
        }
    }

    // Ranging lifecycle

    private var controllerScope: UwbControllerSessionScope? = null

    /**
     * Start UWB ranging as the controller peer.
     *
     * @param peerAddress   The peer's [UwbAddress] received via out-of-band bytes.
     * @param scope         Coroutine scope that owns the ranging session (cancelled on service stop).
     */
    suspend fun startRangingAsController(
        peerAddress: UwbAddress,
        scope: CoroutineScope
    ) {
        if (!isUwbAvailable) {
            Timber.w("UWB not available on this device — skipping proximity confirmation")
            _rangingState.value = RangingState.Unavailable
            return
        }
        try {
            val uwbManager = UwbManager.createInstance(context)
            val controllerSession = uwbManager.controllerSessionScope()
            controllerScope = controllerSession

            val localAddress = controllerSession.localAddress
            Timber.d("UWB controller session started, local=$localAddress peer=$peerAddress")

            val params = RangingParameters(
                uwbConfigType     = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId         = 0x12345678,
                subSessionId      = 0,
                sessionKeyInfo    = null,
                subSessionKeyInfo = null,
                complexChannel    = UwbComplexChannel(UWB_CHANNEL, UWB_PREAMBLE_INDEX),
                peerDevices       = listOf(androidx.core.uwb.UwbDevice.createForAddress(peerAddress.address)),
                updateRateType    = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
            )

            _rangingState.value = RangingState.Ranging

            controllerSession.prepareSession(params)
                .catch { e ->
                    Timber.e(e, "UWB ranging error")
                    _rangingState.value = RangingState.Error(e)
                }
                .onEach { result -> handleRangingResult(result) }
                .launchIn(scope)

        } catch (e: Exception) {
            Timber.e(e, "Failed to start UWB ranging session")
            _rangingState.value = RangingState.Error(e)
        }
    }

    /**
     * Stop the active ranging session and reset state.
     * Safe to call if no session is active.
     */
    fun stopRanging() {
        controllerScope = null
        _rangingState.value = RangingState.Idle
        Timber.d("UWB ranging stopped")
    }

    // Local address for OOB exchange

    /**
     * Return the local [UwbAddress] to be shared with the peer via OOB bytes.
     * Returns null if UWB is unavailable or the session hasn't been created yet.
     */
    suspend fun localAddress(): UwbAddress? {
        if (!isUwbAvailable) return null
        return try {
            val uwbManager = UwbManager.createInstance(context)
            val session = uwbManager.controllerSessionScope()
            controllerScope = session
            session.localAddress
        } catch (e: Exception) {
            Timber.w(e, "Could not obtain UWB local address")
            null
        }
    }

    // Private: interpret ranging result

    private fun handleRangingResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                val distanceM = result.position.distance?.value ?: return
                val distanceCm = distanceM * 100.0
                Timber.d("UWB distance: %.1f cm".format(distanceCm))
                _rangingState.value = if (distanceCm < AUTO_CONFIRM_DISTANCE_CM) {
                    Timber.i("UWB auto-confirm: %.1f cm < ${AUTO_CONFIRM_DISTANCE_CM} cm".format(distanceCm))
                    RangingState.Confirmed(distanceCm)
                } else {
                    RangingState.TooFar(distanceCm)
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                Timber.w("UWB peer disconnected")
                _rangingState.value = RangingState.Idle
            }
            else -> Unit
        }
    }
}
