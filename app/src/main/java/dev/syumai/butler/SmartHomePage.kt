package dev.syumai.butler

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService

/**
 * The smart-home page (design spec §6): a 4-column scrolling grid of device tiles fetched from
 * Home Assistant, each opening a [DeviceSheet] on tap. Fetches on [onShown] and every 30s while
 * shown; [refresh] forces an immediate fetch (used after the assistant itself operates a device,
 * and by [DeviceSheet] after an on-screen action). All network calls run on [worker]; results are
 * posted back to the main thread and dropped if a newer fetch was issued or the page was hidden
 * meanwhile.
 */
class SmartHomePage(
    context: Context,
    private val settings: Settings,
    private val client: ToolClient,
    private val worker: ExecutorService,
) : FrameLayout(context) {
    /** MainActivity sets pager.locked from this (true while the device sheet is open). */
    var onSheetOpenChanged: ((Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var generation = 0
    private val pollRunnable = object : Runnable {
        override fun run() { if (shown) { fetchNow(); handler.postDelayed(this, POLL_INTERVAL_MS) } }
    }

    private val messageText = context.text(15f, "", Palette.CREAM_60).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val gridContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(gridContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val sheet = DeviceSheet(context, settings, client, worker, onOpenChanged = { open -> onSheetOpenChanged?.invoke(open) }, onRefreshNeeded = { refresh() })

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = context.dp(34); rightMargin = context.dp(34); topMargin = context.dp(70); bottomMargin = context.dp(20)
        })
        addView(messageText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = context.dp(48); rightMargin = context.dp(48)
        })
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        HomeStore.devices?.let { render(it) } ?: run { if (!configured()) showMessage(R.string.smart_home_not_configured) }
    }

    fun onShown() {
        shown = true
        if (!configured()) { showMessage(R.string.smart_home_not_configured); return }
        fetchNow()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun onHidden() {
        shown = false
        generation++
        handler.removeCallbacks(pollRunnable)
    }

    fun refresh() {
        if (!shown || !configured()) return
        fetchNow()
    }

    private fun configured(): Boolean = settings.get("haUrl").isNotBlank() && settings.secret("haToken").isNotBlank()

    private fun fetchNow() {
        val myGeneration = ++generation
        worker.execute {
            val result = runCatching { client.homeAssistantDevices(settings) }
            handler.post {
                if (myGeneration != generation || !shown) return@post
                result.onSuccess { json ->
                    val devices = json.optJSONArray("devices") ?: JSONArray()
                    HomeStore.update(devices)
                    render(devices)
                    sheet.applyFreshDevices(devices)
                }.onFailure {
                    if (HomeStore.devices == null) showMessage(R.string.smart_home_fetch_failed)
                }
            }
        }
    }

    private fun showMessage(res: Int) {
        gridContainer.removeAllViews()
        scroll.visibility = View.GONE
        messageText.setText(res)
        messageText.visibility = View.VISIBLE
    }

    private fun render(devices: JSONArray) {
        if (devices.length() == 0) { showMessage(R.string.settings_ha_devices_empty); return }
        messageText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        gridContainer.removeAllViews()
        val list = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }
        val sorted = list.sortedWith(
            compareBy(
                { it.optString("area").ifBlank { "￿" } },
                { DeviceText.TYPE_ORDER.indexOf(it.optString("type")).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { it.optString("name") },
            )
        )
        sorted.chunked(COLUMNS).forEach { row ->
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, device ->
                val params = LinearLayout.LayoutParams(0, context.dp(TILE_HEIGHT_DP), 1f)
                if (i > 0) params.marginStart = context.dp(GAP_DP)
                rowLayout.addView(tile(device), params)
            }
            // Pad an incomplete last row with invisible spacers so tiles keep a consistent width.
            repeat(COLUMNS - row.size) { i ->
                val spacer = View(context)
                val params = LinearLayout.LayoutParams(0, context.dp(TILE_HEIGHT_DP), 1f)
                if (row.size + i > 0) params.marginStart = context.dp(GAP_DP)
                rowLayout.addView(spacer, params)
            }
            gridContainer.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(GAP_DP) })
        }
    }

    private fun tile(device: JSONObject): View {
        val type = device.optString("type"); val state = device.optString("state")
        val attributes = device.optJSONObject("attributes") ?: JSONObject()
        val active = isActiveState(type, state)
        val cool = type == "climate" && state == "cool"
        val (fill, stroke) = when {
            cool -> 0x337FB2C9.toInt() to 0x4D7FB2C9.toInt()
            active -> Palette.BRASS_SOFT to 0x4DE3B865.toInt()
            else -> Palette.CREAM_12 to null
        }
        val radius = context.dp(20).toFloat()
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill)
            if (stroke != null) setStroke(context.dp(1).coerceAtLeast(1), stroke)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(12))
            background = rippleOn(shape, radius, 0x33FFFFFF)
            isClickable = true; isFocusable = true
        }
        root.addView(context.text(20f, DeviceText.kindEmoji(type, state)))
        root.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(context.text(12f, device.optString("area").ifBlank { context.getString(R.string.settings_ha_devices_no_area) }, Palette.CREAM_60))
        root.addView(TextView(context).apply {
            text = device.optString("name"); textSize = 15f; setTextColor(Palette.CREAM)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        })
        root.addView(TextView(context).apply {
            text = DeviceText.tileStateLine(context, device); textSize = 13f; setTextColor(Palette.CREAM_60)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        })
        root.setOnClickListener { sheet.open(device) }
        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
        return root
    }

    private companion object {
        const val COLUMNS = 4
        const val TILE_HEIGHT_DP = 130
        const val GAP_DP = 14
        const val POLL_INTERVAL_MS = 30_000L
    }
}
