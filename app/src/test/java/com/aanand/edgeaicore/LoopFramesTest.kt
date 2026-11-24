package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopFramesTest {
    private val linear: (Float) -> Float = { it }

    @Test
    fun ledIsFullAtTheEndsAndLowAtHalfTime() {
        assertEquals(1f, LoopFrames.ledAlpha(0, 1000, 0.5f), 0.001f)
        assertEquals(0.5f, LoopFrames.ledAlpha(500, 1000, 0.5f), 0.001f)
        assertEquals(1f, LoopFrames.ledAlpha(1000, 1000, 0.5f), 0.001f)
        assertEquals(0.75f, LoopFrames.ledAlpha(250, 1000, 0.5f), 0.001f)
    }

    @Test
    fun aDotRestsBeforeItsDelayPeaksAtFortyPercentAndRestsFromEighty() {
        assertEquals(0.8f, LoopFrames.dotBounce(100, 1400, 160, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        assertEquals(0.8f, LoopFrames.dotBounce(160, 1400, 160, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        assertEquals(1.3f, LoopFrames.dotBounce(160 + 560, 1400, 160, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        assertEquals(0.8f, LoopFrames.dotBounce(160 + 1120, 1400, 160, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        assertEquals(0.8f, LoopFrames.dotBounce(160 + 1300, 1400, 160, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        assertEquals(1.05f, LoopFrames.dotBounce(280, 1400, 0, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
    }

    @Test
    fun theStopsAndTheEasingComeFromTheCaller() {
        // Peak at half the period, rest from 90%: a quarter of the period is half way up.
        assertEquals(1.05f, LoopFrames.dotBounce(250, 1000, 0, 0.8f, 1.3f, 0.5f, 0.9f, linear), 0.001f)
        assertEquals(1.3f, LoopFrames.dotBounce(500, 1000, 0, 0.8f, 1.3f, 0.5f, 0.9f, linear), 0.001f)
        assertEquals(1.05f, LoopFrames.dotBounce(700, 1000, 0, 0.8f, 1.3f, 0.5f, 0.9f, linear), 0.001f)
        assertEquals(0.8f, LoopFrames.dotBounce(850, 1000, 0, 0.8f, 1.3f, 0.4f, 0.8f, linear), 0.001f)
        // The easing shapes each stretch between stops: squared progress is a quarter of the way at half time.
        assertEquals(0.925f, LoopFrames.dotBounce(250, 1000, 0, 0.8f, 1.3f, 0.5f, 0.9f) { it * it }, 0.001f)
    }

    @Test
    fun theSpinnerTurnsOncePerPeriod() {
        assertEquals(0f, LoopFrames.spinnerAngle(0, 800), 0.001f)
        assertEquals(180f, LoopFrames.spinnerAngle(400, 800), 0.001f)
        assertEquals(90f, LoopFrames.spinnerAngle(1000, 800), 0.001f)
    }
}
