package com.showerideas.aura.network

import android.util.Base64
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 59 — Oblivious HTTP (OHTTP) RFC 9458 client.
 *
 * Oblivious HTTP routes AURA relay requests through a separate relay proxy so that
 * the relay server cannot link requests to specific users — even if Tor (Task 55)
 * is unavailable. OHTTP provides metadata privacy that complements the content
 * privacy already provided by end-to-end encryption.
 *
 * ## Why OHTTP instead of (or alongside) Tor
 * Tor provides IP anonymization but the relay server can still observe timing and
 * request patterns to correlate sessions. OHTTP adds request decoupling:
 * - **Client** encrypts the HTTP request to the **Gateway public key** using HPKE
 * - **Relay** (Cloudflare/Fastly) forwards the encrypted request to the Gateway
 * - **Gateway** (AURA relay server) decrypts and processes — sees only the payload,
 *   not the client IP (learned by Relay, not Gateway)
 *
 * ## Combined with Tor (Task 55)
 * Client → Tor → Relay → Gateway: relay sees Tor exit IP (not client);
 * gateway sees relay IP (not client and not Tor exit).
 * This two-hop anonymization is stronger than either Tor or OHTTP alone.
 *
 * ## HPKE encapsulation (simplified)
 * RFC 9458 uses HPKE (RFC 9180) for the outer encryption. AURA implements a
 * compatible HPKE-lite using X25519 + AES-128-GCM + HKDF-SHA256 (KEM ID 0x0020,
 * KDF ID 0x0001, AEAD ID 0x0001 — the mandatory HPKE ciphersuite from RFC 9180 §7).
 *
 * ## Key configuration
 * Gateway public key is fetched from a well-known endpoint and cached 24 hours:
 * `GET https://relay.aura.id/.well-known/ohttp-gateway` → binary HPKE public key.
 *
 * ## Request format (RFC 9458 §4)
 * ```
 * Encapsulated Request = hdr || HPKE-encapsulated-key || ciphertext
 * hdr = key_id (1B) || kem_id (2B) || kdf_id (2B) || aead_id (2B) = 7 bytes
 * ```
 *
 * See: [rfc-editor.org/rfc/rfc9458] — Oblivious HTTP RFC
 * See: [rfc-editor.org/rfc/rfc9180] — HPKE RFC
 * See: [blog.cloudflare.com/building-privacy-into-internet-standards-and-how-to-make-it-work]
 */
@Singleton
class ObliviousHttpClient @Inject constructor() {

    companion object {
        /** OHTTP binary media type — used in Content-Type header. */
        const val OHTTP_REQUEST_CONTENT_TYPE  = "message/ohttp-req"
        const val OHTTP_RESPONSE_CONTENT_TYPE = "message/ohttp-res"

        // HPKE algorithm IDs (matching RFC 9180 §7 mandatory ciphersuite)
        const val KEM_ID  = 0x0020.toShort()   // DHKEM(X25519, HKDF-SHA256)
        const val KDF_ID  = 0x0001.toShort()   // HKDF-SHA256
        const val AEAD_ID = 0x0001.toShort()   // AES-128-GCM
        const val KEY_LEN = 16                  // AES-128-GCM key = 16 bytes

        private const val HKDF_INFO_AURA = "aura-ohttp-v1"
    }

    /**
     * Encapsulated OHTTP request — ready to POST to the Relay.
     * @param ciphertext  RFC 9458 encapsulated request bytes.
     * @param exporterKey Client-side exporter key for response decryption.
     */
    data class EncapsulatedRequest(
        val ciphertext: ByteArray,
        val exporterKey: ByteArray
    )

