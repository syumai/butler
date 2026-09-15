package dev.syumai.butler

import kotlin.math.sqrt

/**
 * Pure-Kotlin, Android-free energy VAD that segments a continuous 16kHz mono s16 PCM stream into
 * speech spans, for [JuliusWakeDecoder] to forward to Julius's adinnet input — Julius does not cut
 * silence itself for that input mode (`-nocutsilence`), unlike `-input mic`/`-input file`. Ported from
 * the offline evaluator's VAD (`scripts/julius-eval.py`'s `vad_segments`): 10ms frames, an RMS energy
 * threshold, and a ~300ms hangover after energy drops below threshold before a segment is considered
 * over (bridging brief pauses inside one utterance). Speech is forwarded as soon as it is detected,
 * including up to 200ms of pre-roll audio kept in a ring buffer while idle so the very start of a word
 * isn't clipped, and a segment is force-ended at 8s so a single stuck-open segment can't block the
 * decoder indefinitely. No Android imports, so it is unit-testable on the JVM (see `WakeVadTest`).
 *
 * Unlike the offline evaluator — which measures each candidate span's *active-only* duration and drops
 * (never writes to a WAV file) any span shorter than 300ms — this live port cannot retroactively
 * "unsend" audio it has already streamed to Julius as it arrived, so it does not filter by minimum
 * duration: any VAD-detected blip is forwarded and still properly terminated with a 0-byte
 * end-of-segment marker like any other segment. This is harmless: the wake grammar's WAKE word needs
 * several hundred ms of matching phones, so a sub-300ms segment just decodes as `<garbage>` (see
 * `scripts/julius-wake/README.md`'s grammar design) or produces no result at all.
 *
 * Live audio can't be peak-normalized the way the offline evaluator normalizes each whole file ahead of
 * time (a live stream's peak isn't known in advance), so [RMS_THRESHOLD] is a fixed absolute value on
 * the int16 scale rather than a value to apply after normalization, chosen to correspond to this app's
 * actual quiet-mic recording level: the offline evaluator's threshold of 1200 (applied after
 * normalizing each file's peak to 16000) divided by the gain `.tools/hello-butler-ja-rec1.pcm` (a real
 * recording, peak ~2755, most negative ~-970) needed to reach that normalized peak (~16000/2755 ~= 5.8)
 * works out to ~1200/5.8 ~= 207. 200 was then checked directly against that recording in `WakeVadTest`
 * (skipped when the gitignored fixture isn't present): it finds 12 segments, matching the ~11-12 the
 * offline evaluator itself found for the same file (`scripts/julius-wake/README.md`'s "Tuning results").
 */
class WakeVad(
    private val onSegmentStart: () -> Unit,
    private val onSegmentAudio: (ShortArray, Int) -> Unit,
    private val onSegmentEnd: () -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100 // 10ms
        const val HANGOVER_FRAMES = 30 // 300ms
        const val MAX_SEGMENT_FRAMES = 800 // 8s
        const val PRE_ROLL_FRAMES = 20 // 200ms
        const val RMS_THRESHOLD = 200.0 // see class doc for how this was derived
    }

    // Ring buffer of the last PRE_ROLL_FRAMES*FRAME_SAMPLES samples seen while not in speech.
    private val preRoll = ShortArray(PRE_ROLL_FRAMES * FRAME_SAMPLES)
    private var preRollFill = 0
    private var preRollStart = 0

    private val frame = ShortArray(FRAME_SAMPLES)
    private var frameFill = 0

    private var inSpeech = false
    private var hangoverLeft = 0
    private var segmentFrames = 0

    /** Feed raw microphone samples (16kHz mono s16, any chunk size — [LocalWakeWordEngine] passes 1600
     * at a time, but this reassembles into internal 10ms frames regardless of the chunk size it's fed). */
    fun accept(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val take = minOf(FRAME_SAMPLES - frameFill, count - i)
            System.arraycopy(samples, i, frame, frameFill, take)
            frameFill += take; i += take
            if (frameFill == FRAME_SAMPLES) { processFrame(); frameFill = 0 }
        }
    }

    private fun rms(): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size)
    }

    private fun pushPreRoll() {
        for (s in frame) {
            if (preRollFill < preRoll.size) { preRoll[preRollFill] = s; preRollFill++ }
            else { preRoll[preRollStart] = s; preRollStart = (preRollStart + 1) % preRoll.size }
        }
    }

    private fun flushPreRoll() {
        if (preRollFill == 0) return
        if (preRollFill < preRoll.size) {
            onSegmentAudio(preRoll.copyOfRange(0, preRollFill), preRollFill)
        } else {
            val ordered = ShortArray(preRoll.size) { j -> preRoll[(preRollStart + j) % preRoll.size] }
            onSegmentAudio(ordered, ordered.size)
        }
        preRollFill = 0; preRollStart = 0
    }

    private fun processFrame() {
        val active = rms() >= RMS_THRESHOLD
        if (!inSpeech) {
            if (active) {
                inSpeech = true; hangoverLeft = HANGOVER_FRAMES; segmentFrames = 1
                onSegmentStart()
                flushPreRoll()
                onSegmentAudio(frame.copyOf(), FRAME_SAMPLES)
            } else pushPreRoll()
            return
        }
        segmentFrames++
        onSegmentAudio(frame.copyOf(), FRAME_SAMPLES)
        if (active) hangoverLeft = HANGOVER_FRAMES else hangoverLeft--
        if (hangoverLeft <= 0 || segmentFrames >= MAX_SEGMENT_FRAMES) endSegment()
    }

    private fun endSegment() {
        inSpeech = false; segmentFrames = 0; hangoverLeft = 0
        onSegmentEnd()
    }

    /** Aborts and properly ends whatever segment is currently open, if any — used when the caller needs
     * to discard in-flight audio (restart/discardAudio/close): if a segment was open, [onSegmentEnd]
     * still fires so the adinnet peer sees a clean end-of-segment marker, exactly as [endSegment] would
     * have sent had the hangover simply expired on its own. A no-op (no callback fires) if no segment
     * was open. Also clears the pre-roll ring buffer so stale audio isn't replayed into the next segment. */
    fun reset() {
        if (inSpeech) endSegment()
        preRollFill = 0; preRollStart = 0; frameFill = 0
    }
}
