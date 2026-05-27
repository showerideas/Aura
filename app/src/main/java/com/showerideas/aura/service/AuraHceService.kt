package com.showerideas.aura.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * NFC Host Card Emulation service with APDU chaining.
 *
 * Extends the v2.0.1 implementation with:
 *  - APDU chaining for payloads >255 bytes (SW=61XX chain protocol)
 *  - GET RESPONSE (INS=C0) handler for chained reads
 *  - [setLocalKeyBytes] with session nonce for Room session bootstrap
 *
 * APDU protocol (with chaining)
 *
 * | Step | APDU                           | Response                    |
 * |------|--------------------------------|-----------------------------|
 * | 1    | SELECT AID F0415552410001      | `9000`                      |
 * | 2    | GET KEY (INS=B0)               | payload[0..255] + `61XX`    |
 * | 3+   | GET RESPONSE (INS=C0, Le=XX)   | next chunk + `61XX`/`9000` |
 */
class AuraHceService : HostApduService() {

    private var isSelected = false

    /** Buffer for chained response — populated when payload exceeds 255 bytes. */
    private val chainBuffer = AtomicReference<ByteArray?>(null)
    private val chainOffset = AtomicInteger(0)

    companion object {
        private val AID = byteArrayOf(
            0xF0.toByte(), 0x41, 0x55, 0x52, 0x41, 0x00, 0x01
        )

        private val SW_OK              = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND  = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_UNSUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_INTERNAL_ERROR  = byteArrayOf(0x6F.toByte(), 0x00)

        private const val INS_SELECT: Byte       = 0xA4.toByte()
        private const val INS_GET_KEY: Byte      = 0xB0.toByte()
        private const val INS_GET_RESPONSE: Byte = 0xC0.toByte()
        private const val MAX_CHUNK              = 255

        private val keyPayload = AtomicReference<String?>(null)

        fun setLocalKey(sessionUuid: String, pubKeyBytes: ByteArray) {
            val encoded = Base64.getEncoder().encodeToString(pubKeyBytes)
            keyPayload.set("$sessionUuid\n$encoded")
            Timber.d("AuraHceService: key registered for session=$sessionUuid")
        }

        /** Extended variant — includes sessionNonce for Room session bootstrap. */
        fun setLocalKeyBytes(sessionUuid: String, pubKeyBytes: ByteArray, sessionNonce: ByteArray) {
            val encodedKey   = Base64.getEncoder().encodeToString(pubKeyBytes)
            val encodedNonce = Base64.getEncoder().encodeToString(sessionNonce)
            keyPayload.set("$sessionUuid\n$encodedKey\n$encodedNonce")
            Timber.d("AuraHceService: key+nonce registered for session=$sessionUuid")
        }

        fun clearLocalKey() {
            keyPayload.set(null)
            Timber.d("AuraHceService: key cleared")
        }

        fun getLocalKey(): String? = keyPayload.get()
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.isEmpty()) return SW_INS_UNSUPPORTED
        return when (val ins = commandApdu.getOrNull(1)) {
            INS_SELECT       -> handleSelect(commandApdu)
            INS_GET_KEY      -> handleGetKey()
            INS_GET_RESPONSE -> handleGetResponse()
            else -> {
                Timber.w("AuraHceService: unsupported INS 0x%02X", ins ?: 0xFF)
                SW_INS_UNSUPPORTED
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        isSelected = false
        chainBuffer.set(null)
        chainOffset.set(0)
        Timber.d("AuraHceService: deactivated (reason=%d)", reason)
    }

    private fun handleSelect(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_INS_UNSUPPORTED
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_INS_UNSUPPORTED
        val aidBytes = apdu.sliceArray(5 until 5 + lc)
        return if (aidBytes.contentEquals(AID)) {
            isSelected = true
            Timber.d("AuraHceService: AID selected")
            SW_OK
        } else {
            Timber.w("AuraHceService: unknown AID — rejecting")
            SW_FILE_NOT_FOUND
        }
    }

    private fun handleGetKey(): ByteArray {
        val payload = keyPayload.get() ?: run {
            Timber.w("AuraHceService: GET KEY with no key set")
            return SW_FILE_NOT_FOUND
        }
        return try {
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            if (payloadBytes.size <= MAX_CHUNK) {
                payloadBytes + SW_OK
            } else {
                chainBuffer.set(payloadBytes)
                chainOffset.set(MAX_CHUNK)
                val firstChunk = payloadBytes.copyOfRange(0, MAX_CHUNK)
                val remaining  = payloadBytes.size - MAX_CHUNK
                firstChunk + byteArrayOf(0x61, minOf(MAX_CHUNK, remaining).toByte())
            }
        } catch (e: Exception) {
            Timber.e(e, "AuraHceService: error building GET KEY response")
            SW_INTERNAL_ERROR
        }
    }

    private fun handleGetResponse(): ByteArray {
        val buf    = chainBuffer.get() ?: return SW_FILE_NOT_FOUND
        val offset = chainOffset.get()
        if (offset >= buf.size) {
            chainBuffer.set(null); chainOffset.set(0)
            return SW_FILE_NOT_FOUND
        }
        val remaining = buf.size - offset
        return if (remaining <= MAX_CHUNK) {
            val chunk = buf.copyOfRange(offset, buf.size)
            chainBuffer.set(null); chainOffset.set(0)
            chunk + SW_OK
        } else {
            val chunk     = buf.copyOfRange(offset, offset + MAX_CHUNK)
            val newOffset = offset + MAX_CHUNK
            chainOffset.set(newOffset)
            val stillLeft = buf.size - newOffset
            chunk + byteArrayOf(0x61, minOf(MAX_CHUNK, stillLeft).toByte())
        }
    }
}

