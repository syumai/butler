package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class VoiceTest {
    @Test fun fromIdReturnsMatchingEntryForEveryId() {
        Voice.entries.forEach { assertEquals(it, Voice.fromId(it.id)) }
    }
    @Test fun fromIdIsCaseAndWhitespaceInsensitive() {
        assertEquals(Voice.CEDAR, Voice.fromId(" Cedar "))
        assertEquals(Voice.ALLOY, Voice.fromId("ALLOY"))
        assertEquals(Voice.SHIMMER, Voice.fromId("  shimmer"))
    }
    @Test fun fromIdFallsBackToDefaultForMissingOrUnknown() {
        assertEquals(Voice.DEFAULT, Voice.fromId(null))
        assertEquals(Voice.DEFAULT, Voice.fromId(""))
        assertEquals(Voice.DEFAULT, Voice.fromId("unknown"))
    }
    @Test fun defaultIsMarin() {
        assertEquals(Voice.MARIN, Voice.DEFAULT)
    }
    @Test fun idsAreDistinctAndNonBlank() {
        val ids = Voice.entries.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.toSet().size)
    }
}
