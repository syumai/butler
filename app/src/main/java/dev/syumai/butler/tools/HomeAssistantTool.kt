package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class HomeAssistantTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "home_assistant"
    override val busyStatus = R.string.status_home_assistant_busy
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_home_assistant_description))
        .put("parameters", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""))
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        client.homeAssistant(settings, arguments.getString("text"), java.util.Locale.getDefault().language)
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
