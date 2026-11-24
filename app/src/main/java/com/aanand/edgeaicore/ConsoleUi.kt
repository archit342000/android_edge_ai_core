package com.aanand.edgeaicore

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * The Console at Rest pieces `MainActivity` shows from code: the brief message, the confirming sheet, the explain card, the
 * busy spinner on buttons and the working movements (pulsing status light, working dots). With the system's animations
 * off, looping movements draw their first frame and stay still. Main thread only.
 */
class ConsoleUi(private val context: Context, private val briefHost: ViewGroup) {
    private val res = context.resources
    private var brief: TextView? = null
    private val hideBrief = Runnable { brief?.let { briefHost.removeView(it) }; brief = null }
    /** Each looping movement: its frame for a time in ms since it started, and when it started. */
    private class Loop(val start: Long, val frame: (Long) -> Unit)
    private val loops = LinkedHashMap<Any, Loop>()
    private val loopIntervalMs = res.getInteger(R.integer.sphere_frame_interval_ms)
    private var lastTickMs = 0L
    private var paused = false
    private var sheet: BottomSheetDialog? = null
    private val motionCheckMs = res.getInteger(R.integer.motion_setting_check_ms).toLong()
    /** While still because of the system setting, looks again now and then: it can be turned back on while showing. */
    private val motionCheck = object : Runnable {
        override fun run() {
            if (paused) return
            if (ValueAnimator.areAnimatorsEnabled()) startTicker() else briefHost.postDelayed(this, motionCheckMs)
        }
    }

