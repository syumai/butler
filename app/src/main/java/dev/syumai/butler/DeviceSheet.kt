package dev.syumai.butler

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService

/**
 * The device control bottom sheet (design spec §6.1): opens over [SmartHomePage]'s grid, 340dp
 * tall, sliding up from the bottom; a dim scrim above the grid closes it on tap. Body content is
 * built per Home Assistant domain (climate/light/cover/fan/humidifier/lock/media_player/vacuum/
 * switch-input_boolean/scene/script). Every control action goes through
 * [ToolClient.homeAssistantCallService] on [worker]; on success the caller is asked (via
 * [onRefreshNeeded]) to refetch after a short delay so the sheet reflects Home Assistant's actual
 * resulting state, not just the optimistic local guess.
 */
class DeviceSheet(
    context: Context,
    private val settings: Settings,
    private val client: ToolClient,
    private val worker: ExecutorService,
    private val onOpenChanged: (Boolean) -> Unit,
    private val onRefreshNeeded: () -> Unit,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var device: JSONObject? = null
    private var pendingTemp: Double? = null
    private val tempRunnable = Runnable { commitPendingTemp() }

    private val scrim = View(context).apply { setBackgroundColor(0x66000000); alpha = 0f; visibility = View.GONE; isClickable = false }
    private val sheetBody = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(0xFF0E2024.toInt())
            cornerRadii = floatArrayOf(context.dp(26).toFloat(), context.dp(26).toFloat(), context.dp(26).toFloat(), context.dp(26).toFloat(), 0f, 0f, 0f, 0f)
        }
        elevation = context.dp(16).toFloat()
        isClickable = true // swallow touches so they never fall through to the grid underneath
    }
    private val headerIcon = context.text(24f, "")
    private val headerName = context.text(20f, "")
    private val headerArea = context.text(14f, "", Palette.CREAM_60)
    private val bodyContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.setOnClickListener { close() }

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerIcon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(12) })
        val nameColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        nameColumn.addView(headerName)
        nameColumn.addView(headerArea)
        header.addView(nameColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(closeButton())

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(bodyContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Top padding 28dp (not 20dp): the settings gear sits at topMargin 20dp + 48dp tall = its
            // bottom edge is 68dp from the screen top; the sheet itself starts at (screen height - 340dp).
            // With 20dp of column padding the close button's top landed only ~6dp below the gear's
            // bottom — comfortably within one stale/imprecise tap of it. 28dp keeps at least 8dp of
            // clearance even before the gear finishes fading out (§ issue 1).
            setPadding(context.dp(34), context.dp(28), context.dp(34), context.dp(16))
        }
        column.addView(header)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(12) })
        sheetBody.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(sheetBody, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(340), Gravity.BOTTOM))
        sheetBody.translationY = context.dp(340).toFloat()
        visibility = View.GONE
    }

    val currentEntityId: String? get() = device?.optString("id")
    fun isOpen(): Boolean = device != null

    fun open(d: JSONObject) {
        pendingTemp = null
        handler.removeCallbacks(tempRunnable)
        device = d
        visibility = View.VISIBLE
        render()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(320).start()
        sheetBody.animate().translationY(0f).setDuration(320).start()
        onOpenChanged(true)
    }

    fun close() {
        if (device == null) return
        handler.removeCallbacks(tempRunnable); pendingTemp = null
        scrim.animate().alpha(0f).setDuration(320).withEndAction { scrim.visibility = View.GONE }.start()
        sheetBody.animate().translationY(context.dp(340).toFloat()).setDuration(320).withEndAction {
            visibility = View.GONE
            device = null
        }.start()
        onOpenChanged(false)
    }

    /** Re-renders from a fresh device list after a poll, when the sheet is open and that device is still in it.
     * Skipped while a climate temperature tap is debouncing so the optimistic value isn't clobbered mid-edit. */
    fun applyFreshDevices(devices: JSONArray) {
        val id = device?.optString("id") ?: return
        if (pendingTemp != null) return
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (d.optString("id") == id) { device = d; render(); return }
        }
    }

    private fun closeButton(): View = FrameLayout(context).apply {
        val size = context.dp(44)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = rippleOn(roundedShape(size / 2f, strokeColor = Palette.CREAM_30, strokeWidth = context.dp(1)), size / 2f, 0x33FFFFFF)
        addView(context.text(20f, "×").apply { gravity = Gravity.CENTER }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.smart_home_close)
        isClickable = true
        setOnClickListener { close() }
    }

    private fun render() {
        val d = device ?: return
        val type = d.optString("type"); val state = d.optString("state")
        headerIcon.text = DeviceText.kindEmoji(type, state)
        headerName.text = d.optString("name")
        headerArea.text = d.optString("area").ifBlank { context.getString(R.string.settings_ha_devices_no_area) }
        bodyContainer.removeAllViews()
        val attributes = d.optJSONObject("attributes") ?: JSONObject()
        when (type) {
            "climate" -> buildClimate(d, attributes)
            "light" -> buildLight(d, attributes)
            "cover" -> buildCover(d, attributes)
            "fan" -> buildFan(d, state, attributes)
            "humidifier" -> buildHumidifier(d, state, attributes)
            "lock" -> buildLock(d, state)
            "media_player" -> buildMediaPlayer(d, state, attributes)
            "vacuum" -> buildVacuum(d)
            "switch", "input_boolean" -> buildToggleOnly(d, state)
            "scene" -> buildRunButton(d, "activate")
            "script" -> buildRunButton(d, "run")
        }
    }

    // ---- shared building blocks -------------------------------------------------------------

    private fun sectionLabel(text: String): TextView = context.text(13f, text, Palette.CREAM_60).apply { setPadding(0, 0, 0, context.dp(8)) }

    private fun sheetChip(label: String, selected: Boolean, warm: Boolean = false, onClick: () -> Unit): TextView =
        context.text(15f, label, if (selected) Palette.INK else Palette.CREAM_60).apply {
            setPadding(context.dp(16), context.dp(9), context.dp(16), context.dp(9))
            background = roundedShape(context.dp(999).toFloat(), fill = if (selected) (if (warm) Palette.BRASS else Palette.RAIN) else Palette.CREAM_12)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun addChips(views: List<View>, marginEnd: Int = context.dp(8)) = addChipsTo(bodyContainer, views, marginEnd)

    /** Like [addChips], but into an arbitrary container — used by [buildClimate] to stack its 運転/風量/
     *  スイング sections in the sheet's right-hand column instead of directly in [bodyContainer]. */
    private fun addChipsTo(container: LinearLayout, views: List<View>, marginEnd: Int = context.dp(8), rowBottomMargin: Int = context.dp(18)) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v -> row.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i < views.size - 1) this.marginEnd = marginEnd }) }
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4); bottomMargin = rowBottomMargin })
    }

    private fun seekBar(min: Int, max: Int, progress: Int, tint: Int, onStop: (Int) -> Unit): SeekBar = SeekBar(context).apply {
        this.max = max - min
        this.progress = (progress - min).coerceIn(0, max - min)
        progressTintList = ColorStateList.valueOf(tint)
        thumbTintList = ColorStateList.valueOf(Palette.CREAM)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { onStop((seekBar?.progress ?: 0) + min) }
        })
    }

    private fun toggle(checked: Boolean, onToggle: (Boolean) -> Unit): ToggleSwitch =
        ToggleSwitch(context).apply { setChecked(checked, notify = false); this.onToggle = onToggle }

    private fun iconTransport(drawableRes: Int, sizeDp: Int, filled: Boolean, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        val size = context.dp(sizeDp)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setImageResource(drawableRes); scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(if (filled) Palette.INK else Palette.CREAM)
        background = if (filled) rippleOn(roundedShape(size / 2f, fill = Palette.CREAM), size / 2f, 0x33000000)
        else rippleOn(roundedShape(size / 2f, fill = android.graphics.Color.TRANSPARENT), size / 2f, 0x33FFFFFF)
        setOnClickListener { onClick() }
    }

    /** Calls [ToolClient.homeAssistantCallService] on [worker]; on success, asks the page to refresh
     * after 800ms (§6.1); on failure, shows [R.string.smart_home_action_failed]. */
    private fun callAction(action: String, value: String?, attributes: JSONObject? = device?.optJSONObject("attributes")) {
        val id = device?.optString("id") ?: return
        worker.execute {
            val result = runCatching { client.homeAssistantCallService(settings, id, action, value, attributes) }
            handler.post {
                result.onSuccess { handler.postDelayed({ onRefreshNeeded() }, 800) }
                    .onFailure { Toast.makeText(context, R.string.smart_home_action_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ---- per-domain bodies --------------------------------------------------------------------

    /** Climate body (design spec §6.1 / issue 2): a single horizontal row — [DialView] (210dp) on the
     *  left, a vertical +/− stepper (60dp buttons) next to it, then a right-hand column stacking the
     *  運転/風量/スイング chip sections, taking the remaining width. [bodyContainer] is already wrapped in
     *  a vertical [ScrollView] (see the constructor), so a device with many modes just scrolls instead
     *  of clipping — this row is the only thing that safety net has to hold. */
    private fun buildClimate(d: JSONObject, attributes: JSONObject) {
        val min = attributes.optDouble("min_temp", 16.0)
        val max = attributes.optDouble("max_temp", 30.0)
        val stepSize = attributes.optDouble("target_temp_step", 1.0).takeIf { it > 0.0 } ?: 1.0
        val state = d.optString("state")
        val current = attributes.optDouble("current_temperature", Double.NaN)
        val target = pendingTemp ?: attributes.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() } ?: (min + max) / 2.0
        val warm = state == "heat"

        val dial = DialView(context)
        fun renderDial(value: Double) {
            val valueText = DeviceText.formatTemp(value) ?: "--"
            val currentText = DeviceText.formatTemp(current) ?: "—"
            dial.render(value, min, max, warm, valueText, context.getString(R.string.smart_home_target_caption, currentText))
        }
        renderDial(target)

        val stepper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun step(delta: Double) {
            val next = ((pendingTemp ?: target) + delta).coerceIn(min, max)
            pendingTemp = next
            renderDial(next)
            handler.removeCallbacks(tempRunnable)
            handler.postDelayed(tempRunnable, 600)
        }
        stepper.addView(roundStepperButton("+", 60) { step(stepSize) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(12) })
        stepper.addView(roundStepperButton("−", 60) { step(-stepSize) })

        val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hvacModes = attributes.optJSONArray("hvac_modes")
        if (hvacModes != null && hvacModes.length() > 0) {
            opts.addView(sectionLabel(context.getString(R.string.smart_home_label_mode)))
            val chips = (0 until hvacModes.length()).map { i ->
                val mode = hvacModes.optString(i)
                val label = if (mode == "off") context.getString(R.string.smart_home_stop) else DeviceText.STATE_STRINGS[mode]?.let { context.getString(it) } ?: mode
                sheetChip(label, selected = state == mode, warm = mode == "heat") { callAction("set_hvac_mode", mode) }
            }
            addChipsTo(opts, chips)
        }
        val fanModes = attributes.optJSONArray("fan_modes")
        if (fanModes != null && fanModes.length() > 0) {
            opts.addView(sectionLabel(context.getString(R.string.smart_home_label_fan)))
            val currentFanMode = attributes.optString("fan_mode")
            val chips = (0 until fanModes.length()).map { i ->
                val mode = fanModes.optString(i)
                val label = if (mode == "auto") context.getString(R.string.smart_home_fan_auto) else mode
                sheetChip(label, selected = mode == currentFanMode) { callAction("set_fan_mode", mode) }
            }
            addChipsTo(opts, chips)
        }
        val swingModes = attributes.optJSONArray("swing_modes")
        if (swingModes != null && swingModes.length() > 0) {
            opts.addView(sectionLabel(context.getString(R.string.smart_home_label_swing)))
            val currentSwingMode = attributes.optString("swing_mode")
            val chips = (0 until swingModes.length()).map { i ->
                val mode = swingModes.optString(i)
                sheetChip(mode, selected = mode == currentSwingMode) { callAction("set_swing_mode", mode) }
            }
            addChipsTo(opts, chips, rowBottomMargin = 0)
        }

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(dial, LinearLayout.LayoutParams(context.dp(210), context.dp(210)).apply { marginEnd = context.dp(24) })
        row.addView(stepper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(28) })
        row.addView(opts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // A plain FrameLayout whose size comes only from the LayoutParams it's added with would stop being
    // a circle the moment a caller adds it with WRAP_CONTENT params (as the "+" button below is, for its
    // bottomMargin) instead of an explicit size — same root cause as DialView's onMeasure override (see
    // its comment): a MATCH_PARENT child under a WRAP_CONTENT parent measures against the AT_MOST spec's
    // full available size, not a natural one, stretching the button into a tall pill instead of a circle.
    // Forcing an EXACTLY [sizeDp] square here regardless of the caller's layout params avoids that.
    private class SquareButton(context: Context, private val sizeDp: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = View.MeasureSpec.makeMeasureSpec(context.dp(sizeDp), View.MeasureSpec.EXACTLY)
            super.onMeasure(spec, spec)
        }
    }

    private fun roundStepperButton(label: String, sizeDp: Int, onClick: () -> Unit): View = SquareButton(context, sizeDp).apply {
        val size = context.dp(sizeDp)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = rippleOn(roundedShape(size / 2f, strokeColor = Palette.CREAM_30, strokeWidth = context.dp(1)), size / 2f, 0x33FFFFFF)
        addView(context.text(26f, label).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun commitPendingTemp() {
        val value = pendingTemp ?: return
        callAction("set_temperature", if (value == Math.floor(value)) value.toLong().toString() else value.toString())
        pendingTemp = null
    }

    private fun buildLight(d: JSONObject, attributes: JSONObject) {
        val on = d.optString("state") == "on"
        val pct = if (on) attributes.optInt("brightness_percent", 0) else 0
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(context.text(64f, "$pct%").apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) })
        left.addView(toggle(on) { checked -> callAction(if (checked) "turn_on" else "turn_off", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(14) })
        row.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(30) })

        val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        opts.addView(sectionLabel(context.getString(R.string.smart_home_label_brightness)))
        opts.addView(seekBar(1, 100, if (pct in 1..100) pct else 50, Palette.BRASS) { v -> callAction("set_brightness", v.toString()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(14) })

        val colorModes = attributes.optJSONArray("supported_color_modes")
        var supportsColorTemp = false
        if (colorModes != null) for (i in 0 until colorModes.length()) if (colorModes.optString(i) == "color_temp") supportsColorTemp = true
        if (supportsColorTemp) {
            val minK = attributes.optInt("min_color_temp_kelvin", 2000)
            val maxK = attributes.optInt("max_color_temp_kelvin", 6500)
            val curK = attributes.optInt("color_temp_kelvin", (minK + maxK) / 2)
            opts.addView(sectionLabel(context.getString(R.string.smart_home_label_color_temp)))
            opts.addView(seekBar(minK, maxK.coerceAtLeast(minK + 1), curK, Palette.RAIN) { v -> callAction("set_color_temp", v.toString()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(14) })
        }
        opts.addView(sectionLabel(context.getString(R.string.smart_home_label_scene)))
        val minK = attributes.optInt("min_color_temp_kelvin", 2000)
        val maxK = attributes.optInt("max_color_temp_kelvin", 6500)
        val scenes = listOf(
            Triple(context.getString(R.string.smart_home_scene_read), 100, maxK),
            Triple(context.getString(R.string.smart_home_scene_relax), 40, (minK + maxK) / 2),
            Triple(context.getString(R.string.smart_home_scene_sleep), 10, minK),
        )
        opts.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            scenes.forEachIndexed { i, (label, brightness, kelvin) ->
                addView(sheetChip(label, selected = false) {
                    callAction("set_brightness", brightness.toString())
                    if (supportsColorTemp) callAction("set_color_temp", kelvin.toString())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i < scenes.size - 1) marginEnd = context.dp(8) })
            }
        })
        row.addView(opts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(row)
    }

    private fun buildCover(d: JSONObject, attributes: JSONObject) {
        val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        opts.addView(sectionLabel(context.getString(R.string.smart_home_label_position)))
        if (attributes.has("current_position")) {
            val pos = attributes.optInt("current_position", 0)
            opts.addView(seekBar(0, 100, pos, Palette.RAIN) { v -> callAction("set_position", v.toString()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(14) })
        }
        opts.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sheetChip(context.getString(R.string.smart_home_cover_open), false) { callAction("open", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
            addView(sheetChip(context.getString(R.string.smart_home_cover_half), false) { callAction("set_position", "50") }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
            addView(sheetChip(context.getString(R.string.smart_home_cover_close), false) { callAction("close", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
            addView(sheetChip(context.getString(R.string.smart_home_stop), false) { callAction("stop", null) })
        })
        bodyContainer.addView(opts)
    }

    private fun buildFan(d: JSONObject, state: String, attributes: JSONObject) {
        val on = state == "on"
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(toggle(on) { checked -> callAction(if (checked) "turn_on" else "turn_off", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(24) })
        val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        opts.addView(sectionLabel(context.getString(R.string.smart_home_label_fan)))
        opts.addView(seekBar(0, 100, attributes.optInt("percentage", 0), Palette.RAIN) { v -> callAction("set_speed", v.toString()) })
        row.addView(opts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(row)
    }

    private fun buildHumidifier(d: JSONObject, state: String, attributes: JSONObject) {
        val on = state == "on"
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(toggle(on) { checked -> callAction(if (checked) "turn_on" else "turn_off", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(24) })
        val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        opts.addView(sectionLabel(context.getString(R.string.device_kind_humidifier)))
        opts.addView(seekBar(0, 100, attributes.optInt("humidity", 50), Palette.RAIN) { v -> callAction("set_humidity", v.toString()) })
        row.addView(opts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(row)
    }

    private fun buildLock(d: JSONObject, state: String) {
        val name = d.optString("name")
        addChips(listOf(
            sheetChip(context.getString(R.string.smart_home_lock), selected = state == "locked") { callAction("lock", null) },
            sheetChip(context.getString(R.string.smart_home_unlock), selected = state == "unlocked") {
                AlertDialog.Builder(context).setMessage(context.getString(R.string.smart_home_confirm_unlock, name))
                    .setPositiveButton(context.getString(R.string.smart_home_unlock)) { _, _ -> callAction("unlock", null) }
                    .setNegativeButton(context.getString(R.string.dialog_cancel), null).show()
            },
        ))
    }

    private fun buildMediaPlayer(d: JSONObject, state: String, attributes: JSONObject) {
        val title = attributes.optString("media_title")
        val artist = attributes.optString("media_artist")
        if (title.isNotBlank() || artist.isNotBlank()) {
            bodyContainer.addView(context.text(18f, title).apply { setPadding(0, 0, 0, context.dp(2)) })
            if (artist.isNotBlank()) bodyContainer.addView(context.text(14f, artist, Palette.CREAM_60).apply { setPadding(0, 0, 0, context.dp(16)) })
        }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(iconTransport(R.drawable.ic_skip_previous, 52, false) { callAction("previous", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(16) })
        val playing = state == "playing"
        controls.addView(iconTransport(if (playing) R.drawable.ic_pause else R.drawable.ic_play, 64, true) { callAction(if (playing) "pause" else "play", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(16) })
        controls.addView(iconTransport(R.drawable.ic_skip_next, 52, false) { callAction("next", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(24) })
        val muted = attributes.optBoolean("is_volume_muted", false)
        controls.addView(ImageButton(context).apply {
            val size = context.dp(40); layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_volume); scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(if (muted) Palette.CREAM_30 else Palette.CREAM_60)
            background = context.borderlessRippleBackground()
            contentDescription = context.getString(R.string.music_volume)
            setOnClickListener { callAction(if (muted) "unmute" else "mute", null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
        val volumePct = (attributes.optDouble("volume_level", 0.5) * 100).toInt()
        controls.addView(seekBar(0, 100, volumePct, Palette.BRASS) { v -> callAction("set_volume", v.toString()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bodyContainer.addView(controls)
    }

    private fun buildVacuum(d: JSONObject) {
        addChips(listOf(
            sheetChip(context.getString(R.string.smart_home_vacuum_start), false) { callAction("start", null) },
            sheetChip(context.getString(R.string.smart_home_vacuum_pause), false) { callAction("pause", null) },
            sheetChip(context.getString(R.string.smart_home_vacuum_return), false) { callAction("return_to_base", null) },
        ))
    }

    private fun buildToggleOnly(d: JSONObject, state: String) {
        val on = state == "on"
        bodyContainer.addView(toggle(on) { checked -> callAction(if (checked) "turn_on" else "turn_off", null) })
    }

    private fun buildRunButton(d: JSONObject, action: String) {
        bodyContainer.addView(sheetChip(context.getString(R.string.smart_home_run), false) { callAction(action, null) })
    }
}

/** A 96x56dp pill toggle switch (design spec §6.1 light/fan/humidifier/switch controls): brass fill
 * with a dark knob when on, dim cream fill with a cream knob when off. */
private class ToggleSwitch(context: Context) : FrameLayout(context) {
    private val knob = View(context)
    private var checked = false
    var onToggle: ((Boolean) -> Unit)? = null
    private val widthPx = context.dp(96)
    private val heightPx = context.dp(56)
    private val knobSize = context.dp(44)
    private val pad = context.dp(6)

    init {
        layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        knob.layoutParams = LayoutParams(knobSize, knobSize).apply { leftMargin = pad; topMargin = pad }
        addView(knob)
        isClickable = true
        setOnClickListener { setChecked(!checked, notify = true) }
        applyStyle()
    }

    // Same WRAP_CONTENT-override hazard as DialView/SquareButton (see their comments): every call site
    // adds this with its own WRAP_CONTENT LayoutParams (for a margin), which would otherwise let it
    // shrink-wrap to just the knob's own bounds — nothing else here is sized wider — instead of the
    // full 96x56dp pill. Forcing the exact size regardless of the caller's layout params fixes that.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        super.onMeasure(w, h)
    }

    fun setChecked(value: Boolean, notify: Boolean) {
        checked = value
        applyStyle()
        knob.animate().translationX(if (value) (widthPx - knobSize - pad * 2).toFloat() else 0f).setDuration(150).start()
        if (notify) onToggle?.invoke(value)
    }

    private fun applyStyle() {
        background = roundedShape(heightPx / 2f, fill = if (checked) Palette.BRASS else Palette.CREAM_12)
        knob.background = roundedShape(knobSize / 2f, fill = if (checked) Palette.INK else Palette.CREAM_60)
    }
}
