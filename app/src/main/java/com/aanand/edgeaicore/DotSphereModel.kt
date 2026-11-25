package com.aanand.edgeaicore

import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Every number the dot sphere uses, filled by `DotSphereView` from `tokens_*.xml` and `pieces.xml` (the reference page's
 * sphere script). Lengths are in dp; per-frame coefficients are per frame of [scriptFrameMs], as in the script.
 */
data class DotSphereSpec(
    val dotsPhone: Int,
    val dotsComputer: Int,
    val tabletMinWidth: Float,
    val computerMinWidth: Float,
    val sizeFactor: Float,
    val radiusPhone: Float,
    val radiusComputer: Float,
    val radiusComputerMin: Float,
    val stretchFactor: Float,
    val stretchCap: Float,
    val goldenAngle: Double,
    val perspective: Float,
    val spinPerMs: Float,
    val spinPulseGain: Float,
    val driftX: Float,
    val driftXPerMs: Float,
    val driftY: Float,
    val driftYPerMs: Float,
    val breathe: Float,
    val breathePerMs: Float,
    val wave: Float,
    val wavePerMs: Float,
    val waveSpread: Float,
    val rippleDecay: Float,
    val rippleSpeed: Float,
    val rippleReach: Float,
    val rippleWidth: Float,
    val ripplePush: Float,
    val rippleStart: Float,
    val rippleFloor: Float,
    val distanceSpread: Float,
    val dotSizeMin: Float,
    val dotSizeRange: Float,
    val dotSizeBase: Float,
    val dotSizeUser: Float,
    val alphaFloor: Float,
    val alphaOffset: Float,
    val alphaCap: Float,
    val follow: Float,
    val lean: Float,
    val leanX: Float,
    val leanY: Float,
    val ease: Float,
    val frameCapMs: Float,
    val scriptFrameMs: Float,
    val colours: Int,
)

/** One dot ready to draw: position and radius in dp, alpha 0..1 before the sphere's strength, and its colour index. */
data class ProjectedDot(val x: Float, val y: Float, val radius: Float, val alpha: Float, val colour: Int)

/**
 * The dot sphere of rule book Part 3, ported from the reference page's script: dots placed by the golden angle on a ball that
 * turns, drifts, leans toward a touch, ripples on a commit and pulses while the app works. Plain Kotlin with no Android
 * calls; `DotSphereView` draws what [project] returns. Not thread-safe: use it from the main thread.
 */
class DotSphereModel(private val spec: DotSphereSpec, seed: Long) {
    private class Dot(val ux: Float, val uy: Float, val uz: Float, val dist: Float, val colour: Int, val size: Float)

    private val random = Random(seed)
    private var dots: List<Dot> = emptyList()
    private var width = 0f
    private var height = 0f
    private var narrow = true
    private var radius = 0f
    private var stretchX = 1f
    private var stretchY = 1f

    // Double, and the angle kept within one turn: in Float a short step stops moving the sphere after a day or two.
    private var now = 0.0
    private var spin = 0.0
    private var pulse = 0f
    private var pulseTarget = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var leanXNow = 0f
    private var leanYNow = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var followX = 0f
    private var followY = 0f
    private var rippleAt = 0f
    private var rippleStrength = 0f

    /** False when the system asks for no motion: [advance] then changes nothing and touches and ripples are ignored. */
    var motionEnabled = true

    /** Number of dots for the current width. */
    val dotCount: Int get() = dots.size

    /** The sphere's base radius after the size factor, in dp. */
    val sphereRadius: Float get() = radius * spec.sizeFactor

    /** Current ripple strength (0 when none is running). */
    val rippleLevel: Float get() = rippleStrength

    /** Current centre the sphere is drawn around, before drift, in dp. */
    val centreX: Float get() = followX
    val centreY: Float get() = followY

    /**
     * Sizes the sphere for a view of [widthDp] by [heightDp]: the phone rules under the tablet width, a true ball above it,
     * and the dot count for the width class. Dots are placed again only when the count changes.
     */
    fun layout(widthDp: Float, heightDp: Float) {
        width = widthDp
        height = heightDp
        narrow = widthDp < spec.tabletMinWidth
        val shorter = min(widthDp, heightDp)
        radius = if (narrow) shorter * spec.radiusPhone else max(spec.radiusComputerMin, shorter * spec.radiusComputer)
        stretchX = if (narrow) min(spec.stretchCap, 1 + (widthDp / shorter - 1) * spec.stretchFactor) else 1f
        stretchY = if (narrow) min(spec.stretchCap, 1 + (heightDp / shorter - 1) * spec.stretchFactor) else 1f
        val count = if (widthDp < spec.computerMinWidth) spec.dotsPhone else spec.dotsComputer
        if (count != dots.size) placeDots(count)
        targetX = widthDp / 2
        targetY = heightDp / 2
        followX = targetX
        followY = targetY
    }

