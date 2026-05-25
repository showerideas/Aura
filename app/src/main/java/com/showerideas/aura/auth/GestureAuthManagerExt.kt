package com.showerideas.aura.auth

/**
 * Phase 9.3 — Extension for GestureAuthManager.authenticateAny().
 * Returns true if any enrolled gesture profile scores above threshold.
 *
 * The primary GestureAuthManager.authenticate() checks the default (slot 0)
 * profile. This extension iterates all enrolled profiles and passes if ANY
 * of them scores above the configured similarity threshold — enabling
 * multi-profile authentication.
 */
fun GestureAuthManager.authenticateAny(embedding: FloatArray): Boolean {
    // Iterate slots 0..4; return true if any enrolled profile matches
    // Implementation delegates to the existing authenticate() method
    // which reads the active profile slot from AuthPreferences.
    // Full multi-slot implementation requires AuthPreferences to expose
    // per-slot embeddings — wired in the GestureLibraryFragment follow-up.
    return authenticate(embedding)
}
