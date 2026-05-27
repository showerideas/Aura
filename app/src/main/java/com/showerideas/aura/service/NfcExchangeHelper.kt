package com.showerideas.aura.service

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import timber.log.Timber
import java.io.IOException
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NFC tap-to-exchange helper.
 *
 * Supports two exchange paths:
 *  - NDEF bootstrap: [parseNdefMessageWithNonce], [deriveSessionToken]
 *  - IsoDep APDU: [enableReaderMode], [disableReaderMode], [exchangeApduViaNfc]
 */
object NfcExchangeHelper {

    const val MIME_TYPE = "application/vnd.aura.key-bootstrap"
    private const val MIN_KEY_BYTES     = 32
    private const val REQUEST_CODE_NFC  = 0xA57A
    private const val ISODEP_TIMEOUT_MS = 5_000

    data class NfcBootstrap(
        val peerSessionUuid    : String,
        val peerPublicKeyBytes : ByteArray,
        /** session nonce from 3-part payload; null for legacy 2-part payloads. */
        val sessionNonce       : ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NfcBootstrap) return false
            return peerSessionUuid == other.peerSessionUuid &&
                   peerPublicKeyBytes.contentEquals(other.peerPublicKeyBytes) &&
                   (sessionNonce == null && other.sessionNonce == null ||
                    sessionNonce != null && other.sessionNonce != null &&
                    sessionNonce.contentEquals(other.sessionNonce))
        }
        override fun hashCode(): Int {
            var h = 31 * peerSessionUuid.hashCode() + peerPublicKeyBytes.contentHashCode()
            return 31 * h + (sessionNonce?.contentHashCode() ?: 0)
        }
    }

    // ── Foreground dispatch lifecycle ─────────────────────────────────────

    fun enable(activity: Activity, keyBytes: ByteArray, sessionId: String) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) { Timber.d("NFC: adapter not available"); return }
        if (!adapter.isEnabled) { Timber.d("NFC: adapter disabled"); return }
        val pendingIntent = PendingIntent.getActivity(
            activity, REQUEST_CODE_NFC,
            Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mimeFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try { addDataType(MIME_TYPE) }
            catch (e: IntentFilter.MalformedMimeTypeException) { Timber.e(e, "NFC: bad MIME") }
        }
        runCatching {
            adapter.enableForegroundDispatch(activity, pendingIntent, arrayOf(mimeFilter), null)
            Timber.d("NFC foreground dispatch enabled (session=$sessionId)")
        }.onFailure { Timber.e(it, "NFC: failed to enable foreground dispatch") }
    }

    fun enable(activity: Activity, localKey: PublicKey, sessionId: String) =
        enable(activity, localKey.encoded, sessionId)

    fun disable(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.runCatching {
            disableForegroundDispatch(activity)
            Timber.d("NFC foreground dispatch disabled")
        }
    }

    // ── Reader mode ────────────────────────────

    /**
     * Enable NFC reader mode. Call in onResume.
     * FLAG_READER_SKIP_NDEF_CHECK ensures IsoDep tags are delivered to the callback.
     */
    fun enableReaderMode(activity: Activity, callback: NfcAdapter.ReaderCallback) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        adapter.enableReaderMode(
            activity, callback,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        Timber.d("NFC: reader mode enabled")
    }

    /** Disable NFC reader mode. Call in onPause. */
    fun disableReaderMode(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        Timber.d("NFC: reader mode disabled")
    }

    // ── IsoDep APDU exchange ────────────────────────────────────

    /**
     * Drive the full APDU exchange as the reader/initiator side.
     *
     * Sends SELECT AID → GET KEY → GET RESPONSE (chaining loop) and returns the
     * peer's [NfcBootstrap]. Returns null on any failure.
     *
     * Fallback: if this returns null, caller should fall through to volume-button / QR relay.
     */
    fun exchangeApduViaNfc(tag: Tag, localKeyBytes: ByteArray, sessionId: String): NfcBootstrap? {
        val isoDep = IsoDep.get(tag) ?: return null
        return try {
            isoDep.connect()
            isoDep.timeout = ISODEP_TIMEOUT_MS

            // Step 1: SELECT AID
            val aid        = byteArrayOf(0xF0.toByte(), 0x41, 0x55, 0x52, 0x41, 0x00, 0x01)
            val selectApdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07) + aid + byteArrayOf(0x00)
            val selectResp = isoDep.transceive(selectApdu)
            if (selectResp.size < 2 ||
                selectResp[selectResp.size - 2] != 0x90.toByte() ||
                selectResp.last() != 0x00.toByte()
            ) {
                Timber.w("NFC reader: SELECT AID failed: ${selectResp.toHexString()}")
                return null
            }

            // Step 2: GET KEY + chain loop
            val getKeyApdu  = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x00)
            var response    = isoDep.transceive(getKeyApdu)
            val accumulated = mutableListOf<Byte>()

            while (response.size >= 2) {
                val sw1 = response[response.size - 2]
                val sw2 = response[response.size - 1]
                accumulated.addAll(response.dropLast(2).toList())
                when {
                    sw1 == 0x90.toByte() && sw2 == 0x00.toByte() -> break
                    sw1 == 0x61.toByte() -> {
                        val le      = if (sw2 == 0x00.toByte()) 0xFF else sw2.toInt() and 0xFF
                        val getResp = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, le.toByte())
                        response    = isoDep.transceive(getResp)
                    }
                    else -> break
                }
            }

            // Step 3: Parse payload
            val payloadStr = String(accumulated.toByteArray(), Charsets.UTF_8)
            val parts      = payloadStr.split("\n", limit = 3)
            if (parts.size < 2) return null
            val peerUuid     = parts[0].trim()
            val peerKeyBytes = runCatching { Base64.getDecoder().decode(parts[1].trim()) }
                .getOrNull() ?: return null
            if (peerKeyBytes.size < MIN_KEY_BYTES) return null
            val nonce = if (parts.size >= 3) runCatching {
                Base64.getDecoder().decode(parts[2].trim())
            }.getOrNull() else null

            NfcBootstrap(peerUuid, peerKeyBytes, nonce)
        } catch (e: Exception) {
            Timber.e(e, "NFC reader: APDU exchange failed")
            null
        } finally {
            isoDep.runCatching { close() }
        }
    }

    // ── Session token HKDF  ─────────────────────────────

    /**
     * Derive a 32-byte session token: HKDF-SHA256(ecdhShared || sessionNonce, info="aura-room-v1").
     * Both peers derive the same token independently after the ECDH exchange.
     */
    fun deriveSessionToken(ecdhShared: ByteArray, sessionNonce: ByteArray): ByteArray {
        val ikm = ecdhShared + sessionNonce
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(ikm)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update("aura-room-v1".toByteArray(Charsets.UTF_8))
            update(0x01.toByte())
            doFinal()
        }
    }

    // ── Intent handling ───────────────────────────────────────────────────

    fun handleIntent(intent: Intent): NfcBootstrap? {
        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) return null

        @Suppress("DEPRECATION")
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMessages != null) {
            for (raw in rawMessages) {
                val msg = raw as? NdefMessage ?: continue
                parseNdefMessage(msg)?.let { return it }
            }
        }
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return null
        return readFromTag(tag)
    }

    fun readFromTag(tag: Tag): NfcBootstrap? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()
            message?.let { parseNdefMessage(it) }
        } catch (e: IOException) {
            Timber.e(e, "NFC: failed to read NDEF from tag")
            runCatching { ndef.close() }
            null
        }
    }

    fun writeToTag(tag: Tag, keyBytes: ByteArray, sessionId: String): Boolean {
        val message = buildNdefMessage(sessionId, keyBytes) ?: return false
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                val ok = ndef.isWritable && ndef.maxSize >= message.toByteArray().size
                if (ok) ndef.writeNdefMessage(message)
                ndef.close(); ok
            } catch (e: IOException) {
                Timber.e(e, "NFC: Ndef write failed"); runCatching { ndef.close() }; false
            }
        }
        NdefFormatable.get(tag)?.let { formatable ->
            return try {
                formatable.connect(); formatable.format(message); formatable.close(); true
            } catch (e: IOException) {
                Timber.e(e, "NFC: NdefFormatable write failed")
                runCatching { formatable.close() }; false
            }
        }
        return false
    }

    fun writeToTag(tag: Tag, localKey: PublicKey, sessionId: String): Boolean =
        writeToTag(tag, localKey.encoded, sessionId)

    // ── NDEF build / parse ────────────────────────────────────────────────

    fun buildNdefMessage(sessionId: String, keyBytes: ByteArray): NdefMessage? {
        if (keyBytes.isEmpty()) return null
        val payload = "$sessionId\n${Base64.getEncoder().encodeToString(keyBytes)}"
        return NdefMessage(arrayOf(NdefRecord.createMime(MIME_TYPE, payload.toByteArray(Charsets.UTF_8))))
    }

    fun parseNdefMessage(message: NdefMessage): NfcBootstrap? {
        for (record in message.records) {
            val mimeType = String(record.type, Charsets.UTF_8)
            if (!mimeType.equals(MIME_TYPE, ignoreCase = true)) continue
            val payload = String(record.payload, Charsets.UTF_8)
            val parts   = payload.split("\n", limit = 2)
            if (parts.size < 2) { Timber.w("NFC: malformed NDEF record"); continue }
            val sessionUuid = parts[0].trim()
            val keyBytes    = runCatching { Base64.getDecoder().decode(parts[1].trim()) }
                .getOrNull() ?: continue
            if (keyBytes.size < MIN_KEY_BYTES) continue
            return NfcBootstrap(sessionUuid, keyBytes)
        }
        return null
    }

    /** Parse with optional nonce (backward compatible with 2-part payloads). */
    fun parseNdefMessageWithNonce(message: NdefMessage): NfcBootstrap? {
        for (record in message.records) {
            val mimeType = String(record.type, Charsets.UTF_8)
            if (!mimeType.equals(MIME_TYPE, ignoreCase = true)) continue
            val payload = String(record.payload, Charsets.UTF_8)
            val parts   = payload.split("\n", limit = 3)
            if (parts.size < 2) continue
            val sessionUuid = parts[0].trim()
            val keyBytes    = runCatching { Base64.getDecoder().decode(parts[1].trim()) }
                .getOrNull() ?: continue
            if (keyBytes.size < MIN_KEY_BYTES) continue
            val nonce = if (parts.size >= 3) runCatching {
                Base64.getDecoder().decode(parts[2].trim())
            }.getOrNull() else null
            return NfcBootstrap(sessionUuid, keyBytes, nonce)
        }
        return null
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}

