package dev.syumai.butler

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Process-wide holder for the most recent `search_web` results shown as picture cards on the home
 * screen's standby viewer ([SearchCardsView]) — the same static-state pattern as
 * [AssistantService.status]/`homeStateSerial`. Written off the main thread ([SearchWebTool.execute]
 * runs on AssistantService's worker), so plain `@Volatile` fields (each write swaps in a whole new
 * immutable list) stand in for the Handler-post AssistantService itself uses, avoiding a dependency
 * on a Looper existing on the writing thread.
 */
object SearchCards {
    /** One resolved card: [bitmap] is null for a text-only card (no image tier resolved one in time/at all). */
    data class Card(val id: Int, val title: String, val description: String, val url: String, val bitmap: Bitmap?)

    @Volatile var query: String = ""; private set
    @Volatile var cards: List<Card> = emptyList(); private set
    @Volatile var serial: Int = 0; private set

    /** Publishes a new result set, bumping [serial] so MainActivity's tick notices even when the new
     * list happens to be equal in content to the old one (e.g. re-running the same search). */
    @Synchronized fun publish(query: String, cards: List<Card>) {
        this.query = query; this.cards = cards; serial++
    }

    /** Drops all cards/bitmaps (viewer hidden, or a search that found nothing worth showing). */
    @Synchronized fun clear() {
        if (cards.isEmpty() && query.isEmpty()) return
        query = ""; cards = emptyList(); serial++
    }

    /** One item from the model's `{spoken, items:[...]}` JSON (see `prompt_search_input`'s schema),
     * before image resolution. */
    data class ParsedItem(val title: String, val description: String, val url: String, val wikipediaTitle: String?, val imageQuery: String)
    data class ParsedSearch(val spoken: String, val items: List<ParsedItem>)

    const val MAX_ITEMS = 5
    private const val DESCRIPTION_MAX_CHARS = 300

    /**
     * Pure parser for the strict-JSON-schema Responses output (search_web's `output_text`). Throws
     * [org.json.JSONException] when [json] isn't valid JSON at all (the caller falls back to a plain,
     * text-only search in that case); tolerates anything short of that — a missing/blank field
     * defaults rather than throwing, an item missing `title`/`url` is dropped rather than kept
     * half-formed, `items` beyond [MAX_ITEMS] are ignored (the schema already caps this, but the
     * model is untrusted), and each description is truncated to ~300 chars.
     */
    fun parse(json: String): ParsedSearch {
        val root = JSONObject(json)
        val spoken = root.optString("spoken").trim()
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until minOf(itemsArray.length(), MAX_ITEMS)).mapNotNull { i ->
            val item = itemsArray.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            val description = item.optString("description").trim().take(DESCRIPTION_MAX_CHARS)
            val wikipediaTitle = item.optString("wikipedia_title").trim().takeIf { it.isNotEmpty() && it != "null" }
            val imageQuery = item.optString("image_query").trim().ifEmpty { title }
            ParsedItem(title, description, url, wikipediaTitle, imageQuery)
        }
        return ParsedSearch(spoken, items)
    }

    /** Builds the `content` array AssistantService (`citations = result.optJSONArray("content")`) and
     * MainActivity.showSources() already understand unchanged: one `output_text` part whose text is
     * every item's title, one per line, with a `url_citation` annotation per line whose
     * start/end_index cover exactly that title's line — reused by the Live tool-result path's own
     * citation collector as the shape to match. */
    fun buildContent(items: List<ParsedItem>): JSONArray {
        var text = ""
        val annotations = JSONArray()
        items.forEach { item ->
            if (text.isNotEmpty()) text += "\n"
            val start = text.length
            text += item.title
            annotations.put(JSONObject().put("type", "url_citation").put("url", item.url)
                .put("title", item.title).put("start_index", start).put("end_index", text.length))
        }
        return JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", annotations))
    }

    /** Cheap highlight check for the viewer's 250ms tick (no allocation beyond two `contains` calls):
     * true when [title] itself, or its first 8 characters (a title longer than the spoken reply is
     * likely to quote verbatim, e.g. "Capybara Facts | National Geographic"), appears in
     * [transcript]. The 8-char prefix is skipped when it's under 4 characters — too short to mean
     * anything as a substring match. */
    fun highlightMatches(transcript: String, title: String): Boolean {
        if (transcript.isBlank() || title.isBlank()) return false
        if (transcript.contains(title)) return true
        val prefix = title.take(8)
        return prefix.length >= 4 && transcript.contains(prefix)
    }

    /** Japanese (or [lang]) Wikipedia REST summary URL for [title] (spaces as underscores, then
     * percent-encoded per MediaWiki convention). */
    fun wikipediaSummaryUrl(title: String, lang: String = "ja"): String =
        "https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")

    /** Wikipedia search-by-title fallback (tolerant of an inexact [title]/query), used when the
     * summary endpoint 404s. */
    fun wikipediaSearchUrl(query: String, lang: String = "ja"): String =
        "https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=1"

    /** Debug-only fake cards for `adb shell setprop debug.butler.cards 1` (docs/architecture.md) —
     * three canned cards with a small solid-color placeholder bitmap drawn in code (no bundled asset
     * needed), so [SearchCardsView]'s layout can be checked on-device without a real search. */
    fun debugCards(): List<Card> {
        fun placeholder(color: Int): Bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).drawColor(color)
        }
        return listOf(
            Card(1, "Capybara", "The world's largest living rodent, a semi-aquatic mammal native to South America.",
                "https://example.com/capybara", placeholder(0xFF7FB2C9.toInt())),
            Card(2, "Mount Fuji", "Japan's tallest mountain (3,776m) and an active stratovolcano near Tokyo.",
                "https://example.com/fuji", placeholder(0xFFE3B865.toInt())),
            Card(3, "Great Barrier Reef", "The world's largest coral reef system, off the coast of Queensland, Australia.",
                "https://example.com/reef", placeholder(0xFF2E6E76.toInt())),
        )
    }
}
