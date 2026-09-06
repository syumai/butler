package dev.syumai.butler

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ToolClient {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    fun cancel() { http.dispatcher.cancelAll() }
    private fun request(request: Request): JSONObject = http.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        val source = it.body!!.source()
        check(!source.request(1_000_001)) { "Response too large" }
        JSONObject(source.readUtf8())
    }
    fun search(settings: Settings, query: String): JSONObject {
        val body = JSONObject().put("model", settings.get("searchModel", "gpt-4.1-mini"))
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
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto").build())
}
