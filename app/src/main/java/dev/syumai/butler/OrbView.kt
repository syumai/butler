package dev.syumai.butler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The assistant's animated "orb" shown by the conversation overlay (§8): three soft, wobbling
 * blob layers (outer halo, main body, inner highlight) whose color and motion speed depend on
 * [mode]. Everything a frame needs — the blob [Path]s, the three [Paint]s, the per-mode
 * [RadialGradient] shaders, and the `FloatArray`s holding the 8 control points of each blob — is
 * allocated once (in the constructor / [layoutShaders]) and mutated in place every frame; the
 * target device is a weak 32-bit ARM tablet, so `onDraw` must not allocate.
 *
 * The redraw loop is capped at ~30 fps: [onDraw] reschedules itself with
 * `postInvalidateOnAnimation`, but only actually recomputes the geometry when at least
 * [FRAME_INTERVAL_MS] has passed since the last recompute — a closer callback (the display's own
 * vsync can be faster) just redraws the last computed frame. The loop only runs while [active] is
 * true and the view is both attached and its window visible, same pattern as `Landscape`.
 *
 * A mode change cross-fades over [FADE_MS]. Rather than interpolating gradient *colors* frame by
 * frame (which would mean building a new [RadialGradient] every frame, an allocation this view is
 * built to avoid), each layer is drawn up to twice per frame during a transition — the outgoing
 * mode's precomputed shader at fading-out alpha, then the incoming mode's at fading-in alpha —
 * which cross-fades the same shapes without ever allocating a shader outside [layoutShaders].
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { CONNECTING, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.CONNECTING
        set(value) {
            if (field == value) return
            fadeFrom = field; fadeStartMs = SystemClock.uptimeMillis(); field = value
            postInvalidateOnAnimation()
        }
    /** Stops the animation loop when false (the redraw scheduled by the last [onDraw] simply isn't renewed). */
    var active: Boolean = true
        set(value) { field = value; if (value) postInvalidateOnAnimation() }

    // ---- geometry, pre-allocated and mutated in place every frame (see class doc) ----
    private val blobPath = Path()
    private val highlightPath = Path()
    private val blobPointX = FloatArray(POINTS)
    private val blobPointY = FloatArray(POINTS)
    private val highlightPointX = FloatArray(POINTS)
    private val highlightPointY = FloatArray(POINTS)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val startMs = SystemClock.uptimeMillis()
    private var lastFrameMs = 0L
    private var fadeFrom: Mode = Mode.CONNECTING
    private var fadeStartMs = 0L
    private var fadeProgress = 1f

    private var bobOffset = 0f
    private var rotationDeg = 0f

    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f
    private var haloRadius = 0f
    private var highlightRadius = 0f

    private var attachedToWindow = false
    private var windowVisible = false

    // One shader per layer per mode, (re)built only in layoutShaders() when the view is sized.
    private val haloShaders = arrayOfNulls<RadialGradient>(Mode.entries.size)
    private val blobShaders = arrayOfNulls<RadialGradient>(Mode.entries.size)
    private val highlightShaders = arrayOfNulls<RadialGradient>(Mode.entries.size)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attachedToWindow = true; maybeResume() }
    override fun onDetachedFromWindow() { attachedToWindow = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); windowVisible = visibility == VISIBLE; maybeResume()
    }
    private fun maybeResume() { if (active && attachedToWindow && windowVisible) postInvalidateOnAnimation() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f; cy = h / 2f
        baseRadius = minOf(w, h) * 0.30f
        haloRadius = baseRadius * 1.3f
        highlightRadius = (baseRadius - dpF(14f)).coerceAtLeast(baseRadius * 0.3f)
        layoutShaders()
        update(SystemClock.uptimeMillis()) // seed geometry so the very first frame isn't empty
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameMs >= FRAME_INTERVAL_MS) { lastFrameMs = now; update(now) }
        render(canvas)
        if (active && attachedToWindow && windowVisible) postInvalidateOnAnimation()
    }

    private fun dpF(value: Float) = value * resources.displayMetrics.density

    private fun update(now: Long) {
        if (width == 0 || height == 0) return
        val tSec = (now - startMs) / 1000f
        fadeProgress = if (fadeStartMs == 0L) 1f else ((now - fadeStartMs) / FADE_MS).coerceIn(0f, 1f)
        val style = STYLES.getValue(mode)
        val twoPi = (2 * Math.PI).toFloat()
        bobOffset = dpF(8f) * sin(twoPi * tSec / (style.bobPeriodMs / 1000f))
        rotationDeg = 6f * sin(twoPi * tSec / (style.bobPeriodMs / 1000f) + 0.6f)
        val haloCycle = 0.5f + 0.5f * sin(twoPi * tSec / (style.haloPeriodMs / 1000f))
        haloPulse = 0.7f + 0.3f * haloCycle
        computePoints(blobPointX, blobPointY, baseRadius, tSec, style.morphPeriodMs / 1000f)
        computePoints(highlightPointX, highlightPointY, highlightRadius, tSec, style.morphPeriodMs / 1000f * 1.15f)
        buildBlob(blobPath, blobPointX, blobPointY)
        buildBlob(highlightPath, highlightPointX, highlightPointY)
    }
    private var haloPulse = 1f

    private fun render(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dim = if (STYLES.getValue(mode).dim) DIM_ALPHA else 1f
        canvas.save()
        canvas.translate(0f, bobOffset)
        drawCircleLayer(canvas, haloPaint, haloShaders, haloRadius, (255 * haloPulse * dim).toInt().coerceIn(0, 255))
        canvas.save(); canvas.rotate(rotationDeg, cx, cy)
        drawPathLayer(canvas, blobPath, blobPaint, blobShaders, (255 * dim).toInt().coerceIn(0, 255))
        canvas.restore()
        canvas.save(); canvas.rotate(-rotationDeg, cx, cy)
        drawPathLayer(canvas, highlightPath, highlightPaint, highlightShaders, (255 * dim).toInt().coerceIn(0, 255))
        canvas.restore()
        canvas.restore()
    }

    private fun drawCircleLayer(canvas: Canvas, paint: Paint, shaders: Array<RadialGradient?>, radius: Float, baseAlpha: Int) {
        if (fadeProgress >= 1f) {
            paint.shader = shaders[mode.ordinal]; paint.alpha = baseAlpha
            canvas.drawCircle(cx, cy, radius, paint)
        } else {
            paint.shader = shaders[fadeFrom.ordinal]; paint.alpha = (baseAlpha * (1f - fadeProgress)).toInt()
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = shaders[mode.ordinal]; paint.alpha = (baseAlpha * fadeProgress).toInt()
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun drawPathLayer(canvas: Canvas, path: Path, paint: Paint, shaders: Array<RadialGradient?>, baseAlpha: Int) {
        if (fadeProgress >= 1f) {
            paint.shader = shaders[mode.ordinal]; paint.alpha = baseAlpha
            canvas.drawPath(path, paint)
        } else {
            paint.shader = shaders[fadeFrom.ordinal]; paint.alpha = (baseAlpha * (1f - fadeProgress)).toInt()
            canvas.drawPath(path, paint)
            paint.shader = shaders[mode.ordinal]; paint.alpha = (baseAlpha * fadeProgress).toInt()
            canvas.drawPath(path, paint)
        }
    }

    /** Places 8 control points evenly around the given radius, each offset by a sum of 3 sine
     *  terms of different phase/frequency so the outline wobbles organically (period ≈
     *  [morphPeriodSec]) rather than breathing in and out uniformly. */
    private fun computePoints(xs: FloatArray, ys: FloatArray, radius: Float, tSec: Float, morphPeriodSec: Float) {
        val twoPi = (2 * Math.PI).toFloat()
        val w1 = twoPi / morphPeriodSec
        val w2 = twoPi / (morphPeriodSec * 0.63f)
        val w3 = twoPi / (morphPeriodSec * 1.7f)
        for (i in xs.indices) {
            val angle = twoPi * i / xs.size
            val wobble = 0.07f * sin(tSec * w1 + angle * 2f) +
                0.035f * sin(tSec * w2 + angle * 3f + 1.3f) +
                0.02f * sin(tSec * w3 - angle * 1.5f)
            val r = radius * (1f + wobble)
            xs[i] = cx + r * cos(angle)
            ys[i] = cy + r * sin(angle)
        }
    }

    /** Builds a smooth closed blob through 8 points: quadratic Bezier segments from the midpoint
     *  before each point to the midpoint after it, using the point itself as the control — the
     *  standard trick for a smooth curve through N points without a full spline, and cheap enough
     *  to redo every frame (no allocation; `path` is reset and reused). */
    private fun buildBlob(path: Path, xs: FloatArray, ys: FloatArray) {
        path.reset()
        val n = xs.size
        val startX = (xs[n - 1] + xs[0]) / 2f; val startY = (ys[n - 1] + ys[0]) / 2f
        path.moveTo(startX, startY)
        for (i in 0 until n) {
            val next = (i + 1) % n
            val midX = (xs[i] + xs[next]) / 2f; val midY = (ys[i] + ys[next]) / 2f
            path.quadTo(xs[i], ys[i], midX, midY)
        }
        path.close()
    }

    private fun layoutShaders() {
        if (width == 0 || height == 0) return
        for (mode in Mode.entries) {
            val style = STYLES.getValue(mode)
            val hi = cx - baseRadius * 0.3f; val hy = cy - baseRadius * 0.4f
            haloShaders[mode.ordinal] = RadialGradient(cx, cy, haloRadius.coerceAtLeast(1f),
                intArrayOf(style.mid and 0x00FFFFFF or 0x59000000, style.mid and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            blobShaders[mode.ordinal] = RadialGradient(hi, hy, (baseRadius * 1.3f).coerceAtLeast(1f),
                intArrayOf(style.bright, style.mid, style.dark), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            highlightShaders[mode.ordinal] = RadialGradient(hi, hy, (highlightRadius * 1.3f).coerceAtLeast(1f),
                intArrayOf(style.highlightCenter, style.highlightMid, style.mid and 0x00FFFFFF), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        }
    }

    /** Per-mode speed/color profile. Halo pulse / blob morph / bob+rotate periods mirror the
     *  reference mockup's CSS animation durations (`butler-ui-proposal.html`'s `.orb` rules):
     *  listening is the baseline (morph 6s, bob 4.5s, halo pulse 3s), speaking speeds all three up
     *  (2.6s / 3s / 1.4s). Thinking reuses speaking's speed (also explicitly called out as a 1.4s
     *  pulse in the design spec) but stays teal. Connecting has no CSS equivalent — "dim, slow" per
     *  the spec — so it runs slower than listening and draws at [DIM_ALPHA] alpha. */
    private class Style(
        val haloPeriodMs: Long, val bobPeriodMs: Long, val morphPeriodMs: Long, val dim: Boolean,
        val bright: Int, val mid: Int, val dark: Int, val highlightCenter: Int, val highlightMid: Int,
    )

    private companion object {
        const val POINTS = 8
        const val FRAME_INTERVAL_MS = 33L // ~30 fps cap
        const val FADE_MS = 400f
        const val DIM_ALPHA = 0.55f

        val TEAL_BRIGHT = Color.parseColor("#8FD0D8")
        val TEAL_DARK = Color.parseColor("#163E45")
        val BRASS_BRIGHT = Color.parseColor("#FFE2A8")
        val BRASS_DARK = Color.parseColor("#8A5A2B")
        val TEAL_HIGHLIGHT_CENTER = Color.argb(230, 245, 239, 227)
        val TEAL_HIGHLIGHT_MID = Color.argb(102, 46, 110, 118)
        val BRASS_HIGHLIGHT_CENTER = Color.argb(242, 255, 255, 255)
        val BRASS_HIGHLIGHT_MID = Color.argb(115, 227, 184, 101)

        val STYLES: Map<Mode, Style> = mapOf(
            Mode.CONNECTING to Style(4500, 6500, 9000, true, TEAL_BRIGHT, Palette.TEAL, TEAL_DARK, TEAL_HIGHLIGHT_CENTER, TEAL_HIGHLIGHT_MID),
            Mode.LISTENING to Style(3000, 4500, 6000, false, TEAL_BRIGHT, Palette.TEAL, TEAL_DARK, TEAL_HIGHLIGHT_CENTER, TEAL_HIGHLIGHT_MID),
            Mode.THINKING to Style(1400, 3000, 2600, false, TEAL_BRIGHT, Palette.TEAL, TEAL_DARK, TEAL_HIGHLIGHT_CENTER, TEAL_HIGHLIGHT_MID),
            Mode.SPEAKING to Style(1400, 3000, 2600, false, BRASS_BRIGHT, Palette.BRASS, BRASS_DARK, BRASS_HIGHLIGHT_CENTER, BRASS_HIGHLIGHT_MID),
        )
    }
}
