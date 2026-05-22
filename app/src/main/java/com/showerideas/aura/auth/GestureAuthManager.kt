package com.showerideas.aura.auth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.showerideas.aura.model.GestureEvent
import com.showerideas.aura.model.GesturePattern
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Manages gesture recording, storage, and matching for AURA authentication.
 *
 * Architecture:
 * - Recording: listens to accelerometer + gyroscope at [SAMPLE_RATE_US] µs
 * - Feature extraction: computes magnitude envelope over a sliding window
 * - Matching: Dynamic Time Warping (DTW) with [DTW_THRESHOLD] tolerance
 *
 * The stored [GesturePattern] is persisted in EncryptedSharedPreferences so
 * that the raw sensor trace never hits the unencrypted Room database.
 */
@Singleton
class GestureAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val SAMPLE_RATE_US = SensorManager.SENSOR_DELAY_GAME  // ~50 Hz
        private const val RECORD_WINDOW_MS = 2000L       // 2-second gesture window
        private const val MIN_EVENTS_FOR_PATTERN = 20    // minimum samples to be valid
        private const val DTW_THRESHOLD = 4.5f           // empirically tuned
        private const val PREFS_KEY_PATTERN = "gesture_feature_vector"
        private const val PREFS_KEY_PATTERN_ID = "gesture_pattern_id"

        /** PR-11: window size (in samples) for the live-strength meter. */
        internal const val LIVE_VARIANCE_WINDOW = 20

        /**
         * Minimum population variance required for a recorded gesture to
         * be accepted (PR-06). A stationary hold produces variance near 0
         * because gravity is subtracted from each sample; even a gentle
         * shake produces > 0.5, so 0.15 sits comfortably between "nothing
         * happened" and "the user did something deliberate".
         */
        const val MIN_GESTURE_VARIANCE = 0.15f

        /**
         * Population variance of a float feature vector. Exposed at the
         * companion-object level so other layers (UI live-strength meter
         * in PR-11, unit tests) share exactly one implementation.
         */
        internal fun computeVariance(vector: FloatArray): Float {
            if (vector.isEmpty()) return 0f
            var sum = 0f
            for (v in vector) sum += v
            val mean = sum / vector.size
            var sqSum = 0f
            for (v in vector) {
                val d = v - mean
                sqSum += d * d
            }
            return sqSum / vector.size
        }
    }

    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
        data class Complete(val pattern: GesturePattern) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Lazy-initialised once — EncryptedSharedPreferences init has I/O cost, don't recreate per call
    private val encryptedPrefs: android.content.SharedPreferences by lazy {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "aura_gesture_prefs",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState

    /**
     * PR-11: rolling population variance of the most recent
     * [LIVE_VARIANCE_WINDOW] accelerometer magnitudes, recomputed on every
     * sensor event while [RecordingState.Recording]. Drives the on-screen
     * 5-bar gesture-strength meter. Reset to 0f whenever recording stops.
     */
    private val _liveVariance = MutableStateFlow(0f)
    val liveVariance: StateFlow<Float> = _liveVariance

    private val eventBuffer = mutableListOf<GestureEvent>()
    private var recordingStart = 0L
    private var storedPattern: GesturePattern? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (_recordingState.value !is RecordingState.Recording) return

            val elapsed = (System.currentTimeMillis() - recordingStart)
            if (elapsed > RECORD_WINDOW_MS) {
                stopRecording()
                return
            }

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    eventBuffer.add(
                        GestureEvent(
                            timestampNs = event.timestamp,
                            ax = event.values[0],
                            ay = event.values[1],
                            az = event.values[2]
                        )
                    )
                    // PR-11: emit a rolling variance over the last
                    // LIVE_VARIANCE_WINDOW magnitudes so the UI can light
                    // up 0..5 strength bars in near-real-time. The whole
                    // recording window is short enough that this is cheap
                    // — we only walk at most LIVE_VARIANCE_WINDOW samples.
                    val recent = if (eventBuffer.size <= LIVE_VARIANCE_WINDOW) eventBuffer
                    else eventBuffer.subList(eventBuffer.size - LIVE_VARIANCE_WINDOW, eventBuffer.size)
                    val mags = FloatArray(recent.size) { i ->
                        val e = recent[i]
                        val raw = sqrt(e.ax * e.ax + e.ay * e.ay + e.az * e.az)
                        (raw - SensorManager.GRAVITY_EARTH).coerceAtLeast(0f)
                    }
                    _liveVariance.value = computeVariance(mags)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val last = eventBuffer.lastOrNull() ?: return
                    eventBuffer[eventBuffer.lastIndex] = last.copy(
                        gx = event.values[0],
                        gy = event.values[1],
                        gz = event.values[2]
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startRecording() {
        eventBuffer.clear()
        recordingStart = System.currentTimeMillis()
        _recordingState.value = RecordingState.Recording
        sensorManager.registerListener(sensorListener, accel, SAMPLE_RATE_US)
        gyro?.let { sensorManager.registerListener(sensorListener, it, SAMPLE_RATE_US) }
        Timber.d("Gesture recording started")
    }

    fun stopRecording() {
        sensorManager.unregisterListener(sensorListener)
        // PR-11: reset the live meter the moment recording ends so the UI
        // doesn't stick on a stale strength reading.
        _liveVariance.value = 0f
        val events = eventBuffer.toList()
        Timber.d("Gesture recording stopped — ${events.size} samples")

        if (events.size < MIN_EVENTS_FOR_PATTERN) {
            _recordingState.value = RecordingState.Error(
                "Too few samples (${events.size}). Try a longer, more deliberate gesture."
            )
            return
        }

        val features = extractFeatures(events)

        // PR-06: reject "gestures" that don't actually move — a stationary
        // 2-second hold currently produces a flat magnitude envelope, which
        // the DTW matcher would happily accept as a valid pattern.
        val variance = computeVariance(features)
        Timber.d("Recorded gesture variance: $variance (threshold: $MIN_GESTURE_VARIANCE)")
        if (variance < MIN_GESTURE_VARIANCE) {
            _recordingState.value = RecordingState.Error(
                "Gesture too subtle — try a more deliberate movement"
            )
            return
        }

        val pattern = GesturePattern(
            id = UUID.randomUUID().toString(),
            label = "user_gesture",
            featureVector = features,
            sampleCount = events.size
        )
        _recordingState.value = RecordingState.Complete(pattern)
    }

    /**
     * Save [pattern] as the authoritative gesture for this device.
     * Persists to EncryptedSharedPreferences.
     */
    fun savePattern(pattern: GesturePattern) {
        storedPattern = pattern
        // Serialize feature vector to comma-delimited string for preferences storage
        val vectorString = pattern.featureVector.joinToString(",")
        encryptedPrefs.edit()
            .putString(PREFS_KEY_PATTERN, vectorString)
            .putString(PREFS_KEY_PATTERN_ID, pattern.id)
            .apply()
        Timber.d("Gesture pattern saved (${pattern.featureVector.size} features)")
    }

    fun loadStoredPattern(): GesturePattern? {
        storedPattern?.let { return it }
        val prefs = encryptedPrefs
        val vectorString = prefs.getString(PREFS_KEY_PATTERN, null) ?: return null
        val id = prefs.getString(PREFS_KEY_PATTERN_ID, UUID.randomUUID().toString())!!
        val features = vectorString.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        return GesturePattern(id = id, featureVector = features, sampleCount = features.size)
            .also { storedPattern = it }
    }

    fun hasStoredPattern(): Boolean = loadStoredPattern() != null

    /**
     * Match [candidate] against the stored pattern using DTW distance.
     * Returns true if the distance is below [DTW_THRESHOLD].
     */
    fun match(candidate: GesturePattern): Boolean {
        val stored = loadStoredPattern() ?: run {
            Timber.w("No stored pattern to match against")
            return false
        }
        val distance = dtw(stored.featureVector, candidate.featureVector)
        Timber.d("DTW distance: $distance (threshold: $DTW_THRESHOLD)")
        return distance <= DTW_THRESHOLD
    }

    fun clearPattern() {
        storedPattern = null
        encryptedPrefs.edit()
            .remove(PREFS_KEY_PATTERN)
            .remove(PREFS_KEY_PATTERN_ID)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Feature extraction
    // -------------------------------------------------------------------------

    /**
     * Extract a magnitude envelope feature vector from raw sensor events.
     *
     * Steps:
     * 1. Compute per-sample magnitude: sqrt(ax² + ay² + az²) - gravity
     * 2. Down-sample to a fixed-length vector via averaging windows
     */
    private fun extractFeatures(events: List<GestureEvent>, targetSize: Int = 50): FloatArray {
        val magnitudes = events.map { e ->
            val rawMag = sqrt(e.ax * e.ax + e.ay * e.ay + e.az * e.az)
            (rawMag - SensorManager.GRAVITY_EARTH).coerceAtLeast(0f)
        }

        if (magnitudes.isEmpty()) return floatArrayOf()

        // Down-sample to targetSize buckets
        val bucketSize = magnitudes.size.toFloat() / targetSize
        return FloatArray(targetSize) { i ->
            val start = (i * bucketSize).toInt()
            val end = ((i + 1) * bucketSize).toInt().coerceAtMost(magnitudes.size)
            if (start >= end) 0f else magnitudes.subList(start, end).average().toFloat()
        }
    }

    // -------------------------------------------------------------------------
    // DTW distance
    // -------------------------------------------------------------------------

    /**
     * Classic O(n*m) Dynamic Time Warping distance between two sequences.
     * Normalised by the length of the longer sequence.
     */
    private fun dtw(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val n = a.size
        val m = b.size
        val cost = Array(n) { FloatArray(m) { Float.MAX_VALUE } }
        cost[0][0] = kotlin.math.abs(a[0] - b[0])

        for (i in 1 until n) cost[i][0] = cost[i - 1][0] + kotlin.math.abs(a[i] - b[0])
        for (j in 1 until m) cost[0][j] = cost[0][j - 1] + kotlin.math.abs(a[0] - b[j])

        for (i in 1 until n) {
            for (j in 1 until m) {
                val localCost = kotlin.math.abs(a[i] - b[j])
                cost[i][j] = localCost + minOf(cost[i - 1][j], cost[i][j - 1], cost[i - 1][j - 1])
            }
        }
        return cost[n - 1][m - 1] / maxOf(n, m).toFloat()
    }

}
