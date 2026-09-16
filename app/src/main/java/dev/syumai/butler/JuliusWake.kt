package dev.syumai.butler

/**
 * Parses a single line of Julius's module (XML fragment) output and reports whether it is a confident
 * recognition of the wake word. Pure Kotlin (no Android imports, no XML parser — the module protocol's
 * `<TAG attr="value" .../>` lines are simple enough to pick apart with a regex) so it is unit-testable
 * on the JVM (see `JuliusWakeTest`).
 *
 * Julius (module output, `julius/output_module.c`) writes one `<RECOGOUT>` block per recognized
 * segment, e.g.:
 * ```
 * <RECOGOUT>
 *   <SHYPO RANK="1" SCORE="-4188.459473">
 *     <WHYPO WORD="ハローバトラー" CLASSID="2" PHONE="h a r o: b a t o r a:" CM="0.078"/>
 *     <WHYPO WORD="<garbage>" CLASSID="3" PHONE="a" CM="0.234"/>
 *   </SHYPO>
 * </RECOGOUT>
 * .
 * ```
 * (each block terminated by a line containing a single `.`), interleaved with unrelated status lines
 * such as `<INPUT STATUS="LISTEN" .../>`. Only `<WHYPO .../>` lines matter here; [hit] is called once
 * per line read from the module socket and simply returns false for every other line (open/close tags,
 * the `.` terminator, status lines) since none of them start with `<WHYPO`.
 *
 * A `WHYPO` line only counts as a hit when its `WORD` attribute equals any entry of [wakeWords] (a
 * `Collection` rather than a single word so a phrase with more than one Julius-recognized pronunciation
 * set — e.g. if a future grammar change adds one — can be checked in one call; `WakePhrase.HELLO_BUTLER`'s
 * `juliusWords` currently has just the one entry, "ハローバトラー" — see its declaration in
 * `LocalWakeWordEngine.kt` for why "ヘイバトラー" isn't in it) AND its `CM` (confidence measure)
 * attribute parses as a number >= [threshold]. A missing or non-numeric `CM` (Julius writes `CM="-"`
 * when confidence-measure computation didn't run for that word) is treated as not confident enough,
 * matching the offline evaluator's behavior (`scripts/julius-eval.py`'s `is_hit`, which also requires a
 * real, present `cmscore`).
 */
object JuliusWake {
    private val ATTR = Regex("""(\w+)="([^"]*)"""")

    fun hit(line: String, wakeWords: Collection<String>, threshold: Double): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("<WHYPO")) return false
        val attrs = ATTR.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
        if (attrs["WORD"] !in wakeWords) return false
        val cm = attrs["CM"]?.toDoubleOrNull() ?: return false
        return cm >= threshold
    }

    /**
     * Judges a whole `<RECOGOUT>` block at once instead of any single `<WHYPO>` line in isolation.
     * [hit] alone (a confidence threshold on one WAKE `<WHYPO>` line) cannot separate genuine wake
     * utterances from false wakes: an offline sweep (`scripts/julius-eval.py`, see
     * `scripts/julius-wake/README.md`'s "2026-09-17: Hey Butler" section) found their `CM` ranges
     * overlap, but their *structure* doesn't — every false wake sat inside running speech, decoded with
     * many `<garbage>` filler words surrounding the wake word, while genuine wake utterances are short,
     * standalone segments with few fillers. [feed] accumulates one call's worth of the sequence of lines
     * `readModule` reads from the module socket (across possibly many `<RECOGOUT>` blocks, one per VAD
     * segment) and reports a hit only when a completed block both had a WAKE `<WHYPO>` with `CM >=`
     * [threshold] AND at most [maxFillers] total `<garbage>` `<WHYPO>` lines in that same block.
     *
     * Call [feed] once per line read from the module socket, in order. It returns true exactly on the
     * line that completes a hitting block (the `.` terminator); every other line — including every line
     * of a non-hitting block, and any status line between blocks (`<INPUT STATUS="..." .../>` etc.,
     * which don't start with `<RECOGOUT`/`<WHYPO` and so are ignored) — returns false. Seeing a new
     * `<RECOGOUT` resets any in-progress accumulation (so a malformed/unterminated block never leaks
     * into the next one), matching how [readModule] just keeps reading lines forever across many
     * segments. Not thread-safe; one instance is owned by one [JuliusWakeDecoder] and fed from its single
     * module-reader thread.
     */
    class BlockParser(
        private val wakeWords: Collection<String>,
        private val threshold: Double,
        private val maxFillers: Int,
    ) {
        private var inBlock = false
        private var fillerCount = 0
        private var wakeHit = false

        fun feed(line: String): Boolean {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("<RECOGOUT") -> {
                    inBlock = true; fillerCount = 0; wakeHit = false
                }
                trimmed == "." -> {
                    val result = inBlock && wakeHit && fillerCount <= maxFillers
                    inBlock = false; fillerCount = 0; wakeHit = false
                    return result
                }
                inBlock && trimmed.startsWith("<WHYPO") -> {
                    val attrs = ATTR.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                    if (attrs["WORD"] == "<garbage>") fillerCount++
                    if (attrs["WORD"] in wakeWords) {
                        val cm = attrs["CM"]?.toDoubleOrNull()
                        if (cm != null && cm >= threshold) wakeHit = true
                    }
                }
            }
            return false
        }
    }
}
