package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import dev.syumai.butler.compactMusicSearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Searches the home's Music Assistant providers (Spotify, YouTube Music, etc. — whatever is
 * configured in Music Assistant) via `music_assistant.search`, returning a compact list of matches
 * (name/artists/album/uri/provider per item, non-empty categories only) so the model can pick a
 * result and pass its `uri` to `play_music`. Needs Music Assistant's config entry id
 * ([ToolClient.musicAssistantConfigEntryId], cached per conversation); unlike the other three music
 * tools this one doesn't target a player at all.
 */
class SearchMusicTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "search_music"
    override val busyStatus = R.string.status_music_search_busy
    override fun definition(): JSONObject {
        val properties = JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_search_music_param_query)))
            .put("media_type", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("description", context.getString(R.string.tool_search_music_param_media_type)))
            .put("artist", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_search_music_param_artist)))
            .put("album", JSONObject().put("type", "string").put("description", context.getString(R.string.tool_search_music_param_album)))
            .put("limit", JSONObject().put("type", "integer").put("description", context.getString(R.string.tool_search_music_param_limit)))
        val parameters = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray().put("query"))
        return JSONObject().put("type", "function").put("name", name)
            .put("description", context.getString(R.string.tool_search_music_description)).put("parameters", parameters)
    }
    override fun execute(arguments: JSONObject): JSONObject = runCatching {
        val configEntryId = client.musicAssistantConfigEntryId(settings)
            ?: return@runCatching JSONObject().put("error", "music_assistant_not_installed")
                .put("message", context.getString(R.string.tool_music_error_not_installed))
        val query = arguments.getString("query")
        val mediaTypeArg = arguments.opt("media_type")
        val mediaTypes: List<String> = when (mediaTypeArg) {
            is JSONArray -> (0 until mediaTypeArg.length()).mapNotNull { mediaTypeArg.optString(it).takeIf(String::isNotBlank) }
            is String -> mediaTypeArg.takeIf(String::isNotBlank)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
        val artist = arguments.optString("artist").takeIf { it.isNotBlank() }
        val album = arguments.optString("album").takeIf { it.isNotBlank() }
        val limit = (if (arguments.has("limit")) arguments.optInt("limit", 5) else 5).coerceIn(1, 10)
        val serviceResponse = client.musicAssistantSearch(settings, configEntryId, query, mediaTypes.takeIf { it.isNotEmpty() }, artist, album, limit)
        compactMusicSearchResult(serviceResponse, limit)
    }.getOrElse { JSONObject().put("error", context.getString(R.string.tool_home_assistant_error_connect, it.message)) }
}
