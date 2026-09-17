package dev.syumai.butler

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.ExecutorService

/**
 * The music page (design spec §6.5): shows the selected Home Assistant `media_player` entity's
 * artwork, now-playing text, progress and transport controls. Best effort, since it depends
 * entirely on the `media_player` integration Home Assistant happens to have for whatever service
 * is actually playing (no direct Spotify SDK/notification-listener integration). Shares its device
 * list fetch with [SmartHomePage] through [HomeStore]; polls every 10s while shown.
 */
class MusicPage(
    context: Context,
    private val settings: Settings,
    private val client: ToolClient,
    private val worker: ExecutorService,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var generation = 0
    private var selectedId: String? = null
    private var lastPlayers: List<JSONObject> = emptyList()
    private var lastArtworkKey: String? = null
    private var artworkGeneration = 0

    private val pollRunnable = object : Runnable {
        override fun run() { if (shown) { fetchNow(); handler.postDelayed(this, POLL_INTERVAL_MS) } }
    }
    private val tickRunnable = object : Runnable {
        override fun run() { if (shown) { updateProgress(); handler.postDelayed(this, 1000L) } }
    }

    private val messageText = context.text(15f, "", Palette.CREAM_60).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val chipsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
    private val artwork = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artworkFrame = FrameLayout(context).apply {
        val size = context.dp(250)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = roundedShape(context.dp(22).toFloat(), fill = 0xFF2E6E76.toInt())
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
    private val sourceDot = View(context).apply {
        val size = context.dp(8); layoutParams = LinearLayout.LayoutParams(size, size)
        background = roundedShape(size / 2f, fill = 0xFF1DB954.toInt())
        visibility = View.GONE
    }
    private val sourceText = context.text(13f, "", Palette.CREAM_60)
    private val titleText = context.text(30f, "").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artistAlbumText = context.text(17f, "", Palette.CREAM_60).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = ProgressBarView(context)
    private val elapsedText = context.text(12f, "0:00", Palette.CREAM_60)
    private val durationText = context.text(12f, "0:00", Palette.CREAM_60)
    private val playPauseButton = mediaButton(R.drawable.ic_play, 68, filled = true) { togglePlayPause() }
    private val muteButton = ImageButton(context).apply {
        val size = context.dp(36); layoutParams = LinearLayout.LayoutParams(size, size)
        setImageResource(R.drawable.ic_volume); scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(Palette.CREAM_60)
        background = context.borderlessRippleBackground()
        contentDescription = context.getString(R.string.music_volume)
        setOnClickListener { toggleMute() }
    }
    private val volumeSeekBar = SeekBar(context).apply {
        max = 100
        layoutParams = LinearLayout.LayoutParams(context.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
        progressTintList = ColorStateList.valueOf(Palette.CREAM_60)
        thumbTintList = ColorStateList.valueOf(Palette.CREAM)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { callAction("set_volume", (seekBar?.progress ?: 0).toString()) }
        })
    }
    private val contentColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

    init {
        val npColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val sourceRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sourceRow.addView(sourceDot, LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(8) })
        sourceRow.addView(sourceText)
        npColumn.addView(sourceRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(10) })
        npColumn.addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(4) })
        npColumn.addView(artistAlbumText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(20) })
        npColumn.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        npColumn.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(4)))
        val timesRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        timesRow.addView(elapsedText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        durationText.gravity = Gravity.END
        timesRow.addView(durationText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        npColumn.addView(timesRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6); bottomMargin = context.dp(14) })

        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(mediaButton(R.drawable.ic_skip_previous, 48, filled = false) { callAction("previous", null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(20) })
        controls.addView(playPauseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(20) })
        controls.addView(mediaButton(R.drawable.ic_skip_next, 48, filled = false) { callAction("next", null) })
        controls.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        controls.addView(muteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
        controls.addView(volumeSeekBar)
        npColumn.addView(controls)

        artworkFrame.addView(artwork, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val mediaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(artworkFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(34) })
        mediaRow.addView(npColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        contentColumn.addView(chipsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(16) })
        contentColumn.addView(mediaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = context.dp(34); rightMargin = context.dp(34); topMargin = context.dp(74); bottomMargin = context.dp(34)
        })
        addView(messageText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = context.dp(48); rightMargin = context.dp(48)
        })
        HomeStore.devices?.let { renderDevices(it) } ?: run { if (!configured()) showMessage(R.string.smart_home_not_configured) }
    }

    fun onShown() {
        shown = true
        if (!configured()) { showMessage(R.string.smart_home_not_configured); return }
        fetchNow()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        handler.postDelayed(tickRunnable, 1000L)
    }

    fun onHidden() {
        shown = false
        generation++
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(tickRunnable)
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
                    renderDevices(devices)
                }.onFailure {
                    if (HomeStore.devices == null) showMessage(R.string.smart_home_fetch_failed)
                }
            }
        }
    }

    private fun showMessage(res: Int) {
        contentColumn.visibility = View.GONE
        messageText.setText(res)
        messageText.visibility = View.VISIBLE
    }

    private fun renderDevices(devices: JSONArray) {
        val players = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }.filter { it.optString("type") == "media_player" }
        lastPlayers = players
        if (players.isEmpty()) { showMessage(R.string.music_no_players); return }
        val selected = selectPlayer(players)
        messageText.visibility = View.GONE
        contentColumn.visibility = View.VISIBLE
        if (players.size > 1) {
            chipsRow.visibility = View.VISIBLE
            chipsRow.removeAllViews()
            players.forEachIndexed { i, p ->
                val id = p.optString("id")
                val c = chip(context, p.optString("name"), id == selectedId)
                c.isClickable = true
                c.setOnClickListener { selectedId = id; renderDevices(HomeStore.devices ?: devices) }
                chipsRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i < players.size - 1) marginEnd = context.dp(8) })
            }
        } else {
            chipsRow.visibility = View.GONE
        }
        renderSelected(selected)
    }

    private fun selectPlayer(players: List<JSONObject>): JSONObject {
        players.firstOrNull { it.optString("id") == selectedId }?.let { return it }
        // Settings → Integrations "Music Assistant player" (musicPlayer), when set and present in the
        // current device list, wins over the playing/paused/first fallback below — the same one-time
        // preference (only applies while selectedId is still unset) the playing/paused checks already are.
        val configured = settings.get("musicPlayer").takeIf { it.isNotBlank() }?.let { id -> players.firstOrNull { it.optString("id") == id } }
        val chosen = configured
            ?: players.firstOrNull { it.optString("state") == "playing" }
            ?: players.firstOrNull { it.optString("state") == "paused" }
            ?: players.first()
        selectedId = chosen.optString("id")
        return chosen
    }

    private fun renderSelected(device: JSONObject) {
        val state = device.optString("state")
        val attributes = device.optJSONObject("attributes") ?: JSONObject()
        val playing = state == "playing"
        sourceDot.visibility = if (playing) View.VISIBLE else View.GONE
        sourceText.text = context.getString(R.string.music_source_format, device.optString("name"))

        val title = attributes.optString("media_title")
        if (title.isBlank()) {
            titleText.text = context.getString(R.string.music_idle)
            artistAlbumText.visibility = View.GONE
        } else {
            titleText.text = title
            val artist = attributes.optString("media_artist")
            val album = attributes.optString("media_album_name")
            val artistAlbum = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — ")
            artistAlbumText.text = artistAlbum
            artistAlbumText.visibility = if (artistAlbum.isNotBlank()) View.VISIBLE else View.GONE
        }

        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val muted = attributes.optBoolean("is_volume_muted", false)
        muteButton.imageTintList = ColorStateList.valueOf(if (muted) Palette.CREAM_30 else Palette.CREAM_60)
        volumeSeekBar.progress = (attributes.optDouble("volume_level", 0.5) * 100).toInt().coerceIn(0, 100)

        updateArtwork(attributes.optString("entity_picture"))
        updateProgress()
    }

    private fun currentSelectedDevice(): JSONObject? = lastPlayers.firstOrNull { it.optString("id") == selectedId }

    private fun updateProgress() {
        val device = currentSelectedDevice() ?: return
        val attributes = device.optJSONObject("attributes") ?: JSONObject()
        val duration = attributes.optDouble("media_duration", Double.NaN)
        val base = attributes.optDouble("media_position", Double.NaN)
        val updatedAt = attributes.optString("media_position_updated_at")
        val elapsedSincePost = if (device.optString("state") == "playing" && updatedAt.isNotBlank()) {
            parseIsoMillis(updatedAt)?.let { ((System.currentTimeMillis() - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0
        } else 0.0
        val position = (base.takeIf { !it.isNaN() } ?: 0.0) + elapsedSincePost
        val fraction = if (!duration.isNaN() && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
        progress.fraction = fraction
        progress.invalidate()
        elapsedText.text = formatTime(position)
        durationText.text = formatTime(if (duration.isNaN()) 0.0 else duration)
    }

    private fun togglePlayPause() {
        val device = currentSelectedDevice() ?: return
        callAction(if (device.optString("state") == "playing") "pause" else "play", null)
    }

    private fun toggleMute() {
        val device = currentSelectedDevice() ?: return
        val muted = device.optJSONObject("attributes")?.optBoolean("is_volume_muted", false) ?: false
        callAction(if (muted) "unmute" else "mute", null)
    }

    private fun callAction(action: String, value: String?) {
        val device = currentSelectedDevice() ?: return
        val id = device.optString("id")
        val attributes = device.optJSONObject("attributes")
        worker.execute {
            val result = runCatching { client.homeAssistantCallService(settings, id, action, value, attributes) }
            handler.post {
                result.onSuccess { handler.postDelayed({ refresh() }, 800) }
                    .onFailure { android.widget.Toast.makeText(context, R.string.smart_home_action_failed, android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun updateArtwork(path: String) {
        if (path.isBlank()) { lastArtworkKey = null; artwork.setImageDrawable(null); return }
        if (path == lastArtworkKey) return
        lastArtworkKey = path
        ArtworkCache.get(path)?.let { artwork.setImageBitmap(it); return }
        artwork.setImageDrawable(null)
        val myGeneration = ++artworkGeneration
        worker.execute {
            val bytes = runCatching { client.homeAssistantImage(settings, path) }.getOrNull()
            val bitmap = bytes?.let { decodeSampled(it, 512) }
            if (bitmap != null) ArtworkCache.put(path, bitmap)
            handler.post {
                if (myGeneration != artworkGeneration || !shown || lastArtworkKey != path) return@post
                if (bitmap != null) artwork.setImageBitmap(bitmap)
            }
        }
    }

    private fun mediaButton(drawableRes: Int, sizeDp: Int, filled: Boolean, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        val size = context.dp(sizeDp)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setImageResource(drawableRes); scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(if (filled) Palette.INK else Palette.CREAM)
        background = if (filled) rippleOn(roundedShape(size / 2f, fill = Palette.CREAM), size / 2f, 0x33000000)
        else rippleOn(roundedShape(size / 2f, fill = Color.TRANSPARENT), size / 2f, 0x33FFFFFF)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
    }
}

/** m:ss, e.g. 1:42. */
private fun formatTime(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Parses an ISO-8601 timestamp with an offset, tolerant of any number of fractional-second digits
 * (Home Assistant's `media_position_updated_at`, e.g. `2026-09-16T12:34:56.123456+09:00`), or null
 * if it can't be parsed. */
private fun parseIsoMillis(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

/** Downsamples a decoded image so neither dimension exceeds [maxSize] px, for artwork thumbnails. */
private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/** Small process-lifetime cache of decoded artwork bitmaps by their `entity_picture` URL/path, shared by [MusicPage]. */
private object ArtworkCache {
    private val cache = LinkedHashMap<String, Bitmap>()
    @Synchronized fun get(key: String): Bitmap? = cache[key]
    @Synchronized fun put(key: String, bitmap: Bitmap) {
        if (cache.size >= 20) cache.remove(cache.keys.first())
        cache[key] = bitmap
    }
}

/** A thin (4dp) rounded progress track, drawn directly rather than styling [android.widget.ProgressBar]. */
private class ProgressBarView(context: Context) : View(context) {
    var fraction = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CREAM_12 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CREAM }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (fraction > 0f) {
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }
}
