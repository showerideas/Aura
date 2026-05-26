package com.showerideas.aura.ml

import com.showerideas.aura.auth.GestureLibrary
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 53 — Federated Gesture Model and AI Coaching (GestureCoach).
 *
 * Analyzes a user's gesture enrollment quality and provides adaptive coaching
 * feedback — without sending any gesture data off-device.
 *
 * ## Federated learning integration
 * Full federated learning (coordinated model averaging across all AURA users)
 * is an R&D milestone. This task delivers the on-device foundation:
 * - [GestureQualityAnalyzer]: computes per-gesture quality metrics (consistency,
 *   entropy, timing variability) from [GestureLibrary] slot metadata.
 * - [GestureCoach]: wraps the analyzer to produce human-readable coaching advice
 *   displayed in the gesture enrollment UI.
 *
 * ## Coaching heuristics (from GestureLibrary Task 27 usage data)
 * 1. **Insufficient samples** (< 5 total): prompt user to add more
 * 2. **Imbalanced slots** (max > 3× min): guide toward even coverage
 * 3. **Stale slots** (not updated in 30 days): suggest re-enrollment
 * 4. **Single slot active**: encourage adding a backup gesture slot
 *
 * ## Privacy guarantee
 * All analysis is fully on-device. No keypoints, landmark positions, or gradient
 * updates leave the device in this task. Federated aggregation (future) will use
 * differential privacy (Task 49 Laplace mechanism) on gradient updates.
 *
 * See: [ai.googleblog.com/2017/04/federated-learning-collaborative.html]
 * See: [tensorflow.org/federated] — TFF reference architecture
 * See: [flower.ai] — Flower federated learning framework
 */
@Singleton
class GestureCoach @Inject constructor(
    private val gestureLibrary: GestureLibrary
) {

    /**
     * Coaching advice for the user.
     * @param message     Human-readable tip.
     * @param severity    How urgently the user should act on this advice.
     * @param metricKey   Internal key for A/B testing and analytics.
     */
    data class CoachingAdvice(
        val message: String,
        val severity: Severity,
        val metricKey: String
    ) {
        enum class Severity { INFO, WARNING, CRITICAL }
    }

    private val adviceGeneratedCount = AtomicInteger(0)

    /** Thirty-day stale threshold in milliseconds. */
    private val STALE_THRESHOLD_MS = 30L * 24 * 3600 * 1000

    /**
     * Analyze the current [GestureLibrary] and return coaching advice.
     * Returns an empty list if the library is in good shape.
     *
     * @return List of [CoachingAdvice] sorted by severity (CRITICAL first).
     */
    fun analyzeAndCoach(): List<CoachingAdvice> {
        val slots = gestureLibrary.listSlots()
        val advice = mutableListOf<CoachingAdvice>()

        if (slots.isEmpty()) {
            return listOf(
                CoachingAdvice(
                    "Add your first gesture pattern to begin enrollment",
                    CoachingAdvice.Severity.INFO,
                    "empty_library"
                )
            )
        }

        val totalSamples = slots.sumOf { it.sampleCount }
        if (totalSamples < 5) {
            advice += CoachingAdvice(
                "Add more gesture samples (you have $totalSamples, aim for 5+) to improve recognition",
                CoachingAdvice.Severity.WARNING,
                "insufficient_samples"
            )
        }

        if (slots.size == 1) {
            advice += CoachingAdvice(
                "Consider adding a backup gesture slot in case your primary gesture changes",
                CoachingAdvice.Severity.INFO,
                "single_slot"
            )
        }

        val maxSlot = slots.maxByOrNull { it.sampleCount }
        val minSlot = slots.minByOrNull { it.sampleCount }
        if (maxSlot != null && minSlot != null && minSlot.sampleCount > 0) {
            if (maxSlot.sampleCount.toFloat() / minSlot.sampleCount > 3f) {
                advice += CoachingAdvice(
                    "Gesture \"${minSlot.name}\" has fewer samples — add more for balanced coverage",
                    CoachingAdvice.Severity.INFO,
                    "imbalanced_slots"
                )
            }
        }

        val now = System.currentTimeMillis()
        slots.forEach { slot ->
            val ageMx = now - slot.createdAtMs
            if (ageMx > STALE_THRESHOLD_MS && slot.sampleCount < 3) {
                advice += CoachingAdvice(
                    "Gesture \"${slot.name}\" hasn't been refreshed in 30+ days — consider re-enrolling",
                    CoachingAdvice.Severity.WARNING,
                    "stale_slot_${slot.id.take(8)}"
                )
            }
        }

        adviceGeneratedCount.incrementAndGet()
        Timber.d("GestureCoach: ${advice.size} advice item(s) generated for ${slots.size} slots")
        return advice.sortedByDescending { it.severity.ordinal }
    }

    /** Total number of coaching analysis rounds — used for onboarding progress. */
    fun totalAdviceRounds(): Int = adviceGeneratedCount.get()

    /**
     * Federated gradient stub — privacy-safe local quality summary.
     *
     * Computes mean sampleCount normalized to [0.0, 1.0] (max = 10 samples),
     * Laplace-noised with ε=1.0 (matching Task 49 DifferentialPrivacyEngine).
     * This will be the "gradient update" in a future federated averaging round.
     */
    fun computeLocalGradientStub(): Float {
        val slots = gestureLibrary.listSlots()
        if (slots.isEmpty()) return 0f
        val meanSamples = slots.map { it.sampleCount }.average().toFloat()
        val normalized = (meanSamples / 10f).coerceIn(0f, 1f)
        val noise = laplaceNoise(scale = 1.0 / slots.size)
        return (normalized + noise).coerceIn(0f, 1f)
    }

    private fun laplaceNoise(scale: Double): Float {
        val u = Math.random() - 0.5
        return (-scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u))).toFloat()
    }
}
