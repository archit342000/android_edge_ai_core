package com.aanand.edgeaicore

import kotlin.math.PI
import kotlin.math.cos

/**
 * The values of the three looping movements (rule book Part 4) at a time [t] in ms since the loop started, so `ConsoleUi` can
 * draw them on its own slow ticker instead of a full-rate animator. Plain Kotlin.
 */
object LoopFrames {
    /** led keyframe: opacity eases from 1 down to [low] at half the [period] and back. */
    fun ledAlpha(t: Long, period: Int, low: Float): Float {
        val phase = (t % period).toFloat() / period
        val eased = (1 - cos(2 * PI * phase).toFloat()) / 2
        return 1 - (1 - low) * eased
    }

    /**
     * dotBounce keyframe for a dot that starts [delay] ms late: at rest at the start, at its [peak] at the share [peakAt] of
     * the [period], at rest again from the share [restFrom]. [ease] shapes each stretch between stops, as a CSS timing
     * function does (the reference uses ease-in-out). Before its delay the dot rests.
     */
    fun dotBounce(
        t: Long, period: Int, delay: Int, rest: Float, peak: Float, peakAt: Float, restFrom: Float, ease: (Float) -> Float,
    ): Float {
        if (t < delay) return rest
        val phase = ((t - delay) % period).toFloat() / period
        return when {
            phase < peakAt -> rest + (peak - rest) * ease(phase / peakAt)
            phase < restFrom -> peak + (rest - peak) * ease((phase - peakAt) / (restFrom - peakAt))
            else -> rest
        }
    }

    /** The busy spinner's start angle in degrees: one turn per [period]. */
    fun spinnerAngle(t: Long, period: Int): Float = (t % period).toFloat() / period * FULL_TURN

    private const val FULL_TURN = 360f
}
