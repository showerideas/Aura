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

/**
 * Manages hand-gesture authentication for AURA exchanges.
 *
 * Architecture (camera embedding model):
 * - Recording: [CameraHandEmbedder] drives a front-facing camera pipeline
 *   backed by MediaPipe GestureRecognizer running in LIVE_STREAM mode.
 * - Feature extraction: 21 hand landmarks → normalised 42-float embedding
 *   (pose-invariant: centred on wrist, scaled by wrist-to-MCP distance).
 * - Matching: cosine similarity with [SIMILARITY_THRESHOLD] tolerance.
 * - Storage: the 42-float embedding is persisted in EncryptedSharedPreferences
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
        /**
         * Cosine similarity threshold. Hand shapes vary slightly between
         * repetitions; 0.88 sits comfortably above random-hand noise (~0.5)
         * and below the within-person consistency floor (~0.93).
         */
        const val SIMILARITY_THRESHOLD = 0.88f
        /**
         * Expected embedding size from [CameraHandEmbedder.EMBEDDING_SIZE].
         * Stored patterns with a different size are treated as legacy sensor
         * patterns and discarded (user must re-enrol with the camera).
         */
        private const val EXPECTED_EMBEDDING_SIZE = CameraHandEmbedder.EMBEDDING_SIZE
    }

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    sealed class RecordingState {
        /** Camera not started or stopped. */
        object Idle : RecordingState()
        /** Camera running — waiting for a stable gesture. */
        object Recording : RecordingState()
        /** A stable gesture embedding is ready. */
        data class Complete(val pattern: GesturePattern) : RecordingState()
        /** Camera or model initialisation failed. */
        data class Error(val message: String) : RecordingState()
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
                    }
                    is CameraHandEmbedder.GestureState.Stable -> {
                        _liveVariance.value = 1f
                        val pattern = GesturePattern(
                            id            = UUID.randomUUID().toString(),
                            label         = state.gesture.displayName,
                            featureVector = state.embedding,
                            sampleCount   = state.embedding.size
                        )
                        _recordingState.value = RecordingState.Complete(pattern)
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
        if (_recordingState.value !is RecordingState.Idle) {
            _recordingState.value = RecordingState.Recording
        }
        _liveVariance.value = 0f
    }

    // -------------------------------------------------------------------------
    // Public API — pattern storage (unchanged external contract)
    // -------------------------------------------------------------------------

    /**
     * Persist [pattern] as the authoritative gesture key for this device.
     */
    fun savePattern(pattern: GesturePattern) {
        storedPattern = pattern
        encryptedPrefs.edit()
            .putString(PREFS_KEY_PATTERN,    pattern.featureVector.joinToString(","))
            .putString(PREFS_KEY_PATTERN_ID, pattern.id)
            .apply()
        Timber.d("Hand embedding saved (${pattern.featureVector.size} floats)")
    }

    fun loadStoredPattern(): GesturePattern? {
        storedPattern?.let { return it }
        val vectorString = encryptedPrefs.getString(PREFS_KEY_PATTERN, null) ?: return null
        val id           = encryptedPrefs.getString(PREFS_KEY_PATTERN_ID, UUID.randomUUID().toString())!!
        val features     = vectorString.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        // Legacy sensor patterns had 50 samples; discard them so users re-enrol
        // with the camera embedding (42 floats).
        if (features.size != EXPECTED_EMBEDDING_SIZE) {
            Timber.w("Legacy sensor pattern (size=${features.size}) discarded — re-enrolment required")
            clearPattern()
            return null
        }
        return GesturePattern(id = id, featureVector = features, sampleCount = features.size)
            .also { storedPattern = it }
    }

    fun hasStoredPattern(): Boolean = loadStoredPattern() != null

    /**
     * Match [candidate]'s embedding against the stored pattern using cosine
     * similarity. Returns true if similarity >= [SIMILARITY_THRESHOLD].
     */
    fun match(candidate: GesturePattern): Boolean {
        val stored = loadStoredPattern() ?: run {
            Timber.w("No stored pattern to match against")
            return false
        }
        val similarity = CameraHandEmbedder.cosineSimilarity(
            stored.featureVector, candidate.featureVector
        )
        Timber.d("Hand embedding similarity: %.4f (threshold: $SIMILARITY_THRESHOLD)".format(similarity))
        return similarity >= SIMILARITY_THRESHOLD
    }

    fun clearPattern() {
        storedPattern = null
        encryptedPrefs.edit()
            .remove(PREFS_KEY_PATTERN)
            .remove(PREFS_KEY_PATTERN_ID)
            .apply()
    }
}