    // The working dots' dotBounce numbers (pieces.xml, .dots i), read once.
    private val dotPeriod = res.getInteger(R.integer.working_dots_loop_ms)
    private val dotDelay = res.getInteger(R.integer.working_dot_delay_ms)
    private val dotRestAlpha = ResourcesCompat.getFloat(res, R.dimen.working_dot_alpha_rest)
    private val dotRestScale = ResourcesCompat.getFloat(res, R.dimen.working_dot_scale_rest)
    private val dotPeakScale = ResourcesCompat.getFloat(res, R.dimen.working_dot_scale_peak)
    private val dotPeakAt = ResourcesCompat.getFloat(res, R.dimen.working_dot_peak_at)
    private val dotRestFrom = ResourcesCompat.getFloat(res, R.dimen.working_dot_rest_from)
    private val dotEase = PathInterpolator(
        ResourcesCompat.getFloat(res, R.dimen.working_dot_ease_x1), ResourcesCompat.getFloat(res, R.dimen.working_dot_ease_y1),
        ResourcesCompat.getFloat(res, R.dimen.working_dot_ease_x2), ResourcesCompat.getFloat(res, R.dimen.working_dot_ease_y2),
    )::getInterpolation

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (paused || loops.isEmpty()) return
            if (!ValueAnimator.areAnimatorsEnabled()) {
                startTicker()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastTickMs >= loopIntervalMs) {
                lastTickMs = now
                loops.values.toList().forEach { it.frame(now - it.start) }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Shows [text] at the bottom centre for 2.6 s (rule book 7.11), replacing any brief message already showing. */
    fun brief(text: CharSequence) {
        brief?.let {
            it.removeCallbacks(hideBrief)
            briefHost.removeView(it)
        }
        val view = TextView(context, null, 0, R.style.Widget_Console_Brief).apply {
            this.text = text
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = res.getDimensionPixelSize(R.dimen.brief_bottom_offset)
        }
        briefHost.addView(view, params)
        brief = view
        if (ValueAnimator.areAnimatorsEnabled()) {
            AnimatorInflater.loadAnimator(context, R.animator.pop_over_in).apply { setTarget(view) }.start()
        }
        view.postDelayed(hideBrief, res.getInteger(R.integer.brief_duration_ms).toLong())
    }

    /**
     * A sheet rising from the bottom that confirms something that cannot be undone (rule book 7.11): [title] is the question,
     * then Cancel and the red [action]. Back or Cancel closes it without acting.
     */
    fun confirm(title: CharSequence, body: CharSequence, action: CharSequence, onConfirm: () -> Unit) {
        val dialog = BottomSheetDialog(context)
        val sheet = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_confirm, briefHost, false)
        sheet.findViewById<TextView>(R.id.tv_sheet_title).text = title
        sheet.findViewById<TextView>(R.id.tv_sheet_body).text = body
        sheet.findViewById<MaterialButton>(R.id.btn_sheet_cancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<MaterialButton>(R.id.btn_sheet_confirm).apply {
            text = action
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        dialog.setContentView(sheet)
        dialog.setOnDismissListener { if (this.sheet === dialog) this.sheet = null }
        this.sheet?.dismiss()
        this.sheet = dialog
        dialog.show()
    }

    /** Closes an open confirming sheet without acting, so it does not outlive its activity (as when the screen turns). */
    fun dismissSheet() {
        sheet?.dismiss()
        sheet = null
    }

    /** Fills an explain card ([card] is an included `view_explain_card`) and wires its two equal buttons. */
    fun bindExplainCard(
        card: View, title: CharSequence, body: CharSequence, allow: CharSequence, buttonsEnabled: Boolean,
        onAllow: () -> Unit, onNotNow: () -> Unit,
    ) {
        val arriving = card.visibility != View.VISIBLE
        card.visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.tv_explain_title).text = title
        card.findViewById<TextView>(R.id.tv_explain_body).text = body
        card.findViewById<MaterialButton>(R.id.btn_explain_allow).apply {
            text = allow
            isEnabled = buttonsEnabled
            setOnClickListener { onAllow() }
        }
        card.findViewById<MaterialButton>(R.id.btn_explain_not_now).apply {
            isEnabled = buttonsEnabled
            setOnClickListener { onNotNow() }
        }
        if (arriving) arrive(card)
    }

    /** msgIn: a card or notice arriving rises and fades in; nothing moves when animations are off. */
    fun arrive(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        AnimatorInflater.loadAnimator(context, R.animator.msg_in).apply { setTarget(view) }.start()
    }

    /** Shows or clears the busy state of [button]: a small spinner before [busyLabel], or its own [label]. */
    fun setBusy(button: MaterialButton, busy: Boolean, label: CharSequence, busyLabel: CharSequence) {
        if (busy) {
            if (button.icon !is SpinnerDrawable) {
                val spinner = SpinnerDrawable(button.currentTextColor)
                button.icon = spinner
                val period = res.getInteger(R.integer.spinner_loop_ms)
                addLoop(button) { t ->
                    spinner.angle = LoopFrames.spinnerAngle(t, period)
                    spinner.invalidateSelf()
                }
            }
            button.text = busyLabel
        } else {
            stopLoop(button)
            button.icon = null
            button.text = label
        }
    }

    /** The busy status light (led keyframe): a soft pulse, 1 s loop, while [working]. */
    fun setLightPulsing(light: View, working: Boolean) {
        if (!working) {
            stopLoop(light)
            return
        }
        if (light in loops) return
        val period = res.getInteger(R.integer.status_light_pulse_ms)
        val low = ResourcesCompat.getFloat(res, R.dimen.status_light_pulse_alpha)
        addLoop(light) { t -> light.alpha = LoopFrames.ledAlpha(t, period, low) }
    }

    /** The three working dots (dotBounce): each swells in turn, 1.4 s loop. Hidden when not [working]. */
    fun setDotsWorking(dots: ViewGroup, working: Boolean) {
        dots.visibility = if (working) View.VISIBLE else View.GONE
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            if (!working) {
                stopLoop(dot)
                continue
            }
            if (dot in loops) continue
            val delay = i * dotDelay
            addLoop(dot) { t ->
                dot.alpha = LoopFrames.dotBounce(t, dotPeriod, delay, dotRestAlpha, 1f, dotPeakAt, dotRestFrom, dotEase)
                val scale = LoopFrames.dotBounce(t, dotPeriod, delay, dotRestScale, dotPeakScale, dotPeakAt, dotRestFrom, dotEase)
                dot.scaleX = scale
                dot.scaleY = scale
            }
        }
    }

