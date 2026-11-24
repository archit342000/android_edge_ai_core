package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereFadePatchesTest {
    // tokens.json wordsOnSphere: x = 14% and y = 6% of line height, x clamped to 8..14, y to 4..8
    private val fade = SphereFadePatches(0.14f, 0.06f, 8f, 14f, 4f, 8f)

    @Test
    fun smallLettersGetTheLowerClamp() {
        val p = fade.patches(listOf(SphereFadePatches.Box(10f, 10f, 110f, 30f))).single()
        assertEquals(SphereFadePatches.Box(2f, 6f, 118f, 34f), p)
    }

    @Test
    fun largeLettersGetTheUpperClamp() {
        val p = fade.patches(listOf(SphereFadePatches.Box(0f, 0f, 400f, 200f))).single()
        assertEquals(SphereFadePatches.Box(-14f, -8f, 414f, 208f), p)
    }

    @Test
    fun paddingInsideTheRangeFollowsTheLineHeight() {
        val p = fade.patches(listOf(SphereFadePatches.Box(0f, 0f, 100f, 80f))).single()
        assertEquals(-11.2f, p.left, 0.001f)
        assertEquals(-4.8f, p.top, 0.001f)
    }

    @Test
    fun anEmptyLineGetsNoPatch() {
        assertTrue(fade.patches(listOf(SphereFadePatches.Box(5f, 5f, 5f, 25f), SphereFadePatches.Box(0f, 5f, 50f, 5f))).isEmpty())
    }

    @Test
    fun eachLineGetsItsOwnPatch() {
        val lines = listOf(SphereFadePatches.Box(0f, 0f, 300f, 40f), SphereFadePatches.Box(0f, 50f, 120f, 70f))
        assertEquals(2, fade.patches(lines).size)
    }
}
