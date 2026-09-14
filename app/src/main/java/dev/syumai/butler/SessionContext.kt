package dev.syumai.butler

import android.content.Context
import java.util.Locale
import java.util.TimeZone

/**
 * Static, non-secret device facts appended to the model instructions at session start (both
 * [WebRtcClient]'s Realtime and GPT-Live signaling paths): the device's time zone, its language,
 * and the configured weather region (name and/or coordinates), if any. Never anything secret —
 * no API keys, tokens, Home Assistant URL, or MCP URL. Anything that can change during a session
 * (the current date/time) is deliberately left out and instead surfaced through the
 * `get_current_time` tool ([dev.syumai.butler.tools.GetCurrentTimeTool]).
 */
object SessionContext {
    /** Pure part, unit-testable: the values that go into the localized template built by [text]. */
    data class Facts(
        val timeZoneId: String,
        val utcOffset: String,
        val language: String,
        val region: String?,
        val latitude: String?,
        val longitude: String?,
    )

    /** Formats a raw UTC offset in milliseconds (as returned by [TimeZone.getOffset]) as "+HH:MM"/"-HH:MM". */
    fun formatUtcOffset(offsetMs: Int): String {
        val totalMinutes = offsetMs / 60000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = kotlin.math.abs(totalMinutes)
        return "%s%02d:%02d".format(sign, absMinutes / 60, absMinutes % 60)
    }

    /**
     * [location]/[latitude]/[longitude] are the raw, possibly-blank values as stored in [Settings]
     * (kept as plain strings here, rather than taking [Settings] itself, so this stays a pure
     * function testable without an Android [Context]/SharedPreferences).
     */
    fun facts(
        location: String,
        latitude: String,
        longitude: String,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        now: Long = System.currentTimeMillis(),
    ): Facts = Facts(
        timeZoneId = zone.id,
        utcOffset = formatUtcOffset(zone.getOffset(now)),
        language = locale.getDisplayLanguage(locale),
        region = location.trim().takeIf { it.isNotBlank() },
        latitude = latitude.trim().takeIf { it.isNotBlank() },
        longitude = longitude.trim().takeIf { it.isNotBlank() },
    )

    /** The context paragraph appended to both the Realtime and GPT-Live session instructions. */
    fun text(context: Context, settings: Settings): String {
        val facts = facts(settings.get("location"), settings.get("latitude"), settings.get("longitude"))
        val sb = StringBuilder(context.getString(R.string.prompt_context_device, facts.timeZoneId, facts.utcOffset, facts.language))
        // The region sentence is included whenever there's a name or a full pair of coordinates to
        // report; a missing name or a missing coordinate is filled in with an "unnamed"/"unknown"
        // placeholder rather than dropping the whole sentence.
        if (facts.region != null || (facts.latitude != null && facts.longitude != null)) {
            val name = facts.region ?: context.getString(R.string.prompt_context_region_unnamed)
            val latitude = facts.latitude ?: context.getString(R.string.prompt_context_region_coordinate_unknown)
            val longitude = facts.longitude ?: context.getString(R.string.prompt_context_region_coordinate_unknown)
            sb.append(context.getString(R.string.prompt_context_region, name, latitude, longitude))
        }
        sb.append(context.getString(R.string.prompt_context_time_tool))
        return sb.toString()
    }
}
