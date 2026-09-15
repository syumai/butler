package dev.syumai.butler.tools

import dev.syumai.butler.DayForecast
import dev.syumai.butler.Forecast
import org.junit.Assert.*
import org.junit.Test

class GetHomeWeatherToolTest {
    private fun day(
        date: String, tempMax: Double, tempMin: Double, tempMaxDelta: Int?, tempMinDelta: Int?, precipitation: IntArray,
    ) = DayForecast(date, 61, 61, 61, tempMax, tempMin, tempMaxDelta, tempMinDelta, precipitation)

    @Test fun buildsFullPayload() {
        val forecast = Forecast(
            currentTemp = 22.6, currentCode = 61, isDay = true, currentTime = "2026-09-16T15:00",
            days = listOf(
                day("2026-09-16", 24.0, 20.0, -4, -3, intArrayOf(60, 70, 90, 80)),
                day("2026-09-17", 27.0, 19.0, 3, -1, intArrayOf(10, 20, 30, -1)),
            ),
        )
        val result = GetHomeWeatherTool.payload(forecast, "自宅", { d -> if (d.date == "2026-09-16") "雨" else "晴れ" }, "雨")

        assertEquals("自宅", result.getString("place"))
        val current = result.getJSONObject("current")
        assertEquals(22.6, current.getDouble("temperature_c"), 0.0001)
        assertEquals("雨", current.getString("condition"))
        assertTrue(current.getBoolean("is_day"))
        assertEquals("2026-09-16T15:00", current.getString("time"))

        val days = result.getJSONArray("days")
        assertEquals(2, days.length())

        val today = days.getJSONObject(0)
        assertEquals("2026-09-16", today.getString("date"))
        assertEquals("雨", today.getString("condition"))
        assertEquals(24.0, today.getDouble("temp_max_c"), 0.0001)
        assertEquals(20.0, today.getDouble("temp_min_c"), 0.0001)
        assertEquals(-4, today.getInt("temp_max_change_c"))
        assertEquals(-3, today.getInt("temp_min_change_c"))
        val todayPrecip = today.getJSONObject("precipitation_probability")
        assertEquals(60, todayPrecip.getInt("00-06"))
        assertEquals(70, todayPrecip.getInt("06-12"))
        assertEquals(90, todayPrecip.getInt("12-18"))
        assertEquals(80, todayPrecip.getInt("18-24"))

        val tomorrow = days.getJSONObject(1)
        assertEquals("晴れ", tomorrow.getString("condition"))
        assertEquals(3, tomorrow.getInt("temp_max_change_c"))
        assertEquals(-1, tomorrow.getInt("temp_min_change_c"))
        val tomorrowPrecip = tomorrow.getJSONObject("precipitation_probability")
        assertEquals(30, tomorrowPrecip.getInt("12-18"))
        assertTrue(tomorrowPrecip.isNull("18-24")) // -1 sentinel becomes JSON null
    }

    @Test fun omitsChangeKeysWhenDeltaIsNull() {
        val forecast = Forecast(
            currentTemp = 10.0, currentCode = 0, isDay = false, currentTime = "2026-09-16T02:00",
            days = listOf(day("2026-09-16", 12.0, 5.0, null, null, intArrayOf(-1, -1, -1, -1))),
        )
        val result = GetHomeWeatherTool.payload(forecast, "自宅", { "晴れ" }, "晴れ")
        val today = result.getJSONArray("days").getJSONObject(0)
        assertFalse(today.has("temp_max_change_c"))
        assertFalse(today.has("temp_min_change_c"))
        val precip = today.getJSONObject("precipitation_probability")
        listOf("00-06", "06-12", "12-18", "18-24").forEach { assertTrue(precip.isNull(it)) }
    }

    @Test fun emptyDaysListProducesEmptyDaysArray() {
        val forecast = Forecast(currentTemp = 0.0, currentCode = 0, isDay = true, currentTime = "", days = emptyList())
        val result = GetHomeWeatherTool.payload(forecast, "自宅", { "" }, "")
        assertEquals(0, result.getJSONArray("days").length())
    }
}
