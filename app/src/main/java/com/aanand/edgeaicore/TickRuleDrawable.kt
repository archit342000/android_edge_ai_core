package com.aanand.edgeaicore

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser

/**
 * The tick rule (rule book 1.4, reference page `.ticks`): thin marks at an even spacing, like the markings on an instrument.
 * Inflated from `res/drawable/tick_rule.xml`; every number comes from `pieces.xml` and the colour from the `tick` token.
 */
class TickRuleDrawable : Drawable() {
    private val paint = Paint()
    private var tickWidth = 0f
    private var spacing = 0f
    private var height = 0

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        paint.color = r.getColor(R.color.console_tick, theme)
        tickWidth = r.getDimension(R.dimen.tick_width)
        spacing = r.getDimension(R.dimen.tick_spacing)
        height = r.getDimensionPixelSize(R.dimen.tick_rule_height)
    }

    override fun draw(canvas: Canvas) {
        if (spacing <= 0f) return
        val b = bounds
        var x = b.left.toFloat()
        while (x < b.right) {
            canvas.drawRect(x, b.top.toFloat(), x + tickWidth, b.bottom.toFloat(), paint)
            x += spacing
        }
    }

    override fun getIntrinsicHeight(): Int = height

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
