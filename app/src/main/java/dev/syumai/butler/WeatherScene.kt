package dev.syumai.butler

/** Coarse visual scene derived from an Open-Meteo WMO weather code and day/night flag. */
enum class WeatherScene {
    DEFAULT, CLEAR_DAY, CLEAR_NIGHT, PARTLY_CLOUDY_DAY, PARTLY_CLOUDY_NIGHT, CLOUDY, FOG, RAIN, SNOW, THUNDER;
    companion object {
        /** Mirrors the Japanese sky-text mapping in MainActivity.refreshWeather(). */
        fun of(code: Int, isDay: Boolean): WeatherScene = when (code) {
            0 -> if (isDay) CLEAR_DAY else CLEAR_NIGHT
            1, 2 -> if (isDay) PARTLY_CLOUDY_DAY else PARTLY_CLOUDY_NIGHT
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> DEFAULT
        }
    }
}
