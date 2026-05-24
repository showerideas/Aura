package com.showerideas.aura.auth

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.showerideas.aura.model.HandGesture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Camera-based hand embedding engine.
 *
 * Binds a front-facing CameraX pipeline to the provided [PreviewView] and
 * feeds each frame through a MediaPipe [GestureRecognizer] running in
 * LIVE_STREAM mode.  For every frame that contains a hand:
 *   1. The 21 normalised landmarks are extracted (x, y, z per point = 63 floats).
 *      The z coordinate is MediaPipe world-space depth, providing a third
 *      dimension that photos and flat displays cannot replicate — improving
 *      both uniqueness entropy and spoofing resistance.
 *   2. The vector is centred on the wrist and scaled by the 3D wrist-to-MCP
 *      distance so it is invariant to hand position and distance from the camera.
 *   3. Consecutive frames must agree (cosine similarity > [STABILITY_THRESHOLD])
 *      for [COMMIT_FRAMES] frames before the state advances to [GestureState.Stable].
 *
 * The resulting 63-float embedding IS the authentication credential — two
 * people making the same named gesture will produce different embeddings
 * because their hand shapes differ.  The gesture label ([HandGesture]) is
 * surfaced only for real-time UI feedback.
 */
@Singleton
class CameraHandEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    sealed class GestureState {
        /** No hand visible in the frame. */
        object NoHand : GestureState()

        /**
         * A hand is visible and classified. [stability] is 0..1 showing how
         * close we are to the [COMMIT_FRAMES] requirement — drives progress UI.
         */
        data class Detecting(
            val gesture: HandGesture,
            val embedding: FloatArray,
            val stability: Float
        ) : GestureState()

        /**
         * Gesture has been stable for [COMMIT_FRAMES] consecutive frames.
         * The embedding is ready to be used as an authentication token.
         */
        data class Stable(
            val gesture: HandGesture,
            val embedding: FloatArray
        ) : GestureState()

