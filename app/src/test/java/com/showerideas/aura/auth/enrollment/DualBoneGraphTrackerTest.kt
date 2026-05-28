package com.showerideas.aura.auth.enrollment

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Task 75 — Unit tests for [DualBoneGraphTracker].
 */
class DualBoneGraphTrackerTest {

    private lateinit var tracker: DualBoneGraphTracker

    @Before fun setUp() {
        tracker = DualBoneGraphTracker()
    }

    @Test fun `constant pose produces motionProfile all-1f for both windows`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)
        // Identical consecutive frames → cosine similarity = 1.0
        descA.motionProfile.forEach {
            assertEquals("Window A motion delta should be 1.0", 1.0f, it, 0.001f)
        }
        descB.motionProfile.forEach {
            assertEquals("Window B motion delta should be 1.0", 1.0f, it, 0.001f)
        }
    }

    @Test fun `window A and B receive correct slice of 60-frame buffer`() {
        val frames = TestLandmarkFactory.slowRotation()
        val (descA, descB) = tracker.extract(frames)

        // Window A centroid should be dominated by frames 0-44
        // Window B centroid should be dominated by frames 15-59
        // Since frames vary monotonically, B centroid > A centroid for increasing dimensions
        // Validate that centroids are different (they represent different temporal slices)
        val centroidDiff = descA.centroid.zip(descB.centroid.toList()).any { (a, b) -> Math.abs(a - b) > 0.001f }
        assertTrue("Window A and B centroids should differ for moving gesture", centroidDiff)
    }

    @Test fun `descriptor lengths are correct`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)
        assertEquals(DualBoneGraphTracker.EMBEDDING_SIZE, descA.centroid.size)
        assertEquals(DualBoneGraphTracker.MOTION_PROFILE_SIZE, descA.motionProfile.size)
        assertEquals(DualBoneGraphTracker.EMBEDDING_SIZE, descB.centroid.size)
        assertEquals(DualBoneGraphTracker.MOTION_PROFILE_SIZE, descB.motionProfile.size)
    }

    @Test fun `compound vector length is 107`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, _) = tracker.extract(frames)
        assertEquals(107, descA.compoundVector().size)
    }

    @Test fun `window tags are assigned correctly`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)
        assertEquals(GestureDescriptor.WindowTag.A, descA.windowTag)
        assertEquals(GestureDescriptor.WindowTag.B, descB.windowTag)
    }

    @Test fun `throws on wrong frame count`() {
        val shortFrames = TestLandmarkFactory.constantPose(frameCount = 30)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.extract(shortFrames)
        }
    }

    @Test fun `A and B descriptors are never merged or averaged`() {
        // Frames 0-14 = slow rotation, frames 15-59 = fast flick
        // Build a hybrid: first 15 slow, next 45 fast
        val slow = TestLandmarkFactory.slowRotation()
        val fast = TestLandmarkFactory.fastFlick(deltaPerFrame = 0.5f)
        val hybrid = slow.subList(0, 15) + fast.subList(0, 45)
        assertEquals(60, hybrid.size)

        val (descA, descB) = tracker.extract(hybrid)
        // A covers 0-44 (slow + transition), B covers 15-59 (transition + fast)
        // Motion profiles should differ significantly due to different temporal content
        val motionDiff = descA.motionProfile.zip(descB.motionProfile.toList())
            .count { (a, b) -> Math.abs(a - b) > 0.05f }
        assertTrue("A and B motion profiles should differ for mixed-motion gesture", motionDiff > 5)
    }
}
