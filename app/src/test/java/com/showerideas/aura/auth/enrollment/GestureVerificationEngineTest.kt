package com.showerideas.aura.auth.enrollment

import org.junit.Assert.*
import com.showerideas.aura.auth.enrollment.GestureEnrollmentCapture
import com.showerideas.aura.auth.CameraHandEmbedder
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Task 75 — Unit tests for [GestureVerificationEngine].
 *
 * Covers all six [VerificationResult] paths:
 * Success, Failure(A), Failure(B), AnchorFailed, NoEnrollment, LegacyEnrollment.
 */
class GestureVerificationEngineTest {

    private lateinit var tracker: DualBoneGraphTracker
    private lateinit var store: GestureDescriptorStore
    private lateinit var engine: GestureVerificationEngine

    @Before fun setUp() {
        tracker = DualBoneGraphTracker()
        store = mock(GestureDescriptorStore::class.java)
        engine = GestureVerificationEngine(tracker, store)
    }

    /**
     * All-negative frames: centroid is anti-correlated with constant-positive pose.
     * Compound-vector cosine against constant pose ≈ 0.67 < MATCH_THRESHOLD(0.85).
     */
    private fun negativePoseFrames(): List<FloatArray> =
        List(GestureEnrollmentCapture.CAPTURE_FRAMES) {
            FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> -((i + 1) * 0.01f) }
        }

    @Test fun `both windows match returns Success`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, descB))

        val result = engine.verify(frames)
        assertEquals(VerificationResult.Success, result)
    }

    @Test fun `window A fails returns Failure(A)`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        // Create a very different stored descriptor for A (random noise)
        val noisyFrames = negativePoseFrames()
        val (storedA, _) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(storedA, descB))  // B matches, A doesn't

        val result = engine.verify(frames)
        assertTrue("Expected Failure", result is VerificationResult.Failure)
        val failure = result as VerificationResult.Failure
        assertEquals(GestureDescriptor.WindowTag.A, failure.failedWindow)
    }

    @Test fun `window B fails returns Failure(B)`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        val noisyFrames = negativePoseFrames()
        val (_, storedB) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, storedB))  // A matches, B doesn't

        val result = engine.verify(frames)
        assertTrue("Expected Failure", result is VerificationResult.Failure)
        val failure = result as VerificationResult.Failure
        assertEquals(GestureDescriptor.WindowTag.B, failure.failedWindow)
    }

    @Test fun `empty frames returns AnchorFailed`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)

        val result = engine.verify(emptyList())
        assertEquals(VerificationResult.AnchorFailed, result)
    }

    @Test fun `no enrollment returns NoEnrollment`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(false)

        val result = engine.verify(TestLandmarkFactory.constantPose())
        assertEquals(VerificationResult.NoEnrollment, result)
    }

    @Test fun `legacy enrollment returns LegacyEnrollment`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(true)

        val result = engine.verify(TestLandmarkFactory.constantPose())
        assertEquals(VerificationResult.LegacyEnrollment, result)
    }

    @Test fun `AND logic enforced — A-pass B-fail is not Success`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, _) = tracker.extract(frames)
        val noisyFrames = negativePoseFrames()
        val (_, badB) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, badB))

        val result = engine.verify(frames)
        assertNotEquals(VerificationResult.Success, result)
    }
}
