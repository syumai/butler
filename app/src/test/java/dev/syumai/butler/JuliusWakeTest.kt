package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test

class JuliusWakeTest {
    private val wake = listOf("ハローバトラー", "ヘイバトラー")

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
        assertTrue(JuliusWake.hit("""<WHYPO WORD="ヘイバトラー" CLASSID="2" PHONE="h e i b a t o r a:" CM="0.078"/>""", wake, 0.05))
    }
    @Test fun secondWakeWordBelowThresholdDoesNotHit() {
        assertFalse(JuliusWake.hit("""<WHYPO WORD="ヘイバトラー" CLASSID="2" PHONE="h e i b a t o r a:" CM="0.02"/>""", wake, 0.05))
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
}
