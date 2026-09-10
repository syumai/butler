package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test

class VoskWakeTest {
    private val phrase = "ハロー バトラー"

    @Test fun partialAdjacentPairHits() {
        assertTrue(VoskWake.hit("""{"partial": "ハロー バトラー"}""", phrase))
    }
    @Test fun finalAdjacentPairHits() {
        assertTrue(VoskWake.hit("""{"text": "ハロー バトラー"}""", phrase))
    }
    @Test fun leadingUnkBeforePairHits() {
        assertTrue(VoskWake.hit("""{"partial": "[unk] [unk] ハロー バトラー"}""", phrase))
    }
    @Test fun bareSecondWordDoesNotHit() {
        assertFalse(VoskWake.hit("""{"partial": "[unk] バトラー [unk]"}""", phrase))
    }
    @Test fun wordsSeparatedByUnkDoNotHit() {
        assertFalse(VoskWake.hit("""{"partial": "ハロー [unk] バトラー"}""", phrase))
    }
    @Test fun firstWordAloneDoesNotHit() {
        assertFalse(VoskWake.hit("""{"partial": "ハロー"}""", phrase))
    }
    @Test fun emptyJsonDoesNotHit() {
        assertFalse(VoskWake.hit("""{}""", phrase))
    }
    @Test fun malformedJsonDoesNotHit() {
        assertFalse(VoskWake.hit("not json", phrase))
    }
    @Test fun blankPartialDoesNotHit() {
        assertFalse(VoskWake.hit("""{"partial": ""}""", phrase))
    }
}
