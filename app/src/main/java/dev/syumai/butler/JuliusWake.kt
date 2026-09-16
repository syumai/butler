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
}
