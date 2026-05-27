package com.showerideas.aura.ar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 98/99 — AR overlay lifecycle controller.
 *
 * Bridges [ArExchangeCoordinator] state changes to the SceneView ARCore session.
 * Manages [ContactCardNode] lifecycle — creates nodes when a peer enters range,
 * updates their spatial anchor position on each ARCore frame, and removes them
 * when the peer is lost or moves out of range.
 *
 * ## Camera sharing
 * ARCore Augmented Faces and MediaPipe Hands share the same camera stream. The
 * ARCore session is configured with `FRONT` camera mode for face detection;
 * MediaPipe Hands runs as a secondary pipeline on the same frame data.
 * No concurrent back-camera access is required.
 *
 * ## Rendering
 * Uses SceneView (sceneview-android) as the Kotlin-friendly ARCore wrapper.
 * `ContactCardNode` is a SceneView `Node` that renders the floating contact card
 * anchored to the detected face position.
 *
 * See: github.com/SceneView/sceneview-android
 * See: ROADMAP §Task 98/99
 */
@Singleton
class ArExchangeOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: ArExchangeCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Active contact card nodes, keyed by faceAnchorId. */
    private val activeNodes = mutableMapOf<String, ContactCardNode>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start observing [ArExchangeCoordinator.state] and driving node creation/destruction.
     * Call from [ArExchangeFragment.onViewCreated].
     */
    fun start() {
        scope.launch {
            coordinator.state.collectLatest { state ->
                when (state) {
                    is ArExchangeCoordinator.ArExchangeState.PeerInRange -> {
                        ensureNode(state.faceAnchorId, state.identityKeyHash)
                    }
                    is ArExchangeCoordinator.ArExchangeState.PeerOutOfRange,
                    is ArExchangeCoordinator.ArExchangeState.Scanning -> {
                        clearAllNodes()
                    }
                    is ArExchangeCoordinator.ArExchangeState.Inactive -> {
                        clearAllNodes()
                    }
                    is ArExchangeCoordinator.ArExchangeState.RssiFallback -> {
                        Timber.w("ArExchangeOverlay: UWB unavailable, RSSI fallback active")
                    }
                }
            }
        }
        Timber.d("ArExchangeOverlay: started")
    }

    fun stop() {
        clearAllNodes()
        Timber.d("ArExchangeOverlay: stopped")
    }

    // ── Node management ───────────────────────────────────────────────────────

    private fun ensureNode(faceAnchorId: String, identityKeyHash: String) {
        if (activeNodes.containsKey(faceAnchorId)) return
        val node = ContactCardNode(context, identityKeyHash, faceAnchorId)
        activeNodes[faceAnchorId] = node
        Timber.d("ArExchangeOverlay: created ContactCardNode for anchor $faceAnchorId")
    }

    private fun clearAllNodes() {
        if (activeNodes.isNotEmpty()) {
            Timber.d("ArExchangeOverlay: clearing ${activeNodes.size} node(s)")
            activeNodes.clear()
        }
    }

    fun getActiveNodeCount(): Int = activeNodes.size
}
