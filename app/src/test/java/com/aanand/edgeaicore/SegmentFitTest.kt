package com.aanand.edgeaicore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentFitTest {
    // A 300 wide track with 6 padding each side, three segments with 12 padding each side, gaps of 2 before the 2nd and 3rd.
    private fun stacks(vararg longestWords: Float) =
        SegmentFit.mustStack(trackInner = 288f, margins = 4f, segmentPadding = 24f, longestWords = longestWords.toList())

    @Test
    fun wordsThatFitStaySideBySide() {
        assertFalse(stacks(40f, 60f, 70f))
    }

    @Test
    fun aWordJustNarrowerThanItsShareFits() {
        // (288 - 4) / 3 = 94.67 per segment, 70.67 for text.
        assertFalse(stacks(40f, 70.6f, 30f))
    }

    @Test
    fun theGapsBetweenSegmentsCount() {
        // Without the gaps the share would be 96 and 72 for text; with them (70.67 for text) a 71 wide word does not fit.
        assertTrue(stacks(40f, 71f, 30f))
    }

    @Test
    fun oneWordTooWideStacksTheTrack() {
        assertTrue(stacks(40f, 60f, 120f))
    }

    @Test
    fun anEmptyTrackDoesNotStack() {
        assertFalse(SegmentFit.mustStack(288f, 0f, 24f, emptyList()))
    }
}
