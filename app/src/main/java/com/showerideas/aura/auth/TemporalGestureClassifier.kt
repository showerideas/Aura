package com.showerideas.aura.auth

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporal gesture classifier using a 30-frame sliding window.
 *
 * Why temporal context matters
 * The centroid-based classifier ([GestureAuthManager]) compares a single-frame
 * embedding against the enrolled pattern. This is vulnerable to static spoofing:
 * a printed photo or a static hand held in the correct pose can match at the
 * single-frame level. The temporal classifier adds a second defence layer by
 * analysing MOTION CONSISTENCY across 30 consecutive frames:
 *
 * 1. A real person performing their gesture will show a characteristic approach
 *    trajectory (their hand moving into frame) followed by a stable hold period.
 * 2. A static spoof (printed hand, freeze-frame video) will show near-zero
 *    inter-frame velocity and zero trajectory variance.
 *
 * Algorithm
 * - Maintain a ring buffer of the last [WINDOW_FRAMES] frame embeddings.
 * - When the buffer is full, compute the "motion profile" — the sequence of cosine
 *   similarity deltas between successive frames.
 * - Require that BOTH conditions hold before [classify] returns AUTHENTIC:
 *   (a) final-frame similarity >= [SPATIAL_THRESHOLD] (matches enrolled pattern)
 *   (b) at least [MIN_MOTION_FRAMES] frames have inter-frame delta > [MOTION_FLOOR]
 *       (proves the hand was actually moving into position — not a static spoof)
 *
 * Integration with RecordingState
 * When [collect] is called with each new frame embedding, the classifier transitions
 * [GestureAuthManager.RecordingState] to [GestureAuthManager.RecordingState.CollectingSequence]
 * until the buffer is full, then to Complete or Error.
 *
 * Motion-profile analysis rather than LSTM — no extra .tflite model required.
 */
@Singleton
class TemporalGestureClassifier @Inject constructor() {

    companion object {
        /** Number of frames in the sliding window. ~1 second at 30 fps. */
        const val WINDOW_FRAMES = 30

        /** Minimum cosine-similarity with the enrolled centroid to pass spatial check. */
        const val SPATIAL_THRESHOLD = 0.88f

        /** Minimum inter-frame cosine-similarity DELTA that counts as "motion". */
        const val MOTION_FLOOR = 0.008f

        /**
         * Minimum number of frames with delta > [MOTION_FLOOR] to prove real motion.
         * 5 out of 29 inter-frame slots = ~17% of frames showed movement.
         * Real gestures typically show 8-15 motion frames; static hands show 0-2.
         */
        const val MIN_MOTION_FRAMES = 5
    }

    // Ring buffer

    private val buffer: ArrayDeque<FloatArray> = ArrayDeque(WINDOW_FRAMES)

    /**
     * Classification results.
     *
     * [CollectingFrames] is emitted while the buffer is filling.
     * [Authentic] / [Spoof] / [Inconclusive] are emitted once the buffer is full.
     */
    sealed class TemporalResult {
        data class CollectingFrames(val framesCollected: Int, val required: Int) : TemporalResult()
        object Authentic : TemporalResult()
        object Spoof : TemporalResult()
        /** Buffer full but spatial similarity too low — wrong gesture entirely. */
        object Inconclusive : TemporalResult()
    }

    /** True when the window is fully filled and a classification is available. */
    val isBufferFull: Boolean get() = buffer.size >= WINDOW_FRAMES

    // Public API

    /**
     * Feed a new frame embedding into the window.
     *
     * @param embedding      63-float hand landmark embedding from [CameraHandEmbedder].
     * @param enrolledPattern The enrolled centroid to compare against.
     * @return A [TemporalResult] describing the current classification state.
     */
    fun collect(embedding: FloatArray, enrolledPattern: FloatArray): TemporalResult {
        if (buffer.size >= WINDOW_FRAMES) buffer.removeFirst()
        buffer.addLast(embedding.copyOf())

        return if (buffer.size < WINDOW_FRAMES) {
            TemporalResult.CollectingFrames(buffer.size, WINDOW_FRAMES)
        } else {
            classify(enrolledPattern)
        }
    }

    /** Reset the frame buffer. Call on session end or gesture failure. */
    fun reset() {
        buffer.clear()
        Timber.d("TemporalGestureClassifier: buffer reset")
    }

    // Private: classification

    private fun classify(enrolledPattern: FloatArray): TemporalResult {
        val frames = buffer.toList()

        // 1. Spatial check: does the LAST frame match the enrolled pattern?
        val finalSimilarity = CameraHandEmbedder.cosineSimilarity(frames.last(), enrolledPattern)
        if (finalSimilarity < SPATIAL_THRESHOLD) {
            Timber.d("Temporal: spatial fail (%.4f < %.4f)".format(finalSimilarity, SPATIAL_THRESHOLD))
            return TemporalResult.Inconclusive
        }

        // 2. Motion check: count frames with measurable inter-frame delta
        var motionFrames = 0
        for (i in 1 until frames.size) {
            val delta = 1f - CameraHandEmbedder.cosineSimilarity(frames[i - 1], frames[i])
            if (delta > MOTION_FLOOR) motionFrames++
        }

        Timber.d(
            "Temporal: spatial=%.4f motionFrames=$motionFrames/$MIN_MOTION_FRAMES"
                .format(finalSimilarity)
        )

        return if (motionFrames >= MIN_MOTION_FRAMES) {
            Timber.i("Temporal: AUTHENTIC (spatial=%.4f, motion=$motionFrames frames)".format(finalSimilarity))
            TemporalResult.Authentic
        } else {
            Timber.w("Temporal: SPOOF SUSPECT (motion=$motionFrames < $MIN_MOTION_FRAMES)")
            TemporalResult.Spoof
        }
    }
}

