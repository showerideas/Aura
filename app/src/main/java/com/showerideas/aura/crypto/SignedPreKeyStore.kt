package com.showerideas.aura.crypto

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 47 — Signed prekey store with 7-day rotation.
 *
 * Manages a single active [SignedPreKey] (X25519 keypair + identity signature).
 * Rotation is checked on every access: if the current key has expired
 * ([SignedPreKey.isExpired]), a new one is generated, signed with the identity
 * key, and persisted.
 *
 * Stored in DataStore (`signed_prekey_prefs`) — lightweight; only one key at a time.
 *
 * See: [signal.org — PQXDH specification §3.3 Signed Prekeys]
 */
@Singleton
class SignedPreKeyStore @Inject constructor(
    private val context: Context,
) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "signed_prekey_prefs"
    )

    private val rng = SecureRandom()
    private var cachedKey: SignedPreKey? = null

    companion object {
        private val KEY_ID         = intPreferencesKey("spk_id")
        private val KEY_PUBLIC     = stringPreferencesKey("spk_pub")
        private val KEY_PRIVATE    = stringPreferencesKey("spk_priv")
        private val KEY_SIGNATURE  = stringPreferencesKey("spk_sig")
        private val KEY_CREATED_AT = longPreferencesKey("spk_created_at")
    }

    /**
     * Load the current signed prekey, rotating if it has expired.
     * On expiry, a new X25519 keypair is generated and signed.
     *
     * @param identityPrivateKeyBytes Raw P-256 identity private key for signing.
     * @return Current [SignedPreKey].
     */
    suspend fun getOrRotate(identityPrivateKeyBytes: ByteArray): SignedPreKey {
        val cached = cachedKey
        if (cached != null && !cached.isExpired) return cached

        val prefs = context.dataStore.data.first()
        val storedId        = prefs[KEY_ID]
        val storedCreatedAt = prefs[KEY_CREATED_AT]

        // Load stored key and check expiry
        if (storedId != null && storedCreatedAt != null) {
            val stored = SignedPreKey(
                id          = storedId,
                publicKey   = Base64.getDecoder().decode(prefs[KEY_PUBLIC] ?: ""),
                privateKey  = Base64.getDecoder().decode(prefs[KEY_PRIVATE] ?: ""),
                signature   = Base64.getDecoder().decode(prefs[KEY_SIGNATURE] ?: ""),
                createdAtMs = storedCreatedAt,
            )
            if (!stored.isExpired) {
                cachedKey = stored
                return stored
            }
            stored.destroy()  // zero-fill expired private key
            Timber.i("SignedPreKeyStore: key $storedId expired — rotating")
        }

        // Generate new key
        return rotate(identityPrivateKeyBytes)
    }

    /**
     * Force generation of a new signed prekey and persist it.
     * Called when [getOrRotate] detects expiry.
     */
    suspend fun rotate(identityPrivateKeyBytes: ByteArray): SignedPreKey {
        val prefs      = context.dataStore.data.first()
        val newId      = (prefs[KEY_ID] ?: 0) + 1
        val x25519Pair = generateX25519()
        val signature  = signX25519Public(x25519Pair.second, identityPrivateKeyBytes)
        val now        = System.currentTimeMillis()

        context.dataStore.edit { mutablePrefs ->
            mutablePrefs[KEY_ID]         = newId
            mutablePrefs[KEY_PUBLIC]     = Base64.getEncoder().encodeToString(x25519Pair.second)
            mutablePrefs[KEY_PRIVATE]    = Base64.getEncoder().encodeToString(x25519Pair.first)
            mutablePrefs[KEY_SIGNATURE]  = Base64.getEncoder().encodeToString(signature)
            mutablePrefs[KEY_CREATED_AT] = now
        }

        val key = SignedPreKey(
            id          = newId,
            publicKey   = x25519Pair.second,
            privateKey  = x25519Pair.first,
            signature   = signature,
            createdAtMs = now,
        )
        cachedKey = key
        Timber.i("SignedPreKeyStore: rotated to key id=$newId expiresAt=${key.expiresAtMs}")
        return key
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Returns (privateKeyBytes, publicKeyBytes). */
    private fun generateX25519(): Pair<ByteArray, ByteArray> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub  = (pair.public  as X25519PublicKeyParameters).encoded
        return Pair(priv, pub)
    }

    /**
     * Sign X25519 public key bytes with P-256 ECDSA identity key.
     * Signature covers the raw 32-byte X25519 public key.
     */
    private fun signX25519Public(publicKeyBytes: ByteArray, identityPrivateKeyBytes: ByteArray): ByteArray {
        return runCatching {
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val keySpec    = java.security.spec.PKCS8EncodedKeySpec(identityPrivateKeyBytes)
            val privateKey = keyFactory.generatePrivate(keySpec)
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(publicKeyBytes)
                sign()
            }
        }.getOrElse { e ->
            Timber.e(e, "SignedPreKeyStore: signing failed")
            ByteArray(0)
        }
    }
}
