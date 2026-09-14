package dev.syumai.butler

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class SessionContextTest {
    private val now = 1789404312000L // 2026-09-15T01:45:12+09:00

    @Test fun offsetTokyo() {
        val facts = SessionContext.facts("", "", "", TimeZone.getTimeZone("Asia/Tokyo"), Locale.JAPANESE, now)
        assertEquals("+09:00", facts.utcOffset)
        assertEquals("Asia/Tokyo", facts.timeZoneId)
    }

    @Test fun offsetNewYork() {
        val facts = SessionContext.facts("", "", "", TimeZone.getTimeZone("America/New_York"), Locale.ENGLISH, now)
        assertEquals("-04:00", facts.utcOffset)
    }

    @Test fun offsetKolkataHalfHour() {
        val facts = SessionContext.facts("", "", "", TimeZone.getTimeZone("Asia/Kolkata"), Locale.ENGLISH, now)
        assertEquals("+05:30", facts.utcOffset)
    }

    @Test fun languageDisplay() {
        val ja = SessionContext.facts("", "", "", TimeZone.getTimeZone("Asia/Tokyo"), Locale.JAPANESE, now)
        assertEquals("日本語", ja.language)
        val en = SessionContext.facts("", "", "", TimeZone.getTimeZone("Asia/Tokyo"), Locale.ENGLISH, now)
        assertEquals("English", en.language)
    }

    @Test fun regionBlankIsNull() {
        val facts = SessionContext.facts("", "", "", TimeZone.getTimeZone("Asia/Tokyo"), Locale.ENGLISH, now)
        assertNull(facts.region)
        assertNull(facts.latitude)
        assertNull(facts.longitude)
    }

    @Test fun regionSetIsTrimmed() {
        val facts = SessionContext.facts("  Tokyo  ", " 35.68 ", " 139.69 ", TimeZone.getTimeZone("Asia/Tokyo"), Locale.ENGLISH, now)
        assertEquals("Tokyo", facts.region)
        assertEquals("35.68", facts.latitude)
        assertEquals("139.69", facts.longitude)
    }

    @Test fun regionNameOnlyCoordinatesBlank() {
        val facts = SessionContext.facts("Tokyo", "", "", TimeZone.getTimeZone("Asia/Tokyo"), Locale.ENGLISH, now)
        assertEquals("Tokyo", facts.region)
        assertNull(facts.latitude)
        assertNull(facts.longitude)
    }
}
