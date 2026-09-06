package dev.syumai.butler

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
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
        if (settings.enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action(AssistantService.START)
    }
    override fun onPause() { visible = false; main.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { weatherClient.cancel(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(size: Float, value: String = "") = TextView(this).apply { textSize = size; text = value; setTextColor(Color.rgb(245, 239, 227)) }
    private fun button(label: String, clicked: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(170, 38, 62, 65))
        setOnClickListener { clicked() }
    }
    private fun home() {
        val root = FrameLayout(this)
        landscape = Landscape(this, settings.background, settings).apply { scene = this@MainActivity.scene }
        root.addView(landscape, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xBB102326.toInt(), 0x20102326)) }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(34), dp(20), dp(34), dp(12)) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(12f, "B U T L E R"), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("設定") { showSettings() }, LinearLayout.LayoutParams(dp(72), dp(42)))
        column.addView(header)
        clock = text(88f).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); includeFontPadding = false }
        column.addView(clock)
        date = text(17f); column.addView(date)
        weather = text(14f, "天気の地域を設定してください").apply { setPadding(0, dp(12), 0, 0); setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/")))
        } }
        column.addView(weather)
        transcript = text(15f).apply { maxLines = 2; setPadding(0, dp(8), 0, 0) }
        column.addView(transcript, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text(12f); column.addView(status)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        talk = Button(this).apply {
            text = "話しかける"; isAllCaps = false; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(230, 46, 110, 118))
            setOnClickListener { action(AssistantService.TALK) }
        }
        controls.addView(talk, LinearLayout.LayoutParams(0, dp(64), 1f))
        end = button("会話終了") { action(AssistantService.END) }.apply { textSize = 16f; visibility = View.GONE }
        controls.addView(end, LinearLayout.LayoutParams(dp(120), dp(64)).apply { marginStart = dp(8) })
        controls.addView(button("出典") { showSources() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        approve = button("実行を確認") { showApproval() }; approve.visibility = View.GONE
        controls.addView(approve, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
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
    private fun showSettings() {
        // Stop input while editing keys or replacing the model; resume only after saving.
        stopService(Intent(this, AssistantService::class.java))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        val fields = mutableMapOf<String, EditText>()
        fun field(key: String, label: String, fallback: String = "", secret: Boolean = false) {
            form.addView(text(13f, label))
            val edit = EditText(this).apply {
                setSingleLine(true); setTextColor(Color.WHITE)
                inputType = if (secret) 129 else 1
                setText(if (secret) "" else settings.get(key, fallback))
                if (secret) hint = "変更する場合だけ入力（保存済みの値は表示しません）"
            }; fields[key] = edit; form.addView(edit)
        }
        field("openai", "OpenAI APIキー", secret = true)
        field("model", "音声モデル", "gpt-realtime-2.1")
        field("searchModel", "検索モデル", "gpt-4.1-mini")
        field("timeout", "会話終了までの無発話秒数（5〜600）", "30")
        form.addView(text(13f, "呼びかけ（Wake word）"))
        val phrase = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, WakePhrase.entries.map { it.label })
            setSelection(settings.wakePhrase.ordinal)
        }
        form.addView(phrase)
        field("wakeThreshold", "呼びかけ検知のしきい値（0.05〜0.9、小さいほど検知しやすい）", "0.25")
        field("location", "天気の地域名（表示用）")
        field("latitude", "緯度（-90〜90）")
        field("longitude", "経度（-180〜180）")
        field("mcpUrl", "公開MCPサーバーURL（任意・HTTPS）")
        field("mcpToken", "MCP Bearer token（必要な場合）", secret = true)
        val enabled = Switch(this).apply { text = "選択した呼びかけを待つ"; isChecked = settings.enabled }; form.addView(enabled)
        val weatherBg = Switch(this).apply { text = "天気に合わせて背景を変える"; isChecked = settings.weatherBackground }; form.addView(weatherBg)
        form.addView(text(12f, "英語の発音で呼びかけてください。モデル同梱・登録不要・端末内検知のみ。"))
        form.addView(button("背景画像を選ぶ") { pick(21, "image/*") })
        form.addView(button("保存済みAPIキーとMCP認証を削除") {
            listOf("openai", "mcpToken").forEach { settings.setSecret(it, "") }
            Toast.makeText(this, "認証情報を削除しました", Toast.LENGTH_SHORT).show()
        })
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("Butler 設定").setView(scroll).setPositiveButton("保存", null).setNegativeButton("閉じる", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val timeout = fields.getValue("timeout").text.toString().toLong(); require(timeout in 5..600)
                    val threshold = fields.getValue("wakeThreshold").text.toString().toFloat(); require(threshold in 0.05f..0.9f)
                    val lat = fields.getValue("latitude").text.toString(); val lon = fields.getValue("longitude").text.toString()
                    require((lat.isBlank() && lon.isBlank()) || (lat.toDouble() in -90.0..90.0 && lon.toDouble() in -180.0..180.0))
                    val url = fields.getValue("mcpUrl").text.toString()
                    require(url.isBlank() || (Uri.parse(url).scheme == "https" && !Uri.parse(url).host.isNullOrBlank()))
                    fields.forEach { (key, edit) ->
                        if (key in listOf("openai", "mcpToken")) { if (edit.text.isNotBlank()) settings.setSecret(key, edit.text.toString().trim()) }
                        else settings.set(key, edit.text.toString().trim())
                    }
                    settings.set("wakePhrase", WakePhrase.entries[phrase.selectedItemPosition].name)
                    settings.enabled = enabled.isChecked; settings.weatherBackground = weatherBg.isChecked
                    weatherAt = 0; dialog.dismiss(); home()
                    if (settings.enabled) action(AssistantService.START)
                } catch (_: Exception) { Toast.makeText(this, "数値の範囲、緯度経度、HTTPS URLを確認してください", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.setOnDismissListener { if (settings.enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action(AssistantService.START) }
        dialog.show()
    }
    private fun pick(code: Int, mime: String) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime), code) }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 21 || result != RESULT_OK || data?.data == null) return
        try {
            stopService(Intent(this, AssistantService::class.java))
            val dest = settings.background
            val temp = File(filesDir, "import.tmp")
            contentResolver.openInputStream(data.data!!)!!.use { input ->
                temp.outputStream().use { output ->
                    val bytes = ByteArray(8192); var total = 0
                    while (true) { val n = input.read(bytes); if (n < 0) break; total += n; require(total <= 20_000_000); output.write(bytes, 0, n) }
                }
            }
            require(temp.length() > 0)
            if (request == 21) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(temp.path, opts)
                require(opts.outWidth > 0 && opts.outHeight > 0)
            }
            check(temp.renameTo(dest)); home()
            Toast.makeText(this, "読み込みました。設定を保存してください", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "ファイルを読み込めませんでした（上限20MB）", Toast.LENGTH_LONG).show() }
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
                    fetchedScene?.let { scene = it; landscape.scene = it }
                }
            }
        }
    }
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

