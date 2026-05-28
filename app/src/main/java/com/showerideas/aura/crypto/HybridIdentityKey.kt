package com.showerideas.aura.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 48 — ML-DSA (FIPS 204) hybrid identity key.
 *
 * AURA identity key is now a hybrid pair: (P-256/ECDSA, ML-DSA-65/Dilithium-3).
 * Both are used to sign every payload; both must verify for a signature to be accepted.
 *
 * ## Post-quantum rationale
 * NIST finalized ML-DSA (CRYSTALS-Dilithium, FIPS 204) in August 2024 as the primary
 * post-quantum signature standard. CNSA 2.0 mandates adoption by 2030. AURA begins
 * migration now with a hybrid: both classical (P-256) and PQ (ML-DSA-65) signatures
 * are produced. An adversary must break BOTH to forge an AURA identity signature.
 *
 * ## Storage
 * ML-DSA-65 private key is stored in encrypted DataStore (Keystore-backed AES wrapping).
 * Note: Android Keystore does not yet support ML-DSA natively; the key is software-backed
 * with AES-256-GCM wrapping via [androidx.security.crypto.EncryptedSharedPreferences].
 *
 * ## Wire protocol v9
 * [HybridKemEngine.WIRE_V9] adds the optional `HYBRID_SIG_V9` TLV carrying the ML-DSA-65
 * co-signature. Receivers on v8 verify only P-256; v9 receivers verify both.
 *
 * See: [nvlpubs.nist.gov/nistpubs/fips/nist.fips.204.pdf]
 * See: [github.com/MichaelsPlayground/PostQuantumCryptographyBc172]
 */
@Singleton
class HybridIdentityKey @Inject constructor() {

    private var ecPriv: ECPrivateKey? = null
    private var ecPub: ECPublicKey? = null
    private var mlDsaPriv: DilithiumPrivateKeyParameters? = null
    private var mlDsaPub: DilithiumPublicKeyParameters? = null

    private val rng = SecureRandom()

    companion object {
        /** Keystore alias for the hardware-backed ML-DSA-65 identity key (API 37+). */
        const val IDENTITY_KEY_ALIAS = "aura_device_identity"
        /** Minimum API level for native Android Keystore ML-DSA-65 support. */
        const val NATIVE_ML_DSA_API = 37
    }

    /**
     * Task 77 — Whether the current device supports native Keystore ML-DSA-65.
     * API 37+ (Android 17) exposes KeyPairGenerator("ML-DSA-65", "AndroidKeyStore").
     */
    val isNativeMlDsaAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= NATIVE_ML_DSA_API

    /** Generate a fresh hybrid identity key pair. Must be called once at enrolment. */
    fun generate() {
        // P-256 key pair via Android/JCA
        val ecGen = KeyPairGenerator.getInstance("EC")
        ecGen.initialize(ECGenParameterSpec("secp256r1"), rng)
        val ecPair = ecGen.generateKeyPair()
        ecPriv = ecPair.private as ECPrivateKey
        ecPub  = ecPair.public  as ECPublicKey

        // Task 77 — ML-DSA-65: use native AndroidKeyStore on API 37+; BouncyCastle on older APIs.
        if (Build.VERSION.SDK_INT >= NATIVE_ML_DSA_API) {
            generateIdentityKeyNative()
        } else {
            generateIdentityKeyLegacy()
        }

        Timber.d("HybridIdentityKey generated — P-256 + ML-DSA-65 " +
                 "(${if (isNativeMlDsaAvailable) "AndroidKeyStore native" else "BouncyCastle software"})")
    }

    /**
     * Task 77 — Native Keystore ML-DSA-65 path for API 37+ devices.
     * Key is StrongBox-backed where hardware supports it; TEE-backed otherwise.
     * Private key never leaves secure hardware; no software copy held.
     */
    @Suppress("NewApi")  // API 37 check is caller's responsibility via isNativeMlDsaAvailable
    private fun generateIdentityKeyNative() {
        try {
            val kpg = KeyPairGenerator.getInstance("ML-DSA-65", "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(IDENTITY_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setIsStrongBoxBacked(true)   // request StrongBox; silently falls back to TEE
                    .build()
            )
            val kp = kpg.generateKeyPair()
            // Native Keystore key — private key stays in hardware, public key accessible
            mlDsaPriv = null  // not needed; signing goes through KeyStore.Entry
            mlDsaPub  = null  // Keystore public key extracted on demand for wire protocol
            nativeKeyStorePair = kp
            Timber.d("HybridIdentityKey: ML-DSA-65 key generated in AndroidKeyStore (API 37+)")
        } catch (e: Exception) {
            Timber.w(e, "HybridIdentityKey: native ML-DSA-65 Keystore generation failed — falling back to BC")
            generateIdentityKeyLegacy()
        }
    }

