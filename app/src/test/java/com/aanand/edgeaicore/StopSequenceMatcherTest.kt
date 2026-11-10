package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Test

class StopSequenceMatcherTest {
    private fun run(sequences: List<String>, vararg chunks: String): Pair<String, Boolean> {
        val matcher = StopSequenceMatcher(sequences)
        val out = StringBuilder()
        for (chunk in chunks) {
            val r = matcher.feed(chunk)
            out.append(r.emit)
            if (r.matched) return out.toString() to true
        }
        out.append(matcher.flush())
        return out.toString() to false
    }

    @Test fun aMatchInsideOneChunkEndsTheTextBeforeIt() = assertEquals("one two " to true, run(listOf("END"), "one two ENDmore"))

    @Test fun aMatchSplitAcrossChunksIsFound() = assertEquals("hello " to true, run(listOf("STOP"), "hello ST", "O", "P tail"))

    @Test fun aHeldBackPrefixThatDoesNotMatchIsReleased() = assertEquals("a STOx b" to false, run(listOf("STOP"), "a STO", "x b"))

    @Test fun textEndingInAPrefixKeepsItsTailAtTheEnd() = assertEquals("done ST" to false, run(listOf("STOP"), "done ST"))

    @Test fun aMatchAtTheStartGivesEmptyOutput() = assertEquals("" to true, run(listOf("STOP"), "STOP now"))

    @Test fun theEarliestMatchWinsAmongSeveralSequences() = assertEquals("x " to true, run(listOf("bc", "ab"), "x abc"))

    @Test fun overlappingSequencesStopAtTheFirstCompleteOne() = assertEquals("" to true, run(listOf("ab", "bc"), "abc"))

    @Test fun noMatchPassesEverythingThrough() = assertEquals("plain text" to false, run(listOf("zzz"), "plain ", "text"))

    @Test fun safeTextIsEmittedWhileAPrefixIsHeld() {
        val matcher = StopSequenceMatcher(listOf("STOP"))
        assertEquals("abc", matcher.feed("abcST").emit)
        assertEquals("", matcher.feed("O").emit)
    }
}
