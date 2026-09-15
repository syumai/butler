package dev.syumai.butler

/**
 * In-memory cache of the last-fetched [Forecast], shared by MainActivity (weather mini text and
 * WeatherPage) and [dev.syumai.butler.tools.GetHomeWeatherTool] so they read one fetch instead of
 * each hitting Open-Meteo independently. Pure Kotlin (no Android imports) — callers pass in `now`
 * (typically `SystemClock.elapsedRealtime()`, kept consistent across all callers so the freshness
 * check means the same thing to everyone) rather than this object reading the clock itself, so it
 * stays unit-testable without Robolectric.
 */
object WeatherStore {
    @Volatile var forecast: Forecast? = null
        private set
    @Volatile private var fetchedAt: Long = 0L
    @Volatile private var fetchedLat: Double = Double.NaN
    @Volatile private var fetchedLon: Double = Double.NaN

    /** Returns the cached [Forecast] when it was fetched for the same [lat]/[lon] and is younger than
     * [maxAgeMs] as of [now]; otherwise fetches a fresh one via [client].forecast, caches it, and
     * returns it. [client]'s call can throw (network/parse failure); the previous cache, if any, is
     * left untouched on failure since the new value is only stored after a successful parse. */
    @Synchronized
    fun current(client: ToolClient, lat: Double, lon: Double, now: Long, maxAgeMs: Long = 15 * 60 * 1000L): Forecast {
        val cached = forecast
        if (cached != null && lat == fetchedLat && lon == fetchedLon && now - fetchedAt < maxAgeMs) return cached
        val fetched = Forecast.parse(client.forecast(lat, lon))
        forecast = fetched; fetchedAt = now; fetchedLat = lat; fetchedLon = lon
        return fetched
    }
}
