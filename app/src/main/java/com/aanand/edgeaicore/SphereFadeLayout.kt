package com.aanand.edgeaicore

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * A short block of words that sits straight on the sphere (rule book 3.3). Behind each line of text and each control it draws
 * a patch of the page colour, blurred and at the fade strength, so dots dim as they pass behind the words. Patches follow the
 * words: they are measured again on every layout. Drawn on a software layer so the blur works on API 26.
 */
class SphereFadeLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val r = resources
    private val fade = SphereFadePatches(
        padXFactor = ResourcesCompat.getFloat(r, R.dimen.sphere_fade_pad_x_factor),
        padYFactor = ResourcesCompat.getFloat(r, R.dimen.sphere_fade_pad_y_factor),
        padXMin = r.getDimension(R.dimen.sphere_fade_pad_x_min),
        padXMax = r.getDimension(R.dimen.sphere_fade_pad_x_max),
        padYMin = r.getDimension(R.dimen.sphere_fade_pad_y_min),
        padYMax = r.getDimension(R.dimen.sphere_fade_pad_y_max),
    )
    private val patchRadius = r.getDimension(R.dimen.sphere_fade_patch_radius)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.console_bg)
        alpha = (ResourcesCompat.getFloat(r, R.dimen.sphere_fade_strength) * FULL_ALPHA).toInt()
        maskFilter = BlurMaskFilter(r.getDimension(R.dimen.sphere_fade_blur), BlurMaskFilter.Blur.NORMAL)
    }
    private var patches: List<SphereFadePatches.Box> = emptyList()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // The patches reach past the children; keep them and the children's focus rings visible.
        clipChildren = false
        clipToPadding = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, right: Int, b: Int) {
        super.onLayout(changed, l, t, right, b)
        val lines = ArrayList<SphereFadePatches.Box>()
        for (i in 0 until childCount) collect(getChildAt(i), 0f, 0f, lines)
        patches = fade.patches(lines)
        invalidate()
    }

    /** Collects each line of text, and the bounds of each other visible view, in this layout's coordinates. */
    private fun collect(view: View, offsetX: Float, offsetY: Float, out: MutableList<SphereFadePatches.Box>) {
        if (view.visibility != View.VISIBLE) return
        val x = offsetX + view.left
        val y = offsetY + view.top
        when {
            view is TextView && view.layout != null && view.background == null -> {
                val layout = view.layout
                val left = x + view.totalPaddingLeft
                val top = y + view.totalPaddingTop
                for (line in 0 until layout.lineCount) {
                    out.add(
                        SphereFadePatches.Box(
                            left + layout.getLineLeft(line), top + layout.getLineTop(line),
                            left + layout.getLineRight(line), top + layout.getLineBottom(line),
                        )
                    )
                }
            }
            view is ViewGroup && view.background == null -> for (i in 0 until view.childCount) collect(view.getChildAt(i), x, y, out)
            else -> out.add(SphereFadePatches.Box(x, y, x + view.width, y + view.height))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in patches) canvas.drawRoundRect(p.left, p.top, p.right, p.bottom, patchRadius, patchRadius, paint)
    }

    companion object {
        private const val FULL_ALPHA = 255
    }
}
