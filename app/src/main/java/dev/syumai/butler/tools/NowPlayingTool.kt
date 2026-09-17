package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import dev.syumai.butler.compactMusicQueue
import dev.syumai.butler.playerFriendlyName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reports what's currently playing on a Music Assistant player: `music_assistant.get_queue` for the
 * full queue (current/next item, shuffle, repeat, elapsed time, queue length — see
 * [compactMusicQueue]); if that call fails (e.g. an older Music Assistant version, or the player has
 * no active queue), falls back to the resolved player's own `media_player` state attributes
 * (`media_title`/`media_artist`/`media_album_name`/`media_duration`/`media_position`).
 */
class NowPlayingTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "now_playing"
    override val busyStatus = R.string.status_music_now_playing_busy
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_now_playing_description))
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("player",
            JSONObject().put("type", "string").put("description", context.getString(R.string.tool_now_playing_param_player)))))
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        val resolved = resolveMusicPlayerOrError(context, client, settings, arguments.optString("player").takeIf { it.isNotBlank() })
        if (resolved.has("error")) return@runCatching resolved
        val entityId = resolved.getString("entity_id")
        val playerName = playerFriendlyName(resolved)
        val queue = runCatching { client.musicAssistantGetQueue(settings, entityId) }.getOrNull()
        if (queue != null && queue.length() > 0) {
            return@runCatching compactMusicQueue(queue).put("player", playerName).put("state", resolved.optString("state"))
        }
        val attributes = resolved.optJSONObject("attributes") ?: JSONObject()
        val artists = JSONArray(); attributes.optString("media_artist").takeIf { it.isNotBlank() }?.let { artists.put(it) }
        JSONObject().put("player", playerName).put("state", resolved.optString("state"))
            .put("current", JSONObject().put("title", attributes.optString("media_title"))
                .put("artists", artists).put("album", attributes.optString("media_album_name")))
            .put("duration_s", attributes.optDouble("media_duration", 0.0))
            .put("elapsed_s", attributes.optDouble("media_position", 0.0))
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
