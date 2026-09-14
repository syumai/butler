package dev.syumai.butler.tools

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class GetCurrentTimeToolTest {
    // 2026-09-15T01:45:12+09:00
    private val epochMs = 1789404312000L

    @Test fun tokyoJapanese() {
        val result = GetCurrentTimeTool.payload(epochMs, TimeZone.getTimeZone("Asia/Tokyo"), Locale.JAPANESE)
        assertEquals("2026-09-15T01:45:12+09:00", result.getString("datetime"))
        assertEquals("2026-09-15", result.getString("date"))
        assertEquals("01:45", result.getString("time"))
        assertEquals("火曜日", result.getString("weekday"))
        assertEquals("Asia/Tokyo", result.getString("time_zone"))
        assertEquals("+09:00", result.getString("utc_offset"))
        assertEquals(epochMs / 1000, result.getLong("epoch_seconds"))
    }

    @Test fun newYorkEnglish() {
        val result = GetCurrentTimeTool.payload(epochMs, TimeZone.getTimeZone("America/New_York"), Locale.ENGLISH)
        assertEquals("2026-09-14", result.getString("date"))
        assertEquals("12:45", result.getString("time"))
        assertEquals("Monday", result.getString("weekday"))
        assertEquals("America/New_York", result.getString("time_zone"))
        assertEquals("-04:00", result.getString("utc_offset"))
        assertEquals("2026-09-14T12:45:12-04:00", result.getString("datetime"))
    }
}
