package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DotSphereModelTest {
    /** The reference page's numbers (pieces.xml and tokens.json). */
    private val spec = DotSphereSpec(
        dotsPhone = 250, dotsComputer = 600, tabletMinWidth = 600f, computerMinWidth = 1024f, sizeFactor = 0.75f,
        radiusPhone = 0.42f, radiusComputer = 0.28f, radiusComputerMin = 300f, stretchFactor = 0.3f, stretchCap = 1.3f,
        goldenAngle = 2.399963229728653, perspective = 800f, spinPerMs = 0.0001f, spinPulseGain = 2.5f,
        driftX = 40f, driftXPerMs = 0.0006f, driftY = 30f, driftYPerMs = 0.0008f, breathe = 0.05f, breathePerMs = 0.006f,
        wave = 15f, wavePerMs = 0.0015f, waveSpread = 2f, rippleDecay = 0.99f, rippleSpeed = 2.5f, rippleReach = 160f,
        rippleWidth = 75f, ripplePush = 140f, rippleStart = 0.3f, rippleFloor = 0.01f, distanceSpread = 0.9f,
        dotSizeMin = 1.5f, dotSizeRange = 2.5f, dotSizeBase = 0.6f, dotSizeUser = 0.4f, alphaFloor = 0.1f, alphaOffset = 0.18f,
        alphaCap = 0.92f, follow = 0.06f, lean = 0.05f, leanX = 0.4f, leanY = 0.3f, ease = 0.04f, frameCapMs = 64f,
        scriptFrameMs = 16.667f, colours = 5,
    )

    private fun model(width: Float = 400f, height: Float = 800f, seed: Long = 7) =
        DotSphereModel(spec, seed).apply { layout(width, height) }

    @Test
    fun dotCountFollowsTheWidthClass() {
        assertEquals(250, model(400f, 800f).dotCount)
        assertEquals(250, model(800f, 1200f).dotCount)
        assertEquals(250, model(1023f, 800f).dotCount)
        assertEquals(600, model(1024f, 800f).dotCount)
        assertEquals(600, model(1280f, 800f).dotCount)
    }

    @Test
    fun everyDotLiesWithinTheRadiusBand() {
        val m = model(400f, 800f)
        val r = m.sphereRadius
        // dist is 1 +- 0.45, the wave adds up to 15, perspective and the phone stretch (up to 1.3) widen it further
        val maxReach = (r * 1.45f + 15f) * 1.3f * 2f
        for (d in m.project()) {
            assertTrue("dot at ${d.x},${d.y} too far", hypot(d.x - m.centreX, d.y - m.centreY) <= maxReach)
        }
    }

    @Test
    fun theSameSeedPlacesTheSameDots() {
        assertEquals(model(seed = 3).project(), model(seed = 3).project())
    }

    @Test
    fun advanceChangesNothingWhenMotionIsOff() {
        val m = model()
        m.motionEnabled = false
        val before = m.project()
        m.leanToward(10f, 10f)
        m.ripple(1.3f)
        repeat(30) { m.advance(16f) }
        assertEquals(before, m.project())
    }

    @Test
    fun aRippleDecaysToZero() {
        val m = model()
        m.ripple(1.3f)
        assertTrue(m.rippleLevel > 0f)
        repeat(2000) { m.advance(16.667f) }
        assertEquals(0f, m.rippleLevel, 0f)
    }

    @Test
    fun projectReturnsDotsBackToFrontWithAlphaInRange() {
        val dots = model().project()
        assertTrue(dots.isNotEmpty())
        for (d in dots) assertTrue("alpha ${d.alpha}", d.alpha in 0.1f..0.92f)
        // The script's alpha grows with depth and dots come far first, so between the floor and the cap alpha never rises.
        val unclamped = dots.map { it.alpha }.filter { it < 0.92f && it > 0.1f }
        assertEquals(unclamped.sortedDescending(), unclamped)
    }

    @Test
    fun aTouchMovesTheCentreTowardIt() {
        val m = model(400f, 800f)
        val startX = m.centreX
        val startY = m.centreY
        m.leanToward(50f, 100f)
        repeat(60) { m.advance(16.667f) }
        assertTrue(m.centreX < startX)
        assertTrue(m.centreY < startY)
    }

    @Test
    fun aTapLeavesTheSphereSettlingWhereItWasTouched() {
        val m = model(400f, 800f)
        m.leanToward(300f, 600f)
        repeat(6) { m.advance(16.667f) } // a tap lasts about 100 ms
        repeat(240) { m.advance(16.667f) }
        assertEquals(300f, m.centreX, 2f)
        assertEquals(600f, m.centreY, 2f)
    }

    @Test
    fun workingSpeedsUpTheTurn() {
        val idle = model()
        val busy = model().apply { setWorking(true) }
        repeat(120) {
            idle.advance(16.667f)
            busy.advance(16.667f)
        }
        assertTrue(idle.project() != busy.project())
    }

    @Test
    fun aShortStepStillMovesTheSphereAfterDaysOfRunning() {
        val m = model()
        repeat(4_000_000) { m.advance(64f) } // about 71 hours
        val before = m.project()
        m.advance(5f)
        assertTrue(before != m.project())
    }
}
