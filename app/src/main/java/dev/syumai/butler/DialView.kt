package dev.syumai.butler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The climate control's round dial (design spec §6.1): a 270° arc track plus a progress arc
 * showing the target temperature within its min/max range, with a big number and a caption
 * centered on top. Pure display — [DeviceSheet] drives it via [render] and reads no state back.
 */
class DialView(context: Context) : FrameLayout(context) {
    private val arc = Arc(context)
    private val bigText = context.text(84f, "", Palette.CREAM).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }
    private val captionView = context.text(13f, "", Palette.CREAM_60).apply { gravity = Gravity.CENTER }

    init {
        val size = context.dp(SIZE_DP)
        layoutParams = ViewGroup.LayoutParams(size, size)
        addView(arc, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(bigText)
        column.addView(captionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // A caller (DeviceSheet) adds this view to a LinearLayout row with WRAP_CONTENT layout params, which
    // replace the ones set above. Without an onMeasure override, that leaves the `arc` child's own
    // MATCH_PARENT x MATCH_PARENT measured against the *parent row's* available (often much larger, or
    // 0) width — Android's default View.onMeasure for an AT_MOST spec just returns the full available
    // size, not a natural size — so the dial would balloon to fill the row's width instead of staying a
    // 210dp square (this was the s_climate.png bug: an oversized, near-invisible arc with everything
    // beside it pushed off-screen). Forcing an EXACTLY spec here makes the square size the caller's
    // layout params ask for irrelevant; it's always exactly SIZE_DP regardless.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(context.dp(SIZE_DP), View.MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }

    /** Redraws the dial for [value] within [min]..[max] (clamped), tinting the progress arc [warm]
     * (heat) or cool, and setting the big number / caption text. */
    fun render(value: Double, min: Double, max: Double, warm: Boolean, valueText: String, caption: String) {
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        arc.fraction = (((value - min) / span).toFloat()).coerceIn(0f, 1f)
        arc.color = if (warm) Palette.BRASS else Palette.RAIN
        arc.invalidate()
        bigText.text = valueText
        captionView.text = caption
    }

    private class Arc(context: Context) : View(context) {
        var fraction = 0f
        var color = Palette.RAIN
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = context.dp(10).toFloat(); strokeCap = Paint.Cap.ROUND; color = Palette.CREAM_12
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = context.dp(10).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val inset = trackPaint.strokeWidth / 2f + 1f
            oval.set(inset, inset, w - inset, h - inset)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)
            if (fraction > 0f) {
                progressPaint.color = color
                canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE * fraction, false, progressPaint)
            }
        }
    }

    private companion object {
        const val SIZE_DP = 210
        const val START_ANGLE = 135f
        const val SWEEP_ANGLE = 270f
    }
}
