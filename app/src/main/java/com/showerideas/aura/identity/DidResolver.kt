package com.showerideas.aura.identity

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.URL
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R&D-D — Multi-method DID resolver.
 *
 * Resolves Decentralized Identifiers (DIDs) from three methods used by AURA:
 *
 * ## Supported DID methods
 *
 * ### `did:key`
 * Self-describing; the public key material is encoded directly in the DID string.
 * Format: `did:key:z<multibase(multicodec(keyBytes))>`
 * Resolution is pure local computation — no network required.
 * AURA uses `did:key` with P-256 (multicodec 0x1200) for all identity keys.
 *
 * ### `did:peer:2`
 * Pairwise DID encoding static key(s) as the DID itself.
 * Format: `did:peer:2.<encodedKey>` where encodedKey is the Base58btc-multibase
 * encoding of the peer's AURA exchange public key.
 * Used for DIDComm v2 routing (Phase 10) — no network resolution.
 *
 * ### `did:web`
 * Domain-anchored DID. The DID Document is fetched via HTTPS from a well-known URL:
 * `https://<domain>/.well-known/did.json`
 * Results are cached in memory for [CACHE_TTL_MS] (default 5 minutes).
 * Used as an enterprise power-user feature — self-host a DID Document on your domain.
 *
 * ## Resolution result
 * [DidDocument] wraps the resolved public key(s) and DID controller.
 * For AURA's purposes, the primary key is an `ECDSA P-256` or `ML-DSA-65` verification key.
 *
 * See: https://w3.org/TR/did-core/
 * See: https://identity.foundation/peer-did-method-spec/
 * See: https://w3c-ccg.github.io/did-method-web/
 * See: ROADMAP §R&D-D
 */
@Singleton
class DidResolver @Inject constructor() {

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1_000L  // 5 minutes
        private const val DID_WEB_TIMEOUT_MS = 10_000

        // Multicodec prefixes
        private const val MULTICODEC_P256_PREFIX = 0x1200   // P-256 / secp256r1
        private const val MULTICODEC_ED25519_PREFIX = 0xED  // Ed25519

