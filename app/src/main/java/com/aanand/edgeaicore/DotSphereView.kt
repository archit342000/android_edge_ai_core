package com.aanand.edgeaicore

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withScale
import java.util.Random

/**
 * The background of every screen: the dot sphere (rule book Part 3) and, at night, the still stars. Draws what
 * [DotSphereModel] projects, at open or reading strength, and hands the same dots to the [SphereFrostView]s that frost the
 * floating pieces. The frame loop runs only between [start] and [stop] (the activity's resume and pause); when the system
 * turns animations off it draws one still frame.
 */
class DotSphereView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs),
    Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private val model = DotSphereModel(specFrom(resources), SEED)
    private val palette = DOT_COLOURS.map { ContextCompat.getColor(context, it) }.toIntArray()
    private val starColour = ContextCompat.getColor(context, R.color.console_fg)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frosts = mutableListOf<SphereFrostView>()
    private val showStars = resources.getBoolean(R.bool.sphere_stars)
    private val strengthOpen = ResourcesCompat.getFloat(resources, R.dimen.sphere_strength_open)
    private val strengthRead = ResourcesCompat.getFloat(resources, R.dimen.sphere_strength_read)
    private val phoneFactor = ResourcesCompat.getFloat(resources, R.dimen.sphere_phone_factor)
    private val starReadingDim = ResourcesCompat.getFloat(resources, R.dimen.sphere_star_reading_dim)
    private val tabletMinWidth = resources.getInteger(R.integer.breakpoint_tablet)
    private val generatingIntervalMs = resources.getInteger(R.integer.sphere_frame_interval_ms)
    private val activeIntervalMs = resources.getInteger(R.integer.sphere_frame_interval_active_ms)
    private val activeAfterTouchMs = resources.getInteger(R.integer.sphere_active_after_touch_ms)
    private var lastTouchMs = 0L

    private val location = IntArray(2)
    private var dots: List<ProjectedDot> = emptyList()
    private var stars = FloatArray(0)
    private var running = false
    /** Between start() and stop(): the activity is showing, so the sphere moves whenever the system allows it. */
    private var wanted = false
    private val motionCheckMs = resources.getInteger(R.integer.motion_setting_check_ms).toLong()
    /** While still because animations are off, looks again now and then: they can be turned back on while showing. */
    private val motionCheck = object : Runnable {
        override fun run() {
            if (!wanted) return
            if (ValueAnimator.areAnimatorsEnabled()) start() else postDelayed(this, motionCheckMs)
        }
    }
    private var lastFrameNanos = 0L
    private var open = true
    private var strength = strengthOpen
    private var strengthAnimator: ValueAnimator? = null

    /** The sphere's current strength (0..1), including the phone factor. */
    val currentStrength: Float get() = strength * if (isPhone()) phoneFactor else 1f

    /** Open screens show the sphere at full strength; reading screens dim it (rule book 3.2). */
    fun setOpen(isOpen: Boolean) {
        if (open == isOpen) return
        open = isOpen
        val target = if (isOpen) strengthOpen else strengthRead
        strengthAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            strength = target
            invalidateAll()
            return
        }
        val animator = ValueAnimator.ofFloat(strength, target)
        animator.duration = resources.getInteger(R.integer.sphere_strength_change_ms).toLong()
        animator.addUpdateListener {
            strength = it.animatedValue as Float
            if (!running) invalidateAll()
        }
        strengthAnimator = animator
        animator.start()
    }

    /**
     * Starts the frame loop, or draws one still frame when animations are off; while still it looks at the setting again every
     * `motion_setting_check_ms` and starts moving by itself when animations come back. Call from `onResume`.
     */
    fun start() {
        wanted = true
        removeCallbacks(motionCheck)
        model.motionEnabled = ValueAnimator.areAnimatorsEnabled()
        Choreographer.getInstance().removeFrameCallback(this)
        if (!model.motionEnabled) {
            running = false
            strengthAnimator?.end()
            dots = model.project()
            invalidateAll()
            postDelayed(motionCheck, motionCheckMs)
            return
        }
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Stops the frame loop. Call from `onPause`. */
    fun stop() {
        wanted = false
        removeCallbacks(motionCheck)
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** A ripple through the sphere for a commit (server switched on, token generated). */
    fun ripple() = model.ripple(ResourcesCompat.getFloat(resources, R.dimen.sphere_ripple_commit))

    /** Pulses while the app works (a model loading, a test running). */
    fun setWorking(working: Boolean) = model.setWorking(working)

    /** The touch ended: the smoother frame rate runs on while the sphere settles where it was touched. */
    fun touchEnded() {
        lastTouchMs = SystemClock.uptimeMillis()
    }

    /** Leans toward a touch at window coordinates ([x], [y]) in pixels. Ripples are kept for commits (rule book 3.1). */
    fun leanToward(x: Float, y: Float) {
        getLocationInWindow(location)
        model.leanToward((x - location[0]) / density, (y - location[1]) / density)
        lastTouchMs = SystemClock.uptimeMillis()
    }

    internal fun addFrost(frost: SphereFrostView) {
        if (frost !in frosts) frosts.add(frost)
    }

    internal fun removeFrost(frost: SphereFrostView) {
        frosts.remove(frost)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Animations can be turned off while the activity stays showing (quick settings): draw the still frame and stop.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            start()
            return
        }
        // Every frame, as in the script, except while a reply is generated: then the model gets the CPU and the sphere
        // draws slowly, with a smoother rate for a moment after a touch or while a ripple runs.
        val touched = model.rippleLevel > 0f || SystemClock.uptimeMillis() - lastTouchMs < activeAfterTouchMs
        val interval = GenerationTracker.sphereFrameIntervalMs(GenerationTracker.active, touched, generatingIntervalMs, activeIntervalMs)
        if (interval > 0 && lastFrameNanos != 0L && (frameTimeNanos - lastFrameNanos) / NANOS_PER_MS < interval) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }
        val dtMs = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / NANOS_PER_MS
        lastFrameNanos = frameTimeNanos
        model.advance(dtMs)
        dots = model.project()
        invalidateAll()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        model.layout(w / density, h / density)
        dots = model.project()
        if (showStars) placeStars(w / density, h / density)
    }

    override fun onDetachedFromWindow() {
        stop()
        strengthAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withScale(density, density) {
            if (showStars) drawStars(this)
            drawDots(this, currentStrength)
        }
    }

    /** Draws the current dots at [alphaScale] onto [canvas], whose units are dp relative to this view. */
    internal fun drawDots(canvas: Canvas, alphaScale: Float) {
        for (d in dots) {
            paint.color = palette[d.colour]
            paint.alpha = (d.alpha * alphaScale * FULL_ALPHA).toInt().coerceIn(0, FULL_ALPHA)
            canvas.drawCircle(d.x, d.y, d.radius, paint)
        }
    }

    private fun drawStars(canvas: Canvas) {
        val dim = if (open) 1f else starReadingDim
        paint.color = starColour
        for (i in stars.indices step STAR_FIELDS) {
            paint.alpha = (stars[i + 3] * dim * FULL_ALPHA).toInt()
            canvas.drawCircle(stars[i], stars[i + 1], stars[i + 2], paint)
        }
    }

    /** Still stars, the same every time for a given size (the script's fixed seed). */
    private fun placeStars(width: Float, height: Float) {
        val r = resources
        val random = Random(r.getInteger(R.integer.sphere_star_seed).toLong())
        val count = (width * height / ResourcesCompat.getFloat(r, R.dimen.sphere_star_area)).toInt()
        val radiusMin = ResourcesCompat.getFloat(r, R.dimen.sphere_star_radius_min)
        val radiusRange = ResourcesCompat.getFloat(r, R.dimen.sphere_star_radius_range)
        val alphaMin = ResourcesCompat.getFloat(r, R.dimen.sphere_star_alpha_min)
        val alphaRange = ResourcesCompat.getFloat(r, R.dimen.sphere_star_alpha_range)
        stars = FloatArray(count * STAR_FIELDS)
        for (i in 0 until count) {
            stars[i * STAR_FIELDS] = random.nextFloat() * width
            stars[i * STAR_FIELDS + 1] = random.nextFloat() * height
            stars[i * STAR_FIELDS + 2] = radiusMin + random.nextFloat() * radiusRange
            stars[i * STAR_FIELDS + 3] = alphaMin + random.nextFloat() * alphaRange
        }
    }

    private fun isPhone() = width / density < tabletMinWidth

    private fun invalidateAll() {
        invalidate()
        frosts.forEach { it.invalidate() }
    }

    companion object {
        private const val SEED = 20261110L
        private const val NANOS_PER_MS = 1_000_000f
        private const val FULL_ALPHA = 255
        private const val STAR_FIELDS = 4
        private val DOT_COLOURS = intArrayOf(
            R.color.console_dot_1, R.color.console_dot_2, R.color.console_dot_3, R.color.console_dot_4, R.color.console_dot_5,
        )

        private fun specFrom(r: Resources): DotSphereSpec {
            fun f(id: Int) = ResourcesCompat.getFloat(r, id)
            val density = r.displayMetrics.density
            return DotSphereSpec(
                dotsPhone = r.getInteger(R.integer.sphere_dots_phone),
                dotsComputer = r.getInteger(R.integer.sphere_dots_computer),
                tabletMinWidth = r.getInteger(R.integer.breakpoint_tablet).toFloat(),
                computerMinWidth = r.getInteger(R.integer.breakpoint_computer).toFloat(),
                sizeFactor = f(R.dimen.sphere_size_factor),
                radiusPhone = f(R.dimen.sphere_radius_phone),
                radiusComputer = f(R.dimen.sphere_radius_computer),
                radiusComputerMin = r.getDimension(R.dimen.sphere_radius_computer_min) / density,
                stretchFactor = f(R.dimen.sphere_stretch_factor),
                stretchCap = f(R.dimen.sphere_stretch_cap),
                goldenAngle = f(R.dimen.sphere_golden_angle).toDouble(),
                perspective = f(R.dimen.sphere_perspective),
                spinPerMs = f(R.dimen.sphere_spin_per_ms),
                spinPulseGain = f(R.dimen.sphere_spin_pulse_gain),
                driftX = f(R.dimen.sphere_drift_x),
                driftXPerMs = f(R.dimen.sphere_drift_x_per_ms),
                driftY = f(R.dimen.sphere_drift_y),
                driftYPerMs = f(R.dimen.sphere_drift_y_per_ms),
                breathe = f(R.dimen.sphere_breathe),
                breathePerMs = f(R.dimen.sphere_breathe_per_ms),
                wave = f(R.dimen.sphere_wave),
                wavePerMs = f(R.dimen.sphere_wave_per_ms),
                waveSpread = f(R.dimen.sphere_wave_spread),
                rippleDecay = f(R.dimen.sphere_ripple_decay),
                rippleSpeed = f(R.dimen.sphere_ripple_speed),
                rippleReach = f(R.dimen.sphere_ripple_reach),
                rippleWidth = f(R.dimen.sphere_ripple_width),
                ripplePush = f(R.dimen.sphere_ripple_push),
                rippleStart = f(R.dimen.sphere_ripple_start),
                rippleFloor = f(R.dimen.sphere_ripple_floor),
                distanceSpread = f(R.dimen.sphere_dot_distance_spread),
                dotSizeMin = f(R.dimen.sphere_dot_size_min),
                dotSizeRange = f(R.dimen.sphere_dot_size_range),
                dotSizeBase = f(R.dimen.sphere_dot_size_base),
                dotSizeUser = f(R.dimen.sphere_dot_size_user),
                alphaFloor = f(R.dimen.sphere_alpha_floor),
                alphaOffset = f(R.dimen.sphere_alpha_offset),
                alphaCap = f(R.dimen.sphere_alpha_cap),
                follow = f(R.dimen.sphere_follow),
                lean = f(R.dimen.sphere_lean),
                leanX = f(R.dimen.sphere_lean_x),
                leanY = f(R.dimen.sphere_lean_y),
                ease = f(R.dimen.sphere_ease),
                frameCapMs = f(R.dimen.sphere_frame_cap_ms),
                scriptFrameMs = f(R.dimen.sphere_script_frame_ms),
                colours = DOT_COLOURS.size,
            )
        }
    }
}