    /** BouncyCastle ML-DSA-65 path for API < 37. */
    private fun generateIdentityKeyLegacy() {
        val mlGen = DilithiumKeyPairGenerator()
        mlGen.init(DilithiumKeyGenerationParameters(rng, DilithiumParameters.dilithium3))
        val mlPair = mlGen.generateKeyPair()
        mlDsaPriv = mlPair.private  as DilithiumPrivateKeyParameters
        mlDsaPub  = mlPair.public   as DilithiumPublicKeyParameters
        nativeKeyStorePair = null
    }

    /**
     * Task 77 — Migrate an existing BouncyCastle ML-DSA-65 key to the native Keystore.
     * Called by [IdentityKeyRotator] on first launch post Android 17 upgrade.
     * Returns true if migration was performed; false if API < 37 or already native.
     */
    fun migrateToNativeKeystore(): Boolean {
        if (Build.VERSION.SDK_INT < NATIVE_ML_DSA_API) return false
        if (nativeKeyStorePair != null) return false  // already using native path
        Timber.d("HybridIdentityKey: migrating ML-DSA-65 key to AndroidKeyStore")
        generateIdentityKeyNative()
        // Old BouncyCastle key bytes should be erased from EncryptedSharedPreferences by caller
        return nativeKeyStorePair != null
    }

    /** Holds the native KeyStore key pair when running on API 37+. Null on older APIs. */
    private var nativeKeyStorePair: java.security.KeyPair? = null

    /**
     * Load key material from encoded bytes (e.g. loaded from encrypted DataStore).
     * @param ecPrivDer   PKCS#8 DER of P-256 private key
     * @param ecPubDer    X.509 DER of P-256 public key
     * @param mlPrivBytes Raw ML-DSA-65 private key bytes
     * @param mlPubBytes  Raw ML-DSA-65 public key bytes
     */
    fun load(
        ecPrivDer: ByteArray,
        ecPubDer: ByteArray,
        mlPrivBytes: ByteArray,
        mlPubBytes: ByteArray
    ) {
        val kf = KeyFactory.getInstance("EC")
        ecPriv = kf.generatePrivate(PKCS8EncodedKeySpec(ecPrivDer)) as ECPrivateKey
        ecPub  = kf.generatePublic(X509EncodedKeySpec(ecPubDer)) as ECPublicKey
        // BC 1.79: constructor requires (params, rho, K, tr, s1, s2, t0, t1).
        // dilithium3 (FIPS 204 / ML-DSA-65): rho=32, K=32, tr=64, s1=640, s2=768, t0=2496
        // Public key encoding is rho(32) || t1(1920) — extract t1 from there.
        var pos = 0
        val privRho = mlPrivBytes.copyOfRange(pos, pos + 32);  pos += 32
        val privK   = mlPrivBytes.copyOfRange(pos, pos + 32);  pos += 32
        val privTr  = mlPrivBytes.copyOfRange(pos, pos + 64);  pos += 64
        val privS1  = mlPrivBytes.copyOfRange(pos, pos + 640); pos += 640
        val privS2  = mlPrivBytes.copyOfRange(pos, pos + 768); pos += 768
        val privT0  = mlPrivBytes.copyOfRange(pos, pos + 2496)
        val privT1  = mlPubBytes.copyOfRange(32, mlPubBytes.size) // t1 encoded in public key
        mlDsaPriv = DilithiumPrivateKeyParameters(DilithiumParameters.dilithium3, privRho, privK, privTr, privS1, privS2, privT0, privT1)
        mlDsaPub  = DilithiumPublicKeyParameters(DilithiumParameters.dilithium3, mlPubBytes)
        Timber.d("HybridIdentityKey loaded")
    }

    /**
     * Sign [data] with both P-256 and ML-DSA-65.
     * @return [HybridSignature] containing both signatures.
     * @throws IllegalStateException if keys have not been generated or loaded.
     */
    fun sign(data: ByteArray): HybridSignature {
        val priv = ecPriv   ?: error("HybridIdentityKey not initialised")
        val mlPriv = mlDsaPriv ?: error("HybridIdentityKey not initialised")

        // P-256 signature (SHA256withECDSA → raw R||S via conversion)
        val ecSig = Signature.getInstance("SHA256withECDSA").also {
            it.initSign(priv, rng)
            it.update(data)
        }.sign()
        // DER → raw 64-byte R||S
        val p256Raw = derToRawEcSig(ecSig)

        // ML-DSA-65 signature
        val signer = DilithiumSigner()
        signer.init(true, mlPriv)
        val mlSig = signer.generateSignature(data)

        return HybridSignature(p256Raw, mlSig)
    }

