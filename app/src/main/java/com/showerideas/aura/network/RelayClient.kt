package com.showerideas.aura.network

import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin HTTP client for the AURA QR relay — a stateless store-and-forward
 * service that holds an encrypted profile payload for up to ~5 minutes until
 * the peer retrieves it.
 *
 * Relay protocol (simple REST, HTTPS only):
 *   PUT  {baseUrl}/v1/slots/{endpoint}   — upload encrypted bytes; idempotent
 *   GET  {baseUrl}/v1/slots/{endpoint}   — fetch bytes; HTTP 204/404 = not yet posted
 *
 * The relay *never sees plaintext*: callers encrypt with the ECDH-derived AES-256
 * session key before handing bytes to this class. The relay is dumb storage only.
 *
 * Configure the relay URL at build time via the [BuildConfig.RELAY_BASE_URL] field
 * (set the RELAY_BASE_URL environment variable in CI or a local gradle.properties).
 * For a zero-ops deployment, a Firebase Realtime Database instance with open
 * read/write rules works out-of-the-box — see docs/qr-relay-setup.md.
 */
@Singleton
class RelayClient @Inject constructor() {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 30_000
    }

    /**
     * Upload [encryptedBytes] to the relay slot owned by [endpoint].
     *
     * @return true on HTTP 2xx, false on any network or server error.
     */
    fun postSlot(baseUrl: String, endpoint: String, encryptedBytes: ByteArray): Boolean {
        return try {
            val conn = openConnection("$baseUrl/v1/slots/$endpoint", "PUT")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", encryptedBytes.size.toString())
            conn.doOutput = true
            conn.outputStream.use { it.write(encryptedBytes) }
            val code = conn.responseCode
            conn.disconnect()
            Timber.i("RelayClient PUT /slots/%s → HTTP %d (%dB)", endpoint, code, encryptedBytes.size)
            code in 200..204
        } catch (e: IOException) {
            Timber.e(e, "RelayClient PUT /slots/%s failed", endpoint)
            false
        }
    }

    /**
     * Retrieve the encrypted payload from relay slot [endpoint].
     *
     * @return raw encrypted bytes on HTTP 200; null on HTTP 404 / no content / error.
     */
    fun getSlot(baseUrl: String, endpoint: String): ByteArray? {
        return try {
            val conn = openConnection("$baseUrl/v1/slots/$endpoint", "GET")
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { it.readBytes() }
                    .also { Timber.i("RelayClient GET /slots/%s → %dB", endpoint, it.size) }
                    .also { conn.disconnect() }
            } else {
                conn.disconnect()
                Timber.d("RelayClient GET /slots/%s → HTTP %d (empty)", endpoint, code)
                null
            }
        } catch (e: IOException) {
            Timber.e(e, "RelayClient GET /slots/%s failed", endpoint)
            null
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod   = method
            connectTimeout  = CONNECT_TIMEOUT_MS
            readTimeout     = READ_TIMEOUT_MS
        }
}
