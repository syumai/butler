package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class ListHomeDevicesTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "list_home_devices"
    override val busyStatus = R.string.status_home_devices_busy
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_list_home_devices_description))
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("domain",
            JSONObject().put("type", "string").put("description", context.getString(R.string.tool_list_home_devices_param_domain)))))
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        client.homeAssistantDevices(settings, arguments.optString("domain").takeIf { it.isNotBlank() })
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
