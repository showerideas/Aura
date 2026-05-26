package com.showerideas.aura.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 47 — One-time prekey pool for PQXDH.
 *
 * Manages a pool of [POOL_TARGET] one-time prekeys, each consisting of a paired
 * (X25519, ML-KEM-768) keypair. One-time prekeys provide per-packet forward
 * secrecy: they are deleted immediately after a single use.
 *
 * ## Pool management
 * - Pool is pre-generated to [POOL_TARGET] on first use or when depleted below [POOL_MIN].
 * - [take] removes and returns one key, zero-fills nothing (caller calls [OneTimePreKey.destroy]).
 * - [replenish] is called from WorkManager when pool drops below [POOL_MIN].
 * - Stored in [EncryptedSharedPreferences] — private key material encrypted at rest.
 *
 * ## Pool exhaustion
 * If pool is empty, [take] returns null. PQXDH sender should delay until pool is replenished
 * or fall back to signed-prekey-only mode (no per-packet forward secrecy).
 *
 * See: [signal.org — PQXDH §3.4 One-Time Prekeys]
 */
@Singleton
class OneTimePreKeyStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        /** Target pool size when fully replenished. */
        const val POOL_TARGET = 10

        /** Minimum pool size before WorkManager replenishment is triggered. */
        const val POOL_MIN = 3

        private const val PREFS_NAME = "otpk_pool"
        private const val KEY_NEXT_ID = "otpk_next_id"
        private const val KEY_IDS     = "otpk_ids"  // comma-separated list of active IDs
    }

    private val rng = SecureRandom()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Current pool size.
     */
    val poolSize: Int get() = activeIds().size

    /**
     * True when pool has dropped below [POOL_MIN] — caller should trigger replenishment.
     */
    val needsReplenishment: Boolean get() = poolSize < POOL_MIN

    /**
     * Take one prekey from the pool and remove it (one-time use).
     *
     * @return [OneTimePreKey] or null if pool is empty.
     */
    fun take(): OneTimePreKey? {
        val ids = activeIds()
        if (ids.isEmpty()) {
            Timber.w("OneTimePreKeyStore: pool empty — no one-time prekeys available")
            return null
        }
        val id = ids.first()
        val key = load(id) ?: run {
            Timber.w("OneTimePreKeyStore: key $id not found in store — removing from ID list")
            updateActiveIds(ids - id)
            return take()  // try next
        }
        updateActiveIds(ids - id)
        deleteKey(id)
        Timber.i("OneTimePreKeyStore: consumed key id=$id — pool size now ${poolSize}")
        if (needsReplenishment) {
            Timber.w("OneTimePreKeyStore: pool below threshold ($poolSize < $POOL_MIN) — replenishment needed")
        }
        return key
    }

    /**
     * Get the public components of all prekeys in the pool (for bundle publication).
     * Does NOT remove keys from the pool.
     */
    fun getPublicKeys(): List<Pair<Int, ByteArray>> {
        return activeIds().mapNotNull { id ->
            load(id)?.let { key -> Pair(id, key.x25519PublicKey + key.mlkem768PublicKey) }
        }
    }

    /**
     * Replenish the pool up to [POOL_TARGET] keys.
     * Call from WorkManager [OneTimePreKeyReplenishWorker] when [needsReplenishment] is true.
     */
    fun replenish() {
        val current = poolSize
        val needed  = POOL_TARGET - current
        if (needed <= 0) return
        Timber.i("OneTimePreKeyStore: replenishing $needed keys (current=$current)")
        repeat(needed) { generateAndStore() }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun generateAndStore(): Int {
        val nextId = prefs.getInt(KEY_NEXT_ID, 1)

        // Generate X25519 keypair
        val x25519Gen = X25519KeyPairGenerator()
        x25519Gen.init(X25519KeyGenerationParameters(rng))
        val x25519Pair = x25519Gen.generateKeyPair()
        val x25519Priv = (x25519Pair.private as X25519PrivateKeyParameters).encoded
        val x25519Pub  = (x25519Pair.public  as X25519PublicKeyParameters).encoded

        // Generate ML-KEM-768 keypair
        val mlkemGen = MLKEMKeyPairGenerator()
        mlkemGen.init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        val mlkemPair = mlkemGen.generateKeyPair()
        val mlkemPriv = (mlkemPair.private as MLKEMPrivateKeyParameters).encoded
        val mlkemPub  = (mlkemPair.public  as MLKEMPublicKeyParameters).encoded

        // Persist (encrypted at rest via EncryptedSharedPreferences)
        prefs.edit()
            .putString("otpk_${nextId}_x25519_pub",  encode(x25519Pub))
            .putString("otpk_${nextId}_x25519_priv", encode(x25519Priv))
            .putString("otpk_${nextId}_mlkem_pub",   encode(mlkemPub))
            .putString("otpk_${nextId}_mlkem_priv",  encode(mlkemPriv))
            .putInt(KEY_NEXT_ID, nextId + 1)
            .apply()

        val ids = activeIds() + nextId
        updateActiveIds(ids)
        Timber.d("OneTimePreKeyStore: generated key id=$nextId")
        return nextId
    }

    private fun load(id: Int): OneTimePreKey? {
        val x25519Pub  = prefs.getString("otpk_${id}_x25519_pub",  null)?.let { decode(it) } ?: return null
        val x25519Priv = prefs.getString("otpk_${id}_x25519_priv", null)?.let { decode(it) } ?: return null
        val mlkemPub   = prefs.getString("otpk_${id}_mlkem_pub",   null)?.let { decode(it) } ?: return null
        val mlkemPriv  = prefs.getString("otpk_${id}_mlkem_priv",  null)?.let { decode(it) } ?: return null
        return OneTimePreKey(
            id                 = id,
            x25519PublicKey    = x25519Pub,
            x25519PrivateKey   = x25519Priv,
            mlkem768PublicKey  = mlkemPub,
            mlkem768PrivateKey = mlkemPriv,
        )
    }

    private fun deleteKey(id: Int) {
        prefs.edit()
            .remove("otpk_${id}_x25519_pub")
            .remove("otpk_${id}_x25519_priv")
            .remove("otpk_${id}_mlkem_pub")
            .remove("otpk_${id}_mlkem_priv")
            .apply()
    }

    private fun activeIds(): List<Int> {
        val raw = prefs.getString(KEY_IDS, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    private fun updateActiveIds(ids: List<Int>) {
        prefs.edit().putString(KEY_IDS, ids.joinToString(",")).apply()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}
