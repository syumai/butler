package dev.syumai.butler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.io.File
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random

// Shared by RAIN and THUNDER: dx/dy ratio for both a streak's own tilt and its fall direction, so the line
// always points the way it is moving (a slight, consistent lean off vertical, not the ~30-degree slant a
// mismatched tilt/drift produced before).
private const val RAIN_SLANT = -0.12f

/** Original code-drawn landscape; users can replace it with a local photograph, or with a weather-linked
 *  animated scene. Animation runs only while the view is attached, its window is visible, and the current
 *  scene has motion (anything but DEFAULT/CLEAR_DAY, see [updateAnimating]) — capped at ~25 fps via
 *  `postInvalidateDelayed(40)` from onDraw and timed off `SystemClock.uptimeMillis()`, which is cheap
 *  enough for the target device (960x480, 32-bit ARM, weak CPU). The default illustration's
 *  palette (sky/sun/ridge colors only — geometry is unchanged) follows the time of day via [DayPalette]
 *  and [minuteOfDay]; the weather-scene and imported-photo paths are unaffected. */
internal class Landscape(context: Context, file: File, private val settings: Settings) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Used only for the per-frame weather-scene draws (drawScene): no anti-aliasing, since those circles/
    // lines are small and redrawn ~25 times a second — AA there was the dominant CPU cost on-device. The
    // default illustration/photo path above keeps using `paint` (with AA) so it stays pixel-identical.
    private val fastPaint = Paint()
    private val bitmap: Bitmap? = if (file.exists()) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1000) options.inSampleSize *= 2
        options.inJustDecodeBounds = false; BitmapFactory.decodeFile(file.path, options)
    } else null
    // Weather-linked background; only used when the setting is on and the scene is known. Rendering
    // precedence: weather scene > imported photo > default illustration (unchanged, pixel-identical).
    var scene: WeatherScene = WeatherScene.DEFAULT
        set(value) { field = value; updateAnimating(); invalidate() }
    // Debug-only: `adb shell setprop debug.butler.scene <SCENE>` forces the scene on regardless of the
    // weather-background setting, to check individual scenes on-device (see docs/device-validation.md).
    var debugForceWeather = false
        set(value) { field = value; updateAnimating() }
    // Drives only the default illustration's time-of-day palette (see DayPalette); the weather-scene and
    // imported-photo paths never read it. MainActivity's tick pushes the current minute once a minute, so
    // the occasional extra invalidate() is negligible.
    var minuteOfDay: Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        set(value) { if (field != value) { field = value; invalidate() } }
    private var art: SceneArt? = null
    private var attachedToWindow = false
    private var windowVisible = false
    private var animating = false
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attachedToWindow = true; updateAnimating() }
    override fun onDetachedFromWindow() { attachedToWindow = false; animating = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); windowVisible = visibility == VISIBLE; updateAnimating()
    }
    private fun updateAnimating() {
        val should = attachedToWindow && windowVisible && (settings.weatherBackground || debugForceWeather) &&
            scene != WeatherScene.DEFAULT && scene != WeatherScene.CLEAR_DAY
        if (should && !animating) { animating = true; invalidate() } else if (!should) animating = false
    }
    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val w = width.toFloat(); val h = height.toFloat()
        if ((settings.weatherBackground || debugForceWeather) && scene != WeatherScene.DEFAULT) {
            drawScene(canvas, w, h)
            if (animating) postInvalidateDelayed(40) // ~25 fps cap, cheap enough for a weak 32-bit ARM device
            return
        }
        bitmap?.let {
            val scale = maxOf(w / it.width, h / it.height); val bw = it.width * scale; val bh = it.height * scale
            canvas.drawBitmap(it, null, RectF((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2), paint); return
        }
        val palette = DayPalette.at(minuteOfDay)
        paint.shader = LinearGradient(0f, 0f, w, h, palette.sky, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        paint.color = palette.sun; canvas.drawCircle(w*.78f, h*.28f, h*.105f, paint)
        fun ridge(color: Int, base: Float, peak: Float, shift: Float) {
            paint.color = color
            val p = Path().apply { moveTo(0f, h*base); cubicTo(w*.25f, h*(base-.12f), w*(.42f+shift), h*peak, w*.67f, h*(base-.08f)); cubicTo(w*.84f, h*(base+.1f), w*.9f, h*(peak+.1f), w, h*base); lineTo(w,h); lineTo(0f,h); close() }
            canvas.drawPath(p, paint)
        }
        ridge(palette.ridges[0], .65f, .24f, .1f)
        ridge(palette.ridges[1], .8f, .5f, -.15f)
        ridge(palette.ridges[2], 1f, .56f, .2f)
    }
    // Weather scenes are code-drawn. SceneArt holds the deterministic base geometry (rebuilt only when the
    // scene or view size changes), a cached bitmap of the parts that never move (sky, sun/moon, ridges —
    // the expensive anti-aliased Bezier fills, baked once instead of on every animation frame), a small
    // rain output buffer, and a lightning flash schedule that update()/flashAt() mutate or read each frame.
    // Everything else (cloud drift, star twinkle, snow fall, fog drift) is computed straight from that base
    // geometry and the elapsed time below — no allocation.
    private fun drawScene(canvas: Canvas, w: Float, h: Float) {
        var a = art
        if (a == null || a.scene != scene || a.w != w || a.h != h) { a = SceneArt(scene, w, h); art = a }
        val t = (SystemClock.uptimeMillis() - a.createdAt).toFloat()
        if (animating) a.update(t)
        canvas.drawBitmap(a.staticBitmap, 0f, 0f, fastPaint)
        for (i in a.stars.indices) {
            val star = a.stars[i]
            fastPaint.color = 0xFFF5F3E0.toInt()
            fastPaint.alpha = if (animating) (180 + 75 * sin(t * a.starFreq[i] + a.starPhase[i])).toInt().coerceIn(50, 255) else 255
            canvas.drawCircle(star[0], star[1], star[2], fastPaint)
        }
        for (i in a.clouds.indices) {
            val centerX = if (animating) a.wrapCloudX(a.cloudCenterX[i], a.cloudSpeedFactor[i], t) else a.cloudCenterX[i]
            fastPaint.color = a.cloudColor
            for (c in a.clouds[i]) canvas.drawCircle(centerX + c[0], c[1], c[2], fastPaint)
        }
        if (a.fogBand) {
            fastPaint.color = 0x66FFFFFF.toInt(); canvas.drawRect(0f, h * .55f, w, h * .68f, fastPaint)
            if (animating) {
                val bandW = w * .35f; val x = a.wrapFogX(t)
                fastPaint.color = 0x33FFFFFF.toInt(); canvas.drawRect(x, h * .55f, x + bandW, h * .68f, fastPaint)
            }
        }
        // Three depth layers drawn as separate drawLines() calls (still cheap) so far streaks can be thin,
        // dim and slow while near ones are thick, bright and fast; each layer is a contiguous slice of the
        // same buffer, grouped by addRain().
        var rainOffset = 0
        for (layer in a.rainLayerCounts.indices) {
            val n = a.rainLayerCounts[layer]
            if (n > 0) {
                fastPaint.color = a.rainLayerColor[layer]; fastPaint.strokeWidth = a.rainLayerWidth[layer]
                canvas.drawLines(a.rainLines, rainOffset, n * 4, fastPaint)
            }
            rainOffset += n * 4
        }
        for (i in a.snowBaseX.indices) {
            val cx: Float; val cy: Float
            if (animating) {
                cx = a.snowBaseX[i] + sin(t * a.snowFreq[i] + a.snowPhase[i]) * (w * .02f)
                cy = wrap(a.snowBaseY[i] + t * a.snowSpeed[i], a.snowRange)
            } else { cx = a.snowBaseX[i]; cy = a.snowBaseY[i] }
            fastPaint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(cx, cy, a.snowR[i], fastPaint)
        }
        a.lightning?.let { bolt ->
            val flash = if (animating) a.flashAt(t) else 0f
            if (flash > 0f) {
                canvas.drawColor(Color.argb((flash * 80).toInt(), 255, 255, 255))
                fastPaint.style = Paint.Style.STROKE; fastPaint.strokeWidth = 4f
                fastPaint.color = 0xFFF5E9A0.toInt(); fastPaint.alpha = (flash * 255).toInt()
                canvas.drawPath(bolt, fastPaint); fastPaint.style = Paint.Style.FILL
            }
        }
    }
    private fun wrap(value: Float, range: Float): Float { var v = value % range; if (v < 0) v += range; return v }
}

