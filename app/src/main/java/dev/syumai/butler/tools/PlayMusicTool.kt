package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import dev.syumai.butler.looksLikeMusicUri
import dev.syumai.butler.playerFriendlyName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays music on a Music Assistant player via `music_assistant.play_media` (see docs/architecture.md
 * "Music Assistant"). Resolves the target player ([resolveMusicPlayerOrError]), starts playback, then
 * waits briefly and re-reads the player's state so the result reports what actually started (Home
 * Assistant queues the play_media call and updates `media_title`/`media_artist`/`media_album_name`
 * asynchronously — there is no synchronous "now playing" response from the service call itself).
 */
class PlayMusicTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "play_music"
    override val busyStatus = R.string.status_music_play_busy
    override fun definition(): JSONObject {
        val properties = JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_query)))
            .put("media_type", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_media_type)))
            .put("artist", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_artist)))
            .put("album", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_album)))
            .put("enqueue", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_enqueue)))
            .put("player", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_play_music_param_player)))
        val parameters = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray().put("query"))
        return JSONObject().put("type", "function").put("name", name)
            .put("description", context.getString(R.string.tool_play_music_description)).put("parameters", parameters)
    }
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        val query = arguments.getString("query")
        val resolved = resolveMusicPlayerOrError(context, client, settings, arguments.optString("player").takeIf { it.isNotBlank() })
        if (resolved.has("error")) return@runCatching resolved
        val entityId = resolved.getString("entity_id")

        // A URI already fully identifies one item; artist/album filters only make sense alongside a
        // plain title/name query (see looksLikeMusicUri's doc comment).
        val isUri = looksLikeMusicUri(query)
        val artist = arguments.optString("artist").takeIf { it.isNotBlank() && !isUri }
        val album = arguments.optString("album").takeIf { it.isNotBlank() && !isUri }
        val mediaType = arguments.optString("media_type").takeIf { it.isNotBlank() }
        val enqueue = arguments.optString("enqueue").takeIf { it.isNotBlank() }
        client.musicAssistantPlayMedia(settings, entityId, query, mediaType, artist, album, enqueue, null)

        // music_assistant.play_media is fire-and-forget: give Home Assistant/Music Assistant a moment
        // to actually start playback and update the entity's media_* attributes before reading them
        // back — this runs on AssistantService's worker thread, never the main thread.
        Thread.sleep(1500)
        val fresh = client.fetchEntityState(settings, entityId)
        val attributes = fresh.optJSONObject("attributes") ?: JSONObject()
        JSONObject()
            .put("player", playerFriendlyName(resolved))
            .put("state", fresh.optString("state"))
            .put("media_title", attributes.optString("media_title"))
            .put("media_artist", attributes.optString("media_artist"))
            .put("media_album_name", attributes.optString("media_album_name"))
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