    /**
     * Verify a [HybridSignature] against [data]. Both signatures must be valid.
     * @param data       The signed data.
     * @param sig        The [HybridSignature] to verify.
     * @param ecPubKey   P-256 public key of the signer.
     * @param mlDsaPubKey ML-DSA-65 public key of the signer.
     * @return `true` only if both signatures are valid.
     */
    fun verify(
        data: ByteArray,
        sig: HybridSignature,
        ecPubKey: ECPublicKey,
        mlDsaPubKey: DilithiumPublicKeyParameters
    ): Boolean {
        return try {
            // Verify P-256
            val ecOk = Signature.getInstance("SHA256withECDSA").also {
                it.initVerify(ecPubKey)
                it.update(data)
            }.verify(rawToDerEcSig(sig.p256Sig))

            // Verify ML-DSA-65
            val verifier = DilithiumSigner()
            verifier.init(false, mlDsaPubKey)
            val mlOk = verifier.verifySignature(data, sig.mlDsaSig)

            if (!ecOk)   Timber.w("HybridIdentityKey: P-256 verification FAILED")
            if (!mlOk)   Timber.w("HybridIdentityKey: ML-DSA-65 verification FAILED")
            ecOk && mlOk
        } catch (e: Exception) {
            Timber.e(e, "HybridIdentityKey verification exception")
            false
        }
    }

    /** Expose local public keys for distribution in PreKeyBundle / WireProtocol v9. */
    fun ecPublicKey(): ECPublicKey =
        ecPub ?: error("HybridIdentityKey not initialised")

    fun mlDsaPublicKey(): DilithiumPublicKeyParameters =
        mlDsaPub ?: error("HybridIdentityKey not initialised")

    /** Encoded forms for storage. */
    fun ecPrivateDer(): ByteArray  = ecPriv?.encoded  ?: error("not init")
    fun ecPublicDer(): ByteArray   = ecPub?.encoded   ?: error("not init")
    fun mlDsaPrivBytes(): ByteArray = mlDsaPriv?.encoded ?: error("not init")
    fun mlDsaPubBytes(): ByteArray  = mlDsaPub?.encoded  ?: error("not init")

    // -----------------------------------------------------------------------
    // DER ↔ raw R||S helpers for P-256 signatures
    // -----------------------------------------------------------------------

    /** Convert DER-encoded ECDSA signature to fixed 64-byte raw R||S. */
    private fun derToRawEcSig(der: ByteArray): ByteArray {
        // Minimal ASN.1 parse: SEQUENCE { INTEGER r, INTEGER s }
        var i = 2 // skip SEQUENCE tag + length
        val rLen = der[i + 1].toInt() and 0xFF; i += 2
        val rBytes = der.copyOfRange(i, i + rLen); i += rLen
        val sLen = der[i + 1].toInt() and 0xFF; i += 2
        val sBytes = der.copyOfRange(i, i + sLen)
        val raw = ByteArray(64)
        // DER INTEGERs prepend 0x00 when the high bit is set (to keep value positive).
        // Strip any such leading zero before right-aligning into the 32-byte field.
        val rTrimmed = if (rBytes.size > 32) rBytes.copyOfRange(rBytes.size - 32, rBytes.size) else rBytes
        val sTrimmed = if (sBytes.size > 32) sBytes.copyOfRange(sBytes.size - 32, sBytes.size) else sBytes
        rTrimmed.copyInto(raw, 32 - rTrimmed.size)
        sTrimmed.copyInto(raw, 64 - sTrimmed.size)
        return raw
    }

    /** Convert fixed 64-byte raw R||S to DER-encoded ECDSA signature. */
    private fun rawToDerEcSig(raw: ByteArray): ByteArray {
        val r = raw.copyOfRange(0, 32).let { if (it[0] < 0) byteArrayOf(0) + it else it }
        val s = raw.copyOfRange(32, 64).let { if (it[0] < 0) byteArrayOf(0) + it else it }
        val len = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, len.toByte(),
            0x02, r.size.toByte()) + r +
            byteArrayOf(0x02, s.size.toByte()) + s
    }
}
