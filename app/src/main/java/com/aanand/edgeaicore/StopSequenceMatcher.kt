package com.aanand.edgeaicore

/**
 * Finds the first of several stop sequences in streamed text. Feed it each chunk: it returns the text that is safe to
 * send (never a sequence or the start of one that might still complete) and whether a sequence matched, in which case
 * the text before the match is returned and the rest is dropped. Call [flush] when the stream ends without a match.
 */
class StopSequenceMatcher(sequences: List<String>) {
    /** [emit] is safe to send; [matched] is true when a stop sequence was found (nothing after it is returned). */
    data class Result(val emit: String, val matched: Boolean)

    private val sequences = sequences.filter { it.isNotEmpty() }
    private val maxLength = this.sequences.maxOfOrNull { it.length } ?: 0
    private val pending = StringBuilder()

    fun feed(chunk: String): Result {
        pending.append(chunk)
        var first = -1
        for (sequence in sequences) {
            val index = pending.indexOf(sequence)
            if (index >= 0 && (first < 0 || index < first)) first = index
        }
        if (first >= 0) {
            val before = pending.substring(0, first)
            pending.setLength(0)
            return Result(before, true)
        }
        // Hold back the longest suffix that could still grow into a stop sequence
        var hold = 0
        for (length in minOf(pending.length, maxLength - 1) downTo 1) {
            val suffix = pending.substring(pending.length - length)
            if (sequences.any { it.startsWith(suffix) }) {
                hold = length
                break
            }
        }
        val emit = pending.substring(0, pending.length - hold)
        pending.delete(0, emit.length)
        return Result(emit, false)
    }

    /** The held-back text, for a stream that ended without a match. */
    fun flush(): String = pending.toString().also { pending.setLength(0) }
}
