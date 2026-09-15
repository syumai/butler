package dev.syumai.butler

import org.json.JSONArray
import org.json.JSONObject

/**
 * One day's forecast, derived from an Open-Meteo response's `daily`/`hourly` arrays for that date
 * (see [Forecast.parse]). [codeFirstHalf]/[codeSecondHalf] are each one representative WMO code for
 * the dominant [WeatherScene] category among that half-day's hourly rows (00-11 / 12-23), falling
 * back to [code] (the daily `weather_code`) when no hourly rows matched the date. [precipitation]
 * holds the max hourly `precipitation_probability` per 6-hour block (0-6/6-12/12-18/18-24), -1 when
 * a block has no data.
 */
data class DayForecast(
    val date: String,
    val code: Int,
    val codeFirstHalf: Int,
    val codeSecondHalf: Int,
    val tempMax: Double,
    val tempMin: Double,
    val tempMaxDelta: Int?,
    val tempMinDelta: Int?,
    val precipitation: IntArray,
) {
    // A compiler-generated equals/hashCode would compare `precipitation` by reference (arrays don't
    // override equals), which breaks the structural equality tests and callers expect from a data
    // class; override both here using contentEquals/contentHashCode instead.
    override fun equals(other: Any?): Boolean = other is DayForecast && date == other.date && code == other.code &&
        codeFirstHalf == other.codeFirstHalf && codeSecondHalf == other.codeSecondHalf && tempMax == other.tempMax &&
        tempMin == other.tempMin && tempMaxDelta == other.tempMaxDelta && tempMinDelta == other.tempMinDelta &&
        precipitation.contentEquals(other.precipitation)
    override fun hashCode(): Int = java.util.Objects.hash(date, code, codeFirstHalf, codeSecondHalf, tempMax, tempMin, tempMaxDelta, tempMinDelta, precipitation.contentHashCode())
}

/**
 * A parsed Open-Meteo forecast response: the current conditions plus [days] (today and tomorrow, in
 * the API's own timezone since the request is sent with `timezone=auto`). Pure Kotlin (`org.json`
 * only) so [parse] is unit-testable without Robolectric; see [ToolClient.forecast] for the request
 * this parses and [WeatherStore] for the in-memory cache built on top of it.
 */
