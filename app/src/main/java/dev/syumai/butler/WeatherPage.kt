package dev.syumai.butler

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen page 1 (§5.4): today's and tomorrow's forecast as two glass cards, or an empty state
 * when the region isn't configured or nothing could be fetched yet. Rebuilt (not diffed) on every
 * [bind] call — that only happens on WeatherStore's refresh cadence (15 min) or when the page is
 * first shown, never per-tick, so the extra view churn is fine even on the target device.
 */
class WeatherPage(context: Context, private val settings: Settings) : FrameLayout(context) {
    private val dateFormat = SimpleDateFormat(getStringPattern(context), Locale.getDefault())
    private val cardsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val cardA = glassCard(context)
    private val cardB = glassCard(context)
    private val footerPlace = context.text(13f, "", Palette.CREAM_30)
    private val footerAsOf = context.text(13f, "", Palette.CREAM_30)
    private val emptyTitle = context.text(16f, "", Palette.CREAM_60).apply { gravity = Gravity.CENTER }
    private val emptyHint = context.text(13f, "", Palette.CREAM_30).apply { gravity = Gravity.CENTER }
    private val emptyContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(emptyTitle, LinearLayout.LayoutParams(-2, -2))
        addView(emptyHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(8) })
        visibility = GONE
    }

    // FrameLayout isn't a Context, so Ui.kt's `Context.dp()` extension needs an explicit receiver
    // everywhere else in this file; this local alias keeps call sites as terse as MainActivity's.
    private fun dp(value: Int) = context.dp(value)

    init {
        cardsRow.addView(cardA, LinearLayout.LayoutParams(0, -1, 1f))
        cardsRow.addView(cardB, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(22) })
        addView(cardsRow, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(74); bottomMargin = dp(40); leftMargin = dp(34); rightMargin = dp(34)
        })
        addView(footerPlace, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(34); bottomMargin = dp(14)
        })
        addView(footerAsOf, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(34); bottomMargin = dp(14)
        })
        addView(emptyContainer, FrameLayout.LayoutParams(dp(480), -2, Gravity.CENTER))
    }

    fun bind(forecast: Forecast?, failed: Boolean) {
        if (forecast == null) {
            cardsRow.visibility = GONE; footerPlace.visibility = GONE; footerAsOf.visibility = GONE
            emptyContainer.visibility = VISIBLE
            if (failed) {
                emptyTitle.text = context.getString(R.string.weather_fetch_failed)
                emptyHint.visibility = GONE
            } else {
                emptyTitle.text = context.getString(R.string.weather_placeholder_not_set)
                emptyHint.text = context.getString(R.string.weather_configure_hint)
                emptyHint.visibility = VISIBLE
            }
            return
        }
        emptyContainer.visibility = GONE
        cardsRow.visibility = VISIBLE; footerPlace.visibility = VISIBLE; footerAsOf.visibility = VISIBLE

        fillCard(cardA, forecast.days.getOrNull(0))
        fillCard(cardB, forecast.days.getOrNull(1))

        val place = settings.get("location", context.getString(R.string.weather_default_place))
        footerPlace.text = "$place  ·  Open-Meteo"
        val time = forecast.currentTime
        val hhmm = if (time.length >= 16) time.substring(11, 16) else time
        footerAsOf.text = context.getString(R.string.weather_as_of, hhmm)
    }

    private fun fillCard(card: FrameLayout, day: DayForecast?) {
        card.removeAllViews()
        if (day == null) { card.visibility = View.INVISIBLE; return }
        card.visibility = View.VISIBLE
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(14)) }

        val headerText = runCatching { dateFormat.format(parseDate(day.date)) }.getOrDefault(day.date)
        content.addView(context.text(22f, headerText, Palette.CREAM))

        // Two-part "AのちB" text (issue 5): drop to 18sp and allow it to wrap to 2 lines (ellipsized) instead
        // of the normal single-line 22sp, and use the short "晴時々曇" wording for a PARTLY_CLOUDY_DAY half
        // instead of the full weather_sky_partly_cloudy — both because the combined two-condition string
        // ("雨のち晴れ時々曇り") was wide enough to run past the card's right edge.
        val (firstScene, secondScene) = WeatherFormat.conditionParts(day)
        val twoPart = secondScene != null
        val iconText = WeatherFormat.emoji(firstScene) + (secondScene?.let { WeatherFormat.emoji(it) } ?: "")
        val firstLabel = context.getString(WeatherFormat.conditionLabelRes(firstScene, twoPart))
        val conditionText = secondScene?.let { context.getString(R.string.weather_condition_then, firstLabel, context.getString(WeatherFormat.conditionLabelRes(it, true))) } ?: firstLabel
        val conditionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        conditionRow.addView(context.text(44f, iconText, Palette.CREAM))
        conditionRow.addView(context.text(if (twoPart) 18f else 22f, conditionText, Palette.CREAM).apply {
            setPadding(dp(10), 0, 0, 0)
            maxLines = if (twoPart) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f)) // weight 1: never exceeds the card's remaining width
        content.addView(conditionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // 16dp gap between the high group ("24℃[-4]") and the low group ("20℃[-3]") — see issue 6: the
        // margins below used to be set via `View.apply { layoutParams as? ... }` chained on the view
        // *before* it was added, when `layoutParams` is still null (addView() only generates default
        // LayoutParams once the view is actually attached), so every marginStart here was silently a
        // no-op and all four pieces ran together. Passing explicit LayoutParams to addView() instead
        // fixes that.
        val tempsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tempsRow.addView(tempText(Math.round(day.tempMax).toString() + "℃", Palette.CREAM))
        deltaText(day.tempMaxDelta)?.let { tempsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) }) }
        tempsRow.addView(tempText(Math.round(day.tempMin).toString() + "℃", Palette.RAIN), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        deltaText(day.tempMinDelta)?.let { tempsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) }) }
        content.addView(tempsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        content.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f)) // spacer: pushes the table to the card's bottom
        content.addView(buildDivider())
        content.addView(buildTableRow(context.getString(R.string.weather_table_time),
            listOf(R.string.weather_block_0_6, R.string.weather_block_6_12, R.string.weather_block_12_18, R.string.weather_block_18_24).map { context.getString(it) },
            14f, Palette.CREAM_60, null))
        content.addView(buildTableRow(context.getString(R.string.weather_table_precip),
            day.precipitation.map { WeatherFormat.precipitationText(it) }, 17f, Palette.CREAM, day.precipitation.toList()))

        card.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun tempText(value: String, color: Int) = context.text(36f, value, color).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private fun deltaText(delta: Int?): TextView? {
        val text = WeatherFormat.deltaText(delta)
        if (text.isEmpty()) return null
        return context.text(15f, context.getString(R.string.weather_delta_format, text), Palette.CREAM_60)
    }
    private fun buildDivider() = View(context).apply { setBackgroundColor(0x14F5EFE3) }
        .also { it.layoutParams = LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10) } }
    private fun buildTableRow(label: String, values: List<String>, valueSizeSp: Float, valueColor: Int, rawValues: List<Int>?): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(context.text(14f, label, Palette.CREAM_60), LinearLayout.LayoutParams(0, -2, 1f))
        values.forEachIndexed { i, v ->
            val heavy = rawValues != null && rawValues.getOrNull(i) != null && rawValues[i] >= 70
            row.addView(context.text(valueSizeSp, v, if (heavy) Palette.RAIN else valueColor).apply {
                gravity = Gravity.CENTER; if (heavy) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        return row
    }
    private fun parseDate(yyyyMMdd: String): Date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMMdd) ?: Date()

    private companion object {
        fun getStringPattern(context: Context): String = context.getString(R.string.home_date_pattern)
    }
}
