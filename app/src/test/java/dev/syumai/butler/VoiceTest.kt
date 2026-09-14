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
    @Test fun fromIdWithApiReturnsMatchingEntryForEveryValidId() {
        Voice.entries.forEach { voice -> voice.apis.forEach { api -> assertEquals(voice, Voice.fromId(voice.id, api)) } }
    }
    @Test fun fromIdWithApiFallsBackWhenVoiceNotValidForApi() {
        assertEquals(Voice.MARIN, Voice.fromId("quartz", VoiceApi.REALTIME))
        assertEquals(Voice.MARIN, Voice.fromId("alloy", VoiceApi.LIVE))
        assertEquals(Voice.MARIN, Voice.fromId("cedar", VoiceApi.LIVE))
    }
    @Test fun fromIdWithApiFallsBackToDefaultForMissingOrUnknown() {
        assertEquals(Voice.DEFAULT, Voice.fromId(null, VoiceApi.REALTIME))
        assertEquals(Voice.DEFAULT, Voice.fromId("", VoiceApi.LIVE))
        assertEquals(Voice.DEFAULT, Voice.fromId("unknown", VoiceApi.LIVE))
    }
    @Test fun marinIsValidForBothApis() {
        assertEquals(setOf(VoiceApi.REALTIME, VoiceApi.LIVE), Voice.MARIN.apis)
    }
    @Test fun forApiListsOnlyVoicesValidForThatApi() {
        val live = Voice.forApi(VoiceApi.LIVE)
        assertTrue(live.contains(Voice.QUARTZ))
        assertTrue(live.contains(Voice.MARIN))
        assertFalse(live.contains(Voice.ALLOY))
        val realtime = Voice.forApi(VoiceApi.REALTIME)
        assertTrue(realtime.contains(Voice.ALLOY))
        assertTrue(realtime.contains(Voice.MARIN))
        assertFalse(realtime.contains(Voice.QUARTZ))
    }
    @Test fun forApiCoversEveryVoiceAcrossBothApis() {
        val union = (Voice.forApi(VoiceApi.REALTIME) + Voice.forApi(VoiceApi.LIVE)).toSet()
        assertEquals(Voice.entries.toSet(), union)
    }
}
