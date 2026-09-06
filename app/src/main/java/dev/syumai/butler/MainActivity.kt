package dev.syumai.butler

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.*
import android.widget.*
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val weatherClient = ToolClient()
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var talk: Button
    private lateinit var end: Button
    private lateinit var approve: Button
    private lateinit var landscape: Landscape
    private var butler: ButlerView? = null
    private var scene = WeatherScene.DEFAULT
    private var weatherAt = 0L
    private var weatherInFlight = false
    private var visible = false
    private var pendingAction = AssistantService.START
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            val now = Date()
            clock.updateText(SimpleDateFormat("HH:mm", Locale.JAPAN).format(now))
            date.updateText(SimpleDateFormat("M月d日 EEEE", Locale.JAPAN).format(now))
            butler?.conversing = AssistantService.conversing
            status.updateText(AssistantService.status)
            transcript.updateText(AssistantService.transcript)
            approve.visibility = if (AssistantService.approval != null) View.VISIBLE else View.GONE
            end.visibility = if (AssistantService.conversing) View.VISIBLE else View.GONE
            if (SystemClock.elapsedRealtime() - weatherAt > 900_000 || weatherAt == 0L) refreshWeather()
            main.postDelayed(this, 250)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        home()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }
    override fun onResume() {
        super.onResume(); visible = true; main.removeCallbacks(tick); main.post(tick)
        // Rebuild the home screen if SettingsActivity changed something it reflects (background, weather region/toggle).
        if (Settings.dirty) { Settings.dirty = false; weatherAt = 0; home() }
        butler?.let { it.active = true; it.refreshMotion() }
        if (settings.enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action(AssistantService.START)
    }
    override fun onPause() { butler?.let { it.active = false; it.refreshMotion() }; visible = false; main.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { weatherClient.cancel(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(size: Float, value: String = "") = TextView(this).apply { textSize = size; text = value; setTextColor(Color.rgb(245, 239, 227)) }
    /** Rounded-rect shape used as both the button's visible background and its ripple mask. */
    private fun roundedShape(radius: Float, fill: Int = Color.WHITE, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius
        if (strokeColor != null) { setColor(Color.TRANSPARENT); setStroke(strokeWidth, strokeColor) } else setColor(fill)
    }
    private fun rippleOn(content: Drawable, radius: Float, rippleColor: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, roundedShape(radius))
    /** Material "contained" button: filled rounded background (tinted), white ripple, default press elevation from the theme's Button style. */
    private fun filledButton(label: String, textSizeSp: Float, tint: Int, clicked: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = textSizeSp; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        val radius = dp(12).toFloat()
        background = rippleOn(roundedShape(radius), radius, Color.argb(90, 255, 255, 255))
        backgroundTintList = ColorStateList.valueOf(tint)
        setOnClickListener { clicked() }
    }
    /** Material "outlined" button: 1dp stroke, transparent fill, ripple; used for secondary actions. */
    private fun outlinedButton(label: String, textSizeSp: Float, strokeColor: Int, clicked: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = textSizeSp; setTextColor(Color.rgb(245, 239, 227))
        val radius = dp(10).toFloat()
        background = rippleOn(roundedShape(radius, strokeColor = strokeColor, strokeWidth = dp(1)), radius, Color.argb(70, strokeColor.red(), strokeColor.green(), strokeColor.blue()))
        backgroundTintList = null
        setPadding(dp(16), paddingTop, dp(16), paddingBottom)
        setOnClickListener { clicked() }
    }
    private fun Int.red() = Color.red(this)
    private fun Int.green() = Color.green(this)
    private fun Int.blue() = Color.blue(this)
    /** Borderless ripple used for icon buttons, matching the theme's ?attr/selectableItemBackgroundBorderless. */
    private fun borderlessRippleBackground(): Drawable {
        val out = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return getDrawable(out.resourceId)!!
    }
    private fun home() {
        butler?.let { it.active = false; it.refreshMotion() }
        butler = null
        val root = FrameLayout(this)
        landscape = Landscape(this, settings.background, settings).apply { scene = this@MainActivity.scene }
        root.addView(landscape, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xBB102326.toInt(), 0x20102326)) }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(34), dp(20), dp(34), dp(12)) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(12f, "B U T L E R"), LinearLayout.LayoutParams(0, -2, 1f))
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings); scaleType = ImageView.ScaleType.CENTER
            background = borderlessRippleBackground()
            contentDescription = "設定"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header)
        clock = text(88f).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); includeFontPadding = false }
        val clockRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clockRow.addView(clock, LinearLayout.LayoutParams(0, -2, 1f))
        if (settings.showCharacter) {
            butler = ButlerView(this).apply {
                motionEnabled = settings.animateCharacter
                active = visible
                conversing = AssistantService.conversing
            }
            clockRow.addView(butler, LinearLayout.LayoutParams(dp(112), dp(104)))
        }
        column.addView(clockRow)
        date = text(17f); column.addView(date)
        weather = text(14f, "天気の地域を設定してください").apply { setPadding(0, dp(12), 0, 0); setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/")))
        } }
        column.addView(weather)
        transcript = text(15f).apply { maxLines = 2; setPadding(0, dp(8), 0, 0) }
        column.addView(transcript, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text(12f); column.addView(status)
        // All four controls share one fixed row height (56dp) via explicit LayoutParams height, never wrap_content,
        // so their top/bottom edges line up regardless of label length; gravity centers them within the row.
        val controlHeight = dp(56)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        talk = filledButton("話しかける", 22f, Color.argb(230, 46, 110, 118)) { action(AssistantService.TALK) }
        controls.addView(talk, LinearLayout.LayoutParams(0, controlHeight, 1f))
        end = outlinedButton("会話終了", 16f, Color.rgb(217, 198, 165)) { action(AssistantService.END) }.apply { visibility = View.GONE }
        controls.addView(end, LinearLayout.LayoutParams(dp(120), controlHeight).apply { marginStart = dp(8) })
        controls.addView(outlinedButton("出典", 16f, Color.argb(160, 217, 198, 165)) { showSources() },
            LinearLayout.LayoutParams(-2, controlHeight).apply { marginStart = dp(8) })
        approve = outlinedButton("実行を確認", 16f, Color.argb(160, 217, 198, 165)) { showApproval() }.apply { visibility = View.GONE }
        controls.addView(approve, LinearLayout.LayoutParams(-2, controlHeight).apply { marginStart = dp(8) })
        column.addView(controls)
        setContentView(root)
    }
    private fun action(action: String) {
        if (action == AssistantService.STOP) { stopService(Intent(this, AssistantService::class.java)); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        startForegroundService(Intent(this, AssistantService::class.java).setAction(action))
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) action(pendingAction)
        else Toast.makeText(this, "マイク権限が必要です。設定から再開できます。", Toast.LENGTH_LONG).show()
    }
    private fun refreshWeather() {
        if (weatherInFlight) return
        weatherAt = SystemClock.elapsedRealtime()
        val lat = settings.get("latitude").toDoubleOrNull(); val lon = settings.get("longitude").toDoubleOrNull()
        if (lat == null || lon == null) { weather.text = "天気の地域を設定してください"; return }
        weatherInFlight = true
        val place = settings.get("location", "設定地域")
        worker.execute {
            var fetchedScene: WeatherScene? = null
            val result = runCatching {
                val current = weatherClient.weather(lat, lon).getJSONObject("current")
                val code = current.getInt("weather_code")
                val isDay = current.optInt("is_day", 1) == 1
                fetchedScene = WeatherScene.of(code, isDay)
                val sky = when (code) { 0 -> "晴れ"; 1, 2 -> "晴れ時々曇り"; 3 -> "曇り"; 45, 48 -> "霧"; in 51..67, in 80..82 -> "雨"; in 71..77, 85, 86 -> "雪"; in 95..99 -> "雷雨"; else -> "天気" }
                "$place  ·  ${current.getDouble("temperature_2m")}°C  $sky\nOpen-Meteo  ·  ${current.getString("time").replace('T', ' ')}"
            }.getOrElse { "天気を取得できませんでした · Open-Meteo" }
            main.post {
                weatherInFlight = false
                if (!isDestroyed && settings.get("latitude").toDoubleOrNull() == lat && settings.get("longitude").toDoubleOrNull() == lon) {
                    weather.text = result
                    // Leave the scene unchanged on failure; only update it once a fetch actually succeeded.
                    // Debug-only: `adb shell setprop debug.butler.scene <SCENE>` overrides it for on-device checks.
                    val applied = debugSceneOverride()?.also { landscape.debugForceWeather = true } ?: fetchedScene
                    applied?.let { scene = it; landscape.scene = it }
                }
            }
        }
    }
    private fun debugSceneOverride(): WeatherScene? = if (!BuildConfig.DEBUG) null else runCatching {
        val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.scene") as String
        WeatherScene.entries.firstOrNull { it.name == prop }
    }.getOrNull()
    private fun showApproval() {
        val item = AssistantService.approval ?: return
        val id = item.optString("id")
        AlertDialog.Builder(this).setTitle("${item.optString("name")} を実行")
            .setMessage(item.optString("arguments").take(4000))
            .setPositiveButton("許可") { _, _ -> if (AssistantService.approval?.optString("id") == id) action("approve") }
            .setNegativeButton("拒否") { _, _ -> if (AssistantService.approval?.optString("id") == id) action("reject") }.show()
    }
    private fun showSources() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        runCatching {
            val content = JSONArray(AssistantService.citations)
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i); val value = SpannableString(part.getString("text"))
                val annotations = part.optJSONArray("annotations") ?: JSONArray()
                for (j in 0 until annotations.length()) {
                    val a = annotations.getJSONObject(j); val url = a.optString("url")
                    if (a.optString("type") != "url_citation" || !(url.startsWith("https://") || url.startsWith("http://"))) continue
                    val start = a.optInt("start_index"); val end = a.optInt("end_index")
                    if (start >= 0 && end > start && end <= value.length) value.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                column.addView(text(15f).apply { text = value; movementMethod = LinkMovementMethod.getInstance() })
            }
        }
        if (column.childCount == 0) column.addView(text(15f, "この会話には検索結果がありません"))
        AlertDialog.Builder(this).setTitle("検索結果と出典").setView(ScrollView(this).apply { addView(column) }).setPositiveButton("閉じる", null).show()
    }
}
