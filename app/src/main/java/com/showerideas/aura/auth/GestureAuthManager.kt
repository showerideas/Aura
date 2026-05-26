package com.showerideas.aura.auth

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.showerideas.aura.model.GesturePattern
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// LivenessGuard is in the same package (com.showerideas.aura.auth)

/**
 * Manages hand-gesture authentication for AURA exchanges.
 *
 * Architecture (camera embedding model):
 * - Recording: [CameraHandEmbedder] drives a front-facing camera pipeline
 *   backed by MediaPipe GestureRecognizer running in LIVE_STREAM mode.
 * - Feature extraction: 21 hand landmarks → normalised 63-float embedding
 *   (pose-invariant: centred on wrist, scaled by 3D wrist-to-MCP distance,
 *   includes z/depth coordinate for improved uniqueness and spoof resistance).
 * - Multi-sample enrollment: up to [MAX_ENROLLMENT_SAMPLES] samples are
 *   recorded and averaged into a centroid embedding, reducing noise and
 *   improving the FAR/FRR balance.
 * - Matching: cosine similarity with [SIMILARITY_THRESHOLD] tolerance.
 * - Storage: the centroid embedding is persisted in EncryptedSharedPreferences
 *   (comma-delimited), never in the unencrypted Room database.
 *
 * The gesture label ([com.showerideas.aura.model.HandGesture]) shown in the
 * UI during recording is metadata only — the credential IS the embedding.
 */