        const val METHOD_KEY  = "key"
        const val METHOD_PEER = "peer"
        const val METHOD_WEB  = "web"
    }

    // ── Cache for did:web documents ────────────────────────────────────────────

    private data class CacheEntry(val document: DidDocument, val expiresAt: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Resolve a DID to a [DidDocument].
     *
     * @param did  A fully-qualified DID string (e.g. `did:key:z6Mk...`).
     * @return [DidDocument] on success, null if the DID is unparseable or unreachable.
     */
    suspend fun resolve(did: String): DidDocument? {
        val parts = did.split(":")
        if (parts.size < 3 || parts[0] != "did") {
            Timber.w("DidResolver: invalid DID format: $did")
            return null
        }
        return when (parts[1]) {
            METHOD_KEY  -> resolveDidKey(did, parts[2])
            METHOD_PEER -> resolveDidPeer2(did, parts.drop(2).joinToString(":"))
            METHOD_WEB  -> resolveDidWeb(did, parts.drop(2).joinToString(":"))
            else        -> {
                Timber.w("DidResolver: unsupported DID method: ${parts[1]}")
                null
            }
        }
    }

    // ── did:key resolution ────────────────────────────────────────────────────

    private fun resolveDidKey(did: String, encodedKey: String): DidDocument? {
        return try {
            // Multibase prefix 'z' = Base58btc
            if (!encodedKey.startsWith("z")) {
                Timber.w("DidResolver: did:key uses unsupported multibase (expected 'z')")
                return null
            }
            val multicodecBytes = decodeBase58(encodedKey.substring(1))
            // First 2 bytes are the varint-encoded multicodec
            val codec = (multicodecBytes[0].toInt() and 0xFF) or
                ((multicodecBytes[1].toInt() and 0xFF) shl 8)
            val keyBytes = multicodecBytes.copyOfRange(2, multicodecBytes.size)

            val publicKey = when (codec) {
                MULTICODEC_P256_PREFIX -> decodeP256Key(keyBytes)
                else -> {
                    Timber.w("DidResolver: did:key unsupported codec 0x${codec.toString(16)}")
                    null
                }
            }

            Timber.d("DidResolver: resolved did:key → codec=0x${codec.toString(16)}")
            DidDocument(
                id = did,
                controller = did,
                method = METHOD_KEY,
                verificationKeyEncoded = keyBytes,
                ecPublicKey = publicKey
            )
        } catch (e: Exception) {
            Timber.e(e, "DidResolver: did:key resolution failed")
            null
        }
    }

    // ── did:peer:2 resolution ─────────────────────────────────────────────────

    /**
     * Encode an AURA exchange public key as a `did:peer:2` DID.
     *
     * The AURA did:peer:2 format encodes the P-256 SubjectPublicKeyInfo DER bytes
     * as Base64url without padding, prefixed with the method-specific identifier.
     *
     * @param publicKeyDer P-256 public key in X.509 SubjectPublicKeyInfo DER format.
     * @return `did:peer:2.<base64url(keyDer)>`
     */
    fun encodePeer2Did(publicKeyDer: ByteArray): String {
        val encoded = Base64.encodeToString(publicKeyDer, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "did:peer:2.$encoded"
    }

    private fun resolveDidPeer2(did: String, identifier: String): DidDocument? {
        return try {
            // AURA did:peer:2 uses Base64url-encoded SubjectPublicKeyInfo
            if (!identifier.startsWith("2.")) {
                Timber.w("DidResolver: did:peer identifier must start with '2.'")
                return null
            }
            val encoded = identifier.substring(2)
            val keyDer = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val ecKey = decodeP256Key(keyDer)

            Timber.d("DidResolver: resolved did:peer:2 (${keyDer.size} bytes)")
            DidDocument(
                id = did,
                controller = did,
                method = METHOD_PEER,
                verificationKeyEncoded = keyDer,
                ecPublicKey = ecKey
            )
        } catch (e: Exception) {
            Timber.e(e, "DidResolver: did:peer:2 resolution failed")
            null
        }
    }

    // ── did:web resolution ────────────────────────────────────────────────────

    private suspend fun resolveDidWeb(did: String, domain: String): DidDocument? =
        withContext(Dispatchers.IO) {
            // Check cache first
            cache[did]?.let { entry ->
                if (System.currentTimeMillis() < entry.expiresAt) {
                    Timber.d("DidResolver: did:web cache hit for $domain")
                    return@withContext entry.document
                }
            }

            try {
                val url = "https://$domain/.well-known/did.json"
                Timber.d("DidResolver: fetching did:web from $url")
                val json = URL(url).openConnection().apply {
                    connectTimeout = DID_WEB_TIMEOUT_MS
                    readTimeout = DID_WEB_TIMEOUT_MS
                }.getInputStream().bufferedReader().readText()

                val doc = parseDidDocument(did, json) ?: return@withContext null

                // Cache the result
                cache[did] = CacheEntry(doc, System.currentTimeMillis() + CACHE_TTL_MS)
                Timber.d("DidResolver: did:web resolved and cached for $domain")
                doc
            } catch (e: Exception) {
                Timber.e(e, "DidResolver: did:web fetch failed for $domain")
                null
            }
        }

    // ── DID Document JSON parser (simplified W3C DID Core subset) ─────────────

    private fun parseDidDocument(did: String, json: String): DidDocument? {
        return try {
            val obj = JSONObject(json)
            val docId = obj.optString("id", did)

            // Extract first verificationMethod public key
            val vmArray = obj.optJSONArray("verificationMethod") ?: return DidDocument(
                id = docId, controller = docId, method = METHOD_WEB,
                verificationKeyEncoded = ByteArray(0), ecPublicKey = null
            )

            val vm = vmArray.getJSONObject(0)
            val jwk = vm.optJSONObject("publicKeyJwk")
            val keyEncoded = jwk?.optString("x")?.let { x ->
                Base64.decode(x, Base64.URL_SAFE or Base64.NO_PADDING)
            } ?: vm.optString("publicKeyBase64").let { b64 ->
                if (b64.isNullOrBlank()) ByteArray(0)
                else Base64.decode(b64, Base64.DEFAULT)
            }

            val ecKey = runCatching { decodeP256Key(keyEncoded) }.getOrNull()
            DidDocument(
                id = docId,
                controller = obj.optString("controller", docId),
                method = METHOD_WEB,
                verificationKeyEncoded = keyEncoded,
                ecPublicKey = ecKey
            )
        } catch (e: Exception) {
            Timber.e(e, "DidResolver: DID document parse failed")
            null
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun decodeP256Key(keyBytes: ByteArray): ECPublicKey? = runCatching {
        val kf = KeyFactory.getInstance("EC")
        kf.generatePublic(X509EncodedKeySpec(keyBytes)) as ECPublicKey
    }.getOrNull()

    /**
     * Base58btc decode — used for did:key multibase decoding.
     * Alphabet: `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`
     */
    private fun decodeBase58(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (char in input) {
            val digit = alphabet.indexOf(char)
            if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $char")
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = input.takeWhile { it == '1' }.length
        val decoded = num.toByteArray().let { bytes ->
            if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        return ByteArray(leadingZeros) + decoded
    }
}

/**
 * Resolved DID Document — AURA-relevant subset of the W3C DID Core spec.
 *
 * @param id                    The resolved DID.
 * @param controller            Controller DID (often same as [id] for self-sovereign DIDs).
 * @param method                DID method: "key", "peer", or "web".
 * @param verificationKeyEncoded Raw key bytes (DER or compressed point).
 * @param ecPublicKey           Parsed EC public key if the key is a P-256 ECDSA key; null otherwise.
 */
data class DidDocument(
    val id: String,
    val controller: String,
    val method: String,
    val verificationKeyEncoded: ByteArray,
    val ecPublicKey: ECPublicKey?
) {
    override fun equals(other: Any?): Boolean =
        other is DidDocument && id == other.id &&
            verificationKeyEncoded.contentEquals(other.verificationKeyEncoded)

    override fun hashCode(): Int = id.hashCode() * 31 + verificationKeyEncoded.contentHashCode()
}
