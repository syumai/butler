package dev.syumai.butler

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Standby "search cards" viewer (cards-spec.md "Viewer"): a full-screen overlay MainActivity adds to
 * its root above the pager and below [ConversationView] — [ConversationView] itself draws the docked
 * bar over this view's reserved top margin, this view never renders one of its own. Shown when
 * [SearchCards.serial] changes and [SearchCards.cards] is non-empty (MainActivity's tick, next to its
 * `homeStateSerial` check); persists after the conversation ends (it *is* the standby viewer) until
 * [hide] (the × button, MainActivity's auto-return timer, or a new search), matching how the weather
 * auto-switch docks [ConversationView] but doesn't otherwise touch the pager underneath.
 *
 * Two modes: the default horizontal card strip, and a per-card detail mode ([showDetail]) with a
 * larger picture, the full (scrollable) description, and a "Read aloud" button that hands the
 * title+description to [onReadAloud] (wired by MainActivity to `AssistantService.say` via its
 * existing `action(...)`/startService plumbing) — hidden on GPT-Live, which has no verified way to
 * inject a text turn (see `AssistantService.say`'s doc comment).
 */
class SearchCardsView(context: Context, private val settings: Settings) : FrameLayout(context) {
    private fun dp(value: Int) = context.dp(value)

    private val queryText = context.text(13f, "", Palette.CREAM_60)
    private val cardsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val cardsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(cardsRow, LinearLayout.LayoutParams(-2, -1))
    }
    private val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val detailImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val detailImageFrame = glassTile().apply { addView(detailImage, LayoutParams(-1, -1)) }
    private val detailBack = iconButton(context, R.drawable.ic_arrow_back, context.getString(R.string.cards_back_description)) { showList() }
    private val detailTitle = context.text(22f, "", Palette.CREAM).apply { maxLines = 2 }
    private val detailDescription = context.text(16f, "", Palette.CREAM_60).apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val detailSource = context.text(12f, "", Palette.CREAM_30)
    private val detailReadAloud = context.filledButton(context.getString(R.string.cards_read_aloud), 14f, Palette.BRASS) {
        detailCard?.let { card -> onReadAloud?.invoke("${card.title}. ${card.description}") }
    }
    private val detailContainer = FrameLayout(context).apply { visibility = GONE }

    private val closeButton = closeButton { hide() }

    /** Set by MainActivity: hands `"<title>. <description>"` to `AssistantService.say(...)`. */
    var onReadAloud: ((String) -> Unit)? = null

    private var currentCards: List<SearchCards.Card> = emptyList()
    private var detailCard: SearchCards.Card? = null
    private var shown = false
    private var lastHighlightId = -1

    /** True while shown (fading in/visible) — MainActivity's tick reads this for `pager.locked`, the
     * chrome-hide toggle, and the auto-return timer, the same way it already reads `sheetOpen`. */
    val standbyShown get() = shown

    init {
        background = ColorDrawable(0xF2102326.toInt())
        alpha = 0f
        visibility = INVISIBLE

        queryText.maxLines = 1; queryText.ellipsize = TextUtils.TruncateAt.END
        listContainer.addView(queryText, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(34); rightMargin = dp(120); topMargin = dp(6) })
        listContainer.addView(cardsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); marginStart = dp(34); marginEnd = dp(34) })
        addView(listContainer, LayoutParams(-1, -1).apply { topMargin = dp(70) })

        addView(buildDetailLayout(), LayoutParams(-1, -1).apply { topMargin = dp(70); leftMargin = dp(34); rightMargin = dp(34); bottomMargin = dp(90) })

        addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(26); bottomMargin = dp(24) })
    }

    private fun buildDetailLayout(): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(detailImageFrame, LinearLayout.LayoutParams(dp(280), -1))
        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(detailBack, LinearLayout.LayoutParams(dp(40), dp(40)))
        textColumn.addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val scroll = ScrollView(context).apply { addView(detailDescription, ViewGroup.LayoutParams(-1, -2)) }
        textColumn.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        textColumn.addView(detailSource, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        textColumn.addView(detailReadAloud, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        row.addView(textColumn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(20) })
        detailContainer.addView(row, FrameLayout.LayoutParams(-1, -1))
        return detailContainer
    }

    /** Shows [cards] for [query]: rebuilds the strip, resets to list mode (a fresh search should never
     * land on a stale detail view), and fades in if not already shown. Called only when [cards] is
     * non-empty — MainActivity calls [hide] instead when a search found nothing worth showing. */
    fun show(query: String, cards: List<SearchCards.Card>) {
        currentCards = cards
        lastHighlightId = -1
        queryText.text = query
        rebuildStrip()
        showList()
        if (!shown) {
            shown = true; visibility = VISIBLE
            animate().cancel(); animate().alpha(1f).setDuration(300).start()
        }
    }

    /** Hides the viewer (× button, MainActivity's auto-return, or a new empty-cards search) and drops
     * the bitmaps (§ Memory) — [SearchCards.clear] releases the holder's own reference so a hidden
     * viewer doesn't keep up to 5 decoded bitmaps alive for no reason. */
    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).setDuration(300).withEndAction { visibility = INVISIBLE }.start()
        currentCards = emptyList(); cardsRow.removeAllViews()
        SearchCards.clear()
    }

    private fun rebuildStrip() {
        cardsRow.removeAllViews()
        currentCards.forEach { card -> cardsRow.addView(cardView(card), LinearLayout.LayoutParams(dp(300), -1).apply { marginEnd = dp(16) }) }
    }

    private fun cardView(card: SearchCards.Card): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBorder(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            tag = card.id
            setOnClickListener { showDetail(card) }
        }
        val imageFrame = glassTile()
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.bitmap?.let { image.setImageBitmap(it) }
        imageFrame.addView(image, ViewGroup.LayoutParams(-1, -1))
        container.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(300) * 10 / 16))
        container.addView(context.text(15f, "${card.id}. ${card.title}", Palette.CREAM).apply {
            maxLines = 2; setPadding(0, dp(8), 0, 0)
        })
        container.addView(context.text(12f, card.description, Palette.CREAM_60).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        })
        container.addView(context.text(11f, sourceDomain(card.url), Palette.CREAM_30).apply { setPadding(0, dp(6), 0, 0) })
        return container
    }

    private fun showDetail(card: SearchCards.Card) {
        detailCard = card
        detailImage.setImageBitmap(card.bitmap)
        detailTitle.text = "${card.id}. ${card.title}"
        detailDescription.text = card.description
        detailSource.text = sourceDomain(card.url)
        detailReadAloud.visibility = if (settings.voiceApi == VoiceApi.LIVE) GONE else VISIBLE
        listContainer.visibility = GONE
        detailContainer.visibility = VISIBLE
    }

    private fun showList() {
        detailCard = null
        detailContainer.visibility = GONE
        listContainer.visibility = VISIBLE
    }

    /** Updates the highlighted card's border from [transcript] (§ Viewer "Highlight"): a cheap
     * substring check per card ([SearchCards.highlightMatches], no allocation storm), and a view
     * touch only when the highlighted card id actually changed since the last tick. No-op while
     * hidden or in detail mode. */
    fun tick(transcript: String) {
        if (!shown || listContainer.visibility != VISIBLE) return
        val matchId = currentCards.firstOrNull { SearchCards.highlightMatches(transcript, it.title) }?.id ?: -1
        if (matchId == lastHighlightId) return
        lastHighlightId = matchId
        for (i in 0 until cardsRow.childCount) {
            val child = cardsRow.getChildAt(i)
            child.background = cardBorder((child.tag as? Int) == matchId)
        }
    }

    private fun cardBorder(highlighted: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat()
        setColor(Palette.CREAM_12)
        setStroke(dp(if (highlighted) 2 else 1).coerceAtLeast(1), if (highlighted) Palette.BRASS else 0x1FFFFFFF)
    }

    /** 16:10 rounded tile used for both the strip's and the detail view's picture — filled with a
     * neutral teal when the card has no bitmap (matches MusicPage's artwork-frame placeholder look),
     * so a text-only card still reads as a picture slot rather than a layout gap. */
    private fun glassTile(): FrameLayout = FrameLayout(context).apply {
        background = roundedShape(dp(12).toFloat(), fill = 0xFF2E6E76.toInt())
        clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun sourceDomain(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

    private fun closeButton(onClick: () -> Unit): View = TextView(context).apply {
        text = "×"; textSize = 22f; setTextColor(Palette.CREAM_60); gravity = Gravity.CENTER
        background = rippleOn(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80102326.toInt()); setStroke(dp(1), Palette.CREAM_30) },
            dp(24).toFloat(), Color.argb(70, 255, 255, 255))
        contentDescription = context.getString(R.string.cards_close_description)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
