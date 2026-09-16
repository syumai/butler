package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test

class WakeEngineTest {
    @Test fun defaultIsJulius() {
        assertEquals(WakeEngine.JULIUS, WakeEngine.DEFAULT)
    }
    @Test fun fromNullFallsBackToJulius() {
        assertEquals(WakeEngine.JULIUS, WakeEngine.fromId(null))
    }
    @Test fun fromBlankFallsBackToJulius() {
        assertEquals(WakeEngine.JULIUS, WakeEngine.fromId(""))
        assertEquals(WakeEngine.JULIUS, WakeEngine.fromId("   "))
    }
    @Test fun fromUnknownFallsBackToJulius() {
        assertEquals(WakeEngine.JULIUS, WakeEngine.fromId("bogus"))
    }
    @Test fun fromVoskIdReturnsVosk() {
        assertEquals(WakeEngine.VOSK, WakeEngine.fromId("vosk"))
    }
    @Test fun fromJuliusIdReturnsJulius() {
        assertEquals(WakeEngine.JULIUS, WakeEngine.fromId("julius"))
    }
    @Test fun fromIdIsCaseAndWhitespaceInsensitive() {
        assertEquals(WakeEngine.VOSK, WakeEngine.fromId(" VOSK "))
    }
}
