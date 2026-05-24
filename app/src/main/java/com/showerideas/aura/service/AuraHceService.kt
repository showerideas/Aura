package com.showerideas.aura.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import timber.log.Timber
import java.util.Base64

/**
 * NFC Host Card Emulation (HCE) service for AURA's tap-to-exchange path.
 *
 * Allows AURA to act as an NFC card when another AURA device taps it — the
 * peer reads the ephemeral ECDH public key and session UUID from the HCE
 * response without needing an NFC tag or Wi-Fi connection. Combined with
 * [NfcExchangeHelper] (which reads real tags), HCE ensures NFC bootstrap
 * works in BOTH directions regardless of which device acts as reader.
 *
 * ## AID
 * Proprietary AID: F0 415552 41 0001 ("F0" = proprietary range, "AURA", version 0001).
 * Registered in `res/xml/aura_apdu_service.xml`.
 *
 * ## APDU protocol
 * | Command (CLA INS P1 P2)   | Response                               |
 * |---------------------------|----------------------------------------|
 * | SELECT AID (00 A4 04 00)  | 90 00 (success)                        |
 * | GET KEY (00 CA 01 00)     | <pubkey-b64>\n<session-uuid> + 90 00   |
 * | Other                     | 6F 00 (application error)              |
 *
 * ## Key lifecycle
 * [MainActivity] calls [setLocalKey] in [onResume] (same time it calls
 * [NfcExchangeHelper.enable]), and clears it in [onPause]. Accessing
 * [localKeyPayload] with no key set returns 6A 82 (file not found).
 */
class AuraHceService : HostApduService() {

    companion object {
        // SELECT AID: CLA=00 INS=A4 P1=04 P2=00
        private val CMD_SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte()
        )
        // GET KEY: CLA=00 INS=CA P1=01 P2=00
        private val CMD_GET_KEY = byteArrayOf(
            0x00.toByte(), 0xCA.toByte(), 0x01.toByte(), 0x00.toByte()
        )

        private val STATUS_OK            = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_APP_ERROR     = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val STATUS_NO_KEY        = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        /**
         * Base64-encoded public key + "\n" + session UUID.
         * Set by [MainActivity.onResume] (same call site as NfcExchangeHelper.enable).
         * Cleared by [MainActivity.onPause].
         */
        @Volatile var localKeyPayload: String? = null

        /**
         * Convenience setter called from [MainActivity].
         * Encodes [pubKeyBytes] as Base64 and stores "base64\nsessionUuid".
         */
        fun setLocalKey(pubKeyBytes: ByteArray, sessionUuid: String) {
            localKeyPayload = Base64.getEncoder().encodeToString(pubKeyBytes) + "\n" + sessionUuid
            Timber.d("AuraHceService: local key set (session=$sessionUuid)")
        }

        /** Clear the local key when AURA moves to background. */
        fun clearLocalKey() {
            localKeyPayload = null
            Timber.d("AuraHceService: local key cleared")
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_APP_ERROR

        return when {
            isCommand(commandApdu, CMD_SELECT_AID) -> {
                Timber.d("AuraHceService: SELECT AID received")
                STATUS_OK
            }
            isCommand(commandApdu, CMD_GET_KEY) -> {
                val payload = localKeyPayload
                if (payload == null) {
                    Timber.w("AuraHceService: GET KEY received but no local key set")
                    STATUS_NO_KEY
                } else {
                    Timber.d("AuraHceService: GET KEY — responding with local key")
                    val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                    payloadBytes + STATUS_OK
                }
            }
            else -> {
                Timber.w("AuraHceService: unknown APDU command %s",
                    commandApdu.take(4).joinToString(" ") { "%02X".format(it) })
                STATUS_APP_ERROR
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        Timber.d("AuraHceService: deactivated (reason=$reason)")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if [apdu] starts with the same CLA+INS+P1+P2 bytes as [cmd].
     * Ignores Lc / data field so the match works for both short and extended APDUs.
     */
    private fun isCommand(apdu: ByteArray, cmd: ByteArray): Boolean {
        if (apdu.size < cmd.size) return false
        return apdu.take(cmd.size).toByteArray().contentEquals(cmd)
    }
}
