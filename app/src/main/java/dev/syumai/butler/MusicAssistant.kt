package dev.syumai.butler

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (no Android imports) helpers for the Music Assistant voice tools (`play_music`,
 * `search_music`, `now_playing`, `control_music` in `dev.syumai.butler.tools`) — see
 * docs/architecture.md "Music Assistant" for the facts this was built from (home-assistant/core's
 * `music_assistant` integration source, read 2026-09-17; this Home Assistant instance has no Music
 * Assistant installed and no `media_player` entities at all, so none of this could be checked live).
 *
 * A Music Assistant (MA) player is an ordinary Home Assistant `media_player.*` entity whose
 * attributes carry `mass_player_type` — that attribute is the only way to tell an MA player apart
 * from any other `media_player` entity (a Chromecast, a Sonos speaker managed directly by its own
 * integration, etc.), which [isMusicAssistantPlayer]/[musicAssistantPlayers] rely on. The HTTP calls
 * themselves ([ToolClient.musicAssistantPlayMedia], `.musicAssistantSearch`, `.musicAssistantGetQueue`,
 * `.musicAssistantConfigEntryId`, `.fetchEntityState`) live on [ToolClient] since they need OkHttp;
 * this file holds only the parts that can be unit-tested on the JVM.
 */

/** True when [entity] (a raw Home Assistant `/api/states` entity, as [ToolClient]'s private
 * `fetchStates` returns) is a Music Assistant player. */
fun isMusicAssistantPlayer(entity: JSONObject): Boolean {
    if (!entity.optString("entity_id").startsWith("media_player.")) return false
    return entity.optJSONObject("attributes")?.has("mass_player_type") == true
}

/** Every Music Assistant player in a raw `/api/states` array, in states order. */
fun musicAssistantPlayers(states: JSONArray): List<JSONObject> =
    (0 until states.length()).mapNotNull { states.optJSONObject(it) }.filter(::isMusicAssistantPlayer)

/** A player's display name: its `friendly_name` attribute, falling back to its entity id. */
fun playerFriendlyName(entity: JSONObject): String =
    entity.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { entity.optString("entity_id") }

/**
 * Resolves which Music Assistant player a `play_music`/`search_music`/`now_playing`/`control_music`
 * call should act on, from a raw `/api/states` array (each entity carrying a top-level "area" string,
 * as [ToolClient]'s private `fetchStates` stamps it). Precedence, per the task's "Player resolution":
 *  1. [query] (free text the user named — a room, a player's friendly name, or its entity id):
 *     exact entity id, then exact normalized-name match, then normalized-name containment, then an
 *     exact normalized-area match, then the closest [deviceMatchScore] match (same 0.3 threshold
 *     [resolveDevices] uses) — ties broken by players order (first match wins).
 *  2. [settingPlayerId] (`Settings.get("musicPlayer")`), when it names an MA player.
 *  3. The first MA player that is "playing".
 *  4. The first MA player that is "paused".
 *  5. The first MA player, in any state.
 *
 * Returns `{"error":"music_assistant_not_installed"}` when [states] has no MA player at all,
 * `{"error":"player_not_found","candidates":[<name>, ...]}` when [query] is given but matches no MA
 * player, or the matched raw `/api/states` entity otherwise (its `entity_id`/`state`/`attributes`/
 * `area` fields — callers build their own response shape from it, e.g. via [playerFriendlyName]).
 * Carries no localized text; callers with a `Context` attach a "message" to an error result (see
 * `dev.syumai.butler.tools.resolveMusicPlayerOrError`).
 */
