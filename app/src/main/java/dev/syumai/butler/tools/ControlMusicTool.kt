package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import dev.syumai.butler.controlMusicServiceCall
import dev.syumai.butler.playerFriendlyName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pause/resume/stop/skip/volume/mute/shuffle/repeat control for a Music Assistant player, via the
 * standard `media_player.*` Home Assistant services (these work on Music Assistant players like any
 * other `media_player` — see docs/architecture.md "Music Assistant"). [controlMusicServiceCall] maps
 * `action`/`value` to the service call; `volume_up`/`volume_down` step ±10 points from the resolved
 * player's current `volume_level`.
 */
class ControlMusicTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "control_music"
    override val busyStatus = R.string.status_music_control_busy
    override fun definition(): JSONObject {
        val properties = JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_music_param_action)))
            .put("value", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_music_param_value)))
            .put("player", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_control_music_param_player)))
        val parameters = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray().put("action"))
        return JSONObject().put("type", "function").put("name", name)
            .put("description", context.getString(R.string.tool_control_music_description)).put("parameters", parameters)
    }
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        val resolved = resolveMusicPlayerOrError(context, client, settings, arguments.optString("player").takeIf { it.isNotBlank() })
        if (resolved.has("error")) return@runCatching resolved
        val entityId = resolved.getString("entity_id")
        val action = arguments.getString("action")
        val value = arguments.optString("value").takeIf { it.isNotBlank() }
        val currentVolume = resolved.optJSONObject("attributes")?.optDouble("volume_level", Double.NaN)?.takeIf { !it.isNaN() }
        val call = controlMusicServiceCall(action, value, currentVolume)
            ?: return@runCatching JSONObject().put("error", "invalid_action_or_value")
                .put("message", context.getString(R.string.tool_control_music_error_invalid))
        val fresh = client.runMediaPlayerServiceCall(settings, entityId, call)
        val attributes = fresh.optJSONObject("attributes") ?: JSONObject()
        val volumeLevel = attributes.optDouble("volume_level", Double.NaN)
        JSONObject()
            .put("player", playerFriendlyName(resolved))
            .put("state", fresh.optString("state"))
            .put("volume", if (volumeLevel.isNaN()) JSONObject.NULL else Math.round(volumeLevel * 100))
            .put("is_volume_muted", attributes.optBoolean("is_volume_muted", false))
            .put("shuffle", attributes.optBoolean("shuffle", false))
            .put("repeat", attributes.optString("repeat", "off"))
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
