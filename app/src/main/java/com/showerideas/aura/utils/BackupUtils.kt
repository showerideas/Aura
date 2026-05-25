package com.showerideas.aura.utils

import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 6.10 — Encrypted contact backup and restore utilities.
 *
 * ## Encryption scheme
 * - KDF: PBKDF2WithHmacSHA256 (t=200_000 iterations, 256-bit key)
 *   Note: Argon2id is preferred but requires BouncyCastle; PBKDF2 is used
 *   here for Android KeyStore compat. Upgrade to Argon2id in Phase 8.x.
 * - Cipher: AES-256-GCM (128-bit auth tag)
 * - Format: [4B version][16B salt][12B IV][ciphertext+tag]
 *
 * ## Backup JSON schema (version 1)
 * ```json
 * {
 *   "version": 1,
 *   "exportedAt": 1716600000000,
 *   "contacts": [ { ...Contact fields... } ],
 *   "knownPeers": [ { ...KnownPeer fields... } ]
 * }
 * ```
 */
object BackupUtils {

    private const val BACKUP_VERSION = 1
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    // 4-byte magic prefix: 'AURA' in ASCII
    private val MAGIC = byteArrayOf(0x41, 0x55, 0x52, 0x41)

    sealed class RestoreResult {
        data class Success(val contactsRestored: Int, val peersRestored: Int) : RestoreResult()
        data class WrongPassphrase(val message: String = "Incorrect passphrase") : RestoreResult()
        data class CorruptBackup(val message: String) : RestoreResult()
        data class VersionMismatch(val found: Int, val supported: Int) : RestoreResult()
    }

    /**
     * Export contacts and known peers to an encrypted backup byte array.
     *
     * @param contactsJson  JSON array of serialized contacts
     * @param knownPeersJson JSON array of serialized known peers
     * @param passphrase    User-supplied passphrase for PBKDF2 key derivation
     * @return  Encrypted backup bytes: MAGIC + version(4) + salt(16) + IV(12) + AES-GCM ciphertext
     */
    fun export(
        contactsJson: String,
        knownPeersJson: String,
        passphrase: String
    ): ByteArray {
        val payload = JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("contacts", JSONArray(contactsJson))
            put("knownPeers", JSONArray(knownPeersJson))
        }.toString()

        val rng = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { rng.nextBytes(it) }
        val iv   = ByteArray(IV_LENGTH).also { rng.nextBytes(it) }
        val key  = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        return MAGIC +
               BACKUP_VERSION.toByteArray4() +
               salt +
               iv +
               ciphertext
    }

    /**
     * Restore from an encrypted backup byte array.
     *
     * @return [RestoreResult] indicating success or failure reason
     */
    fun restore(encryptedBytes: ByteArray, passphrase: String): RestoreResult {
        return try {
            if (encryptedBytes.size < MAGIC.size + 4 + SALT_LENGTH + IV_LENGTH + GCM_TAG_LENGTH / 8) {
                return RestoreResult.CorruptBackup("Backup too short to be valid")
            }
            val magic = encryptedBytes.slice(0..3).toByteArray()
            if (!magic.contentEquals(MAGIC)) {
                return RestoreResult.CorruptBackup("Not a valid AURA backup file")
            }
            val version = encryptedBytes.slice(4..7).toByteArray().toInt4()
            if (version != BACKUP_VERSION) {
                return RestoreResult.VersionMismatch(found = version, supported = BACKUP_VERSION)
            }
            val salt       = encryptedBytes.slice(8 until 8 + SALT_LENGTH).toByteArray()
            val iv         = encryptedBytes.slice(8 + SALT_LENGTH until 8 + SALT_LENGTH + IV_LENGTH).toByteArray()
            val ciphertext = encryptedBytes.slice(8 + SALT_LENGTH + IV_LENGTH until encryptedBytes.size).toByteArray()

            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plaintext = try {
                cipher.doFinal(ciphertext)
            } catch (e: javax.crypto.BadPaddingException) {
                return RestoreResult.WrongPassphrase()
            }

            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            val contacts  = json.getJSONArray("contacts")
            val knownPeers = json.getJSONArray("knownPeers")
            RestoreResult.Success(contacts.length(), knownPeers.length())
        } catch (e: Exception) {
            RestoreResult.CorruptBackup("Backup could not be parsed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    private fun Int.toByteArray4(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(),
        (this shr 8).toByte(), this.toByte()
    )

    private fun ByteArray.toInt4(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8)  or  (this[3].toInt() and 0xFF)
}
