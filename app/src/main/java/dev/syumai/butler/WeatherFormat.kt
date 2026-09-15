package dev.syumai.butler

/**
 * Pure display helpers shared by ClockPage's weather mini text, WeatherPage, and
 * [dev.syumai.butler.tools.GetHomeWeatherTool]. Localized strings themselves are resolved by callers
 * via [labelRes] (a `weather_sky_*` resource id) since this object has no [android.content.Context].
 */
object WeatherFormat {
    /** One emoji per [WeatherScene], for the clock mini text and WeatherPage icons. */
    fun emoji(scene: WeatherScene): String = when (scene) {
        WeatherScene.CLEAR_DAY -> "☀️"
        WeatherScene.CLEAR_NIGHT -> "🌙"
        WeatherScene.PARTLY_CLOUDY_DAY -> "⛅"
        WeatherScene.PARTLY_CLOUDY_NIGHT -> "🌙"
        WeatherScene.CLOUDY -> "☁️"
        WeatherScene.FOG -> "🌫️"
        WeatherScene.RAIN -> "🌧️"
        WeatherScene.SNOW -> "❄️"
        WeatherScene.THUNDER -> "⛈️"
        WeatherScene.DEFAULT -> "🌡️"
    }

    /** The existing `weather_sky_*` string resource for [scene] (moved here from
     * `MainActivity.skyLabelRes`, keyed by [WeatherScene] instead of a raw WMO code). Night scenes
     * share their day counterpart's label — the sky-condition wording doesn't depend on day/night,
     * only the emoji does. */
    fun labelRes(scene: WeatherScene): Int = when (scene) {
        WeatherScene.CLEAR_DAY, WeatherScene.CLEAR_NIGHT -> R.string.weather_sky_clear
        WeatherScene.PARTLY_CLOUDY_DAY, WeatherScene.PARTLY_CLOUDY_NIGHT -> R.string.weather_sky_partly_cloudy
        WeatherScene.CLOUDY -> R.string.weather_sky_cloudy
        WeatherScene.FOG -> R.string.weather_sky_fog
        WeatherScene.RAIN -> R.string.weather_sky_rain
        WeatherScene.SNOW -> R.string.weather_sky_snow
        WeatherScene.THUNDER -> R.string.weather_sky_thunder
        WeatherScene.DEFAULT -> R.string.weather_sky_default
    }

    /** [labelRes], except when [partOfTwoPartCondition] is true and [scene] is PARTLY_CLOUDY_DAY, which
     *  uses the shorter `weather_short_partly_cloudy` label instead — used only when composing a two-part
     *  "AのちB" condition text (§5.4 / issue 5), where the full "晴れ時々曇り" wording made the combined
     *  string too wide for the card. Elsewhere (a single, one-part condition) [labelRes] is used as-is. */
    fun conditionLabelRes(scene: WeatherScene, partOfTwoPartCondition: Boolean): Int =
        if (partOfTwoPartCondition && scene == WeatherScene.PARTLY_CLOUDY_DAY) R.string.weather_short_partly_cloudy else labelRes(scene)

    /** [day]'s first-half-day scene, and its second-half scene only when that half's dominant category
     * differs from the first (both always categorized as day, matching how [Forecast.dominant] derived
     * `codeFirstHalf`/`codeSecondHalf` in the first place). Callers combine these via
     * `R.string.weather_condition_then` when the second is non-null, else use the first alone. */
    fun conditionParts(day: DayForecast): Pair<WeatherScene, WeatherScene?> {
        val first = WeatherScene.of(day.codeFirstHalf, true)
        val second = WeatherScene.of(day.codeSecondHalf, true)
        return first to second.takeIf { it != first }
    }

    /** "+2"/"-4"/"0" for a known delta (via `R.string.weather_delta_format`'s `%1$s`), "" when [delta]
     * is null (previous day unavailable) so callers can skip rendering it entirely. */
    fun deltaText(delta: Int?): String = when {
        delta == null -> ""
        delta > 0 -> "+$delta"
        else -> delta.toString() // "-4" already carries its sign; 0 renders as "0"
    }

    /** "60%", or an en dash when [value] is -1 (no data for that 6-hour block; see [DayForecast.precipitation]). */
    fun precipitationText(value: Int): String = if (value < 0) "–" else "$value%"
}