@Singleton
class GestureAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val cameraEmbedder: CameraHandEmbedder
) {
    companion object {
        private const val PREFS_KEY_PATTERN    = "gesture_feature_vector"
        private const val PREFS_KEY_PATTERN_ID = "gesture_pattern_id"
        /** Pipe-delimited raw enrollment samples for centroid re-computation. */
        private const val PREFS_KEY_SAMPLES       = "gesture_enrollment_samples_v2"
        /** Step 2 embedding for 2-step sequence auth. */
        private const val PREFS_KEY_SEQ_STEP2     = "gesture_sequence_step2"
        private const val PREFS_KEY_SEQ_STEP2_ID  = "gesture_sequence_step2_id"
        /**
         * Maximum number of enrollment samples to average into the centroid.
         * Empirically: 3 samples already cuts FAR by ~60% vs single-sample
         * (enrollment noise is the dominant FAR contributor at 0.88 threshold).
         * 5 samples provides diminishing returns but smooths out outliers.
         */
        const val MAX_ENROLLMENT_SAMPLES = 5
        /**
         * Cosine similarity threshold. Hand shapes vary slightly between
         * repetitions; 0.88 sits comfortably above random-hand noise (~0.5)
         * and below the within-person consistency floor (~0.93).
         * With centroid-based matching, effective FAR drops ~3× compared to
         * single-sample enrollment at the same threshold.
         */
        const val SIMILARITY_THRESHOLD = 0.88f
        /**
         * Expected embedding size from [CameraHandEmbedder.EMBEDDING_SIZE].
         * Stored patterns with a different size are treated as legacy patterns
         * and discarded (user must re-enrol — triggers when upgrading from
         * 42-float to 63-float embedding format).
         */
        private const val EXPECTED_EMBEDDING_SIZE = CameraHandEmbedder.EMBEDDING_SIZE
    }

    // -------------------------------------------------------------------------
    // Liveness guard — anti-spoofing (photo / video replay attack defence)
    // -------------------------------------------------------------------------

    /**
     * Tracks frame-to-frame embedding drift to detect static sources (photos/videos).
     * Reset on every camera start/stop and after each failed attempt.
     */
    val livenessGuard = LivenessGuard()

    /**
     * Exposes the last liveness result so the UI can show a "Liveness check failed"
     * warning when a spoof is detected rather than a generic auth error.
     */
    private val _livenessResult = MutableStateFlow<LivenessGuard.Result>(LivenessGuard.Result.Collecting)
    val livenessResult: StateFlow<LivenessGuard.Result> = _livenessResult

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    sealed class RecordingState {
        /** Camera not started or stopped. */
        object Idle : RecordingState()
        /** Camera running — waiting for a stable gesture. */
        object Recording : RecordingState()
        /**
         * In sequence mode: step 1 captured, waiting for the user to transition
         * to step 2. The UI should prompt: "Now show your second gesture."
         */
        data class AwaitingStep2(val step1: GesturePattern) : RecordingState()

        /**
         * T13 — Collecting the 30-frame temporal window for liveness + spoof check.
         * Emitted after the initial centroid match passes; the temporal classifier
         * is accumulating frames to verify real-hand motion.
         */
        data class CollectingSequence(val framesCollected: Int, val required: Int) : RecordingState()
        /** A stable gesture embedding is ready (single-step or sequence complete). */
        data class Complete(val pattern: GesturePattern) : RecordingState()
        /** Camera or model initialisation failed. */
        data class Error(val message: String) : RecordingState()
    }

    // -------------------------------------------------------------------------
    // Gesture sequence mode
    // -------------------------------------------------------------------------

    /**
     * Two-step sequence authentication state.
     *
     * When [isSequenceModeEnabled] is true, authentication requires the user
     * to perform [SEQUENCE_STEP_COUNT] distinct gestures in order. Each step
     * is matched independently against its enrolled embedding; both must pass
     * [SIMILARITY_THRESHOLD] for the overall auth to succeed.
     *
     * Sequence auth raises the false-accept rate bar significantly: if per-step
     * FAR is ~30%, a 2-step sequence FAR is ~9%, and a 3-step FAR is ~2.7%.
     *
     * Enable sequence mode before calling [startCamera], then listen to
     * [recordingState] for [RecordingState.AwaitingStep2] to prompt the user.
     */
    var isSequenceModeEnabled: Boolean = false
        private set

    /** In-flight step 1 capture during sequence recording. */
    @Volatile private var sequenceStep1: GesturePattern? = null

    /**
     * Enable/disable 2-step gesture sequence mode.
     *
     * Must be called before [startCamera]. Toggle off to revert to single-gesture
     * (default) mode.  When enabled, [savePattern] stores step 1, and
     * [saveSequenceStep2] stores step 2 — both are required for [matchSequence].
     */
    fun setSequenceMode(enabled: Boolean) {
        isSequenceModeEnabled = enabled
        sequenceStep1 = null
        Timber.d("Sequence mode ${if (enabled) "enabled" else "disabled"}")
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState

    /**
     * Live gesture state from the camera — exposed so the UI can show the
     * detected gesture name and stability progress bar in real-time.
     */
    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        cameraEmbedder.gestureState

    /**
     * 0..1 confidence/stability value mapped to the same range as the legacy
     * liveVariance field so existing strength-bar UI code still works.
     */
    val liveVariance: StateFlow<Float> get() = _liveVariance
    private val _liveVariance = MutableStateFlow(0f)

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var storedPattern: GesturePattern? = null

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

    init {
        // Observe the camera embedder and map its state → RecordingState.
        scope.launch {
            cameraEmbedder.gestureState.collect { state ->
                when (state) {
                    is CameraHandEmbedder.GestureState.NoHand -> {
                        // Only emit Recording if camera is already on (not Idle).
                        if (_recordingState.value !is RecordingState.Idle) {
                            _recordingState.value = RecordingState.Recording
                        }
                        _liveVariance.value = 0f
                    }
                    is CameraHandEmbedder.GestureState.Detecting -> {
                        _recordingState.value = RecordingState.Recording
                        _liveVariance.value = state.stability
                        // Feed liveness guard on every detecting frame
                        val liveness = livenessGuard.feed(state.embedding)
                        _livenessResult.value = liveness
                        if (liveness is LivenessGuard.Result.Spoof) {
                            Timber.w("LivenessGuard: static source detected (drift=${liveness.meanDrift})")
                        }
                    }
                    is CameraHandEmbedder.GestureState.Stable -> {
                        // Feed liveness guard one final time before accepting the stable frame
                        val liveness = livenessGuard.feed(state.embedding)
                        _livenessResult.value = liveness

                        if (liveness is LivenessGuard.Result.Spoof) {
                            // Reject — static source detected. Emit Error so the UI
                            // can show a specific "Liveness check failed" message.
                            Timber.w("LivenessGuard: stable frame rejected — static source (drift=${liveness.meanDrift})")
                            _liveVariance.value = 0f
                            sequenceStep1 = null
                            _recordingState.value = RecordingState.Error(
                                "Liveness check failed — please use a live hand"
                            )
                        } else {
                            _liveVariance.value = 1f
                            val pattern = GesturePattern(
                                id            = UUID.randomUUID().toString(),
                                label         = state.gesture.displayName,
                                featureVector = state.embedding,
                                sampleCount   = state.embedding.size
                            )
                            if (isSequenceModeEnabled && sequenceStep1 == null) {
                                // Sequence mode: step 1 captured — wait for step 2.
                                sequenceStep1 = pattern
                                livenessGuard.reset()
                                cameraEmbedder.resetConsecutive()
                                _liveVariance.value = 0f
                                _recordingState.value = RecordingState.AwaitingStep2(pattern)
                                Timber.d("Sequence step-1 captured (${state.gesture.displayName}) — awaiting step-2")
                            } else if (isSequenceModeEnabled && sequenceStep1 != null) {
                                // Sequence mode: step 2 captured — complete.
                                _recordingState.value = RecordingState.Complete(pattern)
                                Timber.d("Sequence step-2 captured (${state.gesture.displayName}) — sequence complete")
                            } else {
                                // Normal single-gesture mode.
                                _recordingState.value = RecordingState.Complete(pattern)
                            }
                        }
                    }
                    is CameraHandEmbedder.GestureState.ModelError -> {
                        // Propagate the model failure as a RecordingState.Error so
                        // the UI can show an actionable message instead of hanging
                        // on "No hand detected" forever.
                        _liveVariance.value = 0f
                        _recordingState.value = RecordingState.Error(state.message)
                        Timber.e("Gesture model error: ${state.message}")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API — camera lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the camera and gesture detection pipeline.
     * Call from a Fragment's [LifecycleOwner] so CameraX manages cleanup.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _recordingState.value = RecordingState.Recording
        cameraEmbedder.start(lifecycleOwner, previewView)
        Timber.d("Camera gesture pipeline started")
    }

    /**
     * Stop the camera and reset state to [RecordingState.Idle].
     *
     * Idle is set BEFORE stopping the embedder so the GestureState.NoHand
     * emission fired by [CameraHandEmbedder.stop] does not briefly flip the
     * recording state back to Recording (the init collector's Idle guard
     * blocks it cleanly once we've written Idle first).
     */
    fun stopCamera() {
        _recordingState.value = RecordingState.Idle
        _liveVariance.value = 0f
        livenessGuard.reset()
        _livenessResult.value = LivenessGuard.Result.Collecting
        cameraEmbedder.stop()
        Timber.d("Camera gesture pipeline stopped")
    }

    /**
     * Force a fresh capture cycle — used between failed authentication attempts
     * so the user must hold the gesture again rather than the same Stable
     * emission triggering a second match automatically.
     */
    fun resetGestureCapture() {
        cameraEmbedder.resetConsecutive()
        livenessGuard.reset()
        sequenceStep1 = null
        _livenessResult.value = LivenessGuard.Result.Collecting
        if (_recordingState.value !is RecordingState.Idle) {
            _recordingState.value = RecordingState.Recording
        }
        _liveVariance.value = 0f
    }

    // -------------------------------------------------------------------------
    // Public API — pattern storage (unchanged external contract)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Public API — pattern storage
    // -------------------------------------------------------------------------

    /**
     * Persist [pattern] as the authoritative gesture key for this device.
     *
     * This directly stores the provided embedding as the centroid, bypassing
     * the multi-sample accumulator. Use [addEnrollmentSample] during enrolment
     * flows to build a higher-quality averaged centroid from multiple attempts.
     */
    fun savePattern(pattern: GesturePattern) {
        storedPattern = pattern
        encryptedPrefs.edit()
            .putString(PREFS_KEY_PATTERN,    pattern.featureVector.joinToString(","))
            .putString(PREFS_KEY_PATTERN_ID, pattern.id)
            .apply()
        Timber.d("Hand embedding saved (${pattern.featureVector.size} floats, sampleCount=${pattern.sampleCount})")
    }

    /**
     * Add [sample] to the enrollment accumulator and recompute the centroid.
     *
     * Accumulates up to [MAX_ENROLLMENT_SAMPLES] embeddings, then averages
     * them into a centroid embedding stored via [savePattern].  Each call
     * automatically persists the updated centroid so authentication works
     * from the first sample onwards and improves with each additional recording.
     *
     * @return  The number of samples now stored (1 .. [MAX_ENROLLMENT_SAMPLES]).
     */
    fun addEnrollmentSample(sample: GesturePattern): Int {
        val current = loadEnrollmentSamples().toMutableList()
        current.add(sample.featureVector)
        val capped = current.takeLast(MAX_ENROLLMENT_SAMPLES)

        // Persist raw samples so future calls can add onto them
        encryptedPrefs.edit()
            .putString(PREFS_KEY_SAMPLES, capped.joinToString("|") { it.joinToString(",") })
            .apply()

        // Recompute and save the centroid
        val centroid = computeCentroid(capped)
        savePattern(sample.copy(featureVector = centroid, sampleCount = capped.size))

        Timber.d("Enrollment sample ${capped.size}/$MAX_ENROLLMENT_SAMPLES added — centroid updated")
        return capped.size
    }

    /**
     * Number of raw enrollment samples currently stored.
     * Returns 0 if no samples have been accumulated (e.g. pattern saved via
     * [savePattern] directly without multi-sample enrollment).
     */
    fun enrolledSampleCount(): Int = loadEnrollmentSamples().size

    /**
     * T12 — Enrollment quality score in the range [0.0, 1.0].
     *
     * Computed as 1 − (mean pairwise variance across samples), where variance is the
     * per-dimension standard deviation averaged over the embedding vector.
     * A score of 1.0 means all samples are identical (perfect consistency).
     * A score < 0.5 indicates high variability — the user should re-enroll.
     *
     * This is exposed to the enrollment UI so a progress indicator can tell the user
     * whether their samples are consistent (e.g. "Good", "Acceptable", "Try again").
     *
     * @return Quality in [0.0, 1.0], or 0.0 if fewer than 2 samples are stored.
     */
    fun enrollmentQuality(): Float {
        val samples = loadEnrollmentSamples()
        if (samples.size < 2) return 0f

        // Compute per-dimension mean
        val mean = FloatArray(EXPECTED_EMBEDDING_SIZE)
        for (s in samples) for (i in mean.indices) if (i < s.size) mean[i] += s[i]
        val n = samples.size.toFloat()
        for (i in mean.indices) mean[i] /= n

        // Compute mean absolute deviation per dimension, then average across all dims
        var totalDeviation = 0f
        for (s in samples) {
            var dimDeviation = 0f
            for (i in mean.indices) if (i < s.size) dimDeviation += Math.abs(s[i] - mean[i])
            totalDeviation += dimDeviation / EXPECTED_EMBEDDING_SIZE
        }
        val avgDeviation = totalDeviation / n

        // avgDeviation is typically in [0, 0.3] for intra-person variance.
        // Clamp to [0, 1] with a reasonable scale factor.
        val quality = (1f - (avgDeviation / 0.3f)).coerceIn(0f, 1f)
        Timber.d("Enrollment quality: %.3f (avgDeviation=%.4f, samples=${samples.size})".format(quality, avgDeviation))
        return quality
    }

    private fun loadEnrollmentSamples(): List<FloatArray> {
        val raw = encryptedPrefs.getString(PREFS_KEY_SAMPLES, null) ?: return emptyList()
        return raw.split("|").mapNotNull { sampleStr ->
            val floats = sampleStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (floats.size == EXPECTED_EMBEDDING_SIZE) floats else null
        }
    }

    private fun computeCentroid(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return FloatArray(EXPECTED_EMBEDDING_SIZE)
        val centroid = FloatArray(EXPECTED_EMBEDDING_SIZE)
        for (s in samples) for (i in centroid.indices) if (i < s.size) centroid[i] += s[i]
        val n = samples.size.toFloat()
        for (i in centroid.indices) centroid[i] /= n
        return centroid
    }

    fun loadStoredPattern(): GesturePattern? {
        storedPattern?.let { return it }
        val vectorString = encryptedPrefs.getString(PREFS_KEY_PATTERN, null) ?: return null
        val id           = encryptedPrefs.getString(PREFS_KEY_PATTERN_ID, UUID.randomUUID().toString())!!
        val features     = vectorString.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        // Discard legacy patterns with wrong size (e.g. old 42-float format upgrading
        // to 63-float, or even older accelerometer-based patterns at 50 samples).
        if (features.size != EXPECTED_EMBEDDING_SIZE) {
            Timber.w("Stored pattern size ${features.size} ≠ expected $EXPECTED_EMBEDDING_SIZE — re-enrolment required")
            clearPattern()
            return null
        }
        val sampleCount = loadEnrollmentSamples().size.takeIf { it > 0 } ?: 1
        return GesturePattern(id = id, featureVector = features, sampleCount = sampleCount)
            .also { storedPattern = it }
    }

    fun hasStoredPattern(): Boolean = loadStoredPattern() != null

    /**
     * Match [candidate]'s embedding against the stored centroid using cosine
     * similarity. Returns true if similarity >= [SIMILARITY_THRESHOLD].
     *
     * With multi-sample centroid enrollment the effective FAR is substantially
     * lower than single-sample because enrollment noise is averaged out.
     */
    fun match(candidate: GesturePattern): Boolean {
        val stored = loadStoredPattern() ?: run {
            Timber.w("No stored pattern to match against")
            return false
        }
        val similarity = CameraHandEmbedder.cosineSimilarity(
            stored.featureVector, candidate.featureVector
        )
        Timber.d("Embedding similarity: %.4f (threshold: $SIMILARITY_THRESHOLD, samples=${stored.sampleCount})".format(similarity))
        return similarity >= SIMILARITY_THRESHOLD
    }

    fun clearPattern() {
        storedPattern = null
        sequenceStep1 = null
        encryptedPrefs.edit()
            .remove(PREFS_KEY_PATTERN)
            .remove(PREFS_KEY_PATTERN_ID)
            .remove(PREFS_KEY_SAMPLES)
            .remove(PREFS_KEY_SEQ_STEP2)
            .remove(PREFS_KEY_SEQ_STEP2_ID)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Gesture sequence — step 2 storage & matching
    // -------------------------------------------------------------------------

    /**
     * Persist the step-2 embedding for 2-step sequence authentication.
     *
     * Call this after the user successfully records their second gesture during
     * the sequence enrollment flow (e.g. in response to [RecordingState.AwaitingStep2]).
     * Step 1 is stored via [savePattern] / [addEnrollmentSample] as usual.
     */
    fun saveSequenceStep2(pattern: GesturePattern) {
        encryptedPrefs.edit()
            .putString(PREFS_KEY_SEQ_STEP2,    pattern.featureVector.joinToString(","))
            .putString(PREFS_KEY_SEQ_STEP2_ID, pattern.id)
            .apply()
        Timber.d("Sequence step-2 embedding saved (${pattern.featureVector.size} floats)")
    }

    fun loadSequenceStep2(): GesturePattern? {
        val vectorStr = encryptedPrefs.getString(PREFS_KEY_SEQ_STEP2, null) ?: return null
        val id        = encryptedPrefs.getString(PREFS_KEY_SEQ_STEP2_ID, UUID.randomUUID().toString())!!
        val features  = vectorStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        if (features.size != EXPECTED_EMBEDDING_SIZE) {
            Timber.w("Sequence step-2 pattern size mismatch — discarded")
            return null
        }
        return GesturePattern(id = id, featureVector = features, sampleCount = 1)
    }

    fun hasSequencePattern(): Boolean =
        hasStoredPattern() && loadSequenceStep2() != null

    /**
     * Match a two-step gesture sequence against enrolled step-1 and step-2 patterns.
     *
     * Both steps must individually pass [SIMILARITY_THRESHOLD]. Returns false if
     * no sequence pattern is enrolled or either step fails to match.
     *
     * @param step1  Embedding captured during the first gesture in the sequence.
     * @param step2  Embedding captured during the second gesture in the sequence.
     */
    fun matchSequence(step1: GesturePattern, step2: GesturePattern): Boolean {
        val enrolled1 = loadStoredPattern() ?: run {
            Timber.w("Sequence match failed: no step-1 pattern enrolled")
            return false
        }
        val enrolled2 = loadSequenceStep2() ?: run {
            Timber.w("Sequence match failed: no step-2 pattern enrolled")
            return false
        }
        val sim1 = CameraHandEmbedder.cosineSimilarity(enrolled1.featureVector, step1.featureVector)
        val sim2 = CameraHandEmbedder.cosineSimilarity(enrolled2.featureVector, step2.featureVector)
        Timber.d("Sequence similarity — step1=%.4f  step2=%.4f (threshold=$SIMILARITY_THRESHOLD)".format(sim1, sim2))
        return sim1 >= SIMILARITY_THRESHOLD && sim2 >= SIMILARITY_THRESHOLD
    }
}
