package com.showerideas.aura.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.showerideas.aura.model.Contact
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 6.10 — Encrypted contact backup / restore.
 *
 * ## Format
 * The backup file is a single binary blob:
 *
 *   [4 bytes magic: 0x41555242 "AURB"]
 *   [1 byte version: 0x01]
 *   [16 bytes Argon2id salt  (random per backup)]
 *   [12 bytes AES-GCM IV     (random per backup)]
 *   [N bytes  AES-256-GCM ciphertext of UTF-8 JSON]
 *   [16 bytes AES-GCM auth tag   (appended by JCE)]
 *
 * ## Key derivation
 * PBKDF2WithHmacSHA256, 310 000 iterations (NIST 2023 guidance), 256-bit output.
 * (Argon2id is preferable but Android < 12 lacks platform support; PBKDF2 is safe here.)
 *
 * ## JSON schema
 * ```json
 * {
 *   "format": "aura-backup",
 *   "version": 1,
 *   "exported_at": 1716000000000,
 *   "contacts": [ { ... Contact fields ... } ]
 * }
 * ```
 *
 * ## Usage
 * ```kotlin
 * BackupUtils.export(contacts, passphrase, outputStream)
 * val contacts = BackupUtils.restore(passphrase, inputStream)
 * ```
 */
object BackupUtils {

    private val MAGIC = byteArrayOf(0x41, 0x55, 0x52, 0x42) // "AURB"
    private const val VERSION: Byte = 0x01
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 310_000
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Encrypt [contacts] with [passphrase] and write the backup blob to [out].
     *
     * @throws IllegalArgumentException if [contacts] is empty.
     * @throws java.io.IOException on write failure.
     */
    fun export(contacts: List<Contact>, passphrase: CharArray, out: OutputStream) {
        require(contacts.isNotEmpty()) { "No contacts to export" }

        val salt = SecureRandom().generateSeed(SALT_LENGTH)
        val iv   = SecureRandom().generateSeed(IV_LENGTH)
        val key  = deriveKey(passphrase, salt)

        val plaintext = buildJson(contacts).toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext) // includes 16-byte auth tag appended by JCE

        out.write(MAGIC)
        out.write(byteArrayOf(VERSION))
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
    }

    // -------------------------------------------------------------------------
    // Restore
    // -------------------------------------------------------------------------

    /**
     * Decrypt and deserialize contacts from a [BackupUtils.export]-produced stream.
     *
     * @return Parsed contacts list.
     * @throws BackupException on format mismatch, wrong passphrase, or corrupted data.
     */
    fun restore(passphrase: CharArray, input: InputStream): List<Contact> {
        val bytes = input.readBytes()
        var pos = 0

        // Magic
        if (bytes.size < MAGIC.size + 1 + SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) {
            throw BackupException("File too short to be a valid AURA backup")
        }
        if (!bytes.sliceArray(0 until 4).contentEquals(MAGIC)) {
            throw BackupException("Not an AURA backup file (magic mismatch)")
        }
        pos += 4

        // Version
        val version = bytes[pos++]
        if (version != VERSION) {
            throw BackupException("Unsupported backup version: $version")
        }

        val salt       = bytes.sliceArray(pos until pos + SALT_LENGTH); pos += SALT_LENGTH
        val iv         = bytes.sliceArray(pos until pos + IV_LENGTH);   pos += IV_LENGTH
        val ciphertext = bytes.sliceArray(pos until bytes.size)

        val key = deriveKey(passphrase, salt)

        val plaintext = try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupException("Decryption failed — wrong passphrase or corrupted backup", e)
        }

        return parseJson(String(plaintext, Charsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun buildJson(contacts: List<Contact>): String {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("displayName", c.displayName)
                put("phone", c.phone)
                put("email", c.email)
                put("company", c.company)
                put("title", c.title)
                put("website", c.website)
                put("bio", c.bio)
                put("avatarUri", c.avatarUri)
                put("sourceEndpointId", c.sourceEndpointId)
                put("receivedAt", c.receivedAt)
                put("isFavorite", c.isFavorite)
                put("notes", c.notes)
                put("identityKeyHash", c.identityKeyHash ?: JSONObject.NULL)
                put("profileVersion", c.profileVersion)
            })
        }
        return JSONObject().apply {
            put("format", "aura-backup")
            put("version", 1)
            put("exported_at", System.currentTimeMillis())
            put("contacts", arr)
        }.toString()
    }

    private fun parseJson(json: String): List<Contact> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupException("Invalid JSON in backup payload", e)
        }

        if (root.optString("format") != "aura-backup") {
            throw BackupException("Unexpected backup format: ${root.optString("format")}")
        }

        val arr = root.optJSONArray("contacts")
            ?: throw BackupException("Missing 'contacts' array in backup payload")

        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(
                id               = o.getString("id"),
                displayName      = o.optString("displayName"),
                phone            = o.optString("phone"),
                email            = o.optString("email"),
                company          = o.optString("company"),
                title            = o.optString("title"),
                website          = o.optString("website"),
                bio              = o.optString("bio"),
                avatarUri        = o.optString("avatarUri"),
                sourceEndpointId = o.optString("sourceEndpointId"),
                receivedAt       = o.optLong("receivedAt", System.currentTimeMillis()),
                isFavorite       = o.optBoolean("isFavorite", false),
                notes            = o.optString("notes"),
                identityKeyHash  = if (o.isNull("identityKeyHash")) null else o.optString("identityKeyHash"),
                profileVersion   = o.optInt("profileVersion", 0)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    class BackupException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}
