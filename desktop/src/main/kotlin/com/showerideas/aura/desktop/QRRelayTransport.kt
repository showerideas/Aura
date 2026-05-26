package com.showerideas.aura.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * T40 — Desktop companion: QR relay transport (primary exchange channel).
 *
 * On desktop there is no BLE or NFC hardware, so the QR relay becomes the
 * primary exchange transport. The user:
 *  1. Generates a QR code containing their ephemeral ECDH public key + relay URL.
 *  2. The mobile peer scans the QR, encrypts their profile, and POSTs to the relay.
 *  3. The desktop app polls the relay until the peer's encrypted profile arrives.
 *  4. Both sides verify the SAS code (shown on-screen), then save the contact.
 *
 * ## Relay API (mirrors RelayClient.kt on Android)
 * POST /api/exchange/v1/{slotId}   — upload encrypted payload (JSON body)
 * GET  /api/exchange/v1/{slotId}   — poll for peer's encrypted payload
 * DELETE /api/exchange/v1/{slotId} — clean up after exchange completes
 *
 * ## Wire compatibility
 * Payloads are JSON with the same structure as the Android relay path:
 * ```json
 * {
 *   "version": 1,
 *   "senderPublicKey": "<base64>",
 *   "encryptedProfile": "<base64>",
 *   "nonce": "<base64>",
 *   "timestampMs": 1716000000000
 * }
 * ```
 */
class QRRelayTransport(
    private val relayBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient()
) {

    companion object {
        /** Default relay URL — can be overridden in settings. */
        const val DEFAULT_RELAY_URL = "https://relay.aura.app"

        /** Maximum time to poll for a peer response. */
        const val POLL_TIMEOUT_MS = 60_000L

        /** Interval between poll requests. */
        const val POLL_INTERVAL_MS = 2_000L

        private const val JSON_MEDIA = "application/json; charset=utf-8"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /**
     * Upload the local encrypted profile to the relay slot [slotId].
     *
     * @param slotId          32-hex identifier for this exchange slot.
     * @param payloadJson     The relay payload JSON string.
     * @return true on HTTP 200/201; false otherwise.
     */
    suspend fun upload(slotId: String, payloadJson: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$relayBaseUrl/api/exchange/v1/$slotId")
            .post(payloadJson.toRequestBody(JSON_MEDIA.toMediaType()))
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // Poll
    // -------------------------------------------------------------------------

    /**
     * Poll the relay for the peer's encrypted profile.
     *
     * Polls every [POLL_INTERVAL_MS] until the peer's payload arrives or
     * [POLL_TIMEOUT_MS] elapses.
     *
     * @return The peer's payload JSON, or null on timeout / error.
     */
    suspend fun pollForPeer(slotId: String): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val result = fetchSlot(slotId)
            if (result != null) return@withContext result
            delay(POLL_INTERVAL_MS)
        }
        null
    }

    private fun fetchSlot(slotId: String): String? {
        val request = Request.Builder()
            .url("$relayBaseUrl/api/exchange/v1/$slotId")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    suspend fun deleteSlot(slotId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$relayBaseUrl/api/exchange/v1/$slotId")
            .delete()
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
