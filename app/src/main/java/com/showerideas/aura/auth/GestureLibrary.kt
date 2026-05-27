package com.showerideas.aura.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-gesture library: up to [MAX_GESTURES] named gesture profiles per user.
 *
 * AURA supports a library of distinct gesture enrollments so users can assign
 * different gestures to different contexts (e.g. "work profile" = thumbs-up,
 * "personal profile" = peace sign). One gesture is always the "active" profile
 * used for authentication; others are stored and can be switched at any time.
 *
 * Storage
 * Each gesture is stored as a named embedding in [EncryptedSharedPreferences]
 * under a UUID slot key. Metadata (name, createdAt) is stored alongside.
 *
 * Zero-fill deletion
 * When a gesture is deleted, the slot is explicitly overwritten with zeros
 * before being removed from preferences, preventing recovery of embedding data
 * from the shared-preferences backing store.
 *
 * Active profile
 * [activeSlotId] is persisted so the correct gesture survives app restarts.
 * [GestureAuthManager] reads the active embedding from [GestureLibrary] during
 * authentication.
 */
@Singleton
class GestureLibrary @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Maximum number of gestures a user can enroll. */
        const val MAX_GESTURES = 5

        private const val PREFS_FILE  = "gesture_library_v1"
        private const val KEY_SLOTS   = "slot_ids"         // comma-delimited UUIDs
        private const val KEY_ACTIVE  = "active_slot_id"
        private const val SLOT_PREFIX = "slot_"
        private const val META_PREFIX = "meta_"
        private const val ZERO_FILLER = "DELETED_ZERO_FILL"
    }

    // Data types

    /**
     * A single enrolled gesture slot.
     *
     * @param id           Stable UUID for this slot.
     * @param name         User-visible label (e.g. "Work", "Personal").
     * @param createdAtMs  Epoch ms when this gesture was first enrolled.
     * @param sampleCount  Number of enrollment samples averaged into the centroid.
     */
    data class GestureSlot(
        val id         : String,
        val name       : String,
        val createdAtMs: Long,
        val sampleCount: Int
    )

    // Prefs access

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Slot management

    /**
     * All currently enrolled gesture slots (in enrollment order).
     */
    fun listSlots(): List<GestureSlot> {
        val ids = slotIds()
        return ids.mapNotNull { id -> loadMeta(id) }
    }

    /**
     * The currently active slot ID, or null if no gestures are enrolled.
     */
    fun activeSlotId(): String? = prefs.getString(KEY_ACTIVE, null)
        ?.takeIf { it.isNotBlank() && slotIds().contains(it) }

    /**
     * Switch the active gesture to [slotId].
     *
     * @throws IllegalArgumentException if [slotId] is not a known slot.
     */
    fun setActiveSlot(slotId: String) {
        require(slotIds().contains(slotId)) { "Unknown slot: $slotId" }
        prefs.edit().putString(KEY_ACTIVE, slotId).apply()
        Timber.i("GestureLibrary: active slot → %s (%s)", slotId.take(8), loadMeta(slotId)?.name)
    }

    /**
     * Enroll a new gesture and return its slot ID.
     *
     * @param name       User-visible label for this gesture profile.
     * @param embedding  63-float centroid embedding from [GestureAuthManager].
     * @param samples    Number of samples averaged (stored as metadata).
     * @return Slot ID of the new entry, or null if [MAX_GESTURES] is already reached.
     */
    fun enroll(name: String, embedding: FloatArray, samples: Int): String? {
        val ids = slotIds().toMutableList()
        if (ids.size >= MAX_GESTURES) {
            Timber.w("GestureLibrary: cannot enroll — already at MAX_GESTURES (%d)", MAX_GESTURES)
            return null
        }
        val id = java.util.UUID.randomUUID().toString()
        ids.add(id)

        prefs.edit()
            .putString("$SLOT_PREFIX$id", embedding.joinToString(","))
            .putString("$META_PREFIX$id", "$name|${System.currentTimeMillis()}|$samples")
            .putString(KEY_SLOTS, ids.joinToString(","))
            .apply()

        // Auto-activate the first enrolled gesture
        if (activeSlotId() == null) {
            prefs.edit().putString(KEY_ACTIVE, id).apply()
        }

        Timber.i("GestureLibrary: enrolled slot %s name='%s' samples=%d", id.take(8), name, samples)
        return id
    }

    /**
     * Read the embedding for a specific slot.
     *
     * @return 63-float embedding, or null if the slot is deleted or unreadable.
     */
    fun getEmbedding(slotId: String): FloatArray? {
        val raw = prefs.getString("$SLOT_PREFIX$slotId", null) ?: return null
        if (raw == ZERO_FILLER) return null   // already zero-filled
        return try {
            raw.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            Timber.w(e, "GestureLibrary: failed to parse embedding for slot %s", slotId.take(8))
            null
        }
    }

    /**
     * Read the active embedding for authentication, or null if none is enrolled.
     */
    fun activeEmbedding(): FloatArray? = activeSlotId()?.let { getEmbedding(it) }

    /**
     * Delete a gesture slot with zero-fill of the embedding data.
     *
     * The embedding bytes are overwritten with [ZERO_FILLER] before removal
     * so the raw shared-preferences XML on-disk cannot leak the gesture data
     * even if the device is accessed before the next GC / encryption cycle.
     *
     * If the deleted slot was active, the active pointer is moved to the
     * first remaining slot (or cleared if no slots remain).
     */
    fun delete(slotId: String) {
        val ids = slotIds().toMutableList()
        if (!ids.contains(slotId)) {
            Timber.w("GestureLibrary: delete called for unknown slot %s", slotId.take(8))
            return
        }

        // Zero-fill before removal
        prefs.edit()
            .putString("$SLOT_PREFIX$slotId", ZERO_FILLER)
            .apply()

        // Remove slot
        ids.remove(slotId)
        val edit = prefs.edit()
            .remove("$SLOT_PREFIX$slotId")
            .remove("$META_PREFIX$slotId")
            .putString(KEY_SLOTS, ids.joinToString(","))

        // Fix active pointer
        if (activeSlotId() == slotId) {
            val newActive = ids.firstOrNull()
            if (newActive != null) edit.putString(KEY_ACTIVE, newActive)
            else edit.remove(KEY_ACTIVE)
        }
        edit.apply()
        Timber.i("GestureLibrary: deleted slot %s (zero-filled + removed)", slotId.take(8))
    }

    /**
     * Update the display name of an existing slot.
     */
    fun rename(slotId: String, newName: String) {
        val meta = loadMeta(slotId) ?: return
        prefs.edit()
            .putString("$META_PREFIX$slotId", "$newName|${meta.createdAtMs}|${meta.sampleCount}")
            .apply()
        Timber.d("GestureLibrary: renamed slot %s to '%s'", slotId.take(8), newName)
    }

    /** True if the library is full. */
    fun isFull(): Boolean = slotIds().size >= MAX_GESTURES

    /** Total number of enrolled gestures. */
    fun count(): Int = slotIds().size

    // Private helpers

    private fun slotIds(): List<String> {
        val raw = prefs.getString(KEY_SLOTS, null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    private fun loadMeta(id: String): GestureSlot? {
        val raw = prefs.getString("$META_PREFIX$id", null) ?: return null
        return try {
            val parts = raw.split("|")
            GestureSlot(
                id          = id,
                name        = parts[0],
                createdAtMs = parts[1].toLong(),
                sampleCount = parts[2].toInt()
            )
        } catch (e: Exception) {
            Timber.w(e, "GestureLibrary: failed to parse meta for slot %s", id.take(8))
            null
        }
    }
}

