package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONArray
import org.json.JSONObject

class ControlDevicesTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "control_devices"
    override val busyStatus = R.string.status_home_assistant_busy
    override fun definition(): JSONObject {
        val properties = JSONObject()
            .put("target", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_devices_param_target)))
            .put("action", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_devices_param_action)))
            .put("value", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_devices_param_value)))
            .put("area", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_devices_param_area)))
            .put("domain", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_devices_param_domain)))
        val parameters = JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray().put("target").put("action"))
        return JSONObject().put("type", "function").put("name", name)
            .put("description", context.getString(R.string.tool_control_devices_description))
            .put("parameters", parameters)
    }
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        client.homeAssistantControl(settings, arguments.getString("target"), arguments.getString("action"),
            arguments.optString("value").takeIf { it.isNotBlank() }, arguments.optString("domain").takeIf { it.isNotBlank() },
            arguments.optString("area").takeIf { it.isNotBlank() })
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
