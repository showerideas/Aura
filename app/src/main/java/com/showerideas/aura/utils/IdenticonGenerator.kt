package com.showerideas.aura.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.security.MessageDigest

/**
 * Deterministic identicon generator for AURA contacts.
 *
 * When a contact has no profile photo, AURA generates a unique visual identity
 * from their identity key hash — so every contact is visually distinguishable at
 * a glance without relying on user-uploaded photos.
 *
 * Algorithm (GitHub Identicons style, with AURA colour system):
 *  - SHA-256 the input bytes (key hash string or raw key bytes).
 *  - Build a 5×5 boolean grid using hash bytes 0–14 (low bit = cell filled).
 *  - Mirror columns 4 and 5 from columns 2 and 1 so the result is symmetric.
 *  - Derive foreground colour from hash bytes 12–14 (HSV: hue from byte 12,
 *    saturation 0.65, value 0.85). This gives ~256 distinguishable palette values.
 *  - Render rounded-rect cells on a dark (#121212) background with a small gap.
 *
 * No external dependencies — pure Kotlin + Android Canvas.
 *
 * Usage:
 * ```kotlin
 * val identityHash = contact.identityKeyHash ?: contact.id
 * val bitmap = IdenticonGenerator.generate(identityHash, size = 128)
 * imageView.setImageBitmap(bitmap)
 * ```
 */
object IdenticonGenerator {

    /** Grid dimension (both rows and columns). */
    private const val GRID_SIZE = 5

    /** Fraction of the bitmap edge used as padding on each side. */
    private const val PADDING_FRACTION = 0.12f

    /** Corner radius for each cell, as a fraction of cell size. */
    private const val CELL_RADIUS_FRACTION = 0.18f

    /** Gap between cells, as a fraction of cell size. */
    private const val CELL_GAP_FRACTION = 0.08f

    /** Background colour — AURA dark surface. */
    private const val BG_COLOR = 0xFF1A1A2E.toInt()

    /**
     * Generate a square [size]×[size] identicon from a string (e.g. identityKeyHash).
     *
     * @param input Raw string; its UTF-8 bytes are SHA-256 hashed before use.
     * @param size  Output bitmap dimension in pixels.
     */
    fun generate(input: String, size: Int = 128): Bitmap =
        generate(input.toByteArray(Charsets.UTF_8), size)

    /**
     * Generate a square [size]×[size] identicon from raw bytes.
     *
     * @param input Bytes to hash (e.g. X.509-encoded public key). SHA-256 is
     *              applied internally so any length is acceptable.
     * @param size  Output bitmap dimension in pixels.
     */
    fun generate(input: ByteArray, size: Int = 128): Bitmap {
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        return render(hash, size)
    }

    // Private rendering

    private fun render(hash: ByteArray, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val color = deriveForegroundColor(hash)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        val padding   = size * PADDING_FRACTION
        val innerSize = size - 2f * padding
        val cellSize  = innerSize / GRID_SIZE
        val gap       = cellSize * CELL_GAP_FRACTION
        val radius    = cellSize * CELL_RADIUS_FRACTION

        for (row in 0 until GRID_SIZE) {
            for (col in 0 until GRID_SIZE) {
                // Map symmetric col → source col (only left 3 columns are hashed)
                val srcCol = if (col <= 2) col else GRID_SIZE - 1 - col
                val byteIndex = row * 3 + srcCol
                val filled = byteIndex < hash.size && (hash[byteIndex].toInt() and 0x01) == 1
                if (!filled) continue

                val left   = padding + col * cellSize + gap
                val top    = padding + row * cellSize + gap
                val right  = left + cellSize - 2f * gap
                val bottom = top + cellSize - 2f * gap

                canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
            }
        }

        return bitmap
    }

    /**
     * Derive a foreground colour from hash bytes 12–14.
     * Byte 12 → hue (0–360°), S = 0.65, V = 0.85 (readable on dark background).
     */
    private fun deriveForegroundColor(hash: ByteArray): Int {
        val hue = (hash[12].toInt() and 0xFF) * 360f / 255f
        // Shift hue toward AURA's cyan/purple palette (offset by 180°, modulo 360)
        val shiftedHue = (hue + 160f) % 360f
        return Color.HSVToColor(floatArrayOf(shiftedHue, 0.65f, 0.88f))
    }
}
