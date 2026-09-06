package dev.syumai.butler

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ToolClient {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val haHttp = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    fun cancel() { http.dispatcher.cancelAll(); haHttp.dispatcher.cancelAll() }
    private fun request(request: Request, client: OkHttpClient = http): JSONObject = client.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        val source = it.body!!.source()
        check(!source.request(1_000_001)) { "Response too large" }
        JSONObject(source.readUtf8())
    }
    fun search(settings: Settings, query: String): JSONObject {
        val body = JSONObject().put("model", settings.get("searchModel", "gpt-5.6-luna"))
            .put("store", false).put("max_output_tokens", 1800)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .put("tool_choice", "required").put("input", "日本語で簡潔に回答し、出典を付けてください。質問: ${query.take(2000)}")
        val response = request(Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${settings.secret("openai")}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val result = JSONObject(); val parts = JSONArray()
        val output = response.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val content = output.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) if (content.getJSONObject(j).optString("type") == "output_text") parts.put(content.getJSONObject(j))
        }
        return result.put("content", parts)
    }
    fun weather(lat: Double, lon: Double): JSONObject = request(Request.Builder().url(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto").build())
    fun homeAssistant(settings: Settings, text: String): JSONObject {
        val body = JSONObject().put("text", text).put("language", "ja")
        val response = runCatching {
            request(Request.Builder().url("${settings.get("haUrl")}/api/conversation/process")
                .header("Authorization", "Bearer ${settings.secret("haToken")}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(), haHttp)
        }.getOrElse { return JSONObject().put("error", "Home Assistantに接続できません（${it.message}）") }
        return parseAssist(response)
    }
}
/** Pure parsing of a Home Assistant /api/conversation/process response into a compact model-facing result. */
fun parseAssist(json: JSONObject): JSONObject {
    val response = json.optJSONObject("response") ?: return JSONObject().put("error", "Home Assistantの応答を解析できません")
    val type = response.optString("response_type")
    val speech = response.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
    val result = JSONObject().put("speech", speech).put("type", type)
    if (type == "action_done") {
        val data = response.optJSONObject("data")
        fun names(key: String): JSONArray {
            val out = JSONArray(); val arr = data?.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) out.put(arr.optJSONObject(i)?.optString("name").orEmpty())
            return out
        }
        result.put("success", names("success")).put("failed", names("failed"))
    }
    return result
}
