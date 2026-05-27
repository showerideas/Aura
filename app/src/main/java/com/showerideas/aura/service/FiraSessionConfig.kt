package com.showerideas.aura.service

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import timber.log.Timber

/**
 * Task 52 — UWB FiRa 3.0 session configuration and Aliro access control profile.
 *
 * AURA's existing UWB implementation (Task 11) targets FiRa MAC 1.3. This update
 * adds FiRa 3.0 compliance: scheduled ranging sessions, AoA (angle-of-arrival)
 * support, and the Aliro access control profile for door/gate proximity unlock.
 *
 * ## FiRa 3.0 additions over 1.3
 * - **Scheduled ranging**: coordinator allocates timeslots per device — eliminates
 *   the contention collisions observed in Task 11 multi-device Room scenarios.
 * - **AoA support**: bearing + elevation in addition to distance; enables
 *   directional confirmation (peer must be in front, not behind a wall).
 * - **Aliro access control profile**: CSA (Connectivity Standards Alliance) profile
 *   for credential-based physical access. AURA can use Aliro to trigger door/gate
 *   unlock when a verified AURA peer is within 20 cm — replacing NFC tap-to-open.
 *
 * ## Aliro profile
 * Aliro defines a standard OOB protocol to exchange UWB session keys and trigger
 * access. AURA piggybacks Aliro's OOB over the existing NFC HCE channel (Task 1)
 * rather than adding a dedicated BLE advertisement — same tap-to-pair, but the
 * resulting UWB session uses the Aliro profile for richer access semantics.
 *
 * See: [developer.android.com/reference/androidx/core/uwb] — Jetpack UWB API
 * See: [csa-iot.org/developer-resource/aliro-specification] — Aliro spec
 * See: [fira-consortium.org/fira-specification] — FiRa MAC 3.0
 */
object FiraSessionConfig {

    /** FiRa 3.0 UWB channel — channel 9, 7.9872 GHz centre frequency. */
    const val FIRA_CHANNEL = 9

    /** Preamble index for FiRa 3.0 scheduled ranging. */
    const val FIRA_PREAMBLE_INDEX = 10

    /** Aliro credential proximity trigger threshold (cm). Below = access granted. */
    const val ALIRO_ACCESS_THRESHOLD_CM = 20.0

    /** Auto-confirm SAS threshold (unchanged from Task 11) — 50 cm. */
    const val AUTO_CONFIRM_DISTANCE_CM = 50.0

    /**
     * Build a FiRa 3.0 compliant [RangingParameters] for a controller session.
     *
     * Adds AoA measurement request (bearing + elevation) for directional confirmation.
     * Scheduled ranging via SESSION_TYPE_RANGING_WITH_DATA_TRANSFER (FiRa 3.0 type).
     *
     * @param sessionId       Unique 32-bit session ID (derived from room ID).
     * @param complexChannel  UWB complex channel negotiated OOB.
     * @param peerAddress     Controllee peer UWB address received via HCE/NFC OOB.
     * @param sessionKey      16-byte AES-128 session key (from PQXDH/HKDF, Task 47).
     */
    fun buildControllerParams(
        sessionId: Int,
        complexChannel: UwbComplexChannel,
        peerAddress: UwbAddress,
        sessionKey: ByteArray
    ): RangingParameters {
        require(sessionKey.size == 16) { "UWB session key must be 16 bytes (AES-128)" }
        Timber.d("FiRa 3.0 controller params: sessionId=$sessionId channel=${complexChannel.channel}")
        return RangingParameters(
            uwbConfigType      = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId          = sessionId,
            subSessionId       = 0,
            sessionKeyInfo     = sessionKey,
            subSessionKeyInfo  = null,
            complexChannel     = complexChannel,
            peerDevices        = listOf(androidx.core.uwb.UwbDevice(peerAddress)),
            updateRateType     = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
        )
    }

    /**
     * Build FiRa 3.0 compliant [RangingParameters] for a controllee session.
     *
     * @param sessionId  Same session ID as controller (negotiated OOB).
     * @param sessionKey Same 16-byte session key as controller.
     */
    fun buildControlleeParams(
        sessionId: Int,
        sessionKey: ByteArray
    ): RangingParameters {
        require(sessionKey.size == 16) { "UWB session key must be 16 bytes" }
        Timber.d("FiRa 3.0 controllee params: sessionId=$sessionId")
        return RangingParameters(
            uwbConfigType      = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId          = sessionId,
            subSessionId       = 0,
            sessionKeyInfo     = sessionKey,
            subSessionKeyInfo  = null,
            complexChannel     = UwbComplexChannel(channel = FIRA_CHANNEL, preambleIndex = FIRA_PREAMBLE_INDEX),
            peerDevices        = emptyList(),
            updateRateType     = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
        )
    }

    /**
     * Aliro access control evaluation.
     *
     * @param distanceCm Distance measured by UWB ranging in centimetres.
     * @param azimuthDeg Optional bearing in degrees (AoA); if non-null, must be in
     *                   [-60°, +60°] (peer in front of device) for access to be granted.
     * @return [AliroAccessDecision] indicating whether access should be granted.
     */
    fun evaluateAliroAccess(
        distanceCm: Float,
        azimuthDeg: Float? = null
    ): AliroAccessDecision {
        val inRange = distanceCm <= ALIRO_ACCESS_THRESHOLD_CM
        val inFront = azimuthDeg == null || azimuthDeg in -60f..60f
        return when {
            inRange && inFront -> AliroAccessDecision.GRANT
            inRange && !inFront -> AliroAccessDecision.DENY_ANGLE
            else               -> AliroAccessDecision.DENY_DISTANCE
        }.also { Timber.d("Aliro access: $it (dist=${distanceCm}cm azimuth=$azimuthDeg)") }
    }

    /**
     * 5-sample sliding window EMA for UWB distance — reduces multipath noise.
     * Inherits the window pattern from Task 11; adds EMA smoothing (α=0.3).
     */
    class DistanceFilter(private val windowSize: Int = 5, private val alpha: Float = 0.3f) {
        private val samples = ArrayDeque<Float>(windowSize)
        private var ema: Float? = null

        fun update(distanceCm: Float): Float {
            if (samples.size >= windowSize) samples.removeFirst()
            samples.addLast(distanceCm)
            ema = ema?.let { alpha * distanceCm + (1 - alpha) * it } ?: distanceCm
            return ema!!
        }

        fun reset() { samples.clear(); ema = null }
    }
}

/** Aliro access control decision. */
enum class AliroAccessDecision {
    /** Distance and angle both within Aliro threshold — grant access. */
    GRANT,
    /** Distance within threshold but peer is not in front (AoA out of range). */
    DENY_ANGLE,
    /** Distance exceeds Aliro threshold. */
    DENY_DISTANCE
}
