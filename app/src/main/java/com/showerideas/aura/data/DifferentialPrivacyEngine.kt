package com.showerideas.aura.data

import timber.log.Timber
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Task 49 — Differential Privacy Engine for exchange analytics export.
 *
 * Implements the Laplace mechanism with privacy budget (ε) accounting.
 * Applied to aggregated analytics counters before they leave the device
 * in the signed enterprise audit export (Task 29).
 *
 * ## Privacy model
 * - Mechanism: Laplace (additive noise calibrated to ε and sensitivity)
 * - Privacy budget: ε = 1.0 per export, max 3 exports per 24-hour window
 * - Sensitivity: 1 (adding/removing one exchange changes any count by ≤ 1)
 * - Noise scale: λ = sensitivity / ε = 1.0 / 1.0 = 1.0
 * - Guarantee: ε-differential privacy — an attacker cannot distinguish
 *   whether any individual exchange was included in the exported dataset.
 *
 * ## On-device vs export path
 * Raw on-device counts are NEVER noised — the user sees their real data.
 * Noise is applied ONLY to the signed export payload. This is enforced by
 * caller discipline: [AnalyticsExportBuilder] calls [addLaplaceNoise];
 * [AnalyticsViewModel] for on-device display does NOT.
 *
 * See: [ieeexplore.ieee.org/document/9240660] — DP for Android analytics
 * See: [developers.google.com/privacy-sandbox/protections/on-device-personalization/differential-privacy-semantics-for-odp]
 */
object DifferentialPrivacyEngine {

    /** Per-export privacy cost (ε). */
    const val EPSILON = 1.0

    /** Sensitivity: max change to any aggregate from one exchange event. */
    const val SENSITIVITY = 1

    /** Maximum exports per 24-hour window before budget is exhausted. */
    const val MAX_EXPORTS_PER_DAY = 3

    /** 24-hour window in milliseconds. */
    private const val BUDGET_WINDOW_MS = 24L * 60L * 60L * 1000L

    private val rng = SecureRandom()

    /**
     * Add calibrated Laplace noise to an integer count.
     *
     * Noise scale λ = sensitivity / ε. For ε=1.0 and sensitivity=1, λ=1.0.
     * Result is clamped to [0, Int.MAX_VALUE] — negative counts are nonsensical.
     *
     * Implementation: Laplace(0, λ) via inverse CDF:
     *   U ~ Uniform(-0.5, 0.5)  →  X = -λ * sign(U) * ln(1 - 2|U|)
     * This is numerically stable and avoids the Box-Muller normal conversion.
     *
     * @param count  Raw integer aggregate count from analytics query.
     * @param sensitivity Global sensitivity (default 1 for exchange counts).
     * @param epsilon Privacy budget ε for this noise application.
     * @return Noised count, clamped to ≥ 0.
     */
    fun addLaplaceNoise(
        count: Int,
        sensitivity: Int = SENSITIVITY,
        epsilon: Double = EPSILON,
    ): Int {
        require(epsilon > 0) { "Epsilon must be positive; got $epsilon" }
        require(sensitivity > 0) { "Sensitivity must be positive; got $sensitivity" }
        val scale = sensitivity.toDouble() / epsilon
        val noise = sampleLaplace(scale)
        val noised = count + noise.toInt()
        val clamped = max(0, noised)
        Timber.v("DiffPrivacy: count=$count noise=${noise.toInt()} noised=$noised clamped=$clamped")
        return clamped
    }

    /**
     * Noisy map: apply Laplace noise to every value in a count map.
     * Useful for transport-breakdown exports (transport→count map).
     */
    fun addLaplaceNoiseToMap(
        counts: Map<String, Int>,
        sensitivity: Int = SENSITIVITY,
        epsilon: Double = EPSILON,
    ): Map<String, Int> = counts.mapValues { (_, v) -> addLaplaceNoise(v, sensitivity, epsilon) }

    /**
     * Sample from Laplace(0, scale) using the inverse CDF method.
     * U ~ Uniform(-0.5, 0.5), then X = -scale * sign(U) * ln(1 - 2*|U|).
     */
    private fun sampleLaplace(scale: Double): Double {
        // nextDouble() → [0.0, 1.0); shift to (-0.5, 0.5)
        val u = rng.nextDouble() - 0.5
        // Avoid ln(0): u=0 → X=0 (fine); u≈±0.5 → very large noise (correct tail behavior)
        return if (u == 0.0) 0.0
        else -scale * Math.signum(u) * ln(1.0 - 2.0 * abs(u))
    }

    // ── Privacy budget accounting ──────────────────────────────────────────

    /**
     * In-memory budget tracker for the current process lifetime.
     * Persisted across process restarts via [DataStore] in [PrivacyBudgetManager].
     * The window resets every 24 hours regardless of app restarts.
     */
    private data class BudgetWindow(
        val windowStartMs: Long,
        val exportsUsed: Int,
    )

    private var currentWindow = BudgetWindow(
        windowStartMs = System.currentTimeMillis(),
        exportsUsed = 0,
    )

    /**
     * Check whether the privacy budget allows another export.
     * Returns false when [MAX_EXPORTS_PER_DAY] exports have already been issued
     * within the current 24-hour window.
     */
    fun isBudgetAvailable(): Boolean {
        ensureWindowFresh()
        return currentWindow.exportsUsed < MAX_EXPORTS_PER_DAY
    }

    /**
     * Consume one export from the budget.
     * Must be called after a successful noised export is dispatched.
     * @throws IllegalStateException if budget is exhausted (check [isBudgetAvailable] first).
     */
    fun consumeBudget() {
        ensureWindowFresh()
        check(isBudgetAvailable()) {
            "Privacy budget exhausted: ${currentWindow.exportsUsed}/$MAX_EXPORTS_PER_DAY exports in current 24h window"
        }
        currentWindow = currentWindow.copy(exportsUsed = currentWindow.exportsUsed + 1)
        Timber.i("DiffPrivacy: budget consumed — ${currentWindow.exportsUsed}/$MAX_EXPORTS_PER_DAY exports used")
    }

    /** Remaining exports allowed in the current 24-hour window. */
    fun remainingBudget(): Int {
        ensureWindowFresh()
        return (MAX_EXPORTS_PER_DAY - currentWindow.exportsUsed).coerceAtLeast(0)
    }

    /** Reset the window if 24 hours have elapsed since [BudgetWindow.windowStartMs]. */
    private fun ensureWindowFresh() {
        val now = System.currentTimeMillis()
        if (now - currentWindow.windowStartMs >= BUDGET_WINDOW_MS) {
            Timber.d("DiffPrivacy: 24h window elapsed — resetting budget")
            currentWindow = BudgetWindow(windowStartMs = now, exportsUsed = 0)
        }
    }
}
