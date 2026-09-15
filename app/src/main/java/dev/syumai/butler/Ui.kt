package dev.syumai.butler

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/** Shared ARGB color constants for the redesigned UI (see design spec §1). Values are always ARGB
 *  ints (alpha included), matching how `Color`/`Paint`/`GradientDrawable` consume them elsewhere
 *  in this codebase; hex literals with the high bit set parse fine as negative `Int`s in Kotlin,
 *  same as in Java, so no `.toInt()` is needed. */
object Palette {
    const val INK = 0xFF102326.toInt()
    const val INK2 = 0xFF17333A.toInt()
    const val TEAL = 0xFF2E6E76.toInt()
    const val TEAL_SOFT = 0x732E6E76.toInt()
    const val CREAM = 0xFFF5EFE3.toInt()
    const val CREAM_60 = 0x9EF5EFE3.toInt()
    const val CREAM_30 = 0x52F5EFE3.toInt()
    const val CREAM_12 = 0x1FF5EFE3.toInt()
    const val BRASS = 0xFFE3B865.toInt()
    const val BRASS_SOFT = 0x38E3B865.toInt()
    const val RAIN = 0xFF7FB2C9.toInt()
}

/** Converts a dp value to px using this context's display density; moved here from MainActivity/
 *  SettingsActivity, which each had their own identical copy, so every page shares one. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Plain [TextView] with the shared cream text color by default (matches the old hard-coded
 *  `Color.rgb(245, 239, 227)` used by MainActivity/SettingsActivity, now [Palette.CREAM]). */
fun Context.text(sizeSp: Float, value: String = "", color: Int = Palette.CREAM): TextView =
    TextView(this).apply { textSize = sizeSp; text = value; setTextColor(color) }

/** Rounded-rect shape used as both a view's visible background and its ripple mask. When
 *  [strokeColor] is given the shape is stroke-only (transparent fill) — used for outlined
 *  buttons; callers that need both a fill and a stroke (e.g. the glass card) build their own
 *  [GradientDrawable] instead of reusing this. */
fun roundedShape(radius: Float, fill: Int = Color.WHITE, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius
        if (strokeColor != null) { setColor(Color.TRANSPARENT); setStroke(strokeWidth, strokeColor) } else setColor(fill)
    }

fun rippleOn(content: Drawable?, radius: Float, rippleColor: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, roundedShape(radius))

private fun Int.red() = Color.red(this)
private fun Int.green() = Color.green(this)
private fun Int.blue() = Color.blue(this)

/** Material "contained" button: filled rounded background (tinted), white ripple, default press elevation from the theme's Button style. */
fun Context.filledButton(label: String, textSizeSp: Float, tint: Int, clicked: () -> Unit): Button = Button(this).apply {
    text = label; isAllCaps = false; textSize = textSizeSp; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
    val radius = dp(12).toFloat()
    background = rippleOn(roundedShape(radius), radius, Color.argb(90, 255, 255, 255))
    backgroundTintList = ColorStateList.valueOf(tint)
    setOnClickListener { clicked() }
}

/** Material "outlined" button: 1dp stroke, transparent fill, ripple; used for secondary actions. */
fun Context.outlinedButton(label: String, textSizeSp: Float, strokeColor: Int, clicked: () -> Unit): Button = Button(this).apply {
    text = label; isAllCaps = false; textSize = textSizeSp; setTextColor(Palette.CREAM)
    val radius = dp(10).toFloat()
    background = rippleOn(roundedShape(radius, strokeColor = strokeColor, strokeWidth = dp(1)), radius, Color.argb(70, strokeColor.red(), strokeColor.green(), strokeColor.blue()))
    backgroundTintList = null
    setPadding(dp(16), paddingTop, dp(16), paddingBottom)
    setOnClickListener { clicked() }
}

/** Borderless ripple used for icon buttons, matching the theme's ?attr/selectableItemBackgroundBorderless. */
fun Context.borderlessRippleBackground(): Drawable {
    val out = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
    return getDrawable(out.resourceId)!!
}

/** "Glass" card container (§1): translucent cream fill, faint white stroke, 22dp corners. Returns
 *  an empty [FrameLayout] with that background so callers add their own content on top. Needs its
 *  own [GradientDrawable] (rather than [roundedShape]) since it wants a fill *and* a stroke at
 *  once, which [roundedShape] deliberately doesn't support (see its doc). */
fun glassCard(context: Context): FrameLayout = FrameLayout(context).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = context.dp(22).toFloat()
        setColor(Palette.CREAM_12); setStroke(context.dp(1).coerceAtLeast(1), 0x1FFFFFFF)
    }
}

/** Small pill-shaped tab/filter chip (§1, §3): dim cream when unselected, filled rain-blue with
 *  ink text when selected. Corner radius is set larger than any reasonable chip height so it
 *  always renders as a full pill, the usual CSS `border-radius: 999px` trick. */
fun chip(context: Context, label: String, selected: Boolean): TextView = context.text(14f, label, if (selected) Palette.INK else Palette.CREAM_60).apply {
    setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
    background = roundedShape(context.dp(999).toFloat(), fill = if (selected) Palette.RAIN else Palette.CREAM_12)
}

/** 48dp borderless icon button, tinted [Palette.CREAM_60] (§1) — the settings gear, conversation
 *  end/sources buttons, etc. all share this. */
fun iconButton(context: Context, drawableRes: Int, contentDescription: String, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
    setImageResource(drawableRes); scaleType = ImageView.ScaleType.CENTER
    background = context.borderlessRippleBackground()
    this.contentDescription = contentDescription
    imageTintList = ColorStateList.valueOf(Palette.CREAM_60)
    val size = context.dp(48)
    layoutParams = ViewGroup.LayoutParams(size, size)
    setOnClickListener { onClick() }
}