        /**
         * MediaPipe model failed to initialise (e.g. missing .task asset or
         * incompatible device). Consumers should surface [message] to the user
         * and refrain from calling [start] again without addressing the cause.
         */
        data class ModelError(val message: String) : GestureState()
    }

    private val _gestureState = MutableStateFlow<GestureState>(GestureState.NoHand)
    val gestureState: StateFlow<GestureState> = _gestureState

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "CameraHandEmbedder"
        private const val MODEL_ASSET = "gesture_recognizer.task"
        /** Minimum MediaPipe confidence to accept a gesture classification. */
        private const val MIN_CONFIDENCE = 0.72f
        /** Consecutive frames required before emitting Stable. */
        private const val COMMIT_FRAMES = 12
        /** Cosine similarity required between frames to count as "same gesture". */
        private const val STABILITY_THRESHOLD = 0.97f
        /**
         * Output size: 21 landmarks × (x, y, z) = 63 floats.
         *
         * Including the z (depth) coordinate serves two purposes:
         *  1. Uniqueness: hand depth profile varies more across individuals than the
         *     2D projection alone, lowering the false-accept rate.
         *  2. Anti-spoofing: a flat photo or display has near-zero z variance across
         *     landmarks, making static-source embeddings more distinguishable from
         *     live hands (complements [LivenessGuard]'s drift check).
         */
        const val EMBEDDING_SIZE = 63

        /**
         * Normalise raw MediaPipe NormalizedLandmark list to a pose-invariant
         * 63-float embedding:
         *   - Translate so wrist (index 0) is at origin in all three axes.
         *   - Scale by 3D wrist → middle-finger-MCP (index 9) distance so hand
         *     size and camera distance do not affect the vector.
         *
         * Returns a zero vector if the hand is too small or degenerate.
         */
        fun normalizeEmbedding(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): FloatArray {
            if (landmarks.size < 21) return FloatArray(EMBEDDING_SIZE)
            val wristX = landmarks[0].x()
            val wristY = landmarks[0].y()
            val wristZ = landmarks[0].z()
            val mcpX   = landmarks[9].x()
            val mcpY   = landmarks[9].y()
            val mcpZ   = landmarks[9].z()
            // 3D Euclidean distance from wrist to middle-MCP as the scale factor
            val scale  = sqrt(
                (mcpX - wristX).pow(2) + (mcpY - wristY).pow(2) + (mcpZ - wristZ).pow(2)
            )
            if (scale < 0.01f) return FloatArray(EMBEDDING_SIZE)
            return FloatArray(EMBEDDING_SIZE) { i ->
                val lm = landmarks[i / 3]
                when (i % 3) {
                    0    -> (lm.x() - wristX) / scale
                    1    -> (lm.y() - wristY) / scale
                    else -> (lm.z() - wristZ) / scale
                }
            }
        }

        /**
         * Cosine similarity between two equal-length float vectors.
         * Returns 0f for zero vectors or mismatched sizes.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f; var normA = 0f; var normB = 0f
            for (i in a.indices) {
                dot   += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            if (normA == 0f || normB == 0f) return 0f
            return dot / sqrt(normA * normB)
        }
    }

    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    // Consecutive-frame accumulator
    private var consecutiveFrames = 0
    private var lastGesture = HandGesture.NONE
    private var lastEmbedding: FloatArray? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Bind the camera + MediaPipe pipeline to [lifecycleOwner] and render the
     * preview into [previewView].  Safe to call multiple times — unbinds any
     * previous session first.
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        analysisExecutor = Executors.newSingleThreadExecutor()
        initRecognizer()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCamera(lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Unbind the camera, close the recognizer, and reset all state.
     */
    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        resetAccumulator()
        _gestureState.value = GestureState.NoHand
    }

    /**
     * Force a state reset so the next stable emission is treated as a fresh
     * gesture capture — used between authentication attempts.
     */
    fun resetConsecutive() {
        resetAccumulator()
        _gestureState.value = GestureState.NoHand
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun initRecognizer() {
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setResultListener(::onResult)
                .setErrorListener { e -> Timber.e(e, "MediaPipe error") }
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            val msg = "Failed to load gesture model ($MODEL_ASSET): ${e.message}"
            Timber.e(e, msg)
            // Emit ModelError so GestureAuthManager can surface it in RecordingState.Error
            // instead of the UI hanging silently on "No hand detected" forever.
            _gestureState.value = GestureState.ModelError(msg)
        }
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { ia ->
                ia.setAnalyzer(analysisExecutor!!) { proxy -> processFrame(proxy) }
            }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
            )
        } catch (e: Exception) {
            Timber.e(e, "Camera bind failed")
        }
    }

    private fun processFrame(proxy: ImageProxy) {
        val rec = recognizer ?: run { proxy.close(); return }
        val bitmap = proxy.toBitmap()
        proxy.close()
        try {
            rec.recognizeAsync(BitmapImageBuilder(bitmap).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Timber.w(e, "recognizeAsync failed")
        }
    }

    private fun onResult(result: GestureRecognizerResult, @Suppress("UNUSED_PARAMETER") unused: com.google.mediapipe.framework.image.MPImage) {
        val gestures  = result.gestures()
        // MediaPipe Tasks Vision 0.10.x changed the API from handLandmarks()
        // (returning List<NormalizedLandmarks>) to landmarks() (returning
        // List<List<NormalizedLandmark>> directly with no wrapper class).
        val landmarks = result.landmarks()

        if (gestures.isEmpty() || gestures[0].isEmpty() || landmarks.isEmpty()) {
            resetAccumulator()
            return
        }

        val top = gestures[0][0]
        if (top.score() < MIN_CONFIDENCE) { resetAccumulator(); return }

        val gesture   = HandGesture.fromMediaPipeLabel(top.categoryName())
        if (gesture == HandGesture.NONE)  { resetAccumulator(); return }

        // landmarks[0] is already List<NormalizedLandmark> in the 0.10.x API
        val embedding = normalizeEmbedding(landmarks[0])

        // Accumulate consecutive stable frames
        val similarity = if (lastEmbedding != null) cosineSimilarity(embedding, lastEmbedding!!) else 0f
        if (gesture == lastGesture && similarity >= STABILITY_THRESHOLD) {
            consecutiveFrames++
        } else {
            lastGesture   = gesture
            lastEmbedding = embedding
            consecutiveFrames = 1
        }

        val stability = (consecutiveFrames.toFloat() / COMMIT_FRAMES).coerceAtMost(1f)
        _gestureState.value = if (consecutiveFrames >= COMMIT_FRAMES) {
            GestureState.Stable(gesture, embedding)
        } else {
            GestureState.Detecting(gesture, embedding, stability)
        }
    }

    private fun resetAccumulator() {
        consecutiveFrames = 0
        lastGesture   = HandGesture.NONE
        lastEmbedding = null
    }
}
