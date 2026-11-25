package com.aanand.edgeaicore

import androidx.core.graphics.withTranslation
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Frosted glass behind one floating piece (rule book 2.1), on API 31 and later: a blurred, more saturated copy of the
 * [DotSphereView]'s current dots, on the page colour, clipped to the piece's shape. The piece's own translucent colour is drawn
 * on top by the piece. [attach] links it to the sphere; the sphere redraws it on every frame. Below API 31, or in solid mode,
 * the activity does not attach it and the piece uses its solid colour.
 */
class SphereFrostView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val pxPerDp = resources.displayMetrics.density
    private val pageColour = ContextCompat.getColor(context, R.color.console_bg)
    private var sphere: DotSphereView? = null
    private var radii = FloatArray(CORNERS * 2)
    private val path = Path()
    private val ownLocation = IntArray(2)
    private val sphereLocation = IntArray(2)

    /**
     * Frosts this view with a copy of [sphere], blurred by [blurRadius] (pixels), and clips it to corners [radii] (pixels,
     * top left, top right, bottom right, bottom left; empty for square).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun attach(sphere: DotSphereView, blurRadius: Float, radii: FloatArray) {
        this.sphere = sphere
        this.radii = FloatArray(CORNERS * 2) { radii.getOrElse(it / 2) { 0f } }
        sphere.addFrost(this)
        val saturate = ColorMatrix().apply { setSaturation(ResourcesCompat.getFloat(resources, R.dimen.glass_saturate)) }
        setRenderEffect(
            RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
                RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP),
            )
        )
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                path.reset()
                path.addRoundRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), this@SphereFrostView.radii, Path.Direction.CW)
                outline.setPath(path)
            }
        }
        clipToOutline = true
        invalidateOutline()
        invalidate()
    }

    /** Stops frosting; the view draws nothing. */
    fun detach() {
        sphere?.removeFrost(this)
        sphere = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        invalidate()
    }

    /** Takes no room of its own: it fills the piece it sits behind once that piece's size is known. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    override fun onDetachedFromWindow() {
        sphere?.removeFrost(this)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sphere?.addFrost(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = sphere ?: return
        canvas.drawColor(pageColour)
        getLocationInWindow(ownLocation)
        s.getLocationInWindow(sphereLocation)
        canvas.withTranslation((sphereLocation[0] - ownLocation[0]).toFloat(), (sphereLocation[1] - ownLocation[1]).toFloat()) {
            scale(pxPerDp, pxPerDp)
            s.drawDots(this, s.currentStrength)
        }
    }

    companion object {
        private const val CORNERS = 4
    }
}
