package com.showerideas.aura.network

import com.showerideas.aura.BuildConfig
import com.showerideas.aura.relay.privacypass.PrivacyPassClient
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Thin HTTPS client for the AURA QR relay with runtime SPKI certificate pinning.
 *
 * Relay protocol (simple REST, HTTPS only):
 *   PUT  {baseUrl}/v1/slots/{endpoint}   — upload encrypted bytes; idempotent
 *   GET  {baseUrl}/v1/slots/{endpoint}   — fetch bytes; HTTP 204/404 = not yet posted
 *
 * Certificate pinning (defence-in-depth)
 *
 * Pinning operates at two independent layers:
 *
 * 1. **Android NSC** (`network_security_config.xml`) — enforced by the OS for all
 *    `HttpsURLConnection` traffic. Protects against rogue CAs on non-rooted devices.
 *
 * 2. **Runtime SPKI pinning** (this class) — a custom [X509TrustManager] computes
 *    SHA-256 of the leaf certificate's SubjectPublicKeyInfo (SPKI) DER bytes and
 *    rejects connections whose pin does not match [BuildConfig.RELAY_SPKI_PIN_PRIMARY]
 *    or [BuildConfig.RELAY_SPKI_PIN_BACKUP]. This layer fires even on rooted devices
 *    and debug builds where NSC can be bypassed via user-installed CAs.
 *
 * Pin rotation
 * Update `RELAY_SPKI_PIN_PRIMARY` / `RELAY_SPKI_PIN_BACKUP` in CI environment variables
 * **before** the current certificate expires. See `docs/QR_RELAY_SETUP.md` for the
 * rotation runbook. The `RELAY_PIN_EXPIRY_EPOCH_MS` BuildConfig field triggers a 30-day
 * early warning in logs so rotation is never a surprise.
 *
 * Generating a pin
 * ```bash
 * openssl s_client -connect relay.example.com:443 < /dev/null 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform DER \
 *   | openssl dgst -sha256 -binary \
 *   | base64
 * ```
 *
 * The relay *never sees plaintext*: callers encrypt with the ECDH-derived AES-256
 * session key before handing bytes to this class.
 */
/**
 * Task 115 — Privacy Pass token redemption wired into [RelayClient].
 *
 * When [privacyPassClient] is non-null and has tokens available, each PUT/GET
 * request attaches a `Privacy-Token` header (RFC 9576 §5) containing a single
 * redeemed token. The relay validates the token against the issuer's public key
 * before processing the request.
 *
 * If no tokens are available the request proceeds without the header — the relay
 * may rate-limit or reject (429) depending on its policy.
 */
