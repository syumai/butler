package dev.syumai.butler

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen conversation overlay (§8): shown while [AssistantService.conversing] is true. Owns
 * the [OrbView], the you/bot caption, and the end/sources/approval controls; [MainActivity]'s tick
 * calls [update] every 250ms (cheap — every child TextView goes through the same "only touch views
 * whose value changed" pattern MainActivity itself used to use) and calls [setDocked] only on the
 * rarer occasions §9 describes (an auto-switch to a page underneath while a tool runs).
 *
 * Docked mode swaps the two-line centered caption for a small bar along the TOP edge (where the tab
 * strip normally sits — MainActivity hides that strip for as long as this is docked) and shrinks/
 * moves the orb to the top-left, while the radial background fades to fully transparent so the page
 * underneath shows through; both are driven by view-property/ValueAnimator animations over 300ms,
 * matching the show/hide fade. The small state dot/text (top-left, same corner) is hidden outright
 * while docked — the orb's own color already conveys listening/speaking — rather than trying to
 * share the corner with the new docked bar.
 */
class ConversationView(context: Context) : FrameLayout(context) {
    /** Set by MainActivity to AssistantService.END / showSources() / showApproval(). */
    var onEnd: (() -> Unit)? = null
    var onSources: (() -> Unit)? = null
    var onApprove: (() -> Unit)? = null
    /** Set by MainActivity so it can hide its own top-left tab strip while docked (§ issue 8): the
     *  docked orb/bar now occupy that same top-edge real estate (34dp from the left, 10-66dp down),
     *  and with the background faded to transparent in that mode (see [animateBackgroundAlpha]) the
     *  two would otherwise draw directly on top of each other, unreadable. */
    var onDockedChanged: ((Boolean) -> Unit)? = null

    private fun dp(value: Int) = context.dp(value)

    private val bgDrawable = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(0xFF153A40.toInt(), 0xF20C1B1E.toInt())
        setGradientCenter(0.5f, 0.45f)
        alpha = 0
    }

    private val stateDot = android.view.View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.TEAL) }
    }
    private val stateText = context.text(13f, "", Palette.CREAM_30)
    // Field (not a local val in init) so setDocked can hide it outright while docked — the orb's
    // color already conveys listening/speaking, and the docked bar now occupies this same corner.
    private val stateRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private val orb = OrbView(context)

    private val userText = context.text(18f, "", Palette.CREAM_60).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.START }
    private val botText = context.text(30f, "", Palette.CREAM).apply {
        maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.START; setLineSpacing(0f, 1.35f)
    }
    private val captionFull = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(userText, LinearLayout.LayoutParams(-2, -2))
        addView(botText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    // Docked "bot" text uses ellipsize START (not END, unlike the single-line "you" text) so the
    // newest words stay visible as the transcript grows past two lines, matching how a live caption
    // should read; both need MATCH_PARENT width (rather than WRAP_CONTENT) so they actually wrap/
    // ellipsize against dockedBar's fixed width instead of measuring themselves unbounded.
    private val dockedUserText = context.text(13f, "", Palette.CREAM_60).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val dockedBotText = context.text(16f, "", Palette.CREAM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.START }
    private val dockedBar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedShape(dp(16).toFloat(), fill = 0xB30E2024.toInt())
        setPadding(dp(14), dp(3), dp(14), dp(3))
        addView(dockedUserText, LinearLayout.LayoutParams(-1, -2))
        addView(dockedBotText, LinearLayout.LayoutParams(-1, -2))
        alpha = 0f; visibility = INVISIBLE
    }

    private val sourcesButton = circleButton(R.drawable.ic_link, context.getString(R.string.home_sources)) { onSources?.invoke() }
    private val endButton = circleButton(R.drawable.ic_close, context.getString(R.string.home_end_conversation)) { onEnd?.invoke() }

    private val approvalPill = context.text(15f, context.getString(R.string.home_confirm_execution), Palette.INK).apply {
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = roundedShape(dp(999).toFloat(), fill = Palette.BRASS)
        isClickable = true
        setOnClickListener { onApprove?.invoke() }
    }

    private var shown = false
    private var docked = false
    private var speaking = false
    private var lastStatusText = ""
    private var lastCitationsBlank = true
    private var lastApprovalPending = false

    init {
        background = bgDrawable
        alpha = 0f
        visibility = INVISIBLE

        stateRow.addView(stateDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        stateRow.addView(stateText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(stateRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(34); topMargin = dp(22) })

        addView(orb, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER).apply { bottomMargin = dp(80) })
        addView(captionFull, FrameLayout.LayoutParams(dp(620), -2, Gravity.CENTER).apply { topMargin = dp(60) })
        // Docked bar (top-edge redesign): left edge just past the 48dp docked orb (34dp orb-left +
        // 48dp orb + 12dp gap), right edge ~80dp short of the screen edge (16dp left of the 48dp
        // settings gear, which itself sits 34dp from the edge), top margin 10dp. MATCH_PARENT width
        // (rather than WRAP_CONTENT) makes those margins actually bound the bar's width so its text
        // children can wrap/ellipsize instead of measuring themselves unbounded.
        addView(dockedBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(34 + 48 + 12); rightMargin = dp(80); topMargin = dp(10)
        })

        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(sourcesButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(endButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(10) })
        addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(26); bottomMargin = dp(24) })

        addView(approvalPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        approvalPill.visibility = GONE
        sourcesButton.visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgDrawable.gradientRadius = maxOf(w, h) * 0.75f
        applyDockedGeometry(animated = false)
    }

    /** Derives the orb's [OrbView.Mode] from a status string resource id (§8). */
    fun modeFor(statusRes: Int): OrbView.Mode = when (statusRes) {
        R.string.status_connecting, R.string.status_preparing_wake -> OrbView.Mode.CONNECTING
        R.string.status_responding, R.string.status_ending_conversation -> OrbView.Mode.SPEAKING
        R.string.status_searching, R.string.status_processing_tool, R.string.status_home_assistant_busy,
        R.string.status_home_devices_busy, R.string.status_home_state_busy, R.string.status_checking_time,
        R.string.status_checking_weather, R.string.status_approval_confirm -> OrbView.Mode.THINKING
        else -> OrbView.Mode.LISTENING
    }

    /** Called every ~250ms from MainActivity's tick. Only touches a child when its value actually
     * changed, same discipline the pre-redesign MainActivity.tick used for its TextViews. */
    fun update(status: Status, userTranscript: String, transcript: String, citations: String, approvalPending: Boolean, conversing: Boolean) {
        val wasShown = shown
        shown = conversing
        // setDocked(false) unconditionally on either edge: a new conversation must never start docked
        // (matches the old behavior), and — the bug this fixes — a conversation that *ends* while still
        // docked (e.g. the end button tapped over the weather page) must undock too, so onDockedChanged
        // fires and MainActivity re-shows its tab strip/gear/dots instead of leaving them hidden forever.
        if (conversing != wasShown) { setDocked(false); setVisible(conversing) }
        // Stayed hidden this tick: skip the detail work below (mode/status/captions) — the weak
        // target device shouldn't pay for string formatting and view diffing while nothing is shown.
        if (!conversing && !wasShown) return
        val mode = modeFor(status.text)
        orb.mode = mode
        val nowSpeaking = mode == OrbView.Mode.SPEAKING
        if (nowSpeaking != speaking) {
            speaking = nowSpeaking
            (stateDot.background as GradientDrawable).setColor(if (speaking) Palette.BRASS else Palette.TEAL)
        }
        val resolved = status.resolve(context)
        if (resolved != lastStatusText) { lastStatusText = resolved; stateText.text = resolved }
        updateCaption(userText, dockedUserText, userTranscript)
        updateCaption(botText, dockedBotText, transcript)
        val citationsBlank = citations.isBlank()
        if (citationsBlank != lastCitationsBlank) { lastCitationsBlank = citationsBlank; sourcesButton.visibility = if (citationsBlank) GONE else VISIBLE }
        if (approvalPending != lastApprovalPending) { lastApprovalPending = approvalPending; approvalPill.visibility = if (approvalPending) VISIBLE else GONE }
    }

    private fun updateCaption(full: TextView, dockedView: TextView, value: String) {
        if (full.text.toString() == value) return
        val wasEmpty = full.text.isNullOrEmpty()
        full.text = value; dockedView.text = value
        if (wasEmpty && value.isNotEmpty()) { full.alpha = 0f; full.animate().alpha(1f).setDuration(250).start() }
    }

    private fun setVisible(visible: Boolean) {
        animate().cancel()
        if (visible) {
            visibility = VISIBLE
            orb.active = true
            animate().alpha(1f).setDuration(300).withEndAction {}.start()
        } else {
            animate().alpha(0f).setDuration(300).withEndAction { visibility = INVISIBLE; orb.active = false }.start()
        }
        animateBackgroundAlpha()
    }

    /** Docked mode (§8): shrinks/moves the orb to the top-left, swaps the centered caption for a bar
     * along the top edge (where the tab strip normally sits — MainActivity hides that strip via
     * [onDockedChanged] for as long as this is true), hides the state dot/text outright (the orb's
     * color already conveys listening/speaking), and fades the background to transparent so the page
     * underneath reads through. */
    fun setDocked(docked: Boolean) {
        if (this.docked == docked) return
        this.docked = docked
        onDockedChanged?.invoke(docked)
        applyDockedGeometry(animated = true)
        stateRow.visibility = if (docked) INVISIBLE else VISIBLE
        captionFull.animate().alpha(if (docked) 0f else 1f).setDuration(300)
            .withEndAction { captionFull.visibility = if (docked) INVISIBLE else VISIBLE }.start()
        if (!docked) captionFull.visibility = VISIBLE
        dockedBar.animate().alpha(if (docked) 1f else 0f).setDuration(300)
            .withEndAction { dockedBar.visibility = if (docked) VISIBLE else INVISIBLE }.start()
        if (docked) dockedBar.visibility = VISIBLE
        animateBackgroundAlpha()
    }

    private fun animateBackgroundAlpha() {
        val target = if (!shown || docked) 0 else 242 // 0xF2, matching the base color's own alpha
        ValueAnimator.ofInt(bgDrawable.alpha, target).apply {
            duration = 300
            addUpdateListener { bgDrawable.alpha = it.animatedValue as Int }
        }.start()
    }

    /** Recomputes the orb's docked-mode scale/translation from the view's current size (§8: shrinks
     * to 48dp, moves to the top-left — centered in the docked bar, which spans y=10dp to y≈66dp, so
     * its own center sits at y=38dp; x is 34dp + half the 48dp orb). The orb's natural (non-docked)
     * layout position is screen center offset up by 80dp (its own bottomMargin above); the natural
     * corner is recomputed from the view's current size (so a size change, e.g. rotation, keeps the
     * docked target correct), while the docked corner is the fixed top-bar position above. */
    private fun applyDockedGeometry(animated: Boolean) {
        if (width == 0 || height == 0) return
        val naturalCx = width / 2f
        val naturalCy = height / 2f - dp(80)
        val dockedCx = dp(34) + dp(24)
        val dockedCy = dp(38)
        val scale = if (docked) 48f / 150f else 1f
        val tx = if (docked) dockedCx - naturalCx else 0f
        val ty = if (docked) dockedCy - naturalCy else 0f
        if (animated) {
            orb.animate().scaleX(scale).scaleY(scale).translationX(tx).translationY(ty).setDuration(300).start()
        } else {
            orb.scaleX = scale; orb.scaleY = scale; orb.translationX = tx; orb.translationY = ty
        }
    }

    /** 56dp circle button (§8's end/sources style): 1dp CREAM_30 stroke, 0x80102326 fill, 22dp icon tinted CREAM_60. */
    private fun circleButton(drawableRes: Int, description: String, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        setImageResource(drawableRes)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconPad = (dp(56) - dp(22)) / 2
        setPadding(iconPad, iconPad, iconPad, iconPad)
        val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80102326.toInt()); setStroke(dp(1), Palette.CREAM_30) }
        background = rippleOn(shape, dp(28).toFloat(), Color.argb(70, 255, 255, 255))
        imageTintList = ColorStateList.valueOf(Palette.CREAM_60)
        contentDescription = description
        layoutParams = ViewGroup.LayoutParams(dp(56), dp(56))
        setOnClickListener { onClick() }
    }
}
