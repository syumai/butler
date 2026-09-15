package dev.syumai.butler.tools

import android.content.Context
import android.os.SystemClock
import dev.syumai.butler.DayForecast
import dev.syumai.butler.Forecast
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import dev.syumai.butler.WeatherFormat
import dev.syumai.butler.WeatherScene
import dev.syumai.butler.WeatherStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Returns the current weather and today/tomorrow forecast for the configured home region, backed by
 * [WeatherStore] (shared with the weather mini text and WeatherPage so all three read one fetch).
 * Always registered (unlike the Home Assistant tools) — with no region configured it reports
 * `region_not_configured` rather than being omitted, so the model can tell the user to set one up.
 */
class GetHomeWeatherTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "get_home_weather"
    override val busyStatus = R.string.status_checking_weather
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_get_home_weather_description))
        .put("parameters", JSONObject("""{"type":"object","properties":{}}"""))

    override fun execute(arguments: JSONObject): JSONObject {
        val lat = settings.get("latitude").toDoubleOrNull()
        val lon = settings.get("longitude").toDoubleOrNull()
        if (lat == null || lon == null) {
            return JSONObject().put("error", "region_not_configured").put("message", context.getString(R.string.tool_home_weather_error_region))
        }
        val place = settings.get("location", context.getString(R.string.weather_default_place))
        val forecast = runCatching { WeatherStore.current(client, lat, lon, SystemClock.elapsedRealtime()) }
            .getOrElse { return JSONObject().put("error", "fetch_failed").put("message", context.getString(R.string.tool_home_weather_error_fetch)) }

        fun conditionText(day: DayForecast): String {
            val (first, second) = WeatherFormat.conditionParts(day)
            val firstLabel = context.getString(WeatherFormat.labelRes(first))
            return if (second == null) firstLabel
            else context.getString(R.string.weather_condition_then, firstLabel, context.getString(WeatherFormat.labelRes(second)))
        }
        val currentConditionText = context.getString(WeatherFormat.labelRes(WeatherScene.of(forecast.currentCode, forecast.isDay)))
        return payload(forecast, place, ::conditionText, currentConditionText)
    }

    companion object {
        private val BLOCK_KEYS = listOf("00-06", "06-12", "12-18", "18-24")

        /** Pure, unit-testable: builds the tool's result JSON from [forecast] and [place]. [conditionText]
         * localizes a day's condition (first-half label, or "first then second" via
         * `R.string.weather_condition_then` when the halves differ — see [WeatherFormat.conditionParts]);
         * [currentConditionText] is the already-localized label for the current conditions. */
        fun payload(forecast: Forecast, place: String, conditionText: (DayForecast) -> String, currentConditionText: String): JSONObject {
            val current = JSONObject()
                .put("temperature_c", forecast.currentTemp)
                .put("condition", currentConditionText)
                .put("is_day", forecast.isDay)
                .put("time", forecast.currentTime)
            val days = JSONArray()
            forecast.days.forEach { day ->
                val dayJson = JSONObject()
                    .put("date", day.date)
                    .put("condition", conditionText(day))
                    .put("temp_max_c", day.tempMax)
                    .put("temp_min_c", day.tempMin)
                day.tempMaxDelta?.let { dayJson.put("temp_max_change_c", it) }
                day.tempMinDelta?.let { dayJson.put("temp_min_change_c", it) }
                val precip = JSONObject()
                BLOCK_KEYS.forEachIndexed { i, key ->
                    val value = day.precipitation.getOrElse(i) { -1 }
                    precip.put(key, if (value < 0) JSONObject.NULL else value)
                }
                dayJson.put("precipitation_probability", precip)
                days.put(dayJson)
            }
            return JSONObject().put("place", place).put("current", current).put("days", days)
        }
    }
}
