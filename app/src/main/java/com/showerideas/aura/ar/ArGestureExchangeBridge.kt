package com.showerideas.aura.ar

import com.showerideas.aura.auth.enrollment.GestureVerificationEngine
import com.showerideas.aura.auth.enrollment.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 100 — Gesture recognition bridge for AR mode.
 *
 * Connects the AR camera feed to [GestureVerificationEngine] (Phase 5) so that
 * performing the enrolled gesture in front of the camera — while a contact card
 * is selected — triggers exchange consent.
 *
 * ## Camera pipeline
 * ARCore Augmented Faces uses the front camera. MediaPipe Hands runs as a
 * secondary listener on the same camera frames. Frame data flows:
 *
 *   ARCore Session → onUpdate callback → ArGestureExchangeBridge.onFrame()
 *       → CameraHandEmbedder (MediaPipe) → GestureVerificationEngine
 *
 * ## Lifecycle
 * - Starts collecting frames only when [ArExchangeCoordinator.state] is
 *   [ArExchangeCoordinator.ArExchangeState.PeerInRange] AND a card is selected.
 * - Stops and resets buffer on peer loss or card de-selection.
 * - Buffer: 60 frames (2 seconds @ 30fps) — matches Phase 5 enrollment contract.
 *
 * See: ROADMAP §Task 100
 */
@Singleton
class ArGestureExchangeBridge @Inject constructor(
    private val verificationEngine: GestureVerificationEngine,
    private val coordinator: ArExchangeCoordinator
) {

    companion object {
        private const val REQUIRED_FRAMES = 60  // 2s @ 30fps — matches enrollment contract
    }

    private val frameBuffer = mutableListOf<FloatArray>()
    private var isCollecting = false

    // ── Frame intake ──────────────────────────────────────────────────────────

    /**
     * Called on each ARCore frame update when a card is selected.
     *
     * @param embedding 63-float MediaPipe Hands embedding from [CameraHandEmbedder].
     *                  Pass null if no hand is detected in this frame (frame is skipped).
     * @return [VerificationResult] once [REQUIRED_FRAMES] are accumulated, null otherwise.
     */
    suspend fun onFrame(embedding: FloatArray?): VerificationResult? = withContext(Dispatchers.Default) {
        if (!isCollecting) return@withContext null
        if (embedding != null) {
            frameBuffer.add(embedding)
        }
        if (frameBuffer.size >= REQUIRED_FRAMES) {
            val frames = frameBuffer.toList()
            reset()
            Timber.d("ArGestureExchangeBridge: buffer full — running verification")
            verificationEngine.verify(frames)
        } else {
            null
        }
    }

    fun startCollecting() {
        if (!isCollecting) {
            frameBuffer.clear()
            isCollecting = true
            Timber.d("ArGestureExchangeBridge: started collecting frames")
        }
    }

    fun reset() {
        frameBuffer.clear()
        isCollecting = false
        Timber.d("ArGestureExchangeBridge: buffer reset")
    }

    val collectedFrameCount: Int get() = frameBuffer.size
    val progressFraction: Float get() =
        (frameBuffer.size.toFloat() / REQUIRED_FRAMES).coerceIn(0f, 1f)
}