    private fun placeDots(count: Int) {
        dots = List(count) { i ->
            val phi = acos(1 - 2 * (i + 0.5) / count)
            val theta = i * spec.goldenAngle
            Dot(
                ux = (cos(theta) * sin(phi)).toFloat(),
                uy = (sin(theta) * sin(phi)).toFloat(),
                uz = cos(phi).toFloat(),
                dist = 1 + (random.nextFloat() - 0.5f) * spec.distanceSpread,
                colour = random.nextInt(spec.colours),
                size = spec.dotSizeMin + random.nextFloat() * spec.dotSizeRange,
            )
        }
    }

    /** Leans the sphere toward a touch at ([x], [y]) in dp. */
    fun leanToward(x: Float, y: Float) {
        if (!motionEnabled || width <= 0f) return
        mouseX = (x - width / 2) / (width / 2)
        mouseY = (y - height / 2) / (height / 2)
        targetX = x.coerceIn(0f, width)
        targetY = y.coerceIn(0f, height)
    }

    /**
     * The touch has ended: the sphere drifts back to the middle and stops leaning. The script keeps following the pointer;
     * on a phone that would park the sphere wherever the last tap was, often under the bottom bar.
     */
    fun release() {
        if (width <= 0f) return
        mouseX = 0f
        mouseY = 0f
        targetX = width / 2
        targetY = height / 2
    }

    /** Sends a ripple through the sphere, as on a commit; [strength] as in the script (touch 1.3, a finished task 0.6). */
    fun ripple(strength: Float) {
        if (!motionEnabled) return
        rippleAt = radius * spec.sizeFactor * spec.rippleStart
        rippleStrength = strength
    }

    /** Pulses slowly while the app works. */
    fun setWorking(working: Boolean) {
        pulseTarget = if (working) 1f else 0f
    }

    /** Moves time on by [dtMs] milliseconds. Does nothing while motion is off. */
    fun advance(dtMs: Float) {
        if (!motionEnabled) return
        val dt = min(spec.frameCapMs, max(0f, dtMs))
        val frames = dt / spec.scriptFrameMs
        now += dt
        leanXNow += (mouseX - leanXNow) * perFrame(spec.lean, frames)
        leanYNow += (mouseY - leanYNow) * perFrame(spec.lean, frames)
        followX += (targetX - followX) * perFrame(spec.follow, frames)
        followY += (targetY - followY) * perFrame(spec.follow, frames)
        pulse += (pulseTarget - pulse) * perFrame(spec.ease, frames)
        spin = (spin + dt * spec.spinPerMs * (1 + spec.spinPulseGain * pulse)) % TURN
        if (rippleStrength > spec.rippleFloor) {
            rippleStrength *= spec.rippleDecay.pow(frames)
            rippleAt += spec.rippleSpeed * frames
        }
        if (rippleStrength <= spec.rippleFloor) {
            rippleStrength = 0f
            rippleAt = 0f
        }
    }

    /** The script eases by a fixed share per frame; this is the same share over [frames] frames. */
    private fun perFrame(share: Float, frames: Float) = 1 - (1 - share).pow(frames)

    /** The dots in drawing order (back to front), positioned for the current time. */
    fun project(): List<ProjectedDot> {
        val r = radius * spec.sizeFactor
        if (r <= 0f) return emptyList()
        val cx = followX + sin(now * spec.driftXPerMs).toFloat() * spec.driftX
        val cy = followY + cos(now * spec.driftYPerMs).toFloat() * spec.driftY
        val rotX = -leanYNow * spec.leanY
        val rotY = (spin + leanXNow * spec.leanX).toFloat()
        val breathe = 1 + spec.breathe * pulse * sin(now * spec.breathePerMs).toFloat()
        val cosY = cos(rotY)
        val sinY = sin(rotY)
        val cosX = cos(rotX)
        val sinX = sin(rotX)
        val out = ArrayList<Pair<Float, ProjectedDot>>(dots.size)
        for (p in dots) {
            val wave = sin(now * spec.wavePerMs + (p.ux + p.uy + p.uz) * spec.waveSpread).toFloat() * spec.wave
            val rb = (p.dist * r + wave) * breathe
            var x = p.ux * rb
            var y = p.uy * rb
            var z = p.uz * rb
            if (rippleStrength > 0f) {
                val diff = abs(rb - rippleAt)
                if (diff < spec.rippleReach) {
                    val f = exp(-(diff / spec.rippleWidth).pow(2)) * rippleStrength * spec.ripplePush
                    x += p.ux * f
                    y += p.uy * f
                    z += p.uz * f
                }
            }
            val x1 = x * cosY - z * sinY
            val z1 = x * sinY + z * cosY
            val y2 = y * cosX - z1 * sinX
            val z2 = y * sinX + z1 * cosX
            val s = spec.perspective / (spec.perspective + z2)
            if (s < 0) continue
            val alpha = min(spec.alphaCap, max(spec.alphaFloor, (z2 + r) / (2 * r) + spec.alphaOffset))
            val size = p.size * s * (spec.dotSizeBase + spec.dotSizeUser * spec.sizeFactor)
            out.add(z2 to ProjectedDot(x1 * s * stretchX + cx, y2 * s * stretchY + cy, size, alpha, p.colour))
        }
        out.sortByDescending { it.first }
        return out.map { it.second }
    }

    private companion object {
        const val TURN = 2 * Math.PI
    }
}