data class Forecast(
    val currentTemp: Double,
    val currentCode: Int,
    val isDay: Boolean,
    val currentTime: String,
    val days: List<DayForecast>,
) {
    companion object {
        /** Tie-break order for [dominant]: more severe categories win a tie in hourly-code frequency.
         * Night categories never occur here since half-day dominance is always categorized as day
         * (see [dominant]); DEFAULT (unrecognized code) is least severe/least informative, last. */
        private val SEVERITY = listOf(
            WeatherScene.THUNDER, WeatherScene.SNOW, WeatherScene.RAIN, WeatherScene.FOG,
            WeatherScene.CLOUDY, WeatherScene.PARTLY_CLOUDY_DAY, WeatherScene.CLEAR_DAY, WeatherScene.DEFAULT,
        )

        private fun dateOf(time: String): String = if (time.length >= 10) time.substring(0, 10) else time
        private fun hourOf(time: String): Int? = if (time.length >= 13) time.substring(11, 13).toIntOrNull() else null

        /** Null-safe [JSONArray] element access: null when [array] is null, [index] is out of range, or the
         * element itself is JSON null — never throws, per Open-Meteo's habit of reporting missing hours as null
         * rather than omitting them. */
        private fun intAt(array: JSONArray?, index: Int): Int? {
            if (array == null || index < 0 || index >= array.length() || array.isNull(index)) return null
            return array.optInt(index)
        }
        private fun doubleAt(array: JSONArray?, index: Int): Double? {
            if (array == null || index < 0 || index >= array.length() || array.isNull(index)) return null
            val value = array.optDouble(index, Double.NaN)
            return if (value.isNaN()) null else value
        }

        /** The dominant [WeatherScene] category (always categorized as day, per §5.1 of the design spec —
         * this is a summary of *conditions*, not a day/night indicator) among [codes] (one half-day's hourly
         * `weather_code`s), ties broken by [SEVERITY]; returns one representative raw code seen for the
         * winning category, or [fallback] (the daily code) when [codes] is empty. */
        private fun dominant(codes: List<Int>, fallback: Int): Int {
            if (codes.isEmpty()) return fallback
            val byScene = codes.groupBy { WeatherScene.of(it, true) }
            val maxCount = byScene.values.maxOf { it.size }
            val tied = byScene.keys.filter { byScene.getValue(it).size == maxCount }
            val winner = tied.minByOrNull { scene -> SEVERITY.indexOf(scene).let { if (it < 0) Int.MAX_VALUE else it } } ?: tied.first()
            return byScene.getValue(winner).first()
        }

        fun parse(json: JSONObject): Forecast {
            val current = json.optJSONObject("current") ?: JSONObject()
            val currentTemp = current.optDouble("temperature_2m", Double.NaN).let { if (it.isNaN()) 0.0 else it }
            val currentCode = current.optInt("weather_code", 0)
            val isDay = current.optInt("is_day", 1) == 1
            val currentTime = current.optString("time", "")

            val daily = json.optJSONObject("daily")
            val dailyTimes = daily?.optJSONArray("time") ?: JSONArray()
            val dailyCodes = daily?.optJSONArray("weather_code")
            val dailyMax = daily?.optJSONArray("temperature_2m_max")
            val dailyMin = daily?.optJSONArray("temperature_2m_min")

            val hourly = json.optJSONObject("hourly")
            val hourlyTimes = hourly?.optJSONArray("time") ?: JSONArray()
            val hourlyCodes = hourly?.optJSONArray("weather_code")
            val hourlyPrecip = hourly?.optJSONArray("precipitation_probability")

            // "today" is the daily entry whose date matches current.time's date; when current is missing
            // or doesn't line up with any daily entry (shouldn't normally happen), fall back to index 1
            // (the API always returns yesterday/today/tomorrow in that order for past_days=1&forecast_days=2).
            val currentDate = dateOf(currentTime)
            var todayIndex = (0 until dailyTimes.length()).firstOrNull { dailyTimes.optString(it) == currentDate } ?: -1
            if (todayIndex < 0) todayIndex = if (dailyTimes.length() > 1) 1 else 0

            fun dayAt(index: Int): DayForecast? {
                if (index < 0 || index >= dailyTimes.length()) return null
                val date = dailyTimes.optString(index)
                val code = intAt(dailyCodes, index) ?: 0
                val curMax = doubleAt(dailyMax, index); val curMin = doubleAt(dailyMin, index)
                val prevMax = doubleAt(dailyMax, index - 1); val prevMin = doubleAt(dailyMin, index - 1)
                val tempMaxDelta = if (curMax != null && prevMax != null) Math.round(curMax - prevMax).toInt() else null
                val tempMinDelta = if (curMin != null && prevMin != null) Math.round(curMin - prevMin).toInt() else null

                val firstHalfCodes = mutableListOf<Int>()
                val secondHalfCodes = mutableListOf<Int>()
                val blocks = intArrayOf(-1, -1, -1, -1)
                for (i in 0 until hourlyTimes.length()) {
                    val time = hourlyTimes.optString(i)
                    if (time.isEmpty() || dateOf(time) != date) continue
                    val hour = hourOf(time) ?: continue
                    intAt(hourlyCodes, i)?.let { hc -> if (hour in 0..11) firstHalfCodes.add(hc) else if (hour in 12..23) secondHalfCodes.add(hc) }
                    val blockIndex = hour / 6
                    if (blockIndex in blocks.indices) intAt(hourlyPrecip, i)?.let { p -> if (p > blocks[blockIndex]) blocks[blockIndex] = p }
                }
                return DayForecast(date, code, dominant(firstHalfCodes, code), dominant(secondHalfCodes, code),
                    curMax ?: 0.0, curMin ?: 0.0, tempMaxDelta, tempMinDelta, blocks)
            }

            val days = listOfNotNull(dayAt(todayIndex), dayAt(todayIndex + 1))
            return Forecast(currentTemp, currentCode, isDay, currentTime, days)
        }
    }
}
