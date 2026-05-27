package com.showerideas.aura.auth

import com.showerideas.aura.model.GesturePattern
import java.util.UUID

/**
 * Extension for GestureAuthManager.authenticateAny().
 * Returns true if any enrolled gesture profile scores above threshold.
 *
 * The primary GestureAuthManager.match() checks the active (slot 0) profile.
 * This extension wraps that call, converting a raw FloatArray embedding into
 * a GesturePattern for matching. Full multi-slot support requires AuthPreferences
 * to expose per-slot embeddings — wired in the GestureLibraryFragment follow-up.
 */
fun GestureAuthManager.authenticateAny(embedding: FloatArray): Boolean {
    val candidate = GesturePattern(
        id = UUID.randomUUID().toString(),
        featureVector = embedding,
        sampleCount = 1
    )
    return match(candidate)
}
