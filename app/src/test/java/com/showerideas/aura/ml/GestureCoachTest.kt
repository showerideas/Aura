package com.showerideas.aura.ml

import com.showerideas.aura.auth.GestureLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Task 53 — GestureCoach unit tests.
 *
 * Uses Mockito to simulate GestureLibrary state without SharedPreferences.
 */
class GestureCoachTest {

    private lateinit var gestureLibrary: GestureLibrary
    private lateinit var coach: GestureCoach

    @Before
    fun setup() {
        gestureLibrary = mock()
        coach = GestureCoach(gestureLibrary)
    }

    @Test
    fun `empty library returns INFO advice`() {
        whenever(gestureLibrary.listSlots()).thenReturn(emptyList())
        val advice = coach.analyzeAndCoach()
        assertEquals(1, advice.size)
        assertEquals("empty_library", advice[0].metricKey)
    }

    @Test
    fun `insufficient samples returns WARNING`() {
        whenever(gestureLibrary.listSlots()).thenReturn(listOf(
            GestureLibrary.GestureSlot("id1", "Wave", System.currentTimeMillis(), sampleCount = 2)
        ))
        val advice = coach.analyzeAndCoach()
        assertTrue("Must warn on insufficient samples",
            advice.any { it.metricKey == "insufficient_samples" })
        val warn = advice.first { it.metricKey == "insufficient_samples" }
        assertEquals(GestureCoach.CoachingAdvice.Severity.WARNING, warn.severity)
    }

    @Test
    fun `single slot returns INFO about backup`() {
        whenever(gestureLibrary.listSlots()).thenReturn(listOf(
            GestureLibrary.GestureSlot("id1", "Swipe", System.currentTimeMillis(), sampleCount = 5)
        ))
        val advice = coach.analyzeAndCoach()
        assertTrue("Single slot must suggest backup", advice.any { it.metricKey == "single_slot" })
    }

    @Test
    fun `imbalanced slots detected`() {
        val now = System.currentTimeMillis()
        whenever(gestureLibrary.listSlots()).thenReturn(listOf(
            GestureLibrary.GestureSlot("id1", "Wave", now, sampleCount = 10),
            GestureLibrary.GestureSlot("id2", "Pinch", now, sampleCount = 2)
        ))
        val advice = coach.analyzeAndCoach()
        assertTrue("Imbalanced slots must be flagged",
            advice.any { it.metricKey == "imbalanced_slots" })
    }

    @Test
    fun `good library returns no CRITICAL advice`() {
        val now = System.currentTimeMillis()
        whenever(gestureLibrary.listSlots()).thenReturn(listOf(
            GestureLibrary.GestureSlot("id1", "Wave", now, sampleCount = 6),
            GestureLibrary.GestureSlot("id2", "Pinch", now, sampleCount = 5)
        ))
        val advice = coach.analyzeAndCoach()
        assertTrue("Good library must have no CRITICAL advice",
            advice.none { it.severity == GestureCoach.CoachingAdvice.Severity.CRITICAL })
    }

    @Test
    fun `advice count increments on each analysis`() {
        whenever(gestureLibrary.listSlots()).thenReturn(emptyList())
        coach.analyzeAndCoach()
        coach.analyzeAndCoach()
        assertEquals(2, coach.totalAdviceRounds())
    }

    @Test
    fun `gradient stub returns value in 0-1 range`() {
        val now = System.currentTimeMillis()
        whenever(gestureLibrary.listSlots()).thenReturn(listOf(
            GestureLibrary.GestureSlot("id1", "Wave", now, sampleCount = 5)
        ))
        val gradient = coach.computeLocalGradientStub()
        assertTrue("Gradient must be in [0, 1]", gradient in 0f..1f)
    }
}
