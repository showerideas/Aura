package com.showerideas.aura.model

/**
 * Represents a recorded hand-gesture biometric pattern.
 *
 * The credential is a normalised 42-float MediaPipe landmark embedding
 * (21 hand landmarks × x,y per point, centred on wrist, scaled by
 * wrist→MCP distance).  Two people making the same named gesture produce
 * different embeddings because their hand shapes differ — the gesture
 * label is metadata only.
 *
 * Authentication is performed via cosine similarity; see
 * [com.showerideas.aura.auth.GestureAuthManager.match].
 */
data class GesturePattern(
    val id: String,
    val label: String = "default",
    /** Pose-invariant 42-float landmark embedding from [com.showerideas.aura.auth.CameraHandEmbedder]. */
    val featureVector: FloatArray = floatArrayOf(),
    val sampleCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GesturePattern) return false
        return id == other.id && featureVector.contentEquals(other.featureVector)
    }

    override fun hashCode(): Int = 31 * id.hashCode() + featureVector.contentHashCode()
}
