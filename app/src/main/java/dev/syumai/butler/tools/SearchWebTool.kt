package dev.syumai.butler.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.syumai.butler.R
import dev.syumai.butler.SearchCards
import dev.syumai.butler.Settings
import dev.syumai.butler.ToolClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `search_web`: answers with a short spoken reply plus up to [SearchCards.MAX_ITEMS] picture cards
 * (see docs/architecture.md "Search cards" and cards-spec.md), published to [SearchCards] for
 * [dev.syumai.butler.SearchCardsView] to render on the standby screen. [ToolClient.searchCards] asks
 * the Responses API for strict JSON ({spoken, items}); [SearchCards.parse] decodes it and, only on a
 * parse failure (never a call failure — that already propagates as `{"error":...}` via
 * `AssistantService.runToolAsync`), this falls back to the old text-only [ToolClient.search] so a
 * card-search regression never makes search itself worse than before.
 */
class SearchWebTool(private val context: Context, private val settings: Settings, private val client: ToolClient) : Tool {
    override val name = "search_web"
    override val busyStatus = R.string.status_searching
    override fun definition(): JSONObject = JSONObject().put("type", "function").put("name", name)
        .put("description", context.getString(R.string.tool_search_description))
        .put("parameters", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""))

    override fun execute(arguments: JSONObject): JSONObject {
        val query = arguments.getString("query")
        val input = context.getString(R.string.prompt_search_input, query.take(2000))
        val raw = client.searchCards(settings, input)
        val parsed = runCatching { SearchCards.parse(raw) }.getOrNull() ?: return client.search(settings, input)
        val cards = resolveCardImages(parsed.items)
        SearchCards.publish(query, cards)
        val cardsJson = JSONArray()
        cards.forEach { card ->
            cardsJson.put(JSONObject().put("number", card.id).put("title", card.title)
                .put("description", card.description).put("source", card.url).put("picture", card.bitmap != null))
        }
        return JSONObject().put("answer", parsed.spoken).put("cards", cardsJson)
            .put("shown_on_display", cards.isNotEmpty()).put("content", SearchCards.buildContent(parsed.items))
    }

    /** Resolves one picture per item, in parallel (4 threads), with a ~5s total budget
     * ([IMAGE_BUDGET_MS] via `ExecutorService.invokeAll`'s own timeout, which cancels whatever hasn't
     * finished) — a slow/unreachable image host must never make search itself slow. The pool is
     * created and torn down per call rather than kept as a field, since [SearchWebTool] itself is
     * recreated every conversation (`ToolRegistry(this, settings, client)` in `AssistantService.begin()`)
     * and a field-held pool would leak a thread pool per conversation. */
    private fun resolveCardImages(items: List<SearchCards.ParsedItem>): List<SearchCards.Card> {
        if (items.isEmpty()) return emptyList()
        val lang = if (Locale.getDefault().language == "ja") "ja" else "en"
        val pool = Executors.newFixedThreadPool(minOf(4, items.size))
        try {
            val tasks = items.map { item -> Callable { resolveImage(item, lang) } }
            val futures = pool.invokeAll(tasks, IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS)
            return items.mapIndexed { i, item ->
                val bitmap = runCatching { futures[i].get() }.getOrNull()
                SearchCards.Card(i + 1, item.title, item.description, item.url, bitmap)
            }
        } finally { pool.shutdownNow() }
    }

    /** One item's image, per docs/architecture.md's tiers: Google image search (only when both
     * `googleCseKey`/`googleCseCx` are set), else the Wikipedia thumbnail (only when the model named
     * an article), else no image — a text-only card. */
    private fun resolveImage(item: SearchCards.ParsedItem, lang: String): Bitmap? {
        val googleConfigured = settings.secret("googleCseKey").isNotBlank() && settings.get("googleCseCx").isNotBlank()
        val url = (if (googleConfigured) client.googleImageSearch(settings, item.imageQuery) else null)
            ?: item.wikipediaTitle?.let { client.wikipediaThumbnail(it, lang) }
            ?: return null
        val bytes = client.fetchImageBytes(url) ?: return null
        return decodeSampled(bytes, CARD_IMAGE_MAX_PX)
    }

    private companion object {
        const val IMAGE_BUDGET_MS = 5_000L
        const val CARD_IMAGE_MAX_PX = 640
    }
}

/** Downsamples a decoded image so neither dimension exceeds [maxSize] px (same `inSampleSize`
 * approach as `MusicPage.decodeSampled`, duplicated here rather than shared since the two live in
 * different packages and this is a two-line function). */
private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()