fun resolveMusicPlayer(states: JSONArray, query: String?, settingPlayerId: String?): JSONObject {
    val players = musicAssistantPlayers(states)
    if (players.isEmpty()) return JSONObject().put("error", "music_assistant_not_installed")

    val trimmedQuery = query?.trim()
    if (!trimmedQuery.isNullOrEmpty()) {
        players.find { it.optString("entity_id") == trimmedQuery }?.let { return it }
        val normalizedQuery = normalizeDeviceName(trimmedQuery)
        players.find { normalizeDeviceName(playerFriendlyName(it)) == normalizedQuery }?.let { return it }
        players.find { normalizeDeviceName(playerFriendlyName(it)).contains(normalizedQuery) }?.let { return it }
        players.find { normalizeDeviceName(it.optString("area")).let { area -> area.isNotEmpty() && area == normalizedQuery } }?.let { return it }
        val scored = players.map { it to deviceMatchScore(normalizedQuery, normalizeDeviceName(playerFriendlyName(it))) }
            .filter { it.second >= 0.3f }.sortedByDescending { it.second }
        scored.firstOrNull()?.let { return it.first }
        val candidates = JSONArray(); players.forEach { candidates.put(playerFriendlyName(it)) }
        return JSONObject().put("error", "player_not_found").put("candidates", candidates)
    }

    if (!settingPlayerId.isNullOrBlank()) {
        players.find { it.optString("entity_id") == settingPlayerId }?.let { return it }
    }
    players.find { it.optString("state") == "playing" }?.let { return it }
    players.find { it.optString("state") == "paused" }?.let { return it }
    return players.first()
}

/** A search category kept by [compactMusicSearchResult], in the order `music_assistant.search`
 * returns them in its `service_response`. */
private val MUSIC_SEARCH_CATEGORIES = listOf("artists", "albums", "tracks", "playlists", "radio", "audiobooks", "podcasts")

