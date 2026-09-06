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
        root.addView(Landscape(this, settings.background), FrameLayout.LayoutParams(-1, -1))
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
                    settings.enabled = enabled.isChecked; weatherAt = 0; dialog.dismiss(); home()
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
            val result = runCatching {
                val current = weatherClient.weather(lat, lon).getJSONObject("current")
                val code = current.getInt("weather_code")
                val sky = when (code) { 0 -> "晴れ"; 1, 2 -> "晴れ時々曇り"; 3 -> "曇り"; 45, 48 -> "霧"; in 51..67, in 80..82 -> "雨"; in 71..77, 85, 86 -> "雪"; in 95..99 -> "雷雨"; else -> "天気" }
                "$place  ·  ${current.getDouble("temperature_2m")}°C  $sky\nOpen-Meteo  ·  ${current.getString("time").replace('T', ' ')}"
            }.getOrElse { "天気を取得できませんでした · Open-Meteo" }
            main.post { weatherInFlight = false; if (!isDestroyed && settings.get("latitude").toDoubleOrNull() == lat && settings.get("longitude").toDoubleOrNull() == lon) weather.text = result }
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

/** Original code-drawn landscape; users can replace it with a local photograph. */
private class Landscape(context: android.content.Context, file: File) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmap: Bitmap? = if (file.exists()) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1000) options.inSampleSize *= 2
        options.inJustDecodeBounds = false; BitmapFactory.decodeFile(file.path, options)
    } else null
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
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
}
