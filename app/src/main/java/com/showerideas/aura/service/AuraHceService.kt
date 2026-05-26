package com.showerideas.aura.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 10.1 — NFC Host Card Emulation service.
 *
 * Allows AURA to act as an NFC card so a peer AURA device (acting as reader)
 * can retrieve our ephemeral ECDH public key via a single tap — without the
 * initiating device needing foreground dispatch or a physical NFC tag.
 *
 * ## APDU protocol
 *
 * | Step | APDU                                             | Response            |
 * |------|--------------------------------------------------|---------------------|
 * | 1    | SELECT AID F0415552410001 (CLA=00, INS=A4, ...)  | `9000` (SW OK)      |
 * | 2    | GET KEY (CLA=00, INS=B0, P1=00, P2=00, Le=00)   | payload + `9000`    |
 *
 * Payload format (GET KEY response body, UTF-8):
 * ```
 * <sessionUuid>
<base64-encoded raw public-key bytes>
 * ```
 *
 * This mirrors [NfcExchangeHelper.buildNdefMessage] so both reader and card
 * paths produce and consume the same payload structure.
 *
 * ## Key lifecycle
 * Call [setLocalKey] before the user enters an exchange session; call
 * [clearLocalKey] when the session ends or the user cancels. The companion
 * object uses an [AtomicReference] so updates from any thread are safe.
 *
 * ## Error SWs
 * | SW     | Meaning                                           |
 * |--------|---------------------------------------------------|
 * | `9000` | Success                                           |
 * | `6A82` | File not found — no key set (GET KEY before SELECT or after clearLocalKey) |
 * | `6D00` | Instruction not supported                         |
 * | `6F00` | Internal error                                    |
 */
class AuraHceService : HostApduService() {

    // ── Private state ──────────────────────────────────────────────────────

    /** True once the reader has successfully selected our AID in this session. */
    private var isSelected = false

    // ── Companion: static key store ────────────────────────────────────────

    companion object {
        private val AID = byteArrayOf(
            0xF0.toByte(), 0x41, 0x55, 0x52, 0x41, 0x00, 0x01
        )

        // Status words
        private val SW_OK              = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND  = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_UNSUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_INTERNAL_ERROR  = byteArrayOf(0x6F.toByte(), 0x00)

        // APDU instruction bytes
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val INS_GET_KEY: Byte = 0xB0.toByte()

        /**
         * Atomic storage for the session key payload.
         * Format: "$sessionUuid\n<base64 pubkey bytes>"
         * Null when no key is set.
         */
        private val keyPayload = AtomicReference<String?>(null)

        /**
         * Store the local ephemeral ECDH public key for HCE retrieval.
         *
         * @param sessionUuid UUID for this exchange session (de-duplicates re-taps).
         * @param pubKeyBytes Raw public-key bytes (e.g., X.509 SubjectPublicKeyInfo DER
         *                    or uncompressed EC point bytes).
         */
        fun setLocalKey(sessionUuid: String, pubKeyBytes: ByteArray) {
            val encoded = Base64.getEncoder().encodeToString(pubKeyBytes)
            keyPayload.set("$sessionUuid\n$encoded")
            Timber.d("AuraHceService: key registered for session=$sessionUuid")
        }

        /** Remove the key from HCE storage. Call when a session ends or is cancelled. */
        fun clearLocalKey() {
            keyPayload.set(null)
            Timber.d("AuraHceService: key cleared")
        }

        /** @return the current payload string, or null if no key is set. */
        fun getLocalKey(): String? = keyPayload.get()
    }

    // ── HostApduService ────────────────────────────────────────────────────

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.isEmpty()) return SW_INS_UNSUPPORTED

        return when (val ins = commandApdu.getOrNull(1)) {
            INS_SELECT -> handleSelect(commandApdu)
            INS_GET_KEY -> handleGetKey()
            else -> {
                Timber.w("AuraHceService: unsupported INS 0x%02X", ins ?: 0xFF)
                SW_INS_UNSUPPORTED
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        isSelected = false
        Timber.d("AuraHceService: deactivated (reason=%d)", reason)
    }

    // ── Private APDU handlers ──────────────────────────────────────────────

    private fun handleSelect(apdu: ByteArray): ByteArray {
        // Minimal SELECT AID parsing:
        // CLA(1) INS(1) P1(1) P2(1) Lc(1) AID(Lc) [Le(0..1)]
        if (apdu.size < 5) return SW_INS_UNSUPPORTED
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_INS_UNSUPPORTED

        val aidBytes = apdu.sliceArray(5 until 5 + lc)
        return if (aidBytes.contentEquals(AID)) {
            isSelected = true
            Timber.d("AuraHceService: AID selected")
            SW_OK
        } else {
            Timber.w("AuraHceService: unknown AID selected — rejecting")
            SW_FILE_NOT_FOUND
        }
    }

    private fun handleGetKey(): ByteArray {
        val payload = keyPayload.get()
        if (payload == null) {
            Timber.w("AuraHceService: GET KEY with no key set")
            return SW_FILE_NOT_FOUND
        }
        return try {
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            payloadBytes + SW_OK
        } catch (e: Exception) {
            Timber.e(e, "AuraHceService: error building GET KEY response")
            SW_INTERNAL_ERROR
        }
    }
}
