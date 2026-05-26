package com.showerideas.aura.identity

import android.util.Base64
import com.showerideas.aura.model.Profile
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 51 — Verifiable Credential issuer.
 *
 * Issues signed AURA profile VCs using:
 * - DID derivation: `did:key` from P-256 identity public key
 * - Proof: JsonWebSignature2020 — detached JWS payload (RFC 7797 §3)
 *
 * ## DID:key derivation
 * 1. Take P-256 compressed public key (33 bytes)
 * 2. Prepend multicodec 0x1200 (P-256 compressed key codec, 2-byte varint)
 * 3. Base58btc-encode the result
 * 4. Prefix with "did:key:z"
 *
 * ## JsonWebSignature2020 detached JWS
 * JWS header: {"alg":"ES256","b64":false,"crit":["b64"]}
 * JWS payload: omitted (detached — verifier reconstructs from VC body)
 * JWS signature: ES256 over ascii(base64url(header)) + "." + VC_JSON
 * JWS encoding:  base64url(header) + ".." + base64url(signature)
 *
 * See: [w3c-ccg.github.io/di-jws2020]
 */
@Singleton
class VcIssuer @Inject constructor() {

    private val rng = SecureRandom()

    /**
     * Issue a signed [VerifiableCredential] for a profile.
     * @param profile  AURA profile to wrap as credential subject.
     * @param ecPriv   P-256 private key for signing.
     * @param ecPub    P-256 public key for DID derivation and verification method.
     */
    fun issue(profile: Profile, ecPriv: ECPrivateKey, ecPub: ECPublicKey): VerifiableCredential {
        val did = deriveDid(ecPub)
        val vc  = VerifiableCredential.fromProfile(profile, did)
        val proof = sign(vc, ecPriv, did)
        return vc.copy(proof = proof)
    }

    /**
     * Verify a [VerifiableCredential] proof using the embedded verification method.
     * @return true if proof is valid and VC is within its validity period.
     */
    fun verify(vc: VerifiableCredential, ecPub: ECPublicKey): Boolean {
        val proof = vc.proof ?: return false
        if (Instant.now().isAfter(vc.validUntil)) {
            Timber.w("VC expired — validUntil=${vc.validUntil}")
            return false
        }
        return try {
            val (headerB64, _, sigB64) = proof.jws.split(".")
            val sigBytes = Base64.decode(sigB64, Base64.URL_SAFE or Base64.NO_PADDING)
            val vcNoProof = vc.copy(proof = null).toJsonString()
            val signedData = "$headerB64.$vcNoProof".toByteArray(Charsets.UTF_8)
            Signature.getInstance("SHA256withECDSA").also {
                it.initVerify(ecPub)
                it.update(signedData)
            }.verify(rawToDerEcSig(sigBytes)).also { ok ->
                if (!ok) Timber.w("VC proof verification failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "VC verification exception")
            false
        }
    }

    /** Derive `did:key:z<base58btc(multicodec(P-256 compressed))>` from a P-256 public key. */
    fun deriveDid(ecPub: ECPublicKey): String {
        val compressed = compressPoint(ecPub)
        // Multicodec 0x1200 for P-256 compressed key (2-byte little-endian varint)
        val multicodec = byteArrayOf(0x12.toByte(), 0x00.toByte()) + compressed
        val encoded = base58btc(multicodec)
        return "did:key:z$encoded"
    }

    // ---- Internals -----------------------------------------------------------

    private fun sign(vc: VerifiableCredential, ecPriv: ECPrivateKey, did: String): JsonWebSignature2020Proof {
        val headerJson = """{"alg":"ES256","b64":false,"crit":["b64"]}"""
        val headerB64  = base64url(headerJson.toByteArray())
        val vcJson = vc.toJsonString()
        val signedData = "$headerB64.$vcJson".toByteArray(Charsets.UTF_8)
        val derSig = Signature.getInstance("SHA256withECDSA").also {
            it.initSign(ecPriv, rng)
            it.update(signedData)
        }.sign()
        val rawSig = derToRawEcSig(derSig)
        val jws = "$headerB64..${base64url(rawSig)}"
        return JsonWebSignature2020Proof(
            created = Instant.now(),
            verificationMethod = "$did#key-1",
            jws = jws
        )
    }

    private fun compressPoint(pub: ECPublicKey): ByteArray {
        // Extract X,Y from SubjectPublicKeyInfo DER and build compressed form
        // P-256 uncompressed key: 0x04 || X(32) || Y(32) — last 65 bytes of encoded
        val encoded = pub.encoded
        val uncompressed = encoded.copyOfRange(encoded.size - 65, encoded.size)
        val x = uncompressed.copyOfRange(1, 33)
        val y = uncompressed.copyOfRange(33, 65)
        val prefix = if (y.last().toInt() and 1 == 0) 0x02.toByte() else 0x03.toByte()
        return byteArrayOf(prefix) + x
    }

    private fun base58btc(input: ByteArray): String {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = java.math.BigInteger(1, input)
        val sb = StringBuilder()
        val base = java.math.BigInteger.valueOf(58)
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(base)
            sb.insert(0, ALPHABET[r.toInt()])
            n = q
        }
        input.takeWhile { it == 0.toByte() }.forEach { sb.insert(0, ALPHABET[0]) }
        return sb.toString()
    }

    private fun base64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun derToRawEcSig(der: ByteArray): ByteArray {
        var i = 2
        val rLen = der[i + 1].toInt() and 0xFF; i += 2
        val r = der.copyOfRange(i, i + rLen); i += rLen
        val sLen = der[i + 1].toInt() and 0xFF; i += 2
        val s = der.copyOfRange(i, i + sLen)
        val raw = ByteArray(64)
        r.copyInto(raw, 32 - r.size.coerceAtMost(32))
        s.copyInto(raw, 64 - s.size.coerceAtMost(32))
        return raw
    }

    private fun rawToDerEcSig(raw: ByteArray): ByteArray {
        val r = raw.copyOfRange(0, 32).let { if (it[0] < 0) byteArrayOf(0) + it else it }
        val s = raw.copyOfRange(32, 64).let { if (it[0] < 0) byteArrayOf(0) + it else it }
        val len = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, len.toByte(), 0x02, r.size.toByte()) + r +
               byteArrayOf(0x02, s.size.toByte()) + s
    }
}
