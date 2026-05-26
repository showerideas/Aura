package com.showerideas.aura.network

import com.showerideas.aura.BuildConfig
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

        /** Warn 30 days ahead of pin expiry so rotation can happen before the deadline. */
        private const val PIN_EXPIRY_WARN_MS = 30L * 24 * 60 * 60 * 1_000
    }

    init {
        // Phase 5.7 — certificate pin expiry early-warning.
        // Logs a WARNING when within 30 days of expiry and an ERROR when already expired.
        // See docs/QR_RELAY_SETUP.md for the pin rotation runbook.
        val expiryMs = BuildConfig.RELAY_PIN_EXPIRY_EPOCH_MS
        val nowMs    = System.currentTimeMillis()
        val remaining = expiryMs - nowMs
        when {
            remaining <= 0 ->
                Timber.e(
                    "RELAY TLS PIN IS EXPIRED — rotate immediately! " +
                    "Expiry was %s. See docs/QR_RELAY_SETUP.md.",
                    java.util.Date(expiryMs)
                )
            remaining <= PIN_EXPIRY_WARN_MS ->
                Timber.w(
                    "Relay TLS pin expiring in %d day(s) — rotate soon! " +
                    "Expiry: %s. See docs/QR_RELAY_SETUP.md.",
                    remaining / (24 * 60 * 60 * 1_000),
                    java.util.Date(expiryMs)
                )
            else ->
                Timber.d(
                    "RelayClient init: pin valid for %d more day(s).",
                    remaining / (24 * 60 * 60 * 1_000)
                )
        }
    }

    /** Phase 8.3 — SOCKS5 proxy address for Tor/Orbot anonymization. Null = direct. */
    @Volatile private var socksProxy: java.net.InetSocketAddress? = null

    /**
     * Phase 8.3 — Configure a SOCKS5 proxy (e.g., Orbot's 127.0.0.1:9050).
     * When set, all relay HTTP connections are routed through this proxy.
     * Pass null to disable and resume direct connections.
     */
    fun setAnonymizationProxy(socksAddress: java.net.InetSocketAddress?) {
        socksProxy = socksAddress
        Timber.i("RelayClient proxy: ${socksAddress?.let { "${it.hostString}:${it.port}" } ?: "direct"}")
    }

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

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val proxy = socksProxy
        val connection = if (proxy != null) {
            val javaProxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, proxy)
            (URL(url).openConnection(javaProxy) as HttpURLConnection)
        } else {
            (URL(url).openConnection() as HttpURLConnection)
        }
        return connection.apply {
            requestMethod   = method
            connectTimeout  = CONNECT_TIMEOUT_MS
            readTimeout     = READ_TIMEOUT_MS
        }
    }
}
