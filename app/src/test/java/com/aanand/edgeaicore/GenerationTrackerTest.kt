package com.aanand.edgeaicore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTrackerTest {
    @After
    fun reset() {
        repeat(4) { GenerationTracker.end() }
    }

    @Test
    fun activeOnlyBetweenBeginAndEnd() {
        assertFalse(GenerationTracker.active)
        GenerationTracker.begin()
        assertTrue(GenerationTracker.active)
        GenerationTracker.end()
        assertFalse(GenerationTracker.active)
    }

    @Test
    fun anExtraEndDoesNotHideTheNextReply() {
        GenerationTracker.end()
        GenerationTracker.begin()
        assertTrue(GenerationTracker.active)
    }

    @Test
    fun theSphereDrawsEveryFrameUnlessAReplyIsGenerated() {
        assertEquals(0, GenerationTracker.sphereFrameIntervalMs(generating = false, touched = false, generatingMs = 100, touchedMs = 33))
        assertEquals(0, GenerationTracker.sphereFrameIntervalMs(generating = false, touched = true, generatingMs = 100, touchedMs = 33))
        assertEquals(100, GenerationTracker.sphereFrameIntervalMs(generating = true, touched = false, generatingMs = 100, touchedMs = 33))
        assertEquals(33, GenerationTracker.sphereFrameIntervalMs(generating = true, touched = true, generatingMs = 100, touchedMs = 33))
    }
}
