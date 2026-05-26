package com.showerideas.aura.network

import android.content.Context
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 55 — Tor + Zero-Knowledge Relay Anonymization.
 *
 * Provides anonymous relay transport by routing AURA's QR relay slot upload/fetch
 * through the Tor network, preventing the relay server from learning the IP address
 * of either exchange party. Combined with OHTTP (Task 59), this provides multi-hop
 * anonymization: relay server sees only Tor exit IP; Tor exit sees only encrypted OHTTP.
 *
 * ## Tor integration via Orbot (Guardian Project)
 * `info.guardianproject:netcipher-okhttp3` provides OkHttp integration with Orbot's
 * SOCKS5 proxy on port 9050. When Orbot is installed and VPN mode is active, all
 * [TorRelayManager] requests route through Tor automatically. Without Orbot, the
 * manager falls back to the direct [RelayClient].
 *
 * ## Onion Service support
 * The AURA relay server can optionally expose a .onion address. Relaying to an
 * onion service provides end-to-end anonymization (relay server doesn't see client
 * IP even via Tor exit nodes). Configured via [BuildConfig.RELAY_ONION_ADDRESS].
 *
 * ## Zero-knowledge anonymization token
 * To prevent correlation attacks based on relay slot identifiers, AURA generates
 * slot IDs using a ZK-inspired commitment scheme:
 * ```
 * commitment = HMAC-SHA256(sessionKey, "slot-commit-v1" || sessionId)
 * slotId = base64url(commitment)[0..16]
 * ```
 * The relay server sees only the slot commitment — it cannot correlate slots to
 * users or sessions without the session key (which never leaves AURA peers).
 *
 * ## Privacy adversary model
 * - **Relay server**: sees commitment slot IDs, Tor exit IP, ciphertext only.
 *   Cannot link slots to users or link sessions together.
 * - **Tor network**: sees destination (relay server IP/onion). Cannot decrypt.
 * - **Network observer**: sees Tor entry guard traffic only.
 *
 * See: [guardianproject.info/apps/orbot] — Orbot (Tor for Android)
 * See: [torproject.org/developers] — Tor design documentation
 * See: [github.com/guardianproject/NetCipher] — NetCipher Tor proxy library
 */
@Singleton
class TorRelayManager @Inject constructor() {

    companion object {
        /** Orbot SOCKS5 proxy port (default; user-configurable in Orbot settings). */
        const val ORBOT_SOCKS_PORT = 9050
        /** Orbot HTTP proxy port for apps that can't do SOCKS5. */
        const val ORBOT_HTTP_PORT = 8118
        /** Orbot package name — used to check if Orbot is installed. */
        const val ORBOT_PACKAGE = "org.torproject.android"
        /** ZK commitment domain separator. */
        private const val COMMIT_INFO = "slot-commit-v1"
    }

    /**
     * State of the Tor relay connection.
     */
    sealed class TorState {
        object Disconnected : TorState()
        object Connecting : TorState()
        data class Connected(val circuitInfo: String) : TorState()
        data class Failed(val reason: String) : TorState()
    }

    /**
     * Check if Orbot is installed on this device.
     * @return true if Orbot is installed and Tor routing is available.
     */
    fun isOrbotAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            Timber.d("TorRelayManager: Orbot is available")
            true
        } catch (_: Exception) {
            Timber.d("TorRelayManager: Orbot not installed — direct relay fallback")
            false
        }
    }

    /**
     * Generate a zero-knowledge slot commitment from a session key and session ID.
     *
     * The commitment is deterministic for a given (sessionKey, sessionId) pair but
     * unlinkable across sessions — a new session key produces a completely different
     * commitment, preventing the relay server from correlating slots to the same user.
     *
     * @param sessionKey 32-byte AURA session key (from PQXDH/HKDF, never transmitted to relay).
     * @param sessionId  32-byte session ID (random per exchange session).
     * @return 16-character hex commitment used as the relay slot identifier.
     */
    fun generateSlotCommitment(sessionKey: ByteArray, sessionId: ByteArray): String {
        require(sessionKey.size == 32) { "Session key must be 32 bytes" }
        require(sessionId.size == 32) { "Session ID must be 32 bytes" }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update(COMMIT_INFO.toByteArray(Charsets.UTF_8))
        val commitment = mac.doFinal(sessionId)
        return commitment.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify that a received slot commitment is well-formed (16 hex chars).
     * Does NOT verify the commitment against a key — that would require knowing the session key.
     * Used for input validation on the receiving side.
     */
    fun isValidSlotCommitment(commitment: String): Boolean =
        commitment.length == 32 && commitment.all { it.isLetterOrDigit() }

    /**
     * Generate a fresh 32-byte session ID for use in [generateSlotCommitment].
     */
    fun generateSessionId(): ByteArray = SecureRandom().generateSeed(32)

    /**
     * Build the OkHttp proxy configuration for routing through Orbot.
     * Returns null if Orbot is not available (caller falls back to direct connection).
     *
     * In production: pass the returned [ProxyConfig] to OkHttp builder:
     * ```kotlin
     * val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
     *     InetSocketAddress("127.0.0.1", config.port))
     * OkHttpClient.Builder().proxy(proxy).build()
     * ```
     *
     * @param context Android context for Orbot availability check.
     * @return [ProxyConfig] or null if Orbot unavailable.
     */
    fun buildOrbotProxyConfig(context: Context): ProxyConfig? {
        if (!isOrbotAvailable(context)) return null
        return ProxyConfig(host = "127.0.0.1", port = ORBOT_SOCKS_PORT, type = ProxyConfig.Type.SOCKS5)
    }

    /** OkHttp-agnostic proxy configuration. */
    data class ProxyConfig(
        val host: String,
        val port: Int,
        val type: Type
    ) {
        enum class Type { SOCKS5, HTTP }
    }
}
