package dev.syumai.butler

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForecastTest {
    private val yesterday = "2026-09-15"
    private val today = "2026-09-16"
    private val tomorrow = "2026-09-17"

    /** Builds one day's 24 hourly rows for [date]: [codes] and [precip] (null = missing/JSON null) are
     * each 24 values, hour 0..23. */
    private fun hourlyDay(date: String, codes: List<Int>, precip: List<Int?>, times: JSONArray, codesOut: JSONArray, precipOut: JSONArray) {
        for (hour in 0..23) {
            times.put("%sT%02d:00".format(date, hour))
            codesOut.put(codes[hour])
            val p = precip[hour]
            if (p == null) precipOut.put(JSONObject.NULL) else precipOut.put(p)
        }
    }

    private fun uniform(value: Int): List<Int> = List(24) { value }
    private fun uniformP(value: Int?): List<Int?> = List(24) { value }

    /** A realistic 3-day (yesterday/today/tomorrow) fixture, matching the shape of ToolClient.forecast's
     * response. today's hours are deliberately mixed to exercise dominance/tie-break and per-block max. */
    private fun fixture(
        currentTime: String = "${today}T15:00",
        dailyTimes: List<String> = listOf(yesterday, today, tomorrow),
        dailyCodes: List<Int> = listOf(3, 61, 0),
        dailyMax: List<Double> = listOf(25.0, 24.0, 27.0),
        dailyMin: List<Double> = listOf(18.0, 20.0, 19.0),
    ): JSONObject {
        val times = JSONArray(); val codes = JSONArray(); val precip = JSONArray()
        // yesterday: uniform cloudy, no precipitation data at all (tests the -1 "no data" path for a day never queried directly).
        hourlyDay(yesterday, uniform(3), uniformP(null), times, codes, precip)
        // today, first half (0-11): a 6-6 tie between partly-cloudy and rain -> rain wins (more severe).
        val todayFirstCodes = List(6) { 1 } + List(6) { 61 }
        val todayFirstPrecip: List<Int?> = listOf(10, 20, 30, 40, 50, 60) + listOf(70, 65, 72, 80, 75, 68)
        // today, second half (12-23): rain dominates (8 of 12), block 18-24 has no precipitation data.
        val todaySecondCodes = List(8) { 63 } + List(4) { 3 }
        val todaySecondPrecip: List<Int?> = listOf(55, 60, 58, 62, 59, 57) + List(6) { null }
        hourlyDay(today, todayFirstCodes + todaySecondCodes, todayFirstPrecip + todaySecondPrecip, times, codes, precip)
        // tomorrow: uniform clear sky, flat 5% precipitation chance all day.
        hourlyDay(tomorrow, uniform(0), uniformP(5), times, codes, precip)

        val daily = JSONObject().put("time", JSONArray(dailyTimes)).put("weather_code", JSONArray(dailyCodes))
            .put("temperature_2m_max", JSONArray(dailyMax)).put("temperature_2m_min", JSONArray(dailyMin))
        val hourly = JSONObject().put("time", times).put("weather_code", codes).put("precipitation_probability", precip)
        val current = JSONObject().put("temperature_2m", 22.6).put("weather_code", 61).put("is_day", 1).put("time", currentTime)
        return JSONObject().put("current", current).put("daily", daily).put("hourly", hourly)
    }

    @Test fun currentFields() {
        val forecast = Forecast.parse(fixture())
        assertEquals(22.6, forecast.currentTemp, 0.0001)
        assertEquals(61, forecast.currentCode)
        assertTrue(forecast.isDay)
        assertEquals("${today}T15:00", forecast.currentTime)
    }

    @Test fun todayAlignsToCurrentDate() {
        val forecast = Forecast.parse(fixture())
        assertEquals(2, forecast.days.size)
        assertEquals(today, forecast.days[0].date)
        assertEquals(tomorrow, forecast.days[1].date)
    }

    @Test fun fallsBackToDailyIndexOneWhenCurrentDateMismatches() {
        // current.time names a date not present in daily.time at all.
        val forecast = Forecast.parse(fixture(currentTime = "2099-01-01T00:00"))
        assertEquals(today, forecast.days[0].date) // daily index 1
        assertEquals(tomorrow, forecast.days[1].date)
    }

    @Test fun halfDayDominanceTieBrokenBySeverity() {
        val today = Forecast.parse(fixture()).days[0]
        // First half: 6x code 1 (PARTLY_CLOUDY_DAY) vs 6x code 61 (RAIN) -> RAIN wins the tie.
        assertEquals(WeatherScene.RAIN, WeatherScene.of(today.codeFirstHalf, true))
        assertEquals(61, today.codeFirstHalf)
    }

    @Test fun halfDayDominanceByMajority() {
        val today = Forecast.parse(fixture()).days[0]
        // Second half: 8x code 63 (RAIN) vs 4x code 3 (CLOUDY) -> RAIN wins outright.
        assertEquals(WeatherScene.RAIN, WeatherScene.of(today.codeSecondHalf, true))
        assertEquals(63, today.codeSecondHalf)
    }

    @Test fun dominanceIsUniformWhenAllHoursAgree() {
        // tomorrow's hourly rows are all clear (code 0) for both halves.
        val tomorrow = Forecast.parse(fixture()).days[1]
        assertEquals(0, tomorrow.codeFirstHalf)
        assertEquals(0, tomorrow.codeSecondHalf)
    }

    @Test fun dominanceFallsBackToDailyCodeWhenNoHourlyRowsMatchTheDate() {
        // hourly only covers "today"; tomorrow has no matching hourly rows at all.
        val times = JSONArray(); val codes = JSONArray(); val precip = JSONArray()
        hourlyDay(today, uniform(61), uniformP(80), times, codes, precip)
        val daily = JSONObject().put("time", JSONArray(listOf(today, tomorrow))).put("weather_code", JSONArray(listOf(61, 45)))
            .put("temperature_2m_max", JSONArray(listOf(24.0, 27.0))).put("temperature_2m_min", JSONArray(listOf(20.0, 19.0)))
        val hourly = JSONObject().put("time", times).put("weather_code", codes).put("precipitation_probability", precip)
        val current = JSONObject().put("time", "${today}T15:00")
        val forecast = Forecast.parse(JSONObject().put("current", current).put("daily", daily).put("hourly", hourly))
        val tomorrowForecast = forecast.days[1]
        assertEquals(45, tomorrowForecast.codeFirstHalf) // falls back to daily.weather_code (fog)
        assertEquals(45, tomorrowForecast.codeSecondHalf)
        assertArrayEquals(intArrayOf(-1, -1, -1, -1), tomorrowForecast.precipitation) // no hourly rows -> no data
    }

    @Test fun precipitationMaxPerBlockAndNoDataSentinel() {
        val today = Forecast.parse(fixture()).days[0]
        assertEquals(60, today.precipitation[0]) // 00-06: max(10,20,30,40,50,60)
        assertEquals(80, today.precipitation[1]) // 06-12: max(70,65,72,80,75,68)
        assertEquals(62, today.precipitation[2]) // 12-18: max(55,60,58,62,59,57)
        assertEquals(-1, today.precipitation[3]) // 18-24: all null -> no data
    }

    @Test fun precipitationUniformBlock() {
        val tomorrow = Forecast.parse(fixture()).days[1]
        assertArrayEquals(intArrayOf(5, 5, 5, 5), tomorrow.precipitation)
    }

    @Test fun deltasRoundedAgainstPreviousDay() {
        val days = Forecast.parse(fixture()).days
        val today = days[0]; val tomorrow = days[1]
        assertEquals(-1, today.tempMaxDelta) // round(24 - 25)
        assertEquals(2, today.tempMinDelta) // round(20 - 18)
        assertEquals(3, tomorrow.tempMaxDelta) // round(27 - 24)
        assertEquals(-1, tomorrow.tempMinDelta) // round(19 - 20)
    }

    @Test fun deltaIsNullWhenPreviousDayMissing() {
        // Only today/tomorrow in daily.time -> today (index 0) has no previous day.
        val forecast = Forecast.parse(fixture(currentTime = "${today}T15:00", dailyTimes = listOf(today, tomorrow),
            dailyCodes = listOf(61, 0), dailyMax = listOf(24.0, 27.0), dailyMin = listOf(20.0, 19.0)))
        val today = forecast.days[0]
        assertNull(today.tempMaxDelta)
        assertNull(today.tempMinDelta)
        val tomorrow = forecast.days[1]
        assertEquals(3, tomorrow.tempMaxDelta) // previous day (today) is still present
    }

    @Test fun temperaturesAndDailyCodeAreCarriedThrough() {
        val today = Forecast.parse(fixture()).days[0]
        assertEquals(24.0, today.tempMax, 0.0001)
        assertEquals(20.0, today.tempMin, 0.0001)
        assertEquals(61, today.code)
    }

    @Test fun missingTopLevelObjectsDoNotThrow() {
        val forecast = Forecast.parse(JSONObject())
        assertEquals(0.0, forecast.currentTemp, 0.0001)
        assertEquals(0, forecast.currentCode)
        assertEquals("", forecast.currentTime)
        assertTrue(forecast.days.isEmpty())
    }

    @Test fun nullDailyValuesDoNotThrowAndYieldNullDelta() {
        val daily = JSONObject().put("time", JSONArray(listOf(yesterday, today, tomorrow)))
            .put("weather_code", JSONArray(listOf(JSONObject.NULL, 61, 0)))
            .put("temperature_2m_max", JSONArray(listOf(JSONObject.NULL, 24.0, 27.0)))
            .put("temperature_2m_min", JSONArray(listOf(18.0, JSONObject.NULL, 19.0)))
        val current = JSONObject().put("time", "${today}T15:00")
        val json = JSONObject().put("current", current).put("daily", daily)
        val forecast = Forecast.parse(json)
        val todayForecast = forecast.days[0]
        assertNull(todayForecast.tempMaxDelta) // yesterday's max is JSON null
        assertNull(todayForecast.tempMinDelta) // today's own min is JSON null -> falls back to 0.0, delta null
        assertEquals(0.0, todayForecast.tempMin, 0.0001)
        assertEquals(24.0, todayForecast.tempMax, 0.0001)
        assertEquals(61, todayForecast.code) // daily weather_code[1] (today's own index) is present
    }

    @Test fun dayForecastEqualityIsStructuralAcrossArrayInstances() {
        val a = DayForecast("2026-09-16", 1, 1, 1, 24.0, 20.0, -1, 2, intArrayOf(10, 20, 30, 40))
        val b = DayForecast("2026-09-16", 1, 1, 1, 24.0, 20.0, -1, 2, intArrayOf(10, 20, 30, 40))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(precipitation = intArrayOf(1, 2, 3, 4)))
    }
}
