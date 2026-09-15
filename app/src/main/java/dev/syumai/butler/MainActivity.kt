package dev.syumai.butler

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Home screen (§2 of the UI redesign spec): a [PagerView] of four full-screen pages (clock/weather/
 * smart home/music) with a floating tab strip, page dots and the settings gear on top, plus the
 * [ConversationView] overlay shown while [AssistantService.conversing]. MainActivity itself only
 * owns cross-cutting state (the tick loop, weather refresh/caching via [WeatherStore], settings-dirty
 * rebuilds, the RECORD_AUDIO flow, the approval/sources dialogs and debug overrides) — everything
 * page-specific lives in ClockPage/WeatherPage/SmartHomePage/MusicPage/ConversationView themselves.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = ToolClient()
    private lateinit var landscape: Landscape
    private lateinit var pager: PagerView
    private lateinit var clockPage: ClockPage
    private lateinit var weatherPage: WeatherPage
    private lateinit var smartHome: SmartHomePage
    private lateinit var music: MusicPage
    private lateinit var conversation: ConversationView
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabButtons: List<TextView>
    private lateinit var dots: List<View>
    private lateinit var dotsRow: LinearLayout
    private lateinit var settingsButton: ImageButton
    private var scene = WeatherScene.DEFAULT
    private var weatherAt = 0L
    private var weatherInFlight = false
    private var visible = false
    private var pendingAction = AssistantService.START
    private var sheetOpen = false
    private var lastTouchAt = 0L
    private var lastViewRequestSerial = 0
    private var lastHomeStateSerial = 0
    private var lastConversing = false
    // Debug-only (see debugBeginOverride()): the last debug.butler.begin value already acted on, so a
    // steady non-blank/non-"0" property value doesn't retrigger action(TALK) on every tick.
    private var lastBeginActedOn: String? = null
    /** True while ConversationView is docked (§9/§8) — its state dot/text shares the tab strip's
     *  top-left corner, so the strip fades out for as long as this is true (§ issue 8). */
    private var conversationDocked = false
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    // Set in onCreate (needs getString, so it can't be a plain field initializer — those run before
    // the Activity is attached to its Context). §4's home_date_pattern, e.g. "9月16日 (水)" / "Sep 16 (Wed)".
    private lateinit var dateFormat: SimpleDateFormat
    // Reused instead of Calendar.getInstance() every tick, just to convert `now` into hour/minute for the
    // default illustration's time-of-day palette (Landscape.minuteOfDay).
    private val cal = Calendar.getInstance()
    /** Status resource ids the clock page's bottom-left line is shown for (§4) — attention-worthy
     * states only; every other status (including the normal idle/listening/responding flow) shows
     * nothing there, unlike the old always-on status line. */
    private val attentionStatuses = setOf(
        R.string.status_mic_stopped, R.string.status_need_openai_key, R.string.status_wake_detected_need_key,
        R.string.status_wake_detect_failed, R.string.status_timeout, R.string.status_api_error,
        R.string.status_connect_failed, R.string.status_realtime_connect_failed, R.string.status_event_process_failed,
        R.string.status_audio_focus_lost, R.string.status_audio_io_stopped, R.string.status_response_failed_retry,
        R.string.status_tool_call_limit, R.string.status_mcp_connect_failed,
    )
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            val now = Date()
            clockPage.updateClock(clockFormat.format(now), dateFormat.format(now))
            clockPage.setStatus(if (AssistantService.status.text in attentionStatuses) AssistantService.status.resolve(this@MainActivity) else "")
            cal.time = now
            landscape.minuteOfDay = debugMinuteOverride() ?: (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
            if (SystemClock.elapsedRealtime() - weatherAt > 900_000 || weatherAt == 0L) refreshWeather()

            // Debug-only (§ issue 8): `adb shell setprop debug.butler.talk 1|2` drives the conversation
            // overlay with a fake exchange (status_responding, a canned user/bot transcript, no citations)
            // so it can be screenshotted on-device without exercising a real conversation; 2 also docks
            // the overlay over the weather page, matching the real auto-switch (§9) it stands in for.
            // Debug-only: `adb shell setprop debug.butler.begin 1` starts a real conversation exactly
            // like the old on-screen Talk button (removed by the redesign), since the service isn't
            // exported so `am start-foreground-service` from the shell is denied. Acts once per distinct
            // non-blank/non-"0" value; set the property back to 0 before reusing the same value again.
            val beginOverride = debugBeginOverride()
            if (beginOverride.isNullOrBlank() || beginOverride == "0") lastBeginActedOn = null
            else if (beginOverride != lastBeginActedOn) { lastBeginActedOn = beginOverride; action(AssistantService.TALK) }

            val talkOverride = debugTalkOverride()
            if (talkOverride != null) {
                conversation.update(Status(R.string.status_responding), DEBUG_FAKE_USER_TRANSCRIPT, DEBUG_FAKE_TRANSCRIPT, "", false, true)
                conversation.setDocked(talkOverride == 2)
                if (talkOverride == 2 && pager.currentPage != 1) pager.setPage(1)
            } else {
                conversation.update(AssistantService.status, AssistantService.userTranscript, AssistantService.transcript,
                    AssistantService.citations, AssistantService.approval != null, AssistantService.conversing)
            }

            if (AssistantService.viewRequestSerial != lastViewRequestSerial) {
                lastViewRequestSerial = AssistantService.viewRequestSerial
                pager.setPage(AssistantService.viewRequest)
                conversation.setDocked(true)
                // The assistant just opened this page on the user's behalf; without this, a stale
                // lastTouchAt (from before/during the conversation) could make auto-return fire almost
                // immediately, before the user has had a chance to look at what it switched to.
                lastTouchAt = SystemClock.elapsedRealtime()
            }
            if (AssistantService.homeStateSerial != lastHomeStateSerial) {
                lastHomeStateSerial = AssistantService.homeStateSerial
                smartHome.refresh(); music.refresh()
            }
            val conversingNow = AssistantService.conversing
            // Same reasoning as above: the moment a conversation ends, the page it leaves behind (e.g. one
            // the assistant switched to) should get the full 3 minutes again, not whatever was left over
            // from a stale touch before/during the conversation.
            if (lastConversing && !conversingNow) lastTouchAt = SystemClock.elapsedRealtime()
            lastConversing = conversingNow
            pager.locked = conversingNow || sheetOpen || talkOverride != null
            // Auto-return (§2): only ever fires away from the clock page, and never mid-conversation
            // (the page is likely docked/driven by the assistant right then, not idly abandoned).
            if (pager.currentPage != 0 && !conversingNow && talkOverride == null &&
                SystemClock.elapsedRealtime() - lastTouchAt > AUTO_RETURN_MS) pager.setPage(0)

            main.postDelayed(this, 250)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); settings = Settings(this)
        dateFormat = SimpleDateFormat(getString(R.string.home_date_pattern), Locale.getDefault())
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
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }
    override fun onResume() {
        super.onResume(); visible = true; lastTouchAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(tick); main.post(tick)
        // Rebuild the home screen if SettingsActivity changed something it reflects (background, weather region/toggle).
        if (Settings.dirty) { Settings.dirty = false; weatherAt = 0; home() } else pageShown(pager.currentPage)
        if (settings.enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action(AssistantService.START)
    }
    override fun onPause() {
        visible = false; main.removeCallbacks(tick)
        if (::pager.isInitialized) pageHidden(pager.currentPage)
        super.onPause()
    }
    override fun onDestroy() { client.cancel(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun home() {
        val root = FrameLayout(this)
        landscape = Landscape(this, settings.background, settings).apply { scene = this@MainActivity.scene }
        root.addView(landscape, FrameLayout.LayoutParams(-1, -1))
        // Scrim (§7): a flat wash over the whole scene, plus a stronger gradient hugging the bottom
        // edge, so the clock/date/status text stays readable against a bright midday sky without
        // darkening the sky itself the way the old left-to-right shade did.
        root.addView(View(this).apply { background = ColorDrawable(0x38102326.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00102326.toInt(), 0x00102326.toInt(), 0x8C102326.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        clockPage = ClockPage(this).apply { onWeatherTap = { pager.setPage(1) } }
        weatherPage = WeatherPage(this, settings)
        clockPage.bind(null, false); weatherPage.bind(null, false) // placeholder until the first refreshWeather() lands
        smartHome = SmartHomePage(this, settings, client, worker).apply {
            onSheetOpenChanged = { open -> sheetOpen = open; setChromeVisible(!open) }
        }
        music = MusicPage(this, settings, client, worker)
        pager = PagerView(this).apply {
            addView(clockPage, ViewGroup.LayoutParams(-1, -1)); addView(weatherPage, ViewGroup.LayoutParams(-1, -1))
            addView(smartHome, ViewGroup.LayoutParams(-1, -1)); addView(music, ViewGroup.LayoutParams(-1, -1))
            onPageChanged = { onPageChanged(it) }
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        val labels = listOf(R.string.home_tab_clock, R.string.home_tab_weather, R.string.home_tab_smart_home, R.string.home_tab_music)
        tabButtons = labels.mapIndexed { i, res -> tabButton(getString(res)) { pager.setPage(i) } }
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(17).toFloat(); setColor(0x8C08191B.toInt()); setStroke(dp(1), 0x1AFFFFFF)
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            tabButtons.forEach { addView(it) }
            alpha = 0f; visibility = View.INVISIBLE
        }
        root.addView(tabStrip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(34); topMargin = dp(18) })

        settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings); scaleType = ImageView.ScaleType.CENTER
            background = borderlessRippleBackground()
            contentDescription = getString(R.string.settings_button_description)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin = dp(34); topMargin = dp(20) })

        dots = (0 until 4).map { View(this).apply { background = roundedShape(dp(2).toFloat(), fill = Palette.CREAM_30) } }
        dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dots.forEach { dotsRow.addView(it, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(4); marginEnd = dp(4) }) }
        root.addView(dotsRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        updateDots(0); updateTabStrip(0, animated = false)

        conversation = ConversationView(this).apply {
            onEnd = { action(AssistantService.END) }
            onSources = { showSources() }
            onApprove = { showApproval() }
            onDockedChanged = { docked -> conversationDocked = docked; updateTabStrip(pager.currentPage, animated = true) }
        }
        root.addView(conversation, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }
    private fun onPageChanged(index: Int) {
        pageHidden(previousPage); previousPage = index; pageShown(index)
        updateTabStrip(index, animated = true)
        updateDots(index)
    }
    private var previousPage = 0
    private fun pageShown(index: Int) { when (index) { 2 -> smartHome.onShown(); 3 -> music.onShown() } }
    private fun pageHidden(index: Int) { when (index) { 2 -> smartHome.onHidden(); 3 -> music.onHidden() } }
    private fun updateTabStrip(index: Int, animated: Boolean) {
        tabButtons.forEachIndexed { i, button ->
            val active = i == index
            button.setTextColor(if (active) Palette.CREAM else Palette.CREAM_60)
            button.background = if (active) roundedShape(dp(999).toFloat(), fill = Palette.CREAM_12) else null
        }
        val shouldShow = index > 0 && !conversationDocked
        val target = if (shouldShow) 1f else 0f
        if (!animated) { tabStrip.alpha = target; tabStrip.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE; return }
        if (shouldShow) tabStrip.visibility = View.VISIBLE
        tabStrip.animate().alpha(target).setDuration(200).withEndAction { if (!shouldShow) tabStrip.visibility = View.INVISIBLE }.start()
    }
    private fun updateDots(index: Int) {
        dots.forEachIndexed { i, dot ->
            val active = i == index
            dot.background = roundedShape(dp(2).toFloat(), fill = if (active) Palette.BRASS else Palette.CREAM_30)
            // Reassigning layoutParams (rather than mutating width in place) triggers requestLayout() on its own.
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply { width = if (active) dp(16) else dp(5) }
        }
    }
    private fun tabButton(label: String, onClick: () -> Unit): TextView = text(14f, label, Palette.CREAM_60).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        // Guards against a stale tap landing on a tab button that's fading out because a device sheet
        // just opened over it (§ issue 1), or because the docked conversation overlay's state indicator
        // now occupies the same corner (§ issue 8) — the strip is hidden, not merely dimmed, in both cases.
        setOnClickListener { if (!sheetOpen && !conversationDocked) onClick() }
    }
    /** Hides/restores the settings gear, tab strip and page dots (§ issue 1): a [DeviceSheet] is drawn
     *  on top of the pager but *below* this chrome (all three were added to root after the pager), so
     *  without this an open sheet's own close button sits right under the gear, and a stale tap could
     *  land on either. Fades over 200ms and, for the gear, also disables clicks once hidden so a tap
     *  during/after the fade can never reach it. */
    private fun setChromeVisible(visible: Boolean) {
        val target = if (visible) 1f else 0f
        settingsButton.isEnabled = visible
        settingsButton.animate().alpha(target).setDuration(200).start()
        dotsRow.animate().alpha(target).setDuration(200).start()
        if (visible) {
            if (pager.currentPage > 0) { tabStrip.visibility = View.VISIBLE; tabStrip.animate().alpha(1f).setDuration(200).start() }
        } else {
            tabStrip.animate().alpha(0f).setDuration(200).start()
        }
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
        else Toast.makeText(this, getString(R.string.toast_mic_permission_required), Toast.LENGTH_LONG).show()
    }
    /** Refreshes [WeatherStore]'s cached [Forecast] (15 min cadence, matching the old single-current
     * fetch) and pushes it to the clock mini text, WeatherPage and Landscape's weather scene. Reads
     * [WeatherStore.forecast] after the attempt (rather than only this call's own result) so a stale
     * but still-cached forecast keeps being shown across a single failed refresh, same as before. */
    private fun refreshWeather() {
        if (weatherInFlight) return
        weatherAt = SystemClock.elapsedRealtime()
        val lat = settings.get("latitude").toDoubleOrNull(); val lon = settings.get("longitude").toDoubleOrNull()
        if (lat == null || lon == null) { clockPage.bind(null, false); weatherPage.bind(null, false); return }
        weatherInFlight = true
        worker.execute {
            val result = runCatching { WeatherStore.current(client, lat, lon, SystemClock.elapsedRealtime()) }
            main.post {
                weatherInFlight = false
                if (isDestroyed) return@post
                if (settings.get("latitude").toDoubleOrNull() != lat || settings.get("longitude").toDoubleOrNull() != lon) return@post
                val forecast = WeatherStore.forecast
                val failed = result.isFailure
                clockPage.bind(forecast, failed)
                weatherPage.bind(forecast, failed)
                // Leave the scene unchanged when nothing could be fetched at all; only update it once a
                // forecast (this attempt's or an earlier cached one) is actually available.
                val applied = debugSceneOverride()?.also { landscape.debugForceWeather = true }
                    ?: forecast?.let { WeatherScene.of(it.currentCode, it.isDay) }
                applied?.let { scene = it; landscape.scene = it }
            }
        }
    }
    private fun debugSceneOverride(): WeatherScene? = if (!BuildConfig.DEBUG) null else runCatching {
        val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.scene") as String
        WeatherScene.entries.firstOrNull { it.name == prop }
    }.getOrNull()
    // Debug-only: `adb shell setprop debug.butler.minute <0..1439>` forces the minute used for the default
    // illustration's DayPalette, to check the time-of-day gradient on-device without waiting for the clock.
    // Read every tick since SystemProperties.get is a cheap native call.
    private fun debugMinuteOverride(): Int? = if (!BuildConfig.DEBUG) null else runCatching {
        val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.minute") as String
        prop.toIntOrNull()
    }.getOrNull()
    // Debug-only (§ issue 8): `adb shell setprop debug.butler.talk 1|2` drives the conversation overlay
    // with a fake exchange so both its full and docked layouts can be checked on-device without a real
    // conversation (the overlay itself can't otherwise be exercised outside a live session). 1 = full
    // overlay; 2 = docked, also switching the pager to the weather page like the real auto-switch (§9).
    private fun debugTalkOverride(): Int? = if (!BuildConfig.DEBUG) null else runCatching {
        val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.talk") as String
        prop.toIntOrNull()?.takeIf { it == 1 || it == 2 }
    }.getOrNull()
    // Debug-only: `adb shell setprop debug.butler.begin <value>` — see the tick loop above for how the
    // raw property value is turned into a one-shot action(TALK) call.
    private fun debugBeginOverride(): String? = if (!BuildConfig.DEBUG) null else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.begin") as String
    }.getOrNull()
    private fun showApproval() {
        val item = AssistantService.approval ?: return
        val id = item.optString("id")
        AlertDialog.Builder(this).setTitle(getString(R.string.approval_title, item.optString("name")))
            .setMessage(item.optString("arguments").take(4000))
            .setPositiveButton(getString(R.string.dialog_allow)) { _, _ -> if (AssistantService.approval?.optString("id") == id) action("approve") }
            .setNegativeButton(getString(R.string.dialog_deny)) { _, _ -> if (AssistantService.approval?.optString("id") == id) action("reject") }.show()
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
        if (column.childCount == 0) column.addView(text(15f, getString(R.string.sources_empty)))
        AlertDialog.Builder(this).setTitle(getString(R.string.sources_title)).setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton(getString(R.string.dialog_close), null).show()
    }
    private companion object {
        const val AUTO_RETURN_MS = 3 * 60 * 1000L
        // Debug-only fake conversation content for `debug.butler.talk` (§ issue 8) — never shown to real
        // users, so plain literals rather than string resources match the other debug.butler.* overrides.
        const val DEBUG_FAKE_USER_TRANSCRIPT = "今日の天気は？"
        const val DEBUG_FAKE_TRANSCRIPT = "今日は一日雨で、最高24℃、最低20℃。午後は降水確率90%です。"
    }
}
