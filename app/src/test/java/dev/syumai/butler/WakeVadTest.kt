package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class WakeVadTest {
    /** Runs [samples] through a fresh [WakeVad] and returns the sample count of each completed
     * segment. A final [WakeVad.reset] flushes any segment still open at the end of the input, mirroring
     * how `scripts/julius-eval.py`'s `vad_segments` force-closes a still-open span at end of file. */
    private fun segments(samples: ShortArray): List<Int> {
        val counts = mutableListOf<Int>()
        var current = 0
        val vad = WakeVad(
            onSegmentStart = { current = 0 },
            onSegmentAudio = { _, count -> current += count },
            onSegmentEnd = { counts.add(current) },
        )
        vad.accept(samples, samples.size)
        vad.reset()
        return counts
    }

    private fun tone(i: Int): Short = (8000 * sin(2 * PI * 440 * i / 16000.0)).toInt().toShort()
    private fun toneRange(samples: ShortArray, from: Int, until: Int) { for (i in from until until) samples[i] = tone(i) }

    @Test fun silenceProducesNoSegments() {
        assertEquals(0, segments(ShortArray(16000)).size) // 1s of silence
    }

    @Test fun oneToneBurstProducesOneSegment() {
        val samples = ShortArray(16000) // 1s
        toneRange(samples, 3200, 11200) // 200-700ms active
        assertEquals(1, segments(samples).size)
    }

    @Test fun twoBurstsSeparatedByLongSilenceProduceTwoSegments() {
        val samples = ShortArray(32000) // 2s
        toneRange(samples, 1600, 6400)   // 100-400ms
        toneRange(samples, 24000, 28800) // 1500-1800ms, trailing silence < hangover: flushed by reset()
        assertEquals(2, segments(samples).size)
    }

    @Test fun briefGapWithinHangoverStaysOneSegment() {
        val samples = ShortArray(16000) // 1s
        toneRange(samples, 1600, 4800)   // 100-300ms
        // 200ms silent gap (< 300ms hangover) at 300-500ms
        toneRange(samples, 8000, 11200)  // 500-700ms
        assertEquals(1, segments(samples).size)
    }

    @Test fun gapLongerThanHangoverSplitsIntoTwoSegments() {
        val samples = ShortArray(24000) // 1.5s
        toneRange(samples, 1600, 4800)   // 100-300ms
        // 400ms silent gap (> 300ms hangover) at 300-700ms
        toneRange(samples, 11200, 14400) // 700-900ms
        assertEquals(2, segments(samples).size)
    }

    @Test fun preRollIsIncludedBeforeSegmentStart() {
        // The active tone alone is under 300ms; if pre-roll audio (kept while idle) were not
        // included, the forwarded segment would only cover the tone itself.
        val samples = ShortArray(8000) // 500ms
        toneRange(samples, 3200, 3520) // 200-220ms, 20ms of tone
        var totalCount = 0
        val vad = WakeVad(onSegmentStart = {}, onSegmentAudio = { _, count -> totalCount += count }, onSegmentEnd = {})
        vad.accept(samples, samples.size)
        vad.reset()
        // At least the 20ms of tone plus some pre-roll should have been forwarded.
        assertTrue("expected forwarded audio to include pre-roll, got $totalCount samples", totalCount > 320)
    }

    /** Finds a repo-root-relative file regardless of whether the JVM test's working directory is the
     * repo root or the app module directory (Gradle's default for :app:testDebugUnitTest). */
    private fun findRepoFile(relative: String): File? {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test fun realRecordingFindsExpectedSegmentCount() {
        val file = findRepoFile(".tools/hello-butler-ja-rec1.pcm") ?: return // gitignored fixture; skip when absent
        val bytes = file.readBytes()
        val samples = ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        val count = segments(samples).size
        assertTrue("expected 10-13 segments over the real recording, got $count", count in 10..13)
    }
}