private fun itemArtistNames(item: JSONObject): List<String> {
    val artists = item.optJSONArray("artists") ?: return emptyList()
    return (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
}

/**
 * Compacts a `music_assistant.search` `service_response` (categories `artists`/`albums`/`tracks`/
 * `playlists`/`radio`/`audiobooks`/`podcasts`, each a list of items with `uri`/`name`/`artists`
 * (list of `{name,...}`)/`album` (`{name,...}`)/`provider`) into `{<category>: [{"name","artists"
 * (flattened to names),"album","uri","provider"}, ...]}`, dropping empty categories entirely and
 * capping each to [limit] items — so the model can pick a result and call `play_music` with its
 * `uri`.
 */
fun compactMusicSearchResult(serviceResponse: JSONObject, limit: Int): JSONObject {
    val result = JSONObject()
    for (category in MUSIC_SEARCH_CATEGORIES) {
        val items = serviceResponse.optJSONArray(category) ?: continue
        val out = JSONArray()
        for (i in 0 until minOf(items.length(), limit)) {
            val item = items.optJSONObject(i) ?: continue
            out.put(JSONObject()
                .put("name", item.optString("name"))
                .put("artists", JSONArray(itemArtistNames(item)))
                .put("album", item.optJSONObject("album")?.optString("name").orEmpty())
                .put("uri", item.optString("uri"))
                .put("provider", item.optString("provider")))
        }
        if (out.length() > 0) result.put(category, out)
    }
    return result
}

/**
 * Compacts a `music_assistant.get_queue` `service_response` (`shuffle_enabled`, `repeat_mode`,
 * `elapsed_time`, `items` (a count), `current_item`/`next_item`, each `{"name", "media_item":
 * {"name","artists","album",...}}`) into `{"current"?, "next"?: {"title","artists","album"}, "shuffle",
 * "repeat", "elapsed_s", "queue_items"}` for `now_playing`. `current`/`next` are omitted when the
 * queue has none.
 */
fun compactMusicQueue(queue: JSONObject): JSONObject {
    fun compactItem(key: String): JSONObject? {
        val entry = queue.optJSONObject(key) ?: return null
        val media = entry.optJSONObject("media_item") ?: JSONObject()
        val title = media.optString("name").ifBlank { entry.optString("name") }
        return JSONObject().put("title", title).put("artists", JSONArray(itemArtistNames(media)))
            .put("album", media.optJSONObject("album")?.optString("name").orEmpty())
    }
    val result = JSONObject()
    compactItem("current_item")?.let { result.put("current", it) }
    compactItem("next_item")?.let { result.put("next", it) }
    return result.put("shuffle", queue.optBoolean("shuffle_enabled", false))
        .put("repeat", queue.optString("repeat_mode", "off"))
        .put("elapsed_s", queue.optDouble("elapsed_time", 0.0))
        .put("queue_items", queue.optInt("items", 0))
}

private val MUSIC_URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** True when [value] looks like a URI (e.g. a `uri` field `search_music` returned, such as
 * `spotify://track/…`, `library://track/123` or an `http(s)://` link) rather than a plain title/name
 * to search for — `play_music`'s `query` accepts either, and a URI already fully identifies one item,
 * so `artist`/`album` filters are only meaningful (and only forwarded) when it isn't one. */
fun looksLikeMusicUri(value: String): Boolean = MUSIC_URI_SCHEME.containsMatchIn(value.trim())

/** Clamps a volume percent (as sent in `control_music`'s `value`, or computed from a step) to 0..100. */
fun clampVolumePercent(value: Int): Int = value.coerceIn(0, 100)

/** The new volume percent for `volume_up`/`volume_down`: [currentVolumeLevel] (Home Assistant's
 * 0.0..1.0 `volume_level` attribute) plus/minus [deltaPercent] points, clamped to 0..100. */
fun volumeStep(currentVolumeLevel: Double, deltaPercent: Int): Int = clampVolumePercent(Math.round(currentVolumeLevel * 100).toInt() + deltaPercent)

/**
 * Maps a `control_music` [action] (pause|resume|stop|next|previous|set_volume|volume_up|volume_down|
 * mute|unmute|shuffle_on|shuffle_off|repeat_off|repeat_one|repeat_all) plus an optional [value]
 * (0..100, `set_volume` only) and the player's current `volume_level` ([currentVolumeLevel], 0.0..1.0,
 * used by `volume_up`/`volume_down`'s ±10-point step) to the standard `media_player.*` Home Assistant
 * service call it corresponds to (these work on Music Assistant players like any other media_player —
 * see docs/architecture.md), or null when [action] is unrecognized, or is `set_volume` with a missing
 * or non-numeric [value].
 */
fun controlMusicServiceCall(action: String, value: String?, currentVolumeLevel: Double?): ServiceCall? {
    fun volumeCall(percent: Int) = ServiceCall("media_player", "volume_set", JSONObject().put("volume_level", percent / 100.0))
    return when (action) {
        "pause" -> ServiceCall("media_player", "media_pause", JSONObject())
        "resume" -> ServiceCall("media_player", "media_play", JSONObject())
        "stop" -> ServiceCall("media_player", "media_stop", JSONObject())
        "next" -> ServiceCall("media_player", "media_next_track", JSONObject())
        "previous" -> ServiceCall("media_player", "media_previous_track", JSONObject())
        "set_volume" -> value?.trim()?.toDoubleOrNull()?.let { volumeCall(clampVolumePercent(Math.round(it).toInt())) }
        "volume_up" -> volumeCall(volumeStep(currentVolumeLevel ?: 0.5, 10))
        "volume_down" -> volumeCall(volumeStep(currentVolumeLevel ?: 0.5, -10))
        "mute" -> ServiceCall("media_player", "volume_mute", JSONObject().put("is_volume_muted", true))
        "unmute" -> ServiceCall("media_player", "volume_mute", JSONObject().put("is_volume_muted", false))
        "shuffle_on" -> ServiceCall("media_player", "shuffle_set", JSONObject().put("shuffle", true))
        "shuffle_off" -> ServiceCall("media_player", "shuffle_set", JSONObject().put("shuffle", false))
        "repeat_off" -> ServiceCall("media_player", "repeat_set", JSONObject().put("repeat", "off"))
        "repeat_one" -> ServiceCall("media_player", "repeat_set", JSONObject().put("repeat", "one"))
        "repeat_all" -> ServiceCall("media_player", "repeat_set", JSONObject().put("repeat", "all"))
        else -> null
    }
}
