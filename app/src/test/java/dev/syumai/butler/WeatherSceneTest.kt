package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class WeatherSceneTest {
    @Test fun clearSky() {
        assertEquals(WeatherScene.CLEAR_DAY, WeatherScene.of(0, true))
        assertEquals(WeatherScene.CLEAR_NIGHT, WeatherScene.of(0, false))
    }
    @Test fun partlyCloudy() {
        assertEquals(WeatherScene.PARTLY_CLOUDY_DAY, WeatherScene.of(1, true))
        assertEquals(WeatherScene.PARTLY_CLOUDY_DAY, WeatherScene.of(2, true))
        assertEquals(WeatherScene.PARTLY_CLOUDY_NIGHT, WeatherScene.of(1, false))
        assertEquals(WeatherScene.PARTLY_CLOUDY_NIGHT, WeatherScene.of(2, false))
    }
    @Test fun cloudyIgnoresDayNight() {
        assertEquals(WeatherScene.CLOUDY, WeatherScene.of(3, true))
        assertEquals(WeatherScene.CLOUDY, WeatherScene.of(3, false))
    }
    @Test fun fog() {
        assertEquals(WeatherScene.FOG, WeatherScene.of(45, true))
        assertEquals(WeatherScene.FOG, WeatherScene.of(48, false))
    }
    @Test fun rain() {
        assertEquals(WeatherScene.RAIN, WeatherScene.of(51, true))
        assertEquals(WeatherScene.RAIN, WeatherScene.of(67, true))
        assertEquals(WeatherScene.RAIN, WeatherScene.of(80, false))
        assertEquals(WeatherScene.RAIN, WeatherScene.of(82, false))
    }
    @Test fun snow() {
        assertEquals(WeatherScene.SNOW, WeatherScene.of(71, true))
        assertEquals(WeatherScene.SNOW, WeatherScene.of(77, true))
        assertEquals(WeatherScene.SNOW, WeatherScene.of(85, false))
        assertEquals(WeatherScene.SNOW, WeatherScene.of(86, false))
    }
    @Test fun thunder() {
        assertEquals(WeatherScene.THUNDER, WeatherScene.of(95, true))
        assertEquals(WeatherScene.THUNDER, WeatherScene.of(99, false))
    }
    @Test fun elseBranchIsDefault() {
        assertEquals(WeatherScene.DEFAULT, WeatherScene.of(4, true))
        assertEquals(WeatherScene.DEFAULT, WeatherScene.of(-1, false))
        assertEquals(WeatherScene.DEFAULT, WeatherScene.of(100, true))
    }
}
