package dev.syumai.butler

import org.json.JSONArray

/**
 * Shared cache of the latest Home Assistant device list (design spec §6/§6.5), refreshed by
 * whichever of [SmartHomePage]/[MusicPage] is currently shown and read by the other, so switching
 * between the two pages shows the last-known list immediately instead of a blank page while a
 * fresh fetch is in flight. [serial] increments on every update so callers can tell whether the
 * cache is newer than what they last rendered.
 */
object HomeStore {
    @Volatile var devices: JSONArray? = null
        private set
    @Volatile var serial: Int = 0
        private set

    @Synchronized
    fun update(devices: JSONArray) {
        this.devices = devices
        serial++
    }
}
