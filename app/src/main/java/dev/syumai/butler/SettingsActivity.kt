package dev.syumai.butler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Dedicated, category-based settings screen (replaces the old settings dialog). Every change is
 * saved immediately, like the Android Settings app. Entering this screen stops the assistant
 * service (so editing keys/models doesn't race a live conversation); category changes that affect
 * the service (会話/呼びかけ/連携) restart or stop it right away, matching the old dialog's save
 * behavior. Settings.dirty flags MainActivity to rebuild its home screen for background/weather
 * changes when the user returns.
 */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val categories = listOf("会話", "呼びかけ", "天気と背景", "連携", "情報")
    private var selected = 0
    private lateinit var leftPane: LinearLayout
    private lateinit var rightPane: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = Settings(this)
        // Stop input while editing keys/models/thresholds; restarted on relevant changes or on leaving.
        stopService(Intent(this, AssistantService::class.java))
        buildUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Match MainActivity: reclaim the status/nav bar space on this small 480px-tall screen.
        if (hasFocus) window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onPause() { applyServiceState(); super.onPause() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(size: Float, value: String = "") = TextView(this).apply { textSize = size; text = value; setTextColor(Color.rgb(245, 239, 227)) }
    private fun button(label: String, clicked: () -> Unit) = android.widget.Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(170, 38, 62, 65))
        setOnClickListener { clicked() }
    }

    /** If enabled and permitted, (re)start the assistant service; otherwise stop it. Mirrors the old dialog's save/dismiss behavior. */
    private fun applyServiceState() {
        if (settings.enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, AssistantService::class.java).setAction(AssistantService.START))
        } else {
            stopService(Intent(this, AssistantService::class.java))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(21, 39, 42)) }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        topBar.addView(button("← 戻る") { finish() }, LinearLayout.LayoutParams(dp(90), dp(40)))
        topBar.addView(text(18f, "設定").apply { setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topBar)
        val divider = View(this).apply { setBackgroundColor(Color.argb(80, 255, 255, 255)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, 1))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val leftScroll = ScrollView(this).apply { addView(leftPane) }
        body.addView(leftScroll, LinearLayout.LayoutParams(dp(230), -1))
        val vDivider = View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) }
        body.addView(vDivider, LinearLayout.LayoutParams(1, -1))
        rightPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val rightScroll = ScrollView(this).apply { addView(rightPane) }
        body.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderCategories()
        renderCategory(selected)
    }

    private fun renderCategories() {
        leftPane.removeAllViews()
        categories.forEachIndexed { i, name ->
            val row = TextView(this).apply {
                text = name; textSize = 15f; setTextColor(Color.WHITE)
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setBackgroundColor(if (i == selected) Color.argb(210, 46, 110, 118) else Color.TRANSPARENT)
                isClickable = true; isFocusable = true
                setOnClickListener { if (selected != i) { selected = i; renderCategories(); renderCategory(selected) } }
            }
            leftPane.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(View(this).apply { setBackgroundColor(Color.argb(50, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
    }

    private fun addRow(container: LinearLayout, title: String, summary: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10)); minimumHeight = dp(56)
            alpha = if (enabled) 1f else 0.55f
            if (onClick != null && enabled) {
                val out = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                isClickable = true; isFocusable = true; setBackgroundResource(out.resourceId)
                setOnClickListener { onClick() }
            }
        }
        row.addView(text(16f, title))
        if (summary.isNotEmpty()) row.addView(text(13f, summary).apply { setTextColor(Color.rgb(185, 195, 195)); setPadding(0, dp(3), 0, 0) })
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        addDivider(container)
    }

    private fun addSwitchRow(container: LinearLayout, title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)); minimumHeight = dp(56) }
        row.addView(text(16f, title), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) } })
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        addDivider(container)
    }

    /** Compact single-EditText dialog (soft keyboard covers a lot on this 480px-tall screen). onSave returns false to keep the dialog open with a Toast. */
    private fun showEditDialog(title: String, initial: String, secret: Boolean, inputType: Int, onClear: (() -> Unit)? = null, onSave: (String) -> Boolean) {
        val edit = EditText(this).apply {
            setSingleLine(true); this.inputType = inputType; setTextColor(Color.WHITE)
            if (secret) hint = "変更する場合だけ入力（保存済みの値は表示しません）" else setText(initial)
        }
        val container = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(edit) }
        val builder = AlertDialog.Builder(this).setTitle(title).setView(container)
            .setPositiveButton("保存", null).setNegativeButton("キャンセル", null)
        if (onClear != null) builder.setNeutralButton("削除", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onSave(edit.text.toString().trim())) dialog.dismiss()
                else Toast.makeText(this, "入力値を確認してください", Toast.LENGTH_LONG).show()
            }
            onClear?.let { clear -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { clear(); dialog.dismiss() } }
        }
        dialog.show()
    }

    private fun renderCategory(index: Int) {
        rightPane.removeAllViews()
        when (index) {
            0 -> renderConversation()
            1 -> renderWake()
            2 -> renderWeatherBackground()
            3 -> renderIntegration()
            4 -> renderInfo()
        }
    }

    private fun renderConversation() {
        addRow(rightPane, "OpenAI APIキー", if (settings.secret("openai").isNotBlank()) "設定済み" else "未設定") {
            showEditDialog("OpenAI APIキー", "", secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                onClear = { settings.setSecret("openai", ""); applyServiceState(); renderCategory(selected) }) { value ->
                if (value.isNotBlank()) { settings.setSecret("openai", value); applyServiceState() }
                renderCategory(selected); true
            }
        }
        addRow(rightPane, "音声モデル", settings.get("model", "gpt-realtime-2.1")) {
            showEditDialog("音声モデル", settings.get("model", "gpt-realtime-2.1"), secret = false, inputType = InputType.TYPE_CLASS_TEXT) { value ->
                if (value.isBlank()) false else { settings.set("model", value); applyServiceState(); renderCategory(selected); true }
            }
        }
        addRow(rightPane, "検索モデル", settings.get("searchModel", "gpt-4.1-mini")) {
            showEditDialog("検索モデル", settings.get("searchModel", "gpt-4.1-mini"), secret = false, inputType = InputType.TYPE_CLASS_TEXT) { value ->
                if (value.isBlank()) false else { settings.set("searchModel", value); applyServiceState(); renderCategory(selected); true }
            }
        }
        addRow(rightPane, "会話終了までの無発話秒数（5〜600）", "${settings.get("timeout", "30")} 秒") {
            showEditDialog("会話終了までの無発話秒数（5〜600）", settings.get("timeout", "30"), secret = false, inputType = InputType.TYPE_CLASS_NUMBER) { value ->
                val ok = runCatching { require(value.toLong() in 5..600) }.isSuccess
                if (!ok) false else { settings.set("timeout", value); applyServiceState(); renderCategory(selected); true }
            }
        }
    }

    private fun renderWake() {
        addSwitchRow(rightPane, "呼びかけを待つ", settings.enabled) { checked -> settings.enabled = checked; applyServiceState(); renderCategory(selected) }
        addRow(rightPane, "呼びかけの言葉", "Hey Butler（現在は固定です）", enabled = false)
        addRow(rightPane, "呼びかけ検知のしきい値（0.05〜0.9、小さいほど検知しやすい）", settings.get("wakeThreshold", "0.25")) {
            showEditDialog("呼びかけ検知のしきい値（0.05〜0.9）", settings.get("wakeThreshold", "0.25"), secret = false,
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { value ->
                val ok = runCatching { require(value.toFloat() in 0.05f..0.9f) }.isSuccess
                if (!ok) false else { settings.set("wakeThreshold", value); applyServiceState(); renderCategory(selected); true }
            }
        }
    }

    private fun renderWeatherBackground() {
        addRow(rightPane, "天気の地域名（表示用）", settings.get("location").ifBlank { "未設定" }) {
            showEditDialog("天気の地域名（表示用）", settings.get("location"), secret = false, inputType = InputType.TYPE_CLASS_TEXT) { value ->
                settings.set("location", value); Settings.dirty = true; renderCategory(selected); true
            }
        }
        addRow(rightPane, "緯度（-90〜90）", settings.get("latitude").ifBlank { "未設定" }) {
            showEditDialog("緯度（-90〜90）", settings.get("latitude"), secret = false,
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) { value ->
                val ok = value.isBlank() || runCatching { require(value.toDouble() in -90.0..90.0) }.isSuccess
                if (!ok) false else { settings.set("latitude", value); Settings.dirty = true; renderCategory(selected); true }
            }
        }
        addRow(rightPane, "経度（-180〜180）", settings.get("longitude").ifBlank { "未設定" }) {
            showEditDialog("経度（-180〜180）", settings.get("longitude"), secret = false,
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) { value ->
                val ok = value.isBlank() || runCatching { require(value.toDouble() in -180.0..180.0) }.isSuccess
                if (!ok) false else { settings.set("longitude", value); Settings.dirty = true; renderCategory(selected); true }
            }
        }
        addSwitchRow(rightPane, "天気に合わせて背景を変える", settings.weatherBackground) { checked ->
            settings.weatherBackground = checked; Settings.dirty = true; renderCategory(selected)
        }
        addRow(rightPane, "背景画像を選ぶ", if (settings.background.exists()) "設定済み" else "標準のイラスト") { pick(21, "image/*") }
        addRow(rightPane, "標準に戻す", "", enabled = settings.background.exists()) {
            AlertDialog.Builder(this).setTitle("標準に戻す")
                .setMessage("背景画像を削除して標準のイラストに戻します。よろしいですか？")
                .setPositiveButton("削除") { _, _ ->
                    settings.background.delete(); Settings.dirty = true; renderCategory(selected)
                    Toast.makeText(this, "標準の背景に戻しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null).show()
        }
    }

    private fun renderIntegration() {
        addRow(rightPane, "Home Assistant URL", settings.get("haUrl").ifBlank { "未設定" }) {
            showEditDialog("Home Assistant URL（同一LAN内。ホスト名よりIP直指定の方がこの端末では安定します）", settings.get("haUrl"), secret = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { value ->
                val trimmed = value.trimEnd('/')
                val ok = trimmed.isBlank() || trimmed.startsWith("http://") || trimmed.startsWith("https://")
                if (!ok) false else { settings.set("haUrl", trimmed); applyServiceState(); renderCategory(selected); true }
            }
        }
        addRow(rightPane, "Home Assistant アクセストークン", if (settings.secret("haToken").isNotBlank()) "設定済み" else "未設定") {
            showEditDialog("Home Assistant アクセストークン", "", secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                onClear = { settings.setSecret("haToken", ""); applyServiceState(); renderCategory(selected) }) { value ->
                if (value.isNotBlank()) { settings.setSecret("haToken", value); applyServiceState() }
                renderCategory(selected); true
            }
        }
        addRow(rightPane, "Home Assistant 接続を確認", "") {
            val url = settings.get("haUrl"); val token = settings.secret("haToken")
            if (url.isBlank() || token.isBlank()) { Toast.makeText(this, "URLとトークンを設定してください", Toast.LENGTH_LONG).show() }
            else Thread {
                val message = runCatching {
                    val http = okhttp3.OkHttpClient.Builder()
                        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    http.newCall(okhttp3.Request.Builder().url("$url/api/").header("Authorization", "Bearer $token").build()).execute().use {
                        check(it.isSuccessful) { "HTTP ${it.code}" }
                        org.json.JSONObject(it.body!!.string()).optString("message", "OK")
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, message.fold({ "接続できました（$it）" }, { "接続できません: ${it.message}" }), Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        addRow(rightPane, "公開MCPサーバーURL（HTTPS）", settings.get("mcpUrl").ifBlank { "未設定" }) {
            showEditDialog("公開MCPサーバーURL（HTTPS）", settings.get("mcpUrl"), secret = false, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { value ->
                val ok = value.isBlank() || (Uri.parse(value).scheme == "https" && !Uri.parse(value).host.isNullOrBlank())
                if (!ok) false else { settings.set("mcpUrl", value); applyServiceState(); renderCategory(selected); true }
            }
        }
        addRow(rightPane, "MCP Bearer token", if (settings.secret("mcpToken").isNotBlank()) "設定済み" else "未設定") {
            showEditDialog("MCP Bearer token", "", secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                onClear = { settings.setSecret("mcpToken", ""); applyServiceState(); renderCategory(selected) }) { value ->
                if (value.isNotBlank()) { settings.setSecret("mcpToken", value); applyServiceState() }
                renderCategory(selected); true
            }
        }
    }

    private fun renderInfo() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val label = runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }.getOrDefault("Butler")
        addRow(rightPane, "アプリ名とバージョン", "$label ・ ${info?.versionName ?: "?"} (${info?.longVersionCode ?: "?"})", enabled = false)
        addRow(rightPane, "配布物と出典", "") {
            AlertDialog.Builder(this).setTitle("配布物と出典").setMessage(
                "sherpa-onnx（Apache-2.0）\nONNX Runtime（MIT）\nWebRTC\nOkHttp\nOpen-Meteo"
            ).setPositiveButton("閉じる", null).show()
        }
    }

    private fun pick(code: Int, mime: String) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime), code) }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 21 || result != RESULT_OK || data?.data == null) return
        try {
            val dest = settings.background
            val temp = File(filesDir, "import.tmp")
            contentResolver.openInputStream(data.data!!)!!.use { input ->
                temp.outputStream().use { output ->
                    val bytes = ByteArray(8192); var total = 0
                    while (true) { val n = input.read(bytes); if (n < 0) break; total += n; require(total <= 20_000_000); output.write(bytes, 0, n) }
                }
            }
            require(temp.length() > 0)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(temp.path, opts)
            require(opts.outWidth > 0 && opts.outHeight > 0)
            check(temp.renameTo(dest))
            Settings.dirty = true
            renderCategory(selected)
            Toast.makeText(this, "背景画像を変更しました", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(this, "ファイルを読み込めませんでした（上限20MB）", Toast.LENGTH_LONG).show() }
    }
}
