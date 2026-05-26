package com.showerideas.aura.auth

/**
 * Task 64 — IMU feature extraction for continuous behavioral authentication.
 *
 * Computes a 36-dimensional feature vector from a 5-second rolling window of
 * accelerometer + gyroscope readings at 50 Hz (250 samples per window).
 *
 * ## Feature set (36 total)
 * For each sensor (accelerometer, gyroscope) and each axis (x, y, z) + magnitude:
 *   - Mean           — average signal level
 *   - Variance       — signal spread (activity intensity)
 *   - Zero-crossing rate — frequency of sign changes (gait cadence proxy)
 *   - Peak count     — local maxima above mean (step/gesture detection)
 *   - Spectral peak  — dominant frequency via Goertzel on 0–5 Hz range (simplified)
 *   - (6 features × 3 axes × 2 sensors = 36 features total)
 *
 * Magnitude is: sqrt(x² + y² + z²).
 * Features are computed over the full window, not per-sample.
 *
 * See: [ncbi.nlm.nih.gov/pmc/articles/PMC11769222] — real-time gait features
 * See: [arxiv.org/pdf/2001.08578] — behavioral biometrics survey
 */
object BehavioralFeatureExtractor {

    /** Expected sample rate in Hz. */
    const val SAMPLE_RATE_HZ = 50

    /** Window duration in seconds (5 s at 50 Hz = 250 samples). */
    const val WINDOW_SECONDS = 5

    /** Expected samples per window. */
    const val WINDOW_SIZE = SAMPLE_RATE_HZ * WINDOW_SECONDS

    /** Total feature vector dimension. */
    const val FEATURE_DIM = 36

    /**
     * Three-axis IMU reading snapshot.
     * @param x X-axis value (m/s² for accel, rad/s for gyro).
     * @param y Y-axis value.
     * @param z Z-axis value.
     */
    data class ImuSample(val x: Float, val y: Float, val z: Float) {
        val magnitude: Float get() = kotlin.math.sqrt(x * x + y * y + z * z)
    }

    /**
     * Extract a 36-dimensional feature vector from a window of IMU data.
     *
     * @param accelWindow Accelerometer samples (ideally [WINDOW_SIZE] samples; padded with zeros if fewer).
     * @param gyroWindow  Gyroscope samples (same length requirement).
     * @return FloatArray of length [FEATURE_DIM].
     */
    fun extract(accelWindow: List<ImuSample>, gyroWindow: List<ImuSample>): FloatArray {
        val features = FloatArray(FEATURE_DIM)
        var idx = 0

        // Accelerometer axes + magnitude (18 features)
        val accelX = accelWindow.map { it.x }
        val accelY = accelWindow.map { it.y }
        val accelZ = accelWindow.map { it.z }
        val accelM = accelWindow.map { it.magnitude }

        for (axis in listOf(accelX, accelY, accelZ)) {
            features[idx++] = mean(axis)
            features[idx++] = variance(axis)
            features[idx++] = zeroCrossingRate(axis)
            features[idx++] = peakCount(axis).toFloat()
            features[idx++] = spectralPeakFrequency(axis)
            features[idx++] = rmsAmplitude(axis)
        }

        // Gyroscope axes + magnitude (18 features)
        val gyroX = gyroWindow.map { it.x }
        val gyroY = gyroWindow.map { it.y }
        val gyroZ = gyroWindow.map { it.z }

        for (axis in listOf(gyroX, gyroY, gyroZ)) {
            features[idx++] = mean(axis)
            features[idx++] = variance(axis)
            features[idx++] = zeroCrossingRate(axis)
            features[idx++] = peakCount(axis).toFloat()
            features[idx++] = spectralPeakFrequency(axis)
            features[idx++] = rmsAmplitude(axis)
        }

        return features
    }

    // ── Feature functions ─────────────────────────────────────────────────

    internal fun mean(data: List<Float>): Float {
        if (data.isEmpty()) return 0f
        return data.sum() / data.size
    }

    internal fun variance(data: List<Float>): Float {
        if (data.size < 2) return 0f
        val m = mean(data)
        return data.sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / data.size
    }

    internal fun zeroCrossingRate(data: List<Float>): Float {
        if (data.size < 2) return 0f
        var crossings = 0
        for (i in 1 until data.size) {
            if (data[i - 1] * data[i] < 0f) crossings++
        }
        return crossings.toFloat() / (data.size - 1)
    }

    internal fun peakCount(data: List<Float>): Int {
        if (data.size < 3) return 0
        val m = mean(data)
        var peaks = 0
        for (i in 1 until data.size - 1) {
            if (data[i] > m && data[i] > data[i - 1] && data[i] > data[i + 1]) peaks++
        }
        return peaks
    }

    /**
     * Approximate dominant frequency using Goertzel algorithm over 0–5 Hz.
     * Tests 10 frequency bins (0.5 Hz resolution) and returns the peak frequency.
     * Full FFT is overkill for 250 samples at 50 Hz; Goertzel is O(N) per bin.
     */
    internal fun spectralPeakFrequency(data: List<Float>): Float {
        if (data.isEmpty()) return 0f
        val n = data.size
        val fs = SAMPLE_RATE_HZ.toFloat()
        val binFreqs = (1..10).map { it * 0.5f }  // 0.5, 1.0, ... 5.0 Hz
        var peakPower = 0f
        var peakFreq = 0f
        for (freq in binFreqs) {
            val power = goertzel(data, freq, fs, n)
            if (power > peakPower) {
                peakPower = power
                peakFreq = freq
            }
        }
        return peakFreq
    }

    internal fun rmsAmplitude(data: List<Float>): Float {
        if (data.isEmpty()) return 0f
        val sumSq = data.sumOf { (it * it).toDouble() }
        return kotlin.math.sqrt(sumSq / data.size).toFloat()
    }

    /** Goertzel DFT power at a single frequency bin. */
    private fun goertzel(data: List<Float>, freq: Float, fs: Float, n: Int): Float {
        val k = (0.5 + n * freq / fs).toInt()
        val omega = 2.0 * Math.PI * k / n
        val cos = kotlin.math.cos(omega).toFloat()
        val coeff = 2f * cos
        var s0 = 0f; var s1 = 0f; var s2 = 0f
        for (sample in data) {
            s0 = sample + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