/** Precomputed, deterministic geometry for one weather scene at one view size; rebuilt only on change.
 *  Also owns the tiny mutable state that animation needs: a shared sky [Shader] (created once instead of
 *  per frame), a rain-streak output buffer mutated by [update], and a lightning flash schedule read by
 *  [flashAt]. Cloud drift, star twinkle, snow fall and fog drift are pure functions of this base geometry
 *  and elapsed time, computed by the caller with no extra state here. */
private class SceneArt(val scene: WeatherScene, val w: Float, val h: Float) {
    val createdAt = SystemClock.uptimeMillis()
    val sky: IntArray
    lateinit var skyShader: Shader
    // Sky gradient, sun/moon and ridges never move once laid out — the ridges in particular are anti-
    // aliased Bezier-path fills spanning most of the screen, the single biggest render cost on-device.
    // Baking them into a bitmap once (here) instead of redrawing them on every ~40ms animation frame is
    // what keeps the animated scenes cheap: Landscape.drawScene() just blits this bitmap, then draws only
    // the parts that actually move (clouds, rain, snow, stars, fog, lightning) on top of it.
    lateinit var staticBitmap: Bitmap
    var sun: FloatArray? = null // cx, cy, r
    var sunColor = 0
    var moon: FloatArray? = null // cx, cy, r
    val stars = mutableListOf<FloatArray>() // cx, cy, r (r fixed; alpha for twinkle comes from starPhase/starFreq)
    val starPhase = mutableListOf<Float>()
    val starFreq = mutableListOf<Float>()
    // Each cloud is a union of circles (relative x offset from the cloud's center, absolute y, r); the
    // center drifts horizontally over time via wrapCloudX(), the circles' relative layout never changes.
    val clouds = mutableListOf<List<FloatArray>>()
    val cloudCenterX = mutableListOf<Float>()
    val cloudSpeedFactor = mutableListOf<Float>()
    var cloudColor = 0
    private val cloudMargin = w * .16f
    private val cloudRange = w + cloudMargin * 2f
    private val cloudSpeed = w / 90_000f // px/ms: a full screen width drift takes about 90s
    var fogBand = false
    private val fogMargin = w * .3f
    private val fogRange = w + fogMargin * 2f
    private val fogSpeed = w / 60_000f // px/ms
    val ridges = mutableListOf<Path>()
    val ridgeColors = mutableListOf<Int>()
    val ridgeAlphas = mutableListOf<Int>()
    val ridgeCaps = mutableListOf<Path?>()
    var capColor = 0
    private var rainBaseX = FloatArray(0)
    private var rainBaseY = FloatArray(0)
    private var rainLen = FloatArray(0)
    private var rainSpeed = FloatArray(0)
    var rainLines = FloatArray(0) // x1,y1,x2,y2 per streak; mutated by update(). Grouped by depth layer
    // (far..near) so Landscape can draw each layer's slice with its own drawLines() call.
    var rainLayerCounts = IntArray(0) // streak count per layer: [far, mid, near]
    var rainLayerColor = IntArray(0)
    var rainLayerWidth = FloatArray(0)
    private val rainRange = h * 1.3f // wrap range; the extra margin lets streaks appear above the top and
    private val rainYOffset = -h * .15f // exit below the bottom edge instead of popping in/out on-screen
    var snowBaseX = FloatArray(0)
    var snowBaseY = FloatArray(0)
    var snowR = FloatArray(0)
    var snowSpeed = FloatArray(0)
    var snowPhase = FloatArray(0)
    var snowFreq = FloatArray(0)
    val snowRange = h * 1.08f
    var lightning: Path? = null
    private var flashStarts = FloatArray(0)
    private var flashCycle = 0f
    init {
        // Seeded by the scene so the same scene always lays out the same stars/clouds/rain (no flicker
        // across redraws) while different scenes still look distinct from each other.
        val rnd = Random(scene.ordinal * 97L + 13L)
        val ridgeDefs = listOf(Triple(.65f, .24f, .1f), Triple(.8f, .5f, -.15f), Triple(1f, .56f, .2f))
        fun ridgePath(base: Float, peak: Float, shift: Float) = Path().apply {
            moveTo(0f, h * base)
            cubicTo(w * .25f, h * (base - .12f), w * (.42f + shift), h * peak, w * .67f, h * (base - .08f))
            cubicTo(w * .84f, h * (base + .1f), w * .9f, h * (peak + .1f), w, h * base)
            lineTo(w, h); lineTo(0f, h); close()
        }
        // Circle offsets relative to a cloud's own center (0,0); cy is absolute since clouds only drift in x.
        fun cloud(cy: Float, scale: Float) = listOf(
            floatArrayOf(0f, cy, .09f * h * scale),
            floatArrayOf(-.07f * w * scale, cy + .015f * h, .065f * h * scale),
            floatArrayOf(.075f * w * scale, cy + .01f * h, .07f * h * scale),
            floatArrayOf(.02f * w * scale, cy - .03f * h, .06f * h * scale)
        )
        fun addClouds(count: Int, color: Int) {
            cloudColor = color
            repeat(count) {
                cloudCenterX.add(w * (.15f + rnd.nextFloat() * .7f))
                clouds.add(cloud(h * (.12f + rnd.nextFloat() * .22f), .8f + rnd.nextFloat() * .5f))
                cloudSpeedFactor.add(.75f + rnd.nextFloat() * .5f) // slight per-cloud speed variety
            }
        }
        fun addStars(count: Int, maxY: Float) {
            repeat(count) {
                stars.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * maxY, 1f + rnd.nextFloat() * 1.8f))
                starPhase.add(rnd.nextFloat() * (Math.PI * 2).toFloat())
                starFreq.add(.0015f + rnd.nextFloat() * .0025f) // ~2.5-4.2s twinkle period
            }
        }
        fun addRidges(colors: List<Int>, alphas: List<Int> = listOf(255, 255, 255), whiteCap: Boolean = false) {
            ridgeDefs.forEachIndexed { i, (base, peak, shift) ->
                ridges.add(ridgePath(base, peak, shift)); ridgeColors.add(colors[i]); ridgeAlphas.add(alphas[i])
                // A thin sliver of the (lighter) cap path drawn just above the ridge peak reads as a snow cap.
                ridgeCaps.add(if (whiteCap) ridgePath(peak + .03f, peak - .02f, shift) else null)
            }
            if (whiteCap) capColor = 0xCCF2F5F7.toInt()
        }
        fun addRain(count: Int) {
            // Three depth layers (far, mid, near): far streaks are short/thin/dim/slow, near streaks are
            // long/thick/bright/fast. The near layer falls the full height in ~0.6-0.8s; the far layer falls
            // at about half that speed, giving a sense of depth instead of one flat sheet of rain.
            val perLayer = count / 3
            rainLayerCounts = intArrayOf(perLayer, perLayer, count - perLayer * 2)
            rainLayerColor = intArrayOf(0x55B9D6E0.toInt(), 0x99B9D6E0.toInt(), 0xE6B9D6E0.toInt())
            rainLayerWidth = floatArrayOf(1f, 1.5f, 2f)
            val nearSpeed = h / 700f // px/ms: full height in ~0.7s
            val farSpeed = nearSpeed * .5f
            val layerSpeed = floatArrayOf(farSpeed, (farSpeed + nearSpeed) / 2f, nearSpeed)
            val layerLenFrac = arrayOf(.02f to .04f, .04f to .065f, .06f to .09f) // fraction of h
            rainBaseX = FloatArray(count); rainBaseY = FloatArray(count); rainLen = FloatArray(count); rainSpeed = FloatArray(count)
            rainLines = FloatArray(count * 4)
            var idx = 0
            for (layer in 0..2) {
                val (lo, hi) = layerLenFrac[layer]
                repeat(rainLayerCounts[layer]) {
                    rainBaseX[idx] = rnd.nextFloat() * w
                    rainBaseY[idx] = rnd.nextFloat() * rainRange
                    rainLen[idx] = h * (lo + rnd.nextFloat() * (hi - lo))
                    rainSpeed[idx] = layerSpeed[layer] * (.9f + rnd.nextFloat() * .2f)
                    idx++
                }
            }
            update(0f) // seed rainLines so the first frame (before any animation tick) matches t=0
        }
        fun addSnow(count: Int) {
            snowBaseX = FloatArray(count); snowBaseY = FloatArray(count); snowR = FloatArray(count)
            snowSpeed = FloatArray(count); snowPhase = FloatArray(count); snowFreq = FloatArray(count)
            for (i in 0 until count) {
                snowBaseX[i] = rnd.nextFloat() * w
                snowBaseY[i] = rnd.nextFloat() * h * .9f
                snowR[i] = 1.5f + rnd.nextFloat() * 2.5f
                snowSpeed[i] = .012f + .01f * (snowR[i] - 1.5f) // px/ms; larger flakes fall a bit faster
                snowPhase[i] = rnd.nextFloat() * (Math.PI * 2).toFloat()
                snowFreq[i] = .0012f + rnd.nextFloat() * .0012f // slow sideways sway
            }
        }
        when (scene) {
            WeatherScene.CLEAR_DAY -> {
                sky = intArrayOf(0xFF3E7BC4.toInt(), 0xFF7FB8D9.toInt(), 0xFFF2C879.toInt())
                sun = floatArrayOf(w * .78f, h * .28f, h * .105f); sunColor = 0xFFE7D7A9.toInt()
                addRidges(listOf(0xFF8FAE6E.toInt(), 0xFF5E8C4E.toInt(), 0xFF355B33.toInt()))
            }
            WeatherScene.CLEAR_NIGHT -> {
                sky = intArrayOf(0xFF060B1F.toInt(), 0xFF11213F.toInt(), 0xFF1C2F52.toInt())
                moon = floatArrayOf(w * .76f, h * .24f, h * .085f)
                addStars(24, h * .6f)
                addRidges(listOf(0xFF2A3A5C.toInt(), 0xFF1B2740.toInt(), 0xFF0E1626.toInt()))
            }
            WeatherScene.PARTLY_CLOUDY_DAY -> {
                sky = intArrayOf(0xFF3E7BC4.toInt(), 0xFF7FB8D9.toInt(), 0xFFF2C879.toInt())
                sun = floatArrayOf(w * .78f, h * .28f, h * .105f); sunColor = 0xFFE7D7A9.toInt()
                addClouds(3, 0xDDEFEFEF.toInt())
                addRidges(listOf(0xFF8FAE6E.toInt(), 0xFF5E8C4E.toInt(), 0xFF355B33.toInt()))
            }
            WeatherScene.PARTLY_CLOUDY_NIGHT -> {
                sky = intArrayOf(0xFF060B1F.toInt(), 0xFF11213F.toInt(), 0xFF1C2F52.toInt())
                moon = floatArrayOf(w * .76f, h * .24f, h * .085f)
                addStars(16, h * .55f)
                addClouds(3, 0xCC3A3F4A.toInt())
                addRidges(listOf(0xFF2A3A5C.toInt(), 0xFF1B2740.toInt(), 0xFF0E1626.toInt()))
            }
            WeatherScene.CLOUDY -> {
                sky = intArrayOf(0xFF7D8285.toInt(), 0xFF9AA0A2.toInt(), 0xFFB7BBBC.toInt())
                addClouds(4, 0xEEDCDFE0.toInt())
                addRidges(listOf(0xFF8A8F91.toInt(), 0xFF666B6D.toInt(), 0xFF454A4C.toInt()))
            }
            WeatherScene.FOG -> {
                sky = intArrayOf(0xFFCED4D5.toInt(), 0xFFDBE0E1.toInt(), 0xFFE7EAEA.toInt())
                fogBand = true
                addRidges(listOf(0xFFB9C0C2.toInt(), 0xFFB9C0C2.toInt(), 0xFFB9C0C2.toInt()), listOf(220, 150, 90))
            }
            WeatherScene.RAIN -> {
                sky = intArrayOf(0xFF232E38.toInt(), 0xFF34434F.toInt(), 0xFF44545F.toInt())
                addClouds(3, 0xEE2C343B.toInt())
                addRidges(listOf(0xFF3A5A5C.toInt(), 0xFF25403F.toInt(), 0xFF142B2A.toInt()))
                addRain(60)
            }
            WeatherScene.SNOW -> {
                sky = intArrayOf(0xFFB9C4D0.toInt(), 0xFFCBD5DE.toInt(), 0xFFDCE4EA.toInt())
                addClouds(3, 0xEEE9EEF2.toInt())
                addRidges(listOf(0xFF6E7B8C.toInt(), 0xFF4F5A6B.toInt(), 0xFF37404E.toInt()), whiteCap = true)
                addSnow(50)
            }
            WeatherScene.THUNDER -> {
                sky = intArrayOf(0xFF0C0E14.toInt(), 0xFF14161F.toInt(), 0xFF1B1E2A.toInt())
                addClouds(4, 0xF01A1D24.toInt())
                addRidges(listOf(0xFF23262E.toInt(), 0xFF16181D.toInt(), 0xFF0A0B0E.toInt()))
                addRain(45)
                lightning = Path().apply {
                    val x = w * .4f
                    moveTo(x, h * .1f); lineTo(x + w * .04f, h * .28f); lineTo(x - w * .02f, h * .3f); lineTo(x + w * .05f, h * .5f)
                }
                // Deterministic repeating flash schedule: a handful of flashes spaced 6-12s apart, then loop.
                var acc = 1500f
                val starts = FloatArray(4)
                for (i in 0 until 4) { starts[i] = acc; acc += 6000f + rnd.nextFloat() * 6000f }
                flashStarts = starts; flashCycle = acc
            }
            WeatherScene.DEFAULT -> sky = intArrayOf(0xFF254C50.toInt(), 0xFF81988D.toInt(), 0xFFE7BF8D.toInt()) // unused: caller skips DEFAULT
        }
        skyShader = LinearGradient(0f, 0f, w, h, sky, null, Shader.TileMode.CLAMP)
        // Bake the never-moving parts (sky, sun/moon, ridges) into a bitmap once; see the field doc above.
        staticBitmap = Bitmap.createBitmap(maxOf(1, w.toInt()), maxOf(1, h.toInt()), Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp); val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.shader = skyShader; c.drawRect(0f, 0f, w, h, sp); sp.shader = null
            sun?.let { sp.color = sunColor; c.drawCircle(it[0], it[1], it[2], sp) }
            moon?.let { m ->
                sp.color = 0xFFEFEFE0.toInt(); c.drawCircle(m[0], m[1], m[2], sp)
                sp.color = sky[0]; c.drawCircle(m[0] + m[2] * .5f, m[1] - m[2] * .3f, m[2] * .92f, sp)
            }
            for (i in ridges.indices) {
                ridgeCaps[i]?.let { sp.color = capColor; c.drawPath(it, sp) }
                sp.color = ridgeColors[i]; sp.alpha = ridgeAlphas[i]; c.drawPath(ridges[i], sp)
            }
        }
    }
    /** Mutates the rain output buffer for elapsed time [t] ms since this SceneArt was created; allocates
     *  nothing. Everything else animated is computed by the caller directly from the base arrays above. */
    fun update(t: Float) {
        for (i in rainBaseX.indices) {
            val speed = rainSpeed[i]
            // Same RAIN_SLANT ratio drives both the fall direction (x drift vs. y fall) and the streak's own
            // tilt below, so the line always points along the way it is actually moving.
            var x = (rainBaseX[i] + t * speed * RAIN_SLANT) % w; if (x < 0) x += w
            var y = (rainBaseY[i] + t * speed) % rainRange; if (y < 0) y += rainRange
            val drawY = y + rainYOffset
            val len = rainLen[i]; val base = i * 4
            rainLines[base] = x; rainLines[base + 1] = drawY; rainLines[base + 2] = x + len * RAIN_SLANT; rainLines[base + 3] = drawY + len
        }
    }
    fun wrapCloudX(baseCenterX: Float, factor: Float, t: Float): Float {
        var raw = (baseCenterX + cloudMargin + t * cloudSpeed * factor) % cloudRange; if (raw < 0) raw += cloudRange
        return raw - cloudMargin
    }
    fun wrapFogX(t: Float): Float {
        var raw = (t * fogSpeed) % fogRange; if (raw < 0) raw += fogRange
        return raw - fogMargin
    }
    /** Returns a 0f..1f envelope for the ~120ms lightning flash active at elapsed time [t], or 0f between flashes. */
    fun flashAt(t: Float): Float {
        if (flashCycle <= 0f) return 0f
        var tMod = t % flashCycle; if (tMod < 0) tMod += flashCycle
        for (start in flashStarts) {
            val local = tMod - start
            if (local in 0f..120f) return when { local < 30f -> local / 30f; local > 90f -> (120f - local) / 30f; else -> 1f }
        }
        return 0f
    }
}
