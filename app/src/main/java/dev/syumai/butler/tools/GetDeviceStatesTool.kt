package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

class GetDeviceStatesTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "get_device_states"
    override val busyStatus = R.string.status_home_state_busy
    override fun definition(): JSONObject {
        val properties = JSONObject()
            .put("target", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_get_device_states_param_target)))
            .put("area", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_get_device_states_param_area)))
            .put("domain", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_get_device_states_param_domain)))
        val parameters = JSONObject().put("type", "object").put("properties", properties)
        return JSONObject().put("type", "function").put("name", name)
            .put("description", context.getString(R.string.tool_get_device_states_description))
            .put("parameters", parameters)
    }
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        client.homeAssistantDeviceStates(settings, arguments.getString("target"), arguments.optString("domain").takeIf { it.isNotBlank() },
            arguments.optString("area").takeIf { it.isNotBlank() })
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
