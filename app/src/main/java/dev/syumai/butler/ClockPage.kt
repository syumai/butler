package dev.syumai.butler

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Home screen page 0 (§4): the big clock, with a small weather summary top-left (tapping it jumps
 * to WeatherPage) and, only during attention-worthy states, a status line bottom-left. Time/date are
 * refreshed every tick by [MainActivity] via [updateClock]; weather via [bind] (only on refresh, not
 * every tick); status via [setStatus] (also every tick, but cheap — see [TextView.updateText]).
 */
class ClockPage(context: Context) : FrameLayout(context) {
    /** Set by MainActivity: tapping the weather mini text should switch to WeatherPage (page 1). */
    var onWeatherTap: (() -> Unit)? = null

    private val clock: TextView
    private val date: TextView
    private val weatherEmoji: TextView
    private val weatherTemp: TextView
    private val status: TextView

    // FrameLayout isn't a Context, so Ui.kt's `Context.dp()` extension needs an explicit receiver
    // everywhere else in this file; this local alias keeps call sites as terse as MainActivity's.
    private fun dp(value: Int) = context.dp(value)

    init {
        val shadowColor = 0x66000000.toInt()
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        clock = context.text(150f, "", Palette.CREAM).apply {
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            letterSpacing = -0.03f
            setShadowLayer(dp(12).toFloat(), 0f, dp(4).toFloat(), shadowColor)
        }
        center.addView(clock, LinearLayout.LayoutParams(-2, -2))
        date = context.text(24f, "", Palette.CREAM_60).apply {
            letterSpacing = 0.04f
            setShadowLayer(dp(12).toFloat(), 0f, dp(4).toFloat(), shadowColor)
        }
        center.addView(date, LinearLayout.LayoutParams(-2, -2))
        addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(6) })

        val weatherRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onWeatherTap?.invoke() }
        }
        weatherEmoji = context.text(18f, "", Palette.CREAM_30).apply { visibility = GONE }
        weatherRow.addView(weatherEmoji, LinearLayout.LayoutParams(-2, -2))
        weatherTemp = context.text(18f, context.getString(R.string.weather_placeholder_not_set), Palette.CREAM_30)
        weatherRow.addView(weatherTemp, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(weatherRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(34); topMargin = dp(22)
        })

        status = context.text(12f, "", Palette.CREAM_30).apply { visibility = GONE }
        addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(34); bottomMargin = dp(22)
        })
    }

    /** Called every tick (250ms) with the already-formatted time/date strings; cheap via [TextView.updateText]. */
    fun updateClock(timeText: String, dateText: String) { clock.updateText(timeText); date.updateText(dateText) }

    /** Weather mini text (§4): the emoji+temperature when [forecast] is available, else a short
     * "not configured" or "fetch failed" placeholder depending on [failed]. Called only when the
     * forecast actually changes (WeatherStore's refresh cadence), not every tick. */
    fun bind(forecast: Forecast?, failed: Boolean) {
        if (forecast != null) {
            val scene = WeatherScene.of(forecast.currentCode, forecast.isDay)
            weatherEmoji.text = WeatherFormat.emoji(scene)
            weatherEmoji.textSize = 18f; weatherEmoji.setTextColor(Palette.CREAM_60); weatherEmoji.visibility = VISIBLE
            weatherTemp.text = String.format(Locale.getDefault(), "%.1f℃", forecast.currentTemp)
            weatherTemp.textSize = 18f; weatherTemp.setTextColor(Palette.CREAM_60)
        } else {
            weatherEmoji.visibility = GONE
            weatherTemp.text = context.getString(if (failed) R.string.weather_fetch_failed_short else R.string.weather_placeholder_not_set)
            weatherTemp.textSize = 13f; weatherTemp.setTextColor(Palette.CREAM_30)
        }
    }

    /** Status line (§4): only ever called with a non-blank [text] for the attention-worthy statuses
     * MainActivity filters for; blank hides the line entirely (no idle/preparing hint on the clock page). */
    fun setStatus(text: String) {
        if (status.text.toString() == text) return
        status.text = text
        status.visibility = if (text.isBlank()) GONE else VISIBLE
    }

    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }
}