/** Original code-drawn landscape; users can replace it with a local photograph, or with a weather-linked scene. */
private class Landscape(context: android.content.Context, file: File, private val settings: Settings) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmap: Bitmap? = if (file.exists()) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1000) options.inSampleSize *= 2
        options.inJustDecodeBounds = false; BitmapFactory.decodeFile(file.path, options)
    } else null
    // Weather-linked background; only used when the setting is on and the scene is known. Rendering
    // precedence: weather scene > imported photo > default illustration (unchanged, pixel-identical).
    var scene: WeatherScene = WeatherScene.DEFAULT
        set(value) { field = value; invalidate() }
    private var art: SceneArt? = null
    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val w = width.toFloat(); val h = height.toFloat()
        if (settings.weatherBackground && scene != WeatherScene.DEFAULT) { drawScene(canvas, w, h); return }
        bitmap?.let {
            val scale = maxOf(w / it.width, h / it.height); val bw = it.width * scale; val bh = it.height * scale
            canvas.drawBitmap(it, null, RectF((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2), paint); return
        }
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF254C50.toInt(), 0xFF81988D.toInt(), 0xFFE7BF8D.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        paint.color = 0xFFE7D7A9.toInt(); canvas.drawCircle(w*.78f, h*.28f, h*.105f, paint)
        fun ridge(color: Int, base: Float, peak: Float, shift: Float) {
            paint.color = color
            val p = Path().apply { moveTo(0f, h*base); cubicTo(w*.25f, h*(base-.12f), w*(.42f+shift), h*peak, w*.67f, h*(base-.08f)); cubicTo(w*.84f, h*(base+.1f), w*.9f, h*(peak+.1f), w, h*base); lineTo(w,h); lineTo(0f,h); close() }
            canvas.drawPath(p, paint)
        }
        ridge(0xFF78918A.toInt(), .65f, .24f, .1f)
        ridge(0xFF446B68.toInt(), .8f, .5f, -.15f)
        ridge(0xFF1A4247.toInt(), 1f, .56f, .2f)
    }
    // Weather scenes are code-drawn and static (no animation, cheap for a 32-bit ARM device). The
    // precomputed geometry (SceneArt) is rebuilt only when the scene or the view size changes.
    private fun drawScene(canvas: Canvas, w: Float, h: Float) {
        var a = art
        if (a == null || a.scene != scene || a.w != w || a.h != h) { a = SceneArt(scene, w, h); art = a }
        paint.shader = LinearGradient(0f, 0f, w, h, a.sky, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        a.sun?.let { paint.color = a.sunColor; canvas.drawCircle(it[0], it[1], it[2], paint) }
        a.moon?.let { m ->
            paint.color = 0xFFEFEFE0.toInt(); canvas.drawCircle(m[0], m[1], m[2], paint)
            // A second, sky-colored circle offset over the disc turns it into a crescent.
            paint.color = a.sky[0]; canvas.drawCircle(m[0] + m[2] * .5f, m[1] - m[2] * .3f, m[2] * .92f, paint)
        }
        for (star in a.stars) { paint.color = 0xFFF5F3E0.toInt(); canvas.drawCircle(star[0], star[1], star[2], paint) }
        for (cloud in a.clouds) { paint.color = a.cloudColor; for (c in cloud) canvas.drawCircle(c[0], c[1], c[2], paint) }
        if (a.fogBand) { paint.color = 0x66FFFFFF.toInt(); canvas.drawRect(0f, h * .55f, w, h * .68f, paint) }
        for (i in a.ridges.indices) {
            a.ridgeCaps[i]?.let { paint.color = a.capColor; canvas.drawPath(it, paint) }
            paint.color = a.ridgeColors[i]; paint.alpha = a.ridgeAlphas[i]; canvas.drawPath(a.ridges[i], paint)
        }
        if (a.rainLines.isNotEmpty()) { paint.color = 0xAAB9D6E0.toInt(); paint.strokeWidth = 2f; canvas.drawLines(a.rainLines, paint) }
        for (dot in a.snowDots) { paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(dot[0], dot[1], dot[2], paint) }
        a.lightning?.let { paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFFF5E9A0.toInt(); canvas.drawPath(it, paint); paint.style = Paint.Style.FILL }
    }
}

