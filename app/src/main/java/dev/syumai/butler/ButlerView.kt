package dev.syumai.butler

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.ImageView
import kotlin.math.sin

/** Shared vector artwork; only the small view's transform changes, at 25 fps. */
class ButlerView(context: Context) : ImageView(context) {
    var motionEnabled = true
    var active = false
    var conversing = false
    private var phase = 0f
    private val frame = object : Runnable {
        override fun run() {
            if (!active || !motionEnabled || !isAttachedToWindow || !isShown ||
                windowVisibility != View.VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
                resetPose()
                return
            }
            phase += if (conversing) 0.12f else 0.045f
            translationY = sin(phase) * resources.displayMetrics.density * 3f
            rotation = sin(phase * 0.7f) * if (conversing) 3f else 1.2f
            postDelayed(this, 40)
        }
    }
    init {
        setImageResource(R.drawable.butler_character)
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun refreshMotion() {
        removeCallbacks(frame)
        resetPose()
        if (active && motionEnabled && isAttachedToWindow) post(frame)
    }
    private fun resetPose() { translationY = 0f; rotation = 0f }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshMotion() }
    override fun onDetachedFromWindow() { removeCallbacks(frame); resetPose(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // A View constructor can call this before field initialization.
        if (isAttachedToWindow) refreshMotion()
    }
}
