package dev.syumai.butler

/** Colors of the default illustration at one time of day; ARGB ints, always fully opaque. */
class DayPalette(val sky: IntArray /* 3 gradient stops, top-left → bottom-right */, val sun: Int, val ridges: IntArray /* 3: far, mid, near */) {
    companion object {
        // CLEAR_NIGHT sky/ridges from SceneArt, reused here so the default illustration matches the weather
        // scene's own night look. Both the 00:00 and 04:30 keyframes point at the same instance's values.
        private fun night() = DayPalette(
            sky = intArrayOf(0xFF060B1F.toInt(), 0xFF11213F.toInt(), 0xFF1C2F52.toInt()),
            sun = 0xFFE8E4D0.toInt(),
            ridges = intArrayOf(0xFF2A3A5C.toInt(), 0xFF1B2740.toInt(), 0xFF0E1626.toInt())
        )
        /** Keyframes as (minuteOfDay, palette), sorted by minute; exposed for tests. 18:00 EVENING is
         *  exactly the original hard-coded illustration colors, so the existing sunset look is preserved. */
        val keyframes: List<Pair<Int, DayPalette>> = listOf(
            0 to night(),
            270 to night(), // 04:30: holds the night look until pre-dawn
            360 to DayPalette( // 06:00 DAWN
                sky = intArrayOf(0xFF3A4A7A.toInt(), 0xFFB57A86.toInt(), 0xFFF2B47A.toInt()),
                sun = 0xFFFFD7A0.toInt(),
                ridges = intArrayOf(0xFF5C6F86.toInt(), 0xFF3E5468.toInt(), 0xFF22323F.toInt())
            ),
            480 to DayPalette( // 08:00 MORNING
                sky = intArrayOf(0xFF5A93CC.toInt(), 0xFFA8CFE3.toInt(), 0xFFF6DEB0.toInt()),
                sun = 0xFFFFF1C8.toInt(),
                ridges = intArrayOf(0xFF8FAE6E.toInt(), 0xFF5E8C4E.toInt(), 0xFF355B33.toInt())
            ),
            720 to DayPalette( // 12:00 NOON (= CLEAR_DAY sky/ridges)
                sky = intArrayOf(0xFF3E7BC4.toInt(), 0xFF7FB8D9.toInt(), 0xFFF2C879.toInt()),
                sun = 0xFFE7D7A9.toInt(),
                ridges = intArrayOf(0xFF8FAE6E.toInt(), 0xFF5E8C4E.toInt(), 0xFF355B33.toInt())
            ),
            960 to DayPalette( // 16:00 AFTERNOON
                sky = intArrayOf(0xFF4D86B8.toInt(), 0xFF93B9C9.toInt(), 0xFFF0C48A.toInt()),
                sun = 0xFFE9D7A0.toInt(),
                ridges = intArrayOf(0xFF86A46C.toInt(), 0xFF55805A.toInt(), 0xFF2E5043.toInt())
            ),
            1080 to DayPalette( // 18:00 EVENING (= original hard-coded illustration colors)
                sky = intArrayOf(0xFF254C50.toInt(), 0xFF81988D.toInt(), 0xFFE7BF8D.toInt()),
                sun = 0xFFE7D7A9.toInt(),
                ridges = intArrayOf(0xFF78918A.toInt(), 0xFF446B68.toInt(), 0xFF1A4247.toInt())
            ),
            1200 to night() // 20:00: interpolates toward the identical 00:00 keyframe, so 20:00-04:30 stays a constant night
        )
        /** Palette for [minuteOfDay] (0..1439; other values are wrapped with floorMod 1440), linearly
         *  interpolated per ARGB channel between the neighbouring keyframes, wrapping across midnight. */
        fun at(minuteOfDay: Int): DayPalette {
            val m = Math.floorMod(minuteOfDay, 1440)
            var lowerIdx = 0
            for (i in keyframes.indices) if (keyframes[i].first <= m) lowerIdx = i
            val upperIdx = (lowerIdx + 1) % keyframes.size
            val lowerMinute = keyframes[lowerIdx].first
            val upperMinute = keyframes[upperIdx].first + if (upperIdx == 0) 1440 else 0
            val span = upperMinute - lowerMinute
            val t = if (span <= 0) 0f else (m - lowerMinute).toFloat() / span
            return lerp(keyframes[lowerIdx].second, keyframes[upperIdx].second, t)
        }
        private fun lerp(a: DayPalette, b: DayPalette, t: Float) = DayPalette(
            sky = IntArray(3) { lerpColor(a.sky[it], b.sky[it], t) },
            sun = lerpColor(a.sun, b.sun, t),
            ridges = IntArray(3) { lerpColor(a.ridges[it], b.ridges[it], t) }
        )
        private fun lerpColor(from: Int, to: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
