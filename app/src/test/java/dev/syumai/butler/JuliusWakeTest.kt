package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test

class JuliusWakeTest {
    // "テストワード" is a made-up second word, not a phrase Butler actually ships -- it only exercises
    // JuliusWake's generic Collection<String> support for more than one wake word.
    private val wake = listOf("ハローバトラー", "テストワード")

    @Test fun wakeWordAboveThresholdHits() {
        assertTrue(JuliusWake.hit("""    <WHYPO WORD="ハローバトラー" CLASSID="2" PHONE="h a r o: b a t o r a:" CM="0.078"/>""", wake, 0.05))
    }
    @Test fun wakeWordAtThresholdExactlyHits() {
        assertTrue(JuliusWake.hit("""<WHYPO WORD="ハローバトラー" CM="0.05"/>""", wake, 0.05))
    }
    @Test fun wakeWordBelowThresholdDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="ハローバトラー" CLASSID="2" PHONE="h a r o: b a t o r a:" CM="0.02"/>""", wake, 0.05))
    }
    @Test fun secondWakeWordAboveThresholdHits() {
        assertTrue(JuliusWake.hit("""<WHYPO WORD="テストワード" CLASSID="2" PHONE="t e s u t o w a: d o" CM="0.078"/>""", wake, 0.05))
    }
    @Test fun secondWakeWordBelowThresholdDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="テストワード" CLASSID="2" PHONE="t e s u t o w a: d o" CM="0.02"/>""", wake, 0.05))
    }
    @Test fun garbageWordDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="<garbage>" CLASSID="3" PHONE="a" CM="0.9"/>""", wake, 0.05))
    }
    @Test fun differentWordDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="something-else" CM="0.9"/>""", wake, 0.05))
    }
    @Test fun wordOutsideSetDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="ハイバトラー" CM="0.9"/>""", wake, 0.05))
    }
    @Test fun missingCmDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="ハローバトラー" CLASSID="2" PHONE="h a r o: b a t o r a:"/>""", wake, 0.05))
    }
    @Test fun dashCmDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="ハローバトラー" CLASSID="2" CM="-"/>""", wake, 0.05))
    }
    @Test fun recogoutOpenTagDoesNotHit() {
        assertFalse(JuliusWake.hit("<RECOGOUT>", wake, 0.05))
    }
    @Test fun shypoTagDoesNotHit() {
        assertFalse(JuliusWake.hit("""  <SHYPO RANK="1" SCORE="-4188.459473">""", wake, 0.05))
    }
    @Test fun blockTerminatorDoesNotHit() {
        assertFalse(JuliusWake.hit(".", wake, 0.05))
    }
    @Test fun statusLineDoesNotHit() {
        assertFalse(JuliusWake.hit("""<INPUT STATUS="LISTEN" TIME="1234567890.123"/>""", wake, 0.05))
    }
    @Test fun emptyLineDoesNotHit() {
        assertFalse(JuliusWake.hit("", wake, 0.05))
    }

    // --- JuliusWake.BlockParser ---

    private fun parser(maxFillers: Int = 6) = JuliusWake.BlockParser(wake, 0.05, maxFillers)

    private fun garbage(cm: Double = 0.9) = """<WHYPO WORD="<garbage>" CLASSID="3" PHONE="a" CM="$cm"/>"""
    private fun wakeWhypo(word: String = "ハローバトラー", cm: Double = 0.078) =
        """<WHYPO WORD="$word" CLASSID="2" PHONE="h a r o: b a t o r a:" CM="$cm"/>"""

    @Test fun blockWithinFillerLimitHitsOnTerminator() {
        val p = parser(maxFillers = 6)
        assertFalse(p.feed("<RECOGOUT>"))
        assertFalse(p.feed("""  <SHYPO RANK="1" SCORE="-4188.459473">"""))
        assertFalse(p.feed(garbage()))
        assertFalse(p.feed(wakeWhypo()))
        assertFalse(p.feed(garbage()))
        assertFalse(p.feed("  </SHYPO>"))
        assertFalse(p.feed("</RECOGOUT>"))
        assertTrue(p.feed("."))
    }

    @Test fun blockWithTooManyFillersIsRejected() {
        val p = parser(maxFillers = 2)
        p.feed("<RECOGOUT>")
        repeat(3) { p.feed(garbage()) }
        p.feed(wakeWhypo())
        assertFalse(p.feed("."))
    }

    @Test fun blockAtFillerLimitHits() {
        val p = parser(maxFillers = 2)
        p.feed("<RECOGOUT>")
        repeat(2) { p.feed(garbage()) }
        p.feed(wakeWhypo())
        assertTrue(p.feed("."))
    }

    @Test fun blockWithNoWakeWordDoesNotHit() {
        val p = parser(maxFillers = 6)
        p.feed("<RECOGOUT>")
        repeat(2) { p.feed(garbage()) }
        assertFalse(p.feed("."))
    }

    @Test fun blockWithLowConfidenceWakeWordDoesNotHit() {
        val p = parser(maxFillers = 6)
        p.feed("<RECOGOUT>")
        p.feed(wakeWhypo(cm = 0.02))
        assertFalse(p.feed("."))
    }

    @Test fun multiBlockStreamJudgesEachBlockIndependently() {
        val p = parser(maxFillers = 2)
        // First block: too many fillers -> reject.
        p.feed("<RECOGOUT>")
        repeat(5) { p.feed(garbage()) }
        p.feed(wakeWhypo())
        assertFalse(p.feed("."))
        // Second block, immediately after: within limits -> hit. The leftover filler count from the
        // first (rejected) block must not carry over.
        p.feed("<RECOGOUT>")
        p.feed(garbage())
        p.feed(wakeWhypo())
        assertTrue(p.feed("."))
        // Third block: no wake word at all -> reject.
        p.feed("<RECOGOUT>")
        assertFalse(p.feed("."))
    }

    @Test fun statusLinesBetweenBlocksAreIgnored() {
        val p = parser(maxFillers = 6)
        assertFalse(p.feed("""<INPUT STATUS="LISTEN" TIME="1234567890.123"/>"""))
        p.feed("<RECOGOUT>")
        p.feed(wakeWhypo())
        assertTrue(p.feed("."))
        assertFalse(p.feed("""<INPUT STATUS="LISTEN" TIME="1234567890.456"/>"""))
    }

    @Test fun terminatorResetsStateForNextBlock() {
        val p = parser(maxFillers = 6)
        p.feed("<RECOGOUT>")
        p.feed(wakeWhypo())
        assertTrue(p.feed("."))
        // A stray terminator with no new block in between must not re-report the previous hit.
        assertFalse(p.feed("."))
        // A fresh block that doesn't itself hit confirms no state leaked across the reset.
        p.feed("<RECOGOUT>")
        assertFalse(p.feed("."))
    }

    @Test fun newRecogoutDiscardsUnterminatedPriorBlock() {
        val p = parser(maxFillers = 2)
        // Block never reaches its terminator (e.g. a dropped/garbled read) but had a hit accumulated.
        p.feed("<RECOGOUT>")
        p.feed(wakeWhypo())
        // A fresh <RECOGOUT> starts a new block outright; the abandoned block's state must not leak in.
        p.feed("<RECOGOUT>")
        repeat(5) { p.feed(garbage()) }
        assertFalse(p.feed("."))
    }

    @Test fun whypoOutsideBlockIsIgnored() {
        val p = parser(maxFillers = 6)
        // No <RECOGOUT> seen yet; a stray WHYPO line must not seed state for the next real block.
        assertFalse(p.feed(wakeWhypo()))
        p.feed("<RECOGOUT>")
        assertFalse(p.feed("."))
    }
}
