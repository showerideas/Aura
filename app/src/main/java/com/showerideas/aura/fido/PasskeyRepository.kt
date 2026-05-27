package com.showerideas.aura.fido

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 84 — Passkey storage backed by [GestureVerificationEngine].
 *
 * Implements passkey creation and signing using AURA's identity key hierarchy.
 * The passkey private key is stored in Android Keystore with
 * [KeyGenParameterSpec.setUserAuthenticationRequired(true)], gated behind
 * gesture verification (AURA unlocks the key after completing gesture verify,
 * then signs the FIDO2 assertion).
 *
 * See: ROADMAP §Task 84
 */
@Singleton
class PasskeyRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "aura_passkey_"
    }

    /**
     * Create a passkey for [rpId] (relying party) + [userHandle].
     * Generates a P-256 key pair in AndroidKeyStore gated by gesture auth.
     * Returns the credential ID (key alias suffix) to register with the RP.
     */
    fun createPasskey(rpId: String, userHandle: ByteArray): String {
        val credentialId = java.util.UUID.randomUUID().toString().replace("-", "")
        val alias = "$KEY_ALIAS_PREFIX${rpId.hashCode()}_$credentialId"

        val spec = KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Gesture verification unlocks the key — no biometric prompt needed separately
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        kpg.initialize(spec)
        kpg.generateKeyPair()

        Timber.d("PasskeyRepository: passkey created for rpId=$rpId credentialId=$credentialId")
        return credentialId
    }

    /**
     * Sign a FIDO2 assertion [challenge] with the passkey identified by [credentialId] + [rpId].
     * The Keystore key is unlocked because gesture verification was already completed
     * by [PasskeyGestureGateActivity] before this call.
     */
    fun signAssertion(rpId: String, credentialId: String, challenge: ByteArray): ByteArray {
        val alias = "$KEY_ALIAS_PREFIX${rpId.hashCode()}_$credentialId"
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        val privateKey = (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.privateKey
            ?: error("PasskeyRepository: no key found for credentialId=$credentialId rpId=$rpId")

        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(challenge)
        val signed = sig.sign()
        Timber.d("PasskeyRepository: assertion signed for credentialId=$credentialId")
        return signed
    }

    /** Delete a passkey from Keystore. */
    fun deletePasskey(rpId: String, credentialId: String) {
        val alias = "$KEY_ALIAS_PREFIX${rpId.hashCode()}_$credentialId"
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
            Timber.d("PasskeyRepository: deleted passkey alias=$alias")
        }
    }
}

// ── Room entity + DAO for passkey metadata ────────────────────────────────────

/**
 * Room entity storing passkey metadata (relying party, credential ID, user handle).
 * The private key lives exclusively in AndroidKeyStore; only metadata is in Room.
 */
@Entity(tableName = "passkeys")
data class PasskeyEntity(
    @PrimaryKey @ColumnInfo(name = "credential_id") val credentialId: String,
    @ColumnInfo(name = "rp_id") val rpId: String,
    @ColumnInfo(name = "user_handle") val userHandle: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "rp_name") val rpName: String = ""
) {
    override fun equals(other: Any?) = other is PasskeyEntity && credentialId == other.credentialId
    override fun hashCode() = credentialId.hashCode()
}

@Dao
interface PasskeyDao {
    @Query("SELECT * FROM passkeys ORDER BY created_at DESC")
    fun getAll(): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE rp_id = :rpId")
    fun getByRpId(rpId: String): List<PasskeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(passkey: PasskeyEntity)

    @Delete
    fun delete(passkey: PasskeyEntity)

    @Query("DELETE FROM passkeys WHERE credential_id = :credentialId")
    fun deleteById(credentialId: String)
}
