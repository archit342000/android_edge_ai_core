package com.aanand.edgeaicore

/** Whether side-by-side choices (rule book 7.3) must stack so that no word breaks in the middle. Plain Kotlin. */
object SegmentFit {
    /**
     * True when a segment's longest word is wider than its share of the track: [trackInner] is the track's width inside its
     * padding, [margins] the gaps between segments added up, [segmentPadding] one segment's left and right padding together, and
     * [longestWords] each segment's widest word. All in the same unit.
     */
    fun mustStack(trackInner: Float, margins: Float, segmentPadding: Float, longestWords: List<Float>): Boolean {
        if (longestWords.isEmpty()) return false
        val textWidth = (trackInner - margins) / longestWords.size - segmentPadding
        return longestWords.any { it > textWidth }
    }
}
