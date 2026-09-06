package dev.syumai.butler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class HomeAssistantTest {
    @Test fun actionDone() {
        val json = JSONObject("""{"response":{"response_type":"action_done","speech":{"plain":{"speech":"リビングの照明をつけました"}},
            "data":{"targets":[],"success":[{"id":"light.living_room","name":"リビング 照明","type":"entity"}],"failed":[]}}}""")
        val result = parseAssist(json)
        assertEquals("action_done", result.getString("type"))
        assertEquals("リビングの照明をつけました", result.getString("speech"))
        assertEquals("リビング 照明", result.getJSONArray("success").getString(0))
        assertEquals(0, result.getJSONArray("failed").length())
    }
    @Test fun queryAnswer() {
        val json = JSONObject("""{"response":{"response_type":"query_answer","speech":{"plain":{"speech":"寝室は24度です"}}}}""")
        val result = parseAssist(json)
        assertEquals("query_answer", result.getString("type"))
        assertEquals("寝室は24度です", result.getString("speech"))
        assertFalse(result.has("success"))
    }
    @Test fun error() {
        val json = JSONObject("""{"response":{"response_type":"error","speech":{"plain":{"speech":"デバイスが見つかりません"}}}}""")
        val result = parseAssist(json)
        assertEquals("error", result.getString("type"))
        assertEquals("デバイスが見つかりません", result.getString("speech"))
    }
}
