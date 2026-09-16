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
 * Two modes: the default horizontal card strip — picture-first, two half-width/full-height cards per
 * screen, title/description/source overlaid on the picture itself — and a full-screen picture mode
 * ([showFullScreen]) covering the whole view: the picture `FIT_CENTER` on a near-black backdrop, a
 * bottom scrim with the full title/description/source, a back arrow, and a "Read aloud" button that
 * hands the title+description to [onReadAloud] (wired by MainActivity to `AssistantService.say` via
 * its existing `action(...)`/startService plumbing) — hidden on GPT-Live, which has no verified way to
 * inject a text turn (see `AssistantService.say`'s doc comment).
 */
class SearchCardsView(context: Context, private val settings: Settings) : FrameLayout(context) {
    private fun dp(value: Int) = context.dp(value)
    private companion object {
        // Two cards per screen: at the 787dp-wide landscape reference size, the strip is
        // (787 - 34dp*2 side margins) wide; splitting that in two around a 14dp gap gives ~352dp per
        // card. Used as the strip's initial layout before onSizeChanged has fired once; after that,
        // cardWidthPx tracks the view's actual measured width so a different window size still shows
        // exactly two cards.
        const val FALLBACK_CARD_WIDTH_DP = 352
        const val CARD_GAP_DP = 14
        const val STRIP_SIDE_MARGIN_DP = 34
        const val MIN_CARD_WIDTH_DP = 120
    }

    private val queryText = context.text(13f, "", Palette.CREAM_60)
    private val cardsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val cardsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(cardsRow, LinearLayout.LayoutParams(-2, -1))
    }
    private val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    // Full-screen picture mode: fullImage (a bitmap card) and fullPlaceholder (a text-only card's
    // glass tile) share fullPictureHolder, only one visible at a time.
    private val fullImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val fullPlaceholder = glassTile()
    private val fullPictureHolder = FrameLayout(context).apply {
        background = ColorDrawable(0xEB000000.toInt()) // near-black, ~92% opaque
        isClickable = true
        setOnClickListener { showList() }
    }
    private val fullBack = iconButton(context, R.drawable.ic_arrow_back, context.getString(R.string.cards_back_description)) { showList() }
    private val fullTitle = context.text(20f, "", Palette.CREAM).apply { maxLines = 2 }
    private val fullDescription = context.text(14f, "", Palette.CREAM_60).apply {
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
    }
    private val fullSource = context.text(12f, "", Palette.CREAM_30)
    private val fullReadAloud = context.filledButton(context.getString(R.string.cards_read_aloud), 14f, Palette.BRASS) {
        fullCard?.let { card -> onReadAloud?.invoke("${card.title}. ${card.description}") }
    }
    private val fullScreenContainer = FrameLayout(context).apply { visibility = GONE }

    private val closeButton = closeButton { hide() }

    /** Set by MainActivity: hands `"<title>. <description>"` to `AssistantService.say(...)`. */
    var onReadAloud: ((String) -> Unit)? = null

    private var currentCards: List<SearchCards.Card> = emptyList()
    private var fullCard: SearchCards.Card? = null
    private var shown = false
    private var lastHighlightId = -1
    private var cardWidthPx = dp(FALLBACK_CARD_WIDTH_DP)

    /** True while shown (fading in/visible) — MainActivity's tick reads this for `pager.locked`, the
     * chrome-hide toggle, and the auto-return timer, the same way it already reads `sheetOpen`. */
    val standbyShown get() = shown

    init {
        background = ColorDrawable(0xF2102326.toInt())
        alpha = 0f
        visibility = INVISIBLE

        queryText.maxLines = 1; queryText.ellipsize = TextUtils.TruncateAt.END
        listContainer.addView(queryText, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(34); rightMargin = dp(120); topMargin = dp(4) })
        // Height 0 + weight 1 so the strip claims all the space below the query line, down to
        // bottomMargin above the view's bottom edge (the "stretch the height to the max" request).
        listContainer.addView(cardsScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4); marginStart = dp(34); marginEnd = dp(34); bottomMargin = dp(10)
        })
        addView(listContainer, LayoutParams(-1, -1).apply { topMargin = dp(70) })

        addView(buildFullScreenLayout(), LayoutParams(-1, -1))

        // Top-right, where MainActivity's settings gear sits while this viewer is hidden (the gear is
        // hidden with the rest of the chrome while cards show, and the docked conversation bar stops
        // 80dp short of the right edge), so the button never overlaps the card strip below. Added last
        // so it stays on top (and clickable) in both the strip and the full-screen mode.
        addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin = dp(34); topMargin = dp(20) })
    }

    private fun buildFullScreenLayout(): View {
        fullPictureHolder.addView(fullImage, FrameLayout.LayoutParams(-1, -1))
        fullPictureHolder.addView(fullPlaceholder, FrameLayout.LayoutParams(dp(320), dp(320), Gravity.CENTER))
        fullScreenContainer.addView(fullPictureHolder, FrameLayout.LayoutParams(-1, -1))

        // Bottom scrim: consumes all touches itself (isClickable=true) so tapping the title/description
        // area doesn't fall through to fullPictureHolder's "tap picture to go back" listener.
        val scrim = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xD9000000.toInt()))
            setPadding(dp(24), dp(40), dp(24), dp(20))
        }
        scrim.addView(fullBack, LinearLayout.LayoutParams(dp(40), dp(40)))
        scrim.addView(fullTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scrim.addView(fullDescription, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        scrim.addView(fullSource, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        scrim.addView(fullReadAloud, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        fullScreenContainer.addView(scrim, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return fullScreenContainer
    }

    /** Tracks the strip's actual width so each card can be exactly half of it: `(stripWidth - gap) /
     * 2`, recomputed only when the measured width actually changes, and only rebuilding the strip
     * (which would otherwise drop scroll position/highlight state for nothing) when it's showing
     * cards already. Before the first layout pass, [cardWidthPx] stays at its [FALLBACK_CARD_WIDTH_DP]
     * default. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val stripWidth = w - dp(STRIP_SIDE_MARGIN_DP) * 2
        val newWidth = ((stripWidth - dp(CARD_GAP_DP)) / 2).coerceAtLeast(dp(MIN_CARD_WIDTH_DP))
        if (newWidth == cardWidthPx) return
        cardWidthPx = newWidth
        if (currentCards.isNotEmpty()) rebuildStrip()
    }

    /** Shows [cards] for [query]: rebuilds the strip, resets to list mode (a fresh search should never
     * land on a stale full-screen view), and fades in if not already shown. Called only when [cards]
     * is non-empty — MainActivity calls [hide] instead when a search found nothing worth showing. */
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
        currentCards.forEach { card -> cardsRow.addView(cardView(card), LinearLayout.LayoutParams(cardWidthPx, -1).apply { marginEnd = dp(CARD_GAP_DP) }) }
    }

    /** One strip card: the picture fills the whole card (`CENTER_CROP`, rounded 18dp corners via
     * `clipToOutline`), a text-only card gets the same neutral teal fill glassTile()/the detail view
     * used) instead of a bitmap, and the number/title/description/source are overlaid on the picture's
     * lower part ([cardTextOverlay]) with a highlight border drawn on top of everything ([cardBorder]),
     * so the border always reads even though the picture goes edge-to-edge underneath it. */
    private fun cardView(card: SearchCards.Card): View {
        val container = FrameLayout(context).apply {
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            background = roundedShape(dp(18).toFloat(), fill = if (card.bitmap != null) Color.TRANSPARENT else 0xFF2E6E76.toInt())
            isClickable = true
            tag = card.id
            setOnClickListener { showFullScreen(card) }
        }
        card.bitmap?.let { bitmap ->
            container.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bitmap) }, FrameLayout.LayoutParams(-1, -1))
        }
        container.addView(cardTextOverlay(card), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        container.addView(View(context).apply { background = cardBorder(false) }, FrameLayout.LayoutParams(-1, -1))
        return container
    }

    /** Number+title/description/source overlaid on a picture's lower part, on a vertical gradient
     * scrim (transparent → ~85% dark) so they stay readable over any picture. */
    private fun cardTextOverlay(card: SearchCards.Card): View {
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xD9000000.toInt()))
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        overlay.addView(context.text(15f, "${card.id}. ${card.title}", Palette.CREAM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        overlay.addView(context.text(12f, card.description, Palette.CREAM_60).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        overlay.addView(context.text(10f, sourceDomain(card.url), Palette.CREAM_30).apply { setPadding(0, dp(3), 0, 0) })
        return overlay
    }

    /** Opens the full-screen picture mode for [card] (tapping a strip card, replacing the old
     * side-by-side detail mode): the bitmap `FIT_CENTER` (or the glass tile placeholder for a
     * text-only card), full title/description/source and the "Read aloud" button — hidden on Live,
     * since `AssistantService.say` doesn't support it. */
    private fun showFullScreen(card: SearchCards.Card) {
        fullCard = card
        if (card.bitmap != null) {
            fullImage.setImageBitmap(card.bitmap); fullImage.visibility = VISIBLE; fullPlaceholder.visibility = GONE
        } else {
            fullImage.setImageBitmap(null); fullImage.visibility = GONE; fullPlaceholder.visibility = VISIBLE
        }
        fullTitle.text = "${card.id}. ${card.title}"
        fullDescription.text = card.description
        fullSource.text = sourceDomain(card.url)
        fullReadAloud.visibility = if (settings.voiceApi == VoiceApi.LIVE) GONE else VISIBLE
        listContainer.visibility = GONE
        fullScreenContainer.visibility = VISIBLE
    }

    /** Back to the strip — the back arrow, tapping the picture itself, or a fresh [show]. */
    private fun showList() {
        fullCard = null
        fullScreenContainer.visibility = GONE
        listContainer.visibility = VISIBLE
    }

    /** Updates the highlighted card's border from [transcript] (§ Viewer "Highlight"): a cheap
     * substring check per card ([SearchCards.highlightMatches], no allocation storm), and a view
     * touch only when the highlighted card id actually changed since the last tick. No-op while
     * hidden or in full-screen mode. */
    fun tick(transcript: String) {
        if (!shown || listContainer.visibility != VISIBLE) return
        val matchId = currentCards.firstOrNull { SearchCards.highlightMatches(transcript, it.title) }?.id ?: -1
        if (matchId == lastHighlightId) return
        lastHighlightId = matchId
        for (i in 0 until cardsRow.childCount) {
            val card = cardsRow.getChildAt(i) as ViewGroup
            val border = card.getChildAt(card.childCount - 1)
            border.background = cardBorder((card.tag as? Int) == matchId)
        }
    }

    /** Stroke-only rounded-rect overlay drawn as the topmost child of each strip card (over the
     * picture and its text scrim), so the highlight border always reads regardless of what's under
     * it. */
    private fun cardBorder(highlighted: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(if (highlighted) 2 else 1).coerceAtLeast(1), if (highlighted) Palette.BRASS else 0x1FFFFFFF)
    }

    /** Rounded tile used for the full-screen mode's text-only placeholder — filled with a neutral
     * teal (matches MusicPage's artwork-frame placeholder look), so a text-only card still reads as
     * a picture slot rather than a layout gap. */
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
