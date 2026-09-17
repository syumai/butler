package dev.syumai.butler.tools

import android.content.Context
import dev.syumai.butler.R
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONObject

/**
 * Shared by [PlayMusicTool]/[NowPlayingTool]/[ControlMusicTool] (every Music Assistant tool except
 * [SearchMusicTool], which resolves a config entry id instead of a player): resolves the player via
 * [ToolClient.resolveMusicAssistantPlayer] and, on an error result
 * (`music_assistant_not_installed`/`player_not_found`), attaches a localized "message" the same way
 * every other Home Assistant tool does (see e.g. `GetHomeWeatherTool`). Returns the raw matched
 * `/api/states` entity on success, or `{"error","message","candidates"?}` — callers check
 * `has("error")`.
 */
fun resolveMusicPlayerOrError(context: Context, client: ToolClient, settings: Settings, playerQuery: String?): JSONObject {
    val resolved = client.resolveMusicAssistantPlayer(settings, playerQuery)
    if (!resolved.has("error")) return resolved
    val message = when (resolved.optString("error")) {
        "music_assistant_not_installed" -> context.getString(R.string.tool_music_error_not_installed)
        else -> context.getString(R.string.tool_music_error_player_not_found, playerQuery.orEmpty())
    }
    return resolved.put("message", message)
}