/** Precomputed, deterministic geometry for one weather scene at one view size; rebuilt only on change. */
private class SceneArt(val scene: WeatherScene, val w: Float, val h: Float) {
    val sky: IntArray
    var sun: FloatArray? = null // cx, cy, r
    var sunColor = 0
    var moon: FloatArray? = null // cx, cy, r
    val stars = mutableListOf<FloatArray>() // cx, cy, r
    val clouds = mutableListOf<List<FloatArray>>() // each cloud is a union of circles: cx, cy, r
    var cloudColor = 0
    var fogBand = false
    val ridges = mutableListOf<Path>()
    val ridgeColors = mutableListOf<Int>()
    val ridgeAlphas = mutableListOf<Int>()
    val ridgeCaps = mutableListOf<Path?>()
    var capColor = 0
    var rainLines = FloatArray(0) // x1, y1, x2, y2 per streak, for a single drawLines() call
    val snowDots = mutableListOf<FloatArray>() // cx, cy, r
    var lightning: Path? = null
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
        fun cloud(cx: Float, cy: Float, scale: Float) = listOf(
            floatArrayOf(cx, cy, .09f * h * scale),
            floatArrayOf(cx - .07f * w * scale, cy + .015f * h, .065f * h * scale),
            floatArrayOf(cx + .075f * w * scale, cy + .01f * h, .07f * h * scale),
            floatArrayOf(cx + .02f * w * scale, cy - .03f * h, .06f * h * scale)
        )
        fun addClouds(count: Int, color: Int) {
            cloudColor = color
            repeat(count) { clouds.add(cloud(w * (.15f + rnd.nextFloat() * .7f), h * (.12f + rnd.nextFloat() * .22f), .8f + rnd.nextFloat() * .5f)) }
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
            val lines = FloatArray(count * 4)
            for (i in 0 until count) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h * .85f; val len = h * (.05f + rnd.nextFloat() * .05f)
                lines[i * 4] = x; lines[i * 4 + 1] = y; lines[i * 4 + 2] = x - len * .28f; lines[i * 4 + 3] = y + len
            }
            rainLines = lines
        }
        fun addSnow(count: Int) { repeat(count) { snowDots.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .9f, 1.5f + rnd.nextFloat() * 2.5f)) } }
        when (scene) {
            WeatherScene.CLEAR_DAY -> {
                sky = intArrayOf(0xFF3E7BC4.toInt(), 0xFF7FB8D9.toInt(), 0xFFF2C879.toInt())
                sun = floatArrayOf(w * .78f, h * .28f, h * .105f); sunColor = 0xFFE7D7A9.toInt()
                addRidges(listOf(0xFF8FAE6E.toInt(), 0xFF5E8C4E.toInt(), 0xFF355B33.toInt()))
            }
            WeatherScene.CLEAR_NIGHT -> {
                sky = intArrayOf(0xFF060B1F.toInt(), 0xFF11213F.toInt(), 0xFF1C2F52.toInt())
                moon = floatArrayOf(w * .76f, h * .24f, h * .085f)
                repeat(24) { stars.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .6f, 1f + rnd.nextFloat() * 1.8f)) }
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
                repeat(16) { stars.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .55f, 1f + rnd.nextFloat() * 1.8f)) }
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
            }
            WeatherScene.DEFAULT -> sky = intArrayOf(0xFF254C50.toInt(), 0xFF81988D.toInt(), 0xFFE7BF8D.toInt()) // unused: caller skips DEFAULT
        }
    }
}
