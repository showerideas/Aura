package com.showerideas.aura.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Helpers for AURA's avatar pipeline (PR-10).
 *
 * Sizing / compression policy:
 *   - Decode the picked image with `inSampleSize` so we never load the full
 *     camera bitmap into memory.
 *   - Re-encode to JPEG at [JPEG_QUALITY] (80) with the longest edge
 *     ≤ [MAX_DIMENSION] (512 px). Empirically every avatar lands well under
 *     the 200 KB transmit guard the service enforces.
 *   - The user's own avatar is always written to `filesDir/avatar.jpg`.
 *   - Incoming peer avatars land in `filesDir/avatars/<contactId>.jpg`.
 */
object AvatarUtils {
    const val MAX_DIMENSION = 512
    const val JPEG_QUALITY = 80
    const val MAX_AVATAR_BYTES = 200_000L

    private const val USER_AVATAR_FILENAME = "avatar.jpg"
    private const val PEER_AVATAR_DIR = "avatars"

    /** Returns the on-disk file the user's avatar lives in (may not exist yet). */
    fun userAvatarFile(context: Context): File = File(context.filesDir, USER_AVATAR_FILENAME)

    /** Returns the file an incoming peer avatar should land at. */
    fun peerAvatarFile(context: Context, contactId: String): File {
        val dir = File(context.filesDir, PEER_AVATAR_DIR).apply { mkdirs() }
        return File(dir, "$contactId.jpg")
    }

    /**
     * Read [sourceUri] (any image type), down-scale to a max edge of
     * [MAX_DIMENSION] px, JPEG-encode at [JPEG_QUALITY], write to [target].
     *
     * @return true on success.
     */
    fun compressFromUri(context: Context, sourceUri: Uri, target: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_DIMENSION * 2) sample *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return false

            val scaled = scaleToMaxEdge(raw, MAX_DIMENSION).also {
                if (it !== raw) raw.recycle()
            }
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            scaled.recycle()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to compress avatar from $sourceUri")
            false
        }
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Decodes a previously-saved avatar from disk; returns null on failure. */
    fun loadBitmap(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode avatar ${file.absolutePath}")
            null
        }
    }

    /**
     * Load the avatar bitmap for a contact, falling back to an identicon when no
     * photo is available.
     *
     * Resolution order:
     *  1. Photo saved to [peerAvatarFile] (real photo from exchange).
     *  2. [IdenticonGenerator] bitmap derived from [identityKeyHash] (or [contactId]
     *     if the identity hash is null — e.g. legacy contacts before FIX-5).
     *
     * The returned bitmap is always non-null: every contact has a visual identity.
     *
     * @param context        Android context.
     * @param contactId      Contact UUID (fallback seed if identityKeyHash is null).
     * @param identityKeyHash SHA-256 Base64 fingerprint — preferred seed for identicon.
     * @param size           Desired pixel dimension (square). Defaults to [MAX_DIMENSION].
     */
    fun loadOrGenerateAvatar(
        context: Context,
        contactId: String,
        identityKeyHash: String?,
        size: Int = MAX_DIMENSION
    ): Bitmap {
        val photoFile = peerAvatarFile(context, contactId)
        val photo = loadBitmap(photoFile)
        if (photo != null) return photo

        // Generate identicon from the most stable seed available
        val seed = identityKeyHash?.takeIf { it.isNotBlank() } ?: contactId
        Timber.d("Avatar: generating identicon for contact $contactId (seed=${seed.take(12)}…)")
        return IdenticonGenerator.generate(seed, size)
    }

    /**
     * Load the user's own avatar, falling back to an identicon seeded from [selfSeed]
     * (typically the device identity key hash).
     *
     * @param context   Android context.
     * @param selfSeed  Seed for identicon — e.g. CryptoUtils.identityKeyHash(pubKey).
     * @param size      Desired pixel dimension (square). Defaults to [MAX_DIMENSION].
     */
    fun loadOrGenerateSelfAvatar(
        context: Context,
        selfSeed: String,
        size: Int = MAX_DIMENSION
    ): Bitmap {
        val photo = loadBitmap(userAvatarFile(context))
        if (photo != null) return photo

        Timber.d("Avatar: generating self-identicon (seed=${selfSeed.take(12)}…)")
        return IdenticonGenerator.generate(selfSeed, size)
    }
}
