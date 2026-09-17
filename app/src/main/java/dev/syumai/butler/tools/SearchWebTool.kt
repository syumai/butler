package dev.syumai.butler.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.graphics.BitmapFactory
import dev.syumai.butler.BuildConfig
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
        // A search that found nothing worth showing (plain facts, numbers) leaves the previous cards on
        // the standby viewer rather than blanking it: the user may still be looking at them.
        if (cards.isNotEmpty()) SearchCards.publish(query, cards)
        val cardsJson = JSONArray()
        cards.forEach { card ->
            cardsJson.put(JSONObject().put("number", card.id).put("title", card.title)
                .put("description", card.description).put("source", card.url).put("picture", card.bitmap != null))
        }
        return JSONObject().put("answer", parsed.spoken).put("cards", cardsJson)
            .put("shown_on_display", cards.isNotEmpty()).put("content", SearchCards.buildContent(parsed.items))
    }

    /** Resolves one picture per item in two parallel phases (4 threads each, [IMAGE_BUDGET_MS] budget
     * per phase via `ExecutorService.invokeAll`'s own timeout, which cancels whatever hasn't finished —
     * a slow/unreachable image host must never make search itself slow): first every item's picture
     * *URL* ([resolveImageUrl], no download yet), then — after deduping so no two cards repeat the same
     * picture (§ below) — a download/decode pass over only the URLs that survived. Items whose URL was
     * deduped away, or whose download/decode failed or timed out, become text-only cards. Each pool is
     * created and torn down within the call rather than kept as a field, since [SearchWebTool] itself is
     * recreated every conversation (`ToolRegistry(this, settings, client)` in `AssistantService.begin()`)
     * and a field-held pool would leak a thread pool per conversation. */
    private fun resolveCardImages(items: List<SearchCards.ParsedItem>): List<SearchCards.Card> {
        if (items.isEmpty()) return emptyList()
        val lang = if (Locale.getDefault().language == "ja") "ja" else "en"
        val googleConfigured = settings.secret("googleCseKey").isNotBlank() && settings.get("googleCseCx").isNotBlank()

        val resolved = runInParallel(items.size) { pool ->
            val tasks = items.map { item -> Callable { resolveImageUrl(item, lang, googleConfigured) } }
            pool.invokeAll(tasks, IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS)
        }

        // The model often returns several items about the same subject (e.g. four capybara facts),
        // all naming the same wikipedia_title — without this, every one of them would resolve to the
        // identical Wikipedia thumbnail. A repeated title only gets a picture for its first item; later
        // ones lose theirs even if the resolved URL string happens to differ (e.g. a redirect). This
        // only applies to the Wikipedia tier: distinct items legitimately sharing a Google image result
        // are caught next by the plain URL dedupe below.
        val usedWikipediaTitles = mutableSetOf<String>()
        val afterTitleDedup = items.indices.map { i ->
            val r = resolved[i] ?: return@map null
            val title = items[i].wikipediaTitle
            if (r.fromWikipedia && title != null && !usedWikipediaTitles.add(title)) null else r.url
        }
        val dedupedUrls = SearchCards.dedupePictureUrls(afterTitleDedup)

        val bitmaps = runInParallel(items.size) { pool ->
            val tasks = dedupedUrls.map { url -> Callable { url?.let { downloadImage(it) } } }
            pool.invokeAll(tasks, IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS)
        }
        return items.mapIndexed { i, item -> SearchCards.Card(i + 1, item.title, item.description, item.url, bitmaps[i]) }
    }

    /** Runs [block] over a fixed thread pool sized to [count] (capped at 4), collecting each task's
     * result (or null on failure/timeout/cancellation) in order; shuts the pool down either way. */
    private fun <T> runInParallel(count: Int, block: (java.util.concurrent.ExecutorService) -> List<java.util.concurrent.Future<T>>): List<T?> {
        val pool = Executors.newFixedThreadPool(minOf(4, count))
        try {
            val futures = block(pool)
            return futures.map { runCatching { it.get() }.getOrNull() }
        } finally { pool.shutdownNow() }
    }

    private class ResolvedUrl(val url: String, val fromWikipedia: Boolean)

    /** One item's picture URL (no download yet), per docs/architecture.md's tiers: Google image search
     * (only when both `googleCseKey`/`googleCseCx` are set), else the Wikipedia thumbnail (only when the
     * model named an article), else no image — a text-only card. */
    private fun resolveImageUrl(item: SearchCards.ParsedItem, lang: String, googleConfigured: Boolean): ResolvedUrl? {
        (if (googleConfigured) client.googleImageSearch(settings, item.imageQuery) else null)
            ?.let { return ResolvedUrl(it, fromWikipedia = false) }
        val wikiUrl = item.wikipediaTitle?.let { client.wikipediaThumbnail(it, lang) } ?: return null
        return ResolvedUrl(wikiUrl, fromWikipedia = true)
    }

    /** Downloads and downsamples the picture at [url] (§ `CARD_IMAGE_MAX_PX`); null on any
     * failure/timeout, same as an unresolved URL — the card just ends up text-only. */
    private fun downloadImage(url: String): Bitmap? {
        val bytes = client.fetchImageBytes(url)
        if (BuildConfig.DEBUG) Log.d("Butler", "card image url=${url.take(80)} bytes=${bytes?.size}")
        return decodeSampled(bytes ?: return null, CARD_IMAGE_MAX_PX)
    }

    private companion object {
        const val IMAGE_BUDGET_MS = 5_000L
        // The device display is 960px wide; a full-screen card picture (SearchCardsView) at ≤640px
        // was visibly soft on it, so this matches the display's own width instead. Memory: at most
        // SearchCards.MAX_ITEMS (5) bitmaps held at once.
        const val CARD_IMAGE_MAX_PX = 960
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