@Singleton
open class RelayClient @Inject constructor(
    private val privacyPassClient: PrivacyPassClient
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 30_000

        /** Warn 30 days ahead of pin expiry so rotation can happen before the deadline. */
        private const val PIN_EXPIRY_WARN_MS = 30L * 24 * 60 * 60 * 1_000
    }

    /** Lazily-built [SSLContext] with our custom SPKI-pinning [X509TrustManager]. */
    private val pinnedSslContext: SSLContext? by lazy { buildPinnedSslContext() }

    init {
        // certificate pin expiry early-warning.
        val expiryMs   = BuildConfig.RELAY_PIN_EXPIRY_EPOCH_MS
        val nowMs      = System.currentTimeMillis()
        val remaining  = expiryMs - nowMs
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
                Timber.d("RelayClient: pin valid for %d more day(s).",
                    remaining / (24 * 60 * 60 * 1_000))
        }
    }

    /** SOCKS5 proxy address for Tor/Orbot anonymization. Null = direct. */
    @Volatile private var socksProxy: java.net.InetSocketAddress? = null

    fun setAnonymizationProxy(socksAddress: java.net.InetSocketAddress?) {
        socksProxy = socksAddress
        Timber.i("RelayClient proxy: ${socksAddress?.let { "${it.hostString}:${it.port}" } ?: "direct"}")
    }

    open fun postSlot(baseUrl: String, endpoint: String, encryptedBytes: ByteArray): Boolean {
        return try {
            val conn = openConnection("$baseUrl/v1/slots/$endpoint", "PUT")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", encryptedBytes.size.toString())
            // T115: attach Privacy Pass token if available
            privacyPassClient?.redeemToken()?.let { tokenHeader ->
                conn.setRequestProperty(PrivacyPassClient.HEADER_NAME, tokenHeader)
                Timber.d("RelayClient: Privacy-Token attached to PUT")
            }
            conn.doOutput = true
            conn.outputStream.use { it.write(encryptedBytes) }
            val code = conn.responseCode
            conn.disconnect()
            Timber.i("RelayClient PUT /slots/%s → HTTP %d (%dB)", endpoint, code, encryptedBytes.size)
            code in 200..204
        } catch (e: SSLHandshakeException) {
            Timber.e(e, "RelayClient PUT: TLS pin mismatch — connection rejected")
            false
        } catch (e: IOException) {
            Timber.e(e, "RelayClient PUT /slots/%s failed", endpoint)
            false
        }
    }

    open fun getSlot(baseUrl: String, endpoint: String): ByteArray? {
        return try {
            val conn = openConnection("$baseUrl/v1/slots/$endpoint", "GET")
            // T115: attach Privacy Pass token if available
            privacyPassClient?.redeemToken()?.let { tokenHeader ->
                conn.setRequestProperty(PrivacyPassClient.HEADER_NAME, tokenHeader)
                Timber.d("RelayClient: Privacy-Token attached to GET")
            }
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
        } catch (e: SSLHandshakeException) {
            Timber.e(e, "RelayClient GET: TLS pin mismatch — connection rejected")
            null
        } catch (e: IOException) {
            Timber.e(e, "RelayClient GET /slots/%s failed", endpoint)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Connection builder
    // ─────────────────────────────────────────────────────────────────────

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val proxy = socksProxy
        val javaProxy = proxy?.let { java.net.Proxy(java.net.Proxy.Type.SOCKS, it) }

        val connection = if (javaProxy != null) {
            URL(url).openConnection(javaProxy) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }

        // Apply runtime SPKI pinning on HTTPS connections.
        if (connection is HttpsURLConnection) {
            pinnedSslContext?.socketFactory?.let {
                connection.sslSocketFactory = it
            }
        }

        return connection.apply {
            requestMethod  = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Runtime SPKI pinning — defence-in-depth beyond network_security_config
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build an [SSLContext] whose [X509TrustManager] rejects any TLS leaf certificate
     * whose SPKI SHA-256 does not match the primary or backup pin from [BuildConfig].
     *
     * If both BuildConfig pins are the placeholder sentinel value (all-zeroes or empty),
     * pinning is skipped and the default system trust manager is used — this allows
     * development builds without real pins to still connect while logging a warning.
     */
    private fun buildPinnedSslContext(): SSLContext? {
        val pinPrimary = BuildConfig.RELAY_SPKI_PIN_PRIMARY
        val pinBackup  = BuildConfig.RELAY_SPKI_PIN_BACKUP

        val pinsConfigured = pinPrimary.isNotBlank() &&
            !pinPrimary.all { it == 'A' || it == '=' }   // detect placeholder "AAAA...="

        if (!pinsConfigured) {
            Timber.w("RelayClient: SPKI pins not configured (placeholder values) — " +
                     "runtime pinning disabled. Set RELAY_SPKI_PIN_PRIMARY in CI.")
            return null
        }

        val tm = SpkiPinTrustManager(pinPrimary, pinBackup)
        return SSLContext.getInstance("TLS").also { ctx ->
            ctx.init(null, arrayOf<TrustManager>(tm), null)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SPKI pin trust manager
    // ─────────────────────────────────────────────────────────────────────

    /**
     * [X509TrustManager] that validates the server certificate chain against the
     * system trust store AND enforces that the leaf certificate's SPKI SHA-256
     * matches one of the configured pins.
     *
     * This provides defence-in-depth: even if a rogue CA is installed on the device
     * (e.g., on a corporate MITM proxy or a rooted phone), the pin check will reject
     * the impersonating certificate.
     */
    private class SpkiPinTrustManager(
        private val pinPrimary: String,
        private val pinBackup: String
    ) : X509TrustManager {

        private val systemTm: X509TrustManager by lazy {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            factory.init(null as java.security.KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            systemTm.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            // 1. Validate chain against system CAs first.
            systemTm.checkServerTrusted(chain, authType)

            // 2. Enforce SPKI pin on the leaf certificate.
            val leaf    = chain[0]
            val spkiPin = computeSpkiPin(leaf)

            val pinMatch = spkiPin == pinPrimary || (!pinBackup.isBlank() && spkiPin == pinBackup)
            if (!pinMatch) {
                throw CertificateException(
                    "Certificate SPKI pin mismatch for relay endpoint.\n" +
                    "  Got:     $spkiPin\n" +
                    "  Primary: $pinPrimary\n" +
                    "  Backup:  $pinBackup\n" +
                    "Rotate the pin or update BuildConfig if the certificate was legitimately renewed."
                )
            }

            Timber.d("RelayClient: SPKI pin verified OK (%s)", spkiPin.take(12) + "…")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            systemTm.acceptedIssuers

        /**
         * Compute SHA-256 of [cert]'s SubjectPublicKeyInfo DER bytes and return
         * the result as a Base64-encoded string (standard alphabet, no line wrapping).
         *
         * This matches the output of:
         * ```
         * openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER |
         * openssl dgst -sha256 -binary | base64
         * ```
         */
        private fun computeSpkiPin(cert: X509Certificate): String {
            val spkiDer  = cert.publicKey.encoded   // X.509 SubjectPublicKeyInfo DER
            val digest   = MessageDigest.getInstance("SHA-256").digest(spkiDer)
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
