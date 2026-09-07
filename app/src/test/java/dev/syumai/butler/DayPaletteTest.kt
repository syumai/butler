package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class DayPaletteTest {
    private fun avgChannel(a: Int, b: Int, shift: Int): Int {
        val x = (a shr shift) and 0xFF; val y = (b shr shift) and 0xFF
        return (x + (y - x) * 0.5f + 0.5f).toInt().coerceIn(0, 255)
    }
    private fun avgColor(a: Int, b: Int) = (0xFF shl 24) or (avgChannel(a, b, 16) shl 16) or (avgChannel(a, b, 8) shl 8) or avgChannel(a, b, 0)
    @Test fun keyframesReturnExactColors() {
        for ((minute, palette) in DayPalette.keyframes) {
            val at = DayPalette.at(minute)
            assertArrayEquals("sky at $minute", palette.sky, at.sky)
            assertEquals("sun at $minute", palette.sun, at.sun)
            assertArrayEquals("ridges at $minute", palette.ridges, at.ridges)
        }
    }
    @Test fun midpointIsPerChannelAverage() {
        val dawn = DayPalette.keyframes.first { it.first == 360 }.second
        val morning = DayPalette.keyframes.first { it.first == 480 }.second
        val mid = DayPalette.at(420) // 07:00, halfway between 06:00 and 08:00
        assertArrayEquals(IntArray(3) { avgColor(dawn.sky[it], morning.sky[it]) }, mid.sky)
        assertEquals(avgColor(dawn.sun, morning.sun), mid.sun)
        assertArrayEquals(IntArray(3) { avgColor(dawn.ridges[it], morning.ridges[it]) }, mid.ridges)
    }
    @Test fun lateAndEarlyHoursAreNight() {
        val night = DayPalette.keyframes.first { it.first == 0 }.second
        val at23 = DayPalette.at(23 * 60)
        val at2 = DayPalette.at(2 * 60)
        assertArrayEquals(night.sky, at23.sky); assertEquals(night.sun, at23.sun); assertArrayEquals(night.ridges, at23.ridges)
        assertArrayEquals(night.sky, at2.sky); assertEquals(night.sun, at2.sun); assertArrayEquals(night.ridges, at2.ridges)
    }
    @Test fun everyMinuteIsFullyOpaque() {
        for (minute in 0 until 1440) {
            val p = DayPalette.at(minute)
            for (c in p.sky) assertEquals(0xFF, (c ushr 24) and 0xFF)
            assertEquals(0xFF, (p.sun ushr 24) and 0xFF)
            for (c in p.ridges) assertEquals(0xFF, (c ushr 24) and 0xFF)
        }
    }
    @Test fun outOfRangeWraps() {
        val at0 = DayPalette.at(0); val at1440 = DayPalette.at(1440)
        assertArrayEquals(at0.sky, at1440.sky); assertEquals(at0.sun, at1440.sun); assertArrayEquals(at0.ridges, at1440.ridges)
        val at1380 = DayPalette.at(1380); val atNeg60 = DayPalette.at(-60)
        assertArrayEquals(at1380.sky, atNeg60.sky); assertEquals(at1380.sun, atNeg60.sun); assertArrayEquals(at1380.ridges, atNeg60.ridges)
    }
}
