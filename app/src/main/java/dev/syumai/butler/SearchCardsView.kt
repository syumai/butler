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
 * Two modes: the default horizontal card strip — picture-first, two half-width/full-height cards per
 * screen; a card with a picture overlays title/description/source on the picture itself, a card
 * without one ([SearchCards.Card.bitmap] null — no tier resolved one in time, or it lost the "no
 * duplicate pictures" dedupe, see `SearchWebTool.resolveCardImages`) renders as a same-size text card
 * instead of an empty picture tile — and a full-screen mode ([showFullScreen]) covering the whole view,
 * split so a picture and its text never overlap: with a picture, the left half is the picture
 * `FIT_CENTER` on a near-black backdrop and the right half is a column (back arrow, title, scrollable
 * description, source, "Read aloud" button); without one, that same column spans the full width with
 * wider side margins instead. The right column keeps clear of the ~70dp docked-conversation-bar zone at
 * the top (a top margin) so the title stays readable while a conversation is docked. "Read aloud" hands
 * the title+description to [onReadAloud] (wired by MainActivity to `AssistantService.say` via its
 * existing `action(...)`/startService plumbing) — hidden on GPT-Live, which has no verified way to
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
        // Full-screen mode's right column margins (§ applyRightColumnLayout): snug next to the picture
        // in split mode, wide ("comfortable side margins") when it's the only thing on screen.
        const val SPLIT_COLUMN_MARGIN_DP = 24
        const val TEXT_ONLY_SIDE_MARGIN_DP = 64
    }

    private val queryText = context.text(13f, "", Palette.CREAM_60)
    private val cardsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val cardsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(cardsRow, LinearLayout.LayoutParams(-2, -1))
    }
    private val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    // Full-screen mode: fullPictureHolder is the left half when the card has a picture, hidden
    // (visibility GONE, which drops its width claim in fullSplitRow) otherwise so fullRightColumn's
    // weight alone fills the whole width — no separate "text-only" layout to keep in sync.
    private val fullImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val fullPictureHolder = FrameLayout(context).apply {
        background = ColorDrawable(0xEB000000.toInt()) // near-black, ~92% opaque
        isClickable = true
        setOnClickListener { showList() }
    }
    private val fullBack = iconButton(context, R.drawable.ic_arrow_back, context.getString(R.string.cards_back_description)) { showList() }
    private val fullTitle = context.text(20f, "", Palette.CREAM).apply { maxLines = 2 }
    // No maxLines/ellipsize: fullDescriptionScroll (below) scrolls whatever doesn't fit instead of
    // truncating it, since a full-screen card is expected to show the whole description.
    private val fullDescription = context.text(14f, "", Palette.CREAM_60).apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val fullDescriptionScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(fullDescription, FrameLayout.LayoutParams(-1, -2))
    }
    private val fullSource = context.text(12f, "", Palette.CREAM_30)
    private val fullReadAloud = context.filledButton(context.getString(R.string.cards_read_aloud), 14f, Palette.BRASS) {
        fullCard?.let { card -> onReadAloud?.invoke("${card.title}. ${card.description}") }
    }
    // Holds fullBack/fullTitle/fullDescriptionScroll/fullSource/fullReadAloud — reused as-is for both
    // the split (picture) and full-width (text-only) layouts; only its LayoutParams change between them.
    private val fullRightColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fullSplitRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    // The whole full-screen mode sits on the same near-black backdrop as the picture half, so the
    // text half (and the picture-less layout) doesn't have the clock/weather page showing through.
    private val fullScreenContainer = FrameLayout(context).apply { visibility = GONE; background = ColorDrawable(0xEB000000.toInt()) }

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

    /** Builds the full-screen split layout once: [fullSplitRow] is a horizontal row of
     * [fullPictureHolder] (left half) and [fullRightColumn] (right half) so a picture and its text
     * never overlap. [showFullScreen] toggles [fullPictureHolder]'s visibility between the two cases —
     * `GONE` drops its width claim in the `LinearLayout`, so [fullRightColumn]'s own weight then fills
     * the whole row on its own, no separate text-only layout needed — and adjusts [fullRightColumn]'s
     * side margins ([applyRightColumnLayout]) since the split case wants it snug against the picture
     * while the text-only case wants comfortable margins on both sides instead. */
    private fun buildFullScreenLayout(): View {
        fullPictureHolder.addView(fullImage, FrameLayout.LayoutParams(-1, -1))
        fullSplitRow.addView(fullPictureHolder, LinearLayout.LayoutParams(0, -1, 1f))

        fullRightColumn.addView(fullBack, LinearLayout.LayoutParams(dp(40), dp(40)))
        fullRightColumn.addView(fullTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // Height 0 + weight 1: the description claims whatever's left between the title and the
        // source/button below, scrolling internally if the text is longer than that.
        fullRightColumn.addView(fullDescriptionScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        fullRightColumn.addView(fullSource, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        fullRightColumn.addView(fullReadAloud, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        fullSplitRow.addView(fullRightColumn, LinearLayout.LayoutParams(0, -1, 1f))
        applyRightColumnLayout(hasPicture = true)

        fullScreenContainer.addView(fullSplitRow, FrameLayout.LayoutParams(-1, -1))
        return fullScreenContainer
    }

    /** [fullRightColumn]'s top margin always clears the ~70dp docked-conversation-bar zone (§ class
     * doc); its side margins differ between the two cases — snug ([SPLIT_COLUMN_MARGIN_DP]) next to
     * the picture in split mode, wide ([TEXT_ONLY_SIDE_MARGIN_DP], "comfortable side margins") when the
     * column is the only thing on screen. */
    private fun applyRightColumnLayout(hasPicture: Boolean) {
        val sideMargin = dp(if (hasPicture) SPLIT_COLUMN_MARGIN_DP else TEXT_ONLY_SIDE_MARGIN_DP)
        fullRightColumn.layoutParams = (fullRightColumn.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(70); bottomMargin = dp(24); leftMargin = sideMargin; rightMargin = sideMargin
        }
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

    /** One strip card, sized/positioned identically either way: a picture card ([pictureCardView]) or,
     * when [SearchCards.Card.bitmap] is null, a same-size text card ([textOnlyCardView]) rather than an
     * empty picture tile. Both end with the same highlight-border overlay ([cardBorder]) as their last
     * child, which [tick] relies on. */
    private fun cardView(card: SearchCards.Card): View = if (card.bitmap != null) pictureCardView(card) else textOnlyCardView(card)

    /** The picture fills the whole card (`CENTER_CROP`, rounded 18dp corners via `clipToOutline`), with
     * the number/title/description/source overlaid on its lower part ([cardTextOverlay]) on a vertical
     * gradient scrim (transparent → ~85% dark) so they stay readable over any picture, and a highlight
     * border drawn on top of everything so it always reads even though the picture goes edge-to-edge
     * underneath it. */
    private fun pictureCardView(card: SearchCards.Card): View {
        val container = FrameLayout(context).apply {
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            background = roundedShape(dp(18).toFloat(), fill = Color.TRANSPARENT)
            isClickable = true
            tag = card.id
            setOnClickListener { showFullScreen(card) }
        }
        container.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(card.bitmap) }, FrameLayout.LayoutParams(-1, -1))
        container.addView(cardTextOverlay(card), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        container.addView(View(context).apply { background = cardBorder(false) }, FrameLayout.LayoutParams(-1, -1))
        return container
    }

    /** Number+title/description/source overlaid on a picture's lower part. */
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

    /** A card with no picture: same size/position as a picture card, a translucent "glass" background
     * (matches the rest of the app's glass-card look, `Ui.kt`'s [glassCard]) instead of a picture,
     * number+title (up to 2 lines) at the top, then as much of the description as the card's actual
     * height allows — this view's own pixel height isn't known until it's laid out (the strip only
     * tracks card *width* up front, see [onSizeChanged]), so [description]'s `maxLines` is computed
     * once from [title]/[source]'s measured heights in an [View.OnLayoutChangeListener] fired after the
     * first layout pass — and the source domain pinned to the bottom via a weighted spacer. */
    private fun textOnlyCardView(card: SearchCards.Card): View {
        val container = FrameLayout(context).apply {
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat()
                setColor(Palette.CREAM_12); setStroke(dp(1), 0x1FFFFFFF)
            }
            isClickable = true
            tag = card.id
            setOnClickListener { showFullScreen(card) }
        }
        val title = context.text(16f, "${card.id}. ${card.title}", Palette.CREAM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val description = context.text(13f, card.description, Palette.CREAM_60).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }
        val source = context.text(11f, sourceDomain(card.url), Palette.CREAM_30)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        column.addView(description, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f)) // spacer: pins source to the bottom
        column.addView(source, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        container.addView(column, FrameLayout.LayoutParams(-1, -1))
        container.addView(View(context).apply { background = cardBorder(false) }, FrameLayout.LayoutParams(-1, -1))

        container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (bottom - top <= 0 || title.height <= 0) return
                container.removeOnLayoutChangeListener(this)
                val used = title.height + dp(8) + source.height + dp(6) + column.paddingTop + column.paddingBottom
                val available = column.height - used
                val lineHeight = description.lineHeight
                if (lineHeight > 0 && available > 0) description.maxLines = (available / lineHeight).coerceAtLeast(1)
            }
        })
        return container
    }

    /** Opens the full-screen mode for [card] (tapping a strip card): with a picture, the split
     * layout — the bitmap `FIT_CENTER` on the left, [fullRightColumn] snug against it on the right;
     * without one, [fullPictureHolder] is hidden ([applyRightColumnLayout] widens the column's own
     * margins to compensate) so [fullRightColumn] spans the full width on its own. The "Read aloud"
     * button stays hidden on Live, since `AssistantService.say` doesn't support it. */
    private fun showFullScreen(card: SearchCards.Card) {
        fullCard = card
        val hasPicture = card.bitmap != null
        fullPictureHolder.visibility = if (hasPicture) VISIBLE else GONE
        fullImage.setImageBitmap(card.bitmap)
        applyRightColumnLayout(hasPicture)
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
