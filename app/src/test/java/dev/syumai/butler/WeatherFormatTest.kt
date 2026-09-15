package dev.syumai.butler

import org.junit.Assert.*
import org.junit.Test

class WeatherFormatTest {
    @Test fun emojiCoversEveryScene() {
        assertEquals("☀️", WeatherFormat.emoji(WeatherScene.CLEAR_DAY))
        assertEquals("🌙", WeatherFormat.emoji(WeatherScene.CLEAR_NIGHT))
        assertEquals("⛅", WeatherFormat.emoji(WeatherScene.PARTLY_CLOUDY_DAY))
        assertEquals("🌙", WeatherFormat.emoji(WeatherScene.PARTLY_CLOUDY_NIGHT))
        assertEquals("☁️", WeatherFormat.emoji(WeatherScene.CLOUDY))
        assertEquals("🌫️", WeatherFormat.emoji(WeatherScene.FOG))
        assertEquals("🌧️", WeatherFormat.emoji(WeatherScene.RAIN))
        assertEquals("❄️", WeatherFormat.emoji(WeatherScene.SNOW))
        assertEquals("⛈️", WeatherFormat.emoji(WeatherScene.THUNDER))
        assertEquals("🌡️", WeatherFormat.emoji(WeatherScene.DEFAULT))
    }

    @Test fun labelResSharesDayAndNightVariants() {
        assertEquals(R.string.weather_sky_clear, WeatherFormat.labelRes(WeatherScene.CLEAR_DAY))
        assertEquals(R.string.weather_sky_clear, WeatherFormat.labelRes(WeatherScene.CLEAR_NIGHT))
        assertEquals(R.string.weather_sky_partly_cloudy, WeatherFormat.labelRes(WeatherScene.PARTLY_CLOUDY_DAY))
        assertEquals(R.string.weather_sky_partly_cloudy, WeatherFormat.labelRes(WeatherScene.PARTLY_CLOUDY_NIGHT))
        assertEquals(R.string.weather_sky_cloudy, WeatherFormat.labelRes(WeatherScene.CLOUDY))
        assertEquals(R.string.weather_sky_fog, WeatherFormat.labelRes(WeatherScene.FOG))
        assertEquals(R.string.weather_sky_rain, WeatherFormat.labelRes(WeatherScene.RAIN))
        assertEquals(R.string.weather_sky_snow, WeatherFormat.labelRes(WeatherScene.SNOW))
        assertEquals(R.string.weather_sky_thunder, WeatherFormat.labelRes(WeatherScene.THUNDER))
        assertEquals(R.string.weather_sky_default, WeatherFormat.labelRes(WeatherScene.DEFAULT))
    }

    private fun day(codeFirstHalf: Int, codeSecondHalf: Int) =
        DayForecast("2026-09-16", codeFirstHalf, codeFirstHalf, codeSecondHalf, 24.0, 20.0, null, null, intArrayOf(-1, -1, -1, -1))

    @Test fun conditionPartsOmitsSecondWhenSameCategory() {
        val (first, second) = WeatherFormat.conditionParts(day(51, 61)) // both RAIN
        assertEquals(WeatherScene.RAIN, first)
        assertNull(second)
    }

    @Test fun conditionPartsIncludesSecondWhenCategoryDiffers() {
        val (first, second) = WeatherFormat.conditionParts(day(0, 61)) // CLEAR_DAY then RAIN
        assertEquals(WeatherScene.CLEAR_DAY, first)
        assertEquals(WeatherScene.RAIN, second)
    }

    @Test fun conditionPartsAlwaysCategorizesAsDay() {
        // WeatherScene.of(0, false) would be CLEAR_NIGHT, but conditionParts always passes isDay=true.
        val (first, _) = WeatherFormat.conditionParts(day(0, 0))
        assertEquals(WeatherScene.CLEAR_DAY, first)
    }

    @Test fun deltaTextFormatting() {
        assertEquals("+2", WeatherFormat.deltaText(2))
        assertEquals("-4", WeatherFormat.deltaText(-4))
        assertEquals("0", WeatherFormat.deltaText(0))
        assertEquals("", WeatherFormat.deltaText(null))
    }

    @Test fun precipitationTextFormatting() {
        assertEquals("60%", WeatherFormat.precipitationText(60))
        assertEquals("0%", WeatherFormat.precipitationText(0))
        assertEquals("–", WeatherFormat.precipitationText(-1))
    }
}
