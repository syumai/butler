package dev.syumai.butler

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minimal horizontal pager for the home screen's four full-screen pages (clock/weather/smart
 * home/music). Reimplements just the slice of the old `androidx.viewpager.ViewPager` this app
 * needs — no AndroidX is used anywhere in this project — the same way: children are laid out
 * side by side at `i * width`, and the visible page is just which part of that is scrolled into
 * view (`View.scrollTo`), not a real per-page offset computed in `onDraw`.
 *
 * Unlike a real fling, releasing past [MIN_FLING_MULTIPLIER]x the platform's minimum fling
 * velocity does *not* hand the resulting distance to `OverScroller.fling()` — with only a
 * handful of fixed-width pages and no continuous content, a physics-based fling can coast past
 * more than one page or stop short of a boundary. Every settle (fling or plain release) instead
 * animates straight to the chosen page's exact `scrollX` with `startScroll()`, just using a
 * shorter duration when the release was fast, so pages always end up pixel-aligned. Touch state
 * ([VelocityTracker] aside, which Android pools internally between `obtain()`/`recycle()`) is all
 * pre-allocated in the constructor, not per touch event.
 */
class PagerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    /** Read-only from outside; use [setPage] to change it. */
    var currentPage: Int = 0
        private set
    /** Invoked as soon as a drag/fling decides its destination page (immediately, not only once the settle animation finishes). */
    var onPageChanged: ((Int) -> Unit)? = null
    /** While true, all touch handling is ignored — used while a device sheet or the conversation overlay is up. */
    var locked: Boolean = false

    private val scroller = OverScroller(context)
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val minFlingVelocity = config.scaledMinimumFlingVelocity
    private val maxFlingVelocity = config.scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragStartScrollX = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childWidthSpec, childHeightSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l; val height = b - t
        for (i in 0 until childCount) { val child = getChildAt(i); child.layout(i * width, 0, i * width + width, height) }
    }

    // Re-snap to the current page whenever this view's own size changes (rotation, or the very
    // first layout pass where width goes from 0 to its real value).
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) scrollTo(currentPage * w, 0)
    }

    fun setPage(index: Int, animated: Boolean = true) {
        val target = index.coerceIn(0, maxOf(0, childCount - 1))
        if (!scroller.isFinished) scroller.abortAnimation()
        if (target != currentPage) { currentPage = target; onPageChanged?.invoke(currentPage) }
        if (width == 0) return // not laid out yet; onSizeChanged's re-snap will land on the right page
        if (animated) startSettle(target, 260) else scrollTo(target * width, 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (locked || childCount == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragStartScrollX = scrollX
                // A fling/settle animation still running when the user touches down again should be
                // grabbed immediately, same as ViewPager, rather than waiting for slop.
                dragging = !scroller.isFinished
                if (!scroller.isFinished) scroller.abortAnimation()
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (locked || childCount == 0 || width == 0) return false
        obtainVelocityTracker().addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragStartScrollX = scrollX
                if (!scroller.isFinished) { scroller.abortAnimation(); dragging = true }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) dragging = true else return true
                }
                dragBy(lastX - ev.x); lastX = ev.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    settle(tracker?.xVelocity ?: 0f)
                }
                dragging = false; recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(0f)
                dragging = false; recycleVelocityTracker()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, scroller.currY); postInvalidateOnAnimation() }
    }

    /** Drags [scrollX] by [dx] px, clamped to the page range with a little rubber-band resistance past either edge. */
    private fun dragBy(dx: Float) {
        val w = width; val max = (childCount - 1) * w
        val cur = scrollX.toFloat()
        val resisted = if ((cur <= 0f && dx < 0f) || (cur >= max && dx > 0f)) dx * EDGE_RESISTANCE else dx
        val next = (cur + resisted).coerceIn(-w * EDGE_OVERSCROLL_FRACTION, max + w * EDGE_OVERSCROLL_FRACTION)
        scrollTo(next.roundToInt(), 0)
    }

    /** Decides the destination page for the drag that just ended (or was canceled, with [vx] = 0)
     *  and animates there. A fast enough release (beyond [MIN_FLING_MULTIPLIER]x the platform's
     *  minimum fling velocity) always moves exactly one page in the fling's direction from where
     *  the drag started; otherwise the page only changes once the drag crossed [SNAP_FRACTION] of
     *  the pager's width, so small, hesitant drags snap back. */
    private fun settle(vx: Float) {
        val w = width; if (w == 0) return
        val startPage = (dragStartScrollX.toFloat() / w).roundToInt().coerceIn(0, maxOf(0, childCount - 1))
        val target = if (abs(vx) > minFlingVelocity * MIN_FLING_MULTIPLIER) {
            // dx = lastX - eventX means dragging left (finger moving left, vx negative) increases
            // scrollX, i.e. advances to the next page; a rightward flick (vx positive) goes back.
            startPage + if (vx < 0) 1 else -1
        } else {
            val movedFraction = (scrollX - dragStartScrollX).toFloat() / w
            startPage + when { movedFraction > SNAP_FRACTION -> 1; movedFraction < -SNAP_FRACTION -> -1; else -> 0 }
        }
        startSettle(target.coerceIn(0, maxOf(0, childCount - 1)), durationFor(vx))
    }

    private fun startSettle(target: Int, duration: Int) {
        if (target != currentPage) { currentPage = target; onPageChanged?.invoke(currentPage) }
        val destX = target * width
        scroller.startScroll(scrollX, 0, destX - scrollX, 0, duration)
        postInvalidateOnAnimation()
    }

    private fun durationFor(vx: Float): Int {
        val speed = (abs(vx) / maxFlingVelocity).coerceIn(0f, 1f)
        return (300 - speed * 140).roundToInt() // fast flicks settle in ~160ms, slow releases in ~300ms
    }

    private fun obtainVelocityTracker(): VelocityTracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    private fun recycleVelocityTracker() { velocityTracker?.recycle(); velocityTracker = null }

    private companion object {
        const val EDGE_RESISTANCE = 0.35f
        const val EDGE_OVERSCROLL_FRACTION = 0.06f
        const val SNAP_FRACTION = 0.25f
        const val MIN_FLING_MULTIPLIER = 4
    }
}