    /**
     * Side-by-side choices (rule book 7.3) stack one above another when a label's longest word does not fit its share of the
     * [track], as with large text on a phone, so no word breaks in the middle. Checked again whenever the track's width changes.
     */
    fun fitSegments(track: LinearLayout) {
        track.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) track.post { stackIfCramped(track) }
        }
    }

    private fun stackIfCramped(track: LinearLayout) {
        val segments = (0 until track.childCount).map { track.getChildAt(it) }.filterIsInstance<TextView>()
        if (segments.isEmpty()) return
        val margins = segments.sumOf { segment ->
            (segment.layoutParams as LinearLayout.LayoutParams).let { it.marginStart + it.marginEnd }
        }
        val cramped = SegmentFit.mustStack(
            trackInner = (track.width - track.paddingLeft - track.paddingRight).toFloat(),
            margins = margins.toFloat(),
            segmentPadding = segments.maxOf { it.paddingLeft + it.paddingRight }.toFloat(),
            longestWords = segments.map { segment -> segment.text.split(' ').maxOf { segment.paint.measureText(it) } },
        )
        val orientation = if (cramped) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (track.orientation == orientation) return
        track.orientation = orientation
        for (segment in segments) {
            segment.layoutParams = (segment.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (cramped) ViewGroup.LayoutParams.MATCH_PARENT else 0
                weight = if (cramped) 0f else 1f
            }
        }
    }

    /** Stops the looping movements while the activity is not showing. */
    fun pause() {
        paused = true
        Choreographer.getInstance().removeFrameCallback(ticker)
        briefHost.removeCallbacks(motionCheck)
    }

    /** Resumes the looping movements; they stay still when the system turns animations off. */
    fun resume() {
        paused = false
        startTicker()
    }

    /**
     * Runs [frame] with the time since it was added, on one shared ticker at the sphere's resting rate: the movements are
     * slow, and drawing them at the display's full rate took a CPU core from the model on the test emulator. With animations
     * off the first frame is drawn and nothing moves.
     */
    private fun addLoop(key: Any, frame: (Long) -> Unit) {
        val start = SystemClock.uptimeMillis()
        loops[key] = Loop(start, frame)
        frame(0)
        startTicker()
    }

    /** Runs the ticker; with the system's animations off, every movement goes to its first frame and stays still. */
    private fun startTicker() {
        briefHost.removeCallbacks(motionCheck)
        if (paused || loops.isEmpty()) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            Choreographer.getInstance().removeFrameCallback(ticker)
            loops.values.toList().forEach { it.frame(0) }
            briefHost.postDelayed(motionCheck, motionCheckMs)
            return
        }
        Choreographer.getInstance().removeFrameCallback(ticker)
        Choreographer.getInstance().postFrameCallback(ticker)
    }

    private fun stopLoop(key: Any) {
        loops.remove(key) ?: return
        if (key is View && key !is MaterialButton) {
            key.alpha = 1f
            key.scaleX = 1f
            key.scaleY = 1f
        }
    }

    /** The busy spinner (.spin): a 14 ring with a 2 stroke and a quarter open, turned by the ticker. */
    private inner class SpinnerDrawable(colour: Int) : Drawable() {
        private val size = res.getDimensionPixelSize(R.dimen.spinner_size)
        private val stroke = res.getDimension(R.dimen.spinner_stroke)
        private val sweep = ResourcesCompat.getFloat(res, R.dimen.spinner_sweep)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()
        var angle = 0f

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = stroke / 2
            oval.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
            canvas.drawArc(oval, angle, sweep, false, paint)
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
