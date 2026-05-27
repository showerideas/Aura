package com.showerideas.aura.service

/**
 * Task 66 — BLE 6.2 SCI metrics captured per GATT session.
 *
 * Stored in [ExchangeAuditLog.bleMetrics] as a JSON blob (Moshi serialized).
 * SCI = Shorter Connection Interval, a BLE 6.2 feature that reduces the
 * minimum LE connection interval from 30 ms (BLE 5.x) to 375 µs.
 *
 * ## Compatibility matrix
 * | Initiator  | Responder  | Outcome                        |
 * |------------|------------|--------------------------------|
 * | BLE 6.2+   | BLE 6.2+   | Full SCI negotiated (DCK prio) |
 * | BLE 6.2+   | BLE 5.x    | Fall back to HIGH priority      |
 * | BLE 5.x    | BLE 5.x    | Existing behavior unchanged     |
 *
 * See: [argenox.com — BLE 6.2 deep-dive, SCI negotiation procedure]
 * See: [NordicSemiconductor/Android-BLE-Library — CONNECTION_PRIORITY_DCK]
 */
data class BleSessionMetrics(
    /** Negotiated MTU in bytes (517 max on Android). */
    val mtu: Int = 0,
    /** Connection interval in milliseconds post-negotiation (best available). */
    val connectionIntervalMs: Float = 0f,
    /** True when BLE 6.2 SCI was successfully negotiated on both sides. */
    val sci: Boolean = false,
    /** Wall-clock duration of the full exchange (connect → disconnect) in ms. */
    val totalExchangeDurationMs: Long = 0L,
    /** Device address (anonymized — last 3 octets zeroed for audit log). */
    val deviceAddressAnon: String = "",
) {
    companion object {
        /**
         * CONNECTION_PRIORITY_DCK is the BLE 6.2 ultra-low-latency mode constant.
         * Added in Android 16 (API 36). Targeting older compileSdk: use raw int 4.
         * Platform rejects gracefully if the hardware doesn't support SCI.
         *
         * Note: [BluetoothGatt.CONNECTION_PRIORITY_HIGH] = 1 (11.25–15 ms interval)
         *       CONNECTION_PRIORITY_DCK                  = 4 (375 µs–4 ms interval)
         */
        const val CONNECTION_PRIORITY_DCK = 4

        /**
         * Android feature flag for BLE 6.2 SCI support.
         * Only available on API 36+ devices with BLE 6.2-capable chipsets.
         * Pixel 9 series and Galaxy S25 series both ship BLE 6.2.
         */
        const val FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING =
            "android.hardware.bluetooth.le.channel_sounding"

        /** Minimum API level for SCI negotiation via CONNECTION_PRIORITY_DCK. */
        const val SCI_MIN_API = 36

        /**
         * Anonymize a BLE MAC address for audit logging:
         * replaces the last 3 octets (device-specific portion) with zeros.
         * OUI prefix (first 3 octets) is kept for transport diagnostics.
         */
        fun anonymizeAddress(address: String): String {
            val parts = address.split(":")
            if (parts.size != 6) return "??:??:??:00:00:00"
            return "${parts[0]}:${parts[1]}:${parts[2]}:00:00:00"
        }
    }
}
