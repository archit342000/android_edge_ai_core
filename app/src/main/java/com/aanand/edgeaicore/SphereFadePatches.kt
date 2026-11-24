package com.aanand.edgeaicore

/**
 * The fade behind words on the sphere (rule book 3.3): one patch per line of words and per control, reaching a little past
 * it. Padding grows with the lettering: x = [padXFactor] and y = [padYFactor] of the line height, clamped to the given
 * ranges. Plain Kotlin; all lengths in the same unit (the caller uses pixels).
 */
class SphereFadePatches(
    private val padXFactor: Float,
    private val padYFactor: Float,
    private val padXMin: Float,
    private val padXMax: Float,
    private val padYMin: Float,
    private val padYMax: Float,
) {
    /** A rectangle: a line of words, a control, or a patch. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** One patch per non-empty [lines] entry, padded by its own height. */
    fun patches(lines: List<Box>): List<Box> = lines.filter { it.right > it.left && it.bottom > it.top }.map { line ->
        val height = line.bottom - line.top
        val padX = (height * padXFactor).coerceIn(padXMin, padXMax)
        val padY = (height * padYFactor).coerceIn(padYMin, padYMax)
        Box(line.left - padX, line.top - padY, line.right + padX, line.bottom + padY)
    }
}