    /**
     * Encapsulate an HTTP request for OHTTP relay transmission.
     *
     * This is a simplified HPKE-lite encapsulation for the DHKEM(X25519)+AES-128-GCM
     * ciphersuite. Full RFC 9180 HPKE (including Label-based extraction, HKDF Expand)
     * is approximated here for pure-JVM compatibility. BouncyCastle HPKE support is
     * tracked for a future hardening pass.
     *
     * @param gatewayPublicKeyBytes X25519 public key of the Gateway (32 bytes).
     * @param method                HTTP method (e.g. "PUT", "GET").
     * @param url                   Target URL (e.g. "https://relay.aura.id/v1/slots/abc").
     * @param body                  Request body bytes (may be empty for GET).
     * @param keyId                 Gateway key identifier (from key config endpoint).
     * @return [EncapsulatedRequest] to POST to the Relay.
     */
    fun encapsulate(
        gatewayPublicKeyBytes: ByteArray,
        method: String,
        url: String,
        body: ByteArray,
        keyId: Byte = 0x01
    ): EncapsulatedRequest {
        require(gatewayPublicKeyBytes.size == 32) { "OHTTP gateway X25519 key must be 32 bytes" }
        val rng = SecureRandom()

        // Generate ephemeral X25519 key pair (sender ephemeral)
        val ephPrivBytes = rng.generateSeed(32)
        val ephPubBytes  = x25519PublicFromPrivate(ephPrivBytes)

        // ECDH: DH(ephPriv, gatewayPub)
        val dhShared = x25519(ephPrivBytes, gatewayPublicKeyBytes)

        // HKDF extract+expand to derive symmetric key
        val prk = hkdfExtract(salt = ByteArray(32), ikm = dhShared)
        val aesKey  = hkdfExpand(prk, info = "$HKDF_INFO_AURA-key", len = KEY_LEN)
        val exporter = hkdfExpand(prk, info = "$HKDF_INFO_AURA-exp", len = 32)

        // Build RFC 9458 header
        val hdr = buildHeader(keyId, KEM_ID, KDF_ID, AEAD_ID)

        // Plaintext = method + " " + url + "\n" + body
        val plaintext = "$method $url\n".toByteArray() + body

        // Encrypt with AES-128-GCM; AAD = hdr
        val nonce = rng.generateSeed(12)
        val ct = aesGcmEncrypt(aesKey, nonce, plaintext, ad = hdr)

        val encapsulated = hdr + ephPubBytes + nonce + ct
        Timber.d("OHTTP: encapsulated ${plaintext.size}B → ${encapsulated.size}B ciphertext")
        return EncapsulatedRequest(encapsulated, exporter)
    }

    /**
     * Validate an OHTTP response ciphertext using the exporter key from [encapsulate].
     * Returns the decrypted response body bytes.
     */
    fun decapsulateResponse(
        responseCiphertext: ByteArray,
        exporterKey: ByteArray
    ): ByteArray {
        require(responseCiphertext.size >= 28) { "OHTTP response too short" }
        // Response format: nonce(12) || ciphertext
        val nonce = responseCiphertext.copyOfRange(0, 12)
        val ct    = responseCiphertext.copyOfRange(12, responseCiphertext.size)
        val respKey = hkdfExpand(exporterKey, info = "$HKDF_INFO_AURA-resp", len = KEY_LEN)
        return aesGcmDecrypt(respKey, nonce, ct, ad = ByteArray(0))
    }

    // ---- Wire format helpers -------------------------------------------------

    fun buildHeader(keyId: Byte, kemId: Short, kdfId: Short, aeadId: Short): ByteArray =
        byteArrayOf(keyId,
            (kemId.toInt() shr 8).toByte(), kemId.toByte(),
            (kdfId.toInt() shr 8).toByte(), kdfId.toByte(),
            (aeadId.toInt() shr 8).toByte(), aeadId.toByte()
        )

    // ---- Crypto primitives ---------------------------------------------------

    private fun x25519PublicFromPrivate(priv: ByteArray): ByteArray {
        // Use BouncyCastle X25519 to derive public key
        val privParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0)
        return privParams.generatePublicKey().encoded
    }

    private fun x25519(privBytes: ByteArray, pubBytes: ByteArray): ByteArray {
        val agreement = org.bouncycastle.crypto.agreement.X25519Agreement()
        agreement.init(org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privBytes, 0))
        val shared = ByteArray(32)
        agreement.calculateAgreement(
            org.bouncycastle.crypto.params.X25519PublicKeyParameters(pubBytes, 0), shared, 0)
        return shared
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.ifEmpty { ByteArray(32) }, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: String, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info.toByteArray() + byteArrayOf(0x01)).copyOf(len)
    }

    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, pt: ByteArray, ad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(ad)
        return cipher.doFinal(pt)
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, ad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(ad)
        return cipher.doFinal(ct)
    }
}
