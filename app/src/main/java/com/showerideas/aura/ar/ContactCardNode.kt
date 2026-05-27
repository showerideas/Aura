package com.showerideas.aura.ar

import android.content.Context
import timber.log.Timber

/**
 * Task 99 — Floating AR contact card node.
 *
 * Represents a single peer's contact card anchored to an ARCore `AugmentedFace`
 * anchor in 3D space. In the full SceneView integration this extends
 * `io.github.sceneview.node.Node`; that dependency is not declared in the
 * baseline build because ARCore is an optional enterprise feature gated by
 * [BuildConfig.ENABLE_AR_EXCHANGE].
 *
 * ## Rendering contract
 * - Card renders at 0.3m above the detected face centroid anchor.
 * - Billboarding: always faces the camera (no manual rotation needed — SceneView
 *   handles billboard nodes natively).
 * - Content: peer avatar (CircleImageView, 48dp), display name placeholder,
 *   "Tap to exchange" affordance.
 * - Gesture confirmation: tap on this node surfaces the exchange consent dialog
 *   after gesture verification succeeds (Task 100).
 *
 * ## Layout
 * The card content is inflated from `fragment_ar_exchange.xml` and rendered
 * to an off-screen `SurfaceTexture` that is applied to a SceneView `MaterialInstance`.
 * This avoids ARCore's ViewRenderable dependency on the older ARSceneView SDK.
 *
 * See: ROADMAP §Task 99
 */
class ContactCardNode(
    private val context: Context,
    val identityKeyHash: String,
    val faceAnchorId: String
) {

    companion object {
        /** Vertical offset above face anchor centroid in metres. */
        const val CARD_OFFSET_Y_M = 0.3f
        /** Card width in metres (world scale). */
        const val CARD_WIDTH_M = 0.25f
        /** Card height in metres (world scale). */
        const val CARD_HEIGHT_M = 0.14f
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Whether the user has tapped this card to initiate exchange confirmation. */
    var isSelected: Boolean = false
        private set

    init {
        Timber.d("ContactCardNode: created for $identityKeyHash @ anchor $faceAnchorId")
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    /**
     * Called when the user taps on this card's rendered region.
     * Sets [isSelected] and returns true so the AR fragment can proceed
     * to gesture verification and exchange consent.
     */
    fun onTapped(): Boolean {
        if (isSelected) return false
        isSelected = true
        Timber.d("ContactCardNode: tapped — $identityKeyHash selected for exchange")
        return true
    }

    /**
     * Reset selection state (e.g. after exchange is cancelled or completes).
     */
    fun clearSelection() {
        isSelected = false
    }

    override fun toString(): String =
        "ContactCardNode(anchor=$faceAnchorId, keyHash=$identityKeyHash, selected=$isSelected)"
}
