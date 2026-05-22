package com.showerideas.aura.model

/**
 * Represents a recorded gesture pattern used to authenticate an exchange.
 *
 * A gesture is a sequence of [GestureEvent]s captured from the device
 * accelerometer/gyroscope during a time window. The pattern is stored as
 * a normalised feature vector for fuzzy matching — exact byte equality is
 * intentionally NOT required (people are not robots).
 */
data class GesturePattern(
    val id: String,
    val label: String = "default",
    /** Normalised gesture feature vector (magnitude envelope, DTW-ready) */
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

/** A single sensor reading within a gesture recording session. */
data class GestureEvent(
    val timestampNs: Long,
    val ax: Float,  // Accelerometer X
    val ay: Float,  // Accelerometer Y
    val az: Float,  // Accelerometer Z
    val gx: Float = 0f,  // Gyroscope X
    val gy: Float = 0f,
    val gz: Float = 0f
)
