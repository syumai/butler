package dev.syumai.butler

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicAssistantTest {
    private fun player(id: String, name: String, state: String, area: String = "", massType: String? = "player", volume: Double? = null): JSONObject {
        val attributes = JSONObject().put("friendly_name", name)
        if (massType != null) attributes.put("mass_player_type", massType)
        volume?.let { attributes.put("volume_level", it) }
        return JSONObject().put("entity_id", id).put("state", state).put("area", area).put("attributes", attributes)
    }
    private fun states(vararg entities: JSONObject): JSONArray { val a = JSONArray(); entities.forEach { a.put(it) }; return a }

    // --- musicAssistantPlayers / isMusicAssistantPlayer ---

    @Test fun onlyMediaPlayersWithMassPlayerTypeCount() {
        val ma = player("media_player.living_room", "Living Room", "playing")
        val plain = player("media_player.tv", "TV", "off", massType = null)
        val light = JSONObject().put("entity_id", "light.kitchen").put("state", "on").put("area", "")
            .put("attributes", JSONObject().put("mass_player_type", "player")) // wrong domain, must not count
        val players = musicAssistantPlayers(states(ma, plain, light))
        assertEquals(1, players.size)
        assertEquals("media_player.living_room", players[0].optString("entity_id"))
    }

    // --- resolveMusicPlayer ---

    @Test fun noMassPlayersReportsNotInstalled() {
        val result = resolveMusicPlayer(states(player("media_player.tv", "TV", "off", massType = null)), null, null)
        assertEquals("music_assistant_not_installed", result.getString("error"))
    }

    @Test fun resolvesByExactEntityId() {
        val target = player("media_player.living_room", "Living Room", "off")
        val other = player("media_player.bedroom", "Bedroom", "off")
        val result = resolveMusicPlayer(states(target, other), "media_player.bedroom", null)
        assertEquals("media_player.bedroom", result.getString("entity_id"))
    }

    @Test fun resolvesByFriendlyNameContainment() {
        val target = player("media_player.living_room", "Living Room Speaker", "off")
        val result = resolveMusicPlayer(states(target), "living room", null)
        assertEquals("media_player.living_room", result.getString("entity_id"))
    }

    @Test fun queryNotMatchingAnyPlayerReturnsCandidates() {
        val a = player("media_player.living_room", "Living Room", "off")
        val b = player("media_player.bedroom", "Bedroom", "off")
        val result = resolveMusicPlayer(states(a, b), "garage", null)
        assertEquals("player_not_found", result.getString("error"))
        val candidates = result.getJSONArray("candidates")
        assertEquals(2, candidates.length())
    }

    @Test fun prefersSettingsPlayerWhenNoQueryGiven() {
        val a = player("media_player.living_room", "Living Room", "off")
        val b = player("media_player.bedroom", "Bedroom", "off")
        val result = resolveMusicPlayer(states(a, b), null, "media_player.bedroom")
        assertEquals("media_player.bedroom", result.getString("entity_id"))
    }

    @Test fun fallsBackToPlayingThenPausedThenFirst() {
        val idle = player("media_player.a", "A", "idle")
        val paused = player("media_player.b", "B", "paused")
        val playing = player("media_player.c", "C", "playing")
        assertEquals("media_player.c", resolveMusicPlayer(states(idle, paused, playing), null, null).getString("entity_id"))
        assertEquals("media_player.b", resolveMusicPlayer(states(idle, paused), null, null).getString("entity_id"))
        assertEquals("media_player.a", resolveMusicPlayer(states(idle), null, null).getString("entity_id"))
    }

    @Test fun settingsPlayerIgnoredWhenNotAMassPlayer() {
        val playing = player("media_player.c", "C", "playing")
        val result = resolveMusicPlayer(states(playing), null, "media_player.nonexistent")
        assertEquals("media_player.c", result.getString("entity_id"))
    }

    // --- compactMusicSearchResult ---

    private fun searchItem(name: String, artists: List<String>, album: String?, uri: String, provider: String): JSONObject {
        val item = JSONObject().put("name", name).put("uri", uri).put("provider", provider)
        val artistsArray = JSONArray(); artists.forEach { artistsArray.put(JSONObject().put("name", it)) }
        item.put("artists", artistsArray)
        if (album != null) item.put("album", JSONObject().put("name", album))
        return item
    }

    @Test fun compactSearchResultDropsEmptyCategoriesAndFlattensArtists() {
        val response = JSONObject()
            .put("tracks", JSONArray().put(searchItem("Bohemian Rhapsody", listOf("Queen"), "A Night at the Opera", "library://track/1", "library")))
            .put("albums", JSONArray())
        val result = compactMusicSearchResult(response, 5)
        assertFalse(result.has("albums")) // empty category dropped
        assertFalse(result.has("artists")) // missing category not added
        val tracks = result.getJSONArray("tracks")
        assertEquals(1, tracks.length())
        val track = tracks.getJSONObject(0)
        assertEquals("Bohemian Rhapsody", track.getString("name"))
        assertEquals("Queen", track.getJSONArray("artists").getString(0))
        assertEquals("A Night at the Opera", track.getString("album"))
        assertEquals("library://track/1", track.getString("uri"))
        assertEquals("library", track.getString("provider"))
    }

    @Test fun compactSearchResultRespectsLimit() {
        val items = JSONArray()
        repeat(10) { items.put(searchItem("Track $it", listOf("Artist"), null, "library://track/$it", "library")) }
        val result = compactMusicSearchResult(JSONObject().put("tracks", items), 3)
        assertEquals(3, result.getJSONArray("tracks").length())
    }

    // --- compactMusicQueue ---

    @Test fun compactQueueBuildsCurrentAndNext() {
        fun queueItem(title: String, artist: String) = JSONObject().put("media_item", JSONObject()
            .put("name", title).put("artists", JSONArray().put(JSONObject().put("name", artist))))
        val queue = JSONObject()
            .put("current_item", queueItem("Song A", "Artist A"))
            .put("next_item", queueItem("Song B", "Artist B"))
            .put("shuffle_enabled", true).put("repeat_mode", "all").put("elapsed_time", 42.5).put("items", 12)
        val result = compactMusicQueue(queue)
        assertEquals("Song A", result.getJSONObject("current").getString("title"))
        assertEquals("Artist A", result.getJSONObject("current").getJSONArray("artists").getString(0))
        assertEquals("Song B", result.getJSONObject("next").getString("title"))
        assertTrue(result.getBoolean("shuffle"))
        assertEquals("all", result.getString("repeat"))
        assertEquals(42.5, result.getDouble("elapsed_s"), 0.0001)
        assertEquals(12, result.getInt("queue_items"))
    }

    @Test fun compactQueueOmitsMissingCurrentAndNext() {
        val result = compactMusicQueue(JSONObject().put("shuffle_enabled", false).put("repeat_mode", "off"))
        assertFalse(result.has("current"))
        assertFalse(result.has("next"))
    }

    // --- looksLikeMusicUri ---

    @Test fun uriDetection() {
        assertTrue(looksLikeMusicUri("spotify://track/abc123"))
        assertTrue(looksLikeMusicUri("library://track/123"))
        assertTrue(looksLikeMusicUri("http://example.com/track"))
        assertTrue(looksLikeMusicUri("https://example.com/track"))
        assertFalse(looksLikeMusicUri("Bohemian Rhapsody"))
        assertFalse(looksLikeMusicUri("Queen - Bohemian Rhapsody"))
        assertFalse(looksLikeMusicUri(""))
    }

    // --- volume step / clamp ---

    @Test fun volumeStepClampsToRange() {
        assertEquals(100, volumeStep(0.95, 10))
        assertEquals(0, volumeStep(0.05, -10))
        assertEquals(60, volumeStep(0.5, 10))
        assertEquals(40, volumeStep(0.5, -10))
    }

    @Test fun clampVolumePercentClamps() {
        assertEquals(100, clampVolumePercent(150))
        assertEquals(0, clampVolumePercent(-20))
        assertEquals(55, clampVolumePercent(55))
    }

    // --- controlMusicServiceCall ---

    @Test fun controlMusicMapsSimpleActions() {
        assertEquals("media_play", controlMusicServiceCall("resume", null, null)!!.service)
        assertEquals("media_pause", controlMusicServiceCall("pause", null, null)!!.service)
        assertEquals("media_stop", controlMusicServiceCall("stop", null, null)!!.service)
        assertEquals("media_next_track", controlMusicServiceCall("next", null, null)!!.service)
        assertEquals("media_previous_track", controlMusicServiceCall("previous", null, null)!!.service)
    }

    @Test fun controlMusicSetVolumeParsesAndClampsValue() {
        val call = controlMusicServiceCall("set_volume", "150", null)!!
        assertEquals("volume_set", call.service)
        assertEquals(1.0, call.data.getDouble("volume_level"), 0.0001) // 150 clamped to 100 -> 1.0
    }

    @Test fun controlMusicSetVolumeWithMissingValueReturnsNull() {
        assertNull(controlMusicServiceCall("set_volume", null, null))
        assertNull(controlMusicServiceCall("set_volume", "not-a-number", null))
    }

    @Test fun controlMusicVolumeUpDownStepFromCurrentLevel() {
        val up = controlMusicServiceCall("volume_up", null, 0.5)!!
        assertEquals(0.6, up.data.getDouble("volume_level"), 0.0001)
        val down = controlMusicServiceCall("volume_down", null, 0.5)!!
        assertEquals(0.4, down.data.getDouble("volume_level"), 0.0001)
    }

    @Test fun controlMusicShuffleAndRepeat() {
        assertTrue(controlMusicServiceCall("shuffle_on", null, null)!!.data.getBoolean("shuffle"))
        assertFalse(controlMusicServiceCall("shuffle_off", null, null)!!.data.getBoolean("shuffle"))
        assertEquals("one", controlMusicServiceCall("repeat_one", null, null)!!.data.getString("repeat"))
        assertEquals("all", controlMusicServiceCall("repeat_all", null, null)!!.data.getString("repeat"))
        assertEquals("off", controlMusicServiceCall("repeat_off", null, null)!!.data.getString("repeat"))
    }

    @Test fun controlMusicUnknownActionReturnsNull() {
        assertNull(controlMusicServiceCall("dance", null, null))
    }
}
