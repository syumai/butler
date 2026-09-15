package dev.syumai.butler

import android.content.Context
import org.json.JSONObject

/**
 * Localized labels for Home Assistant device kinds/states (design spec §6), shared by
 * [SettingsActivity]'s devices dialog and the smart-home/music pages ([SmartHomePage],
 * [DeviceSheet], [MusicPage]). Moved out of `SettingsActivity` so both places describe a device
 * the same way.
 */
object DeviceText {
    val KIND_STRINGS = mapOf(
        "light" to R.string.device_kind_light, "switch" to R.string.device_kind_switch, "climate" to R.string.device_kind_climate,
        "fan" to R.string.device_kind_fan, "cover" to R.string.device_kind_cover, "lock" to R.string.device_kind_lock,
        "media_player" to R.string.device_kind_media_player, "humidifier" to R.string.device_kind_humidifier,
        "vacuum" to R.string.device_kind_vacuum, "scene" to R.string.device_kind_scene, "script" to R.string.device_kind_script,
        "input_boolean" to R.string.device_kind_input_boolean,
    )
    val STATE_STRINGS = mapOf(
        "cool" to R.string.device_state_cool, "heat" to R.string.device_state_heat, "dry" to R.string.device_state_dry,
        "fan_only" to R.string.device_state_fan_only, "auto" to R.string.device_state_auto, "heat_cool" to R.string.device_state_heat_cool,
        "open" to R.string.device_state_open, "closed" to R.string.device_state_closed, "opening" to R.string.device_state_opening,
        "closing" to R.string.device_state_closing, "locked" to R.string.device_state_locked, "unlocked" to R.string.device_state_unlocked,
        "playing" to R.string.device_state_playing, "paused" to R.string.device_state_paused, "idle" to R.string.device_state_idle,
        "standby" to R.string.device_state_standby, "unavailable" to R.string.device_state_unavailable, "unknown" to R.string.device_state_unknown,
    )

    /** Sort order for the smart-home tile grid (§6): area (blank last), then this type order, then name. */
    val TYPE_ORDER = listOf(
        "climate", "light", "cover", "fan", "humidifier", "media_player", "switch", "input_boolean", "lock", "vacuum", "scene", "script",
    )

    fun kindLabel(context: Context, type: String): String = KIND_STRINGS[type]?.let { context.getString(it) } ?: type

    /** Formats a temperature-like number without a trailing ".0" (24.0 -> "24", 24.5 -> "24.5"), or null when [value] is NaN. */
    fun formatTemp(value: Double): String? {
        if (value.isNaN()) return null
        return if (value == Math.floor(value)) value.toLong().toString() else value.toString()
    }

    /** Generic localized state label for a device chip (SettingsActivity's devices dialog), with current/target
     * temperature appended for climate entities. */
    fun stateLabel(context: Context, type: String, state: String, attributes: JSONObject?): String {
        val key = deviceStateKey(type, state)
        var label = when (key) {
            "on" -> context.getString(if (type == "light") R.string.device_state_light_on else R.string.device_state_on)
            "off" -> context.getString(if (type == "light") R.string.device_state_light_off else R.string.device_state_off)
            "raw" -> state
            else -> STATE_STRINGS[key]?.let { context.getString(it) } ?: state
        }
        if (type == "climate" && attributes != null) {
            formatTemp(attributes.optDouble("current_temperature", Double.NaN))?.let { label += " · " + context.getString(R.string.device_state_current_temp, it) }
            formatTemp(attributes.optDouble("temperature", Double.NaN))?.let { label += " · " + context.getString(R.string.device_state_target_temp, it) }
        }
        return label
    }

    /** Kind emoji for the smart-home tile/sheet header (§6); climate varies with hvac state/mode. */
    fun kindEmoji(type: String, state: String): String = when (type) {
        "light" -> "💡" // 💡
        "switch", "input_boolean" -> "🔌" // 🔌
        "climate" -> if (state == "heat") "🔥" else "❄️" // 🔥 / ❄️
        "fan" -> "🌀" // 🌀
        "cover" -> "🪟" // 🪟
        "lock" -> if (state == "locked") "🔒" else "🔓" // 🔒 / 🔓
        "media_player" -> "🔊" // 🔊
        "humidifier" -> "💧" // 💧
        "vacuum" -> "🧹" // 🧹
        "scene" -> "🎬" // 🎬
        "script" -> "▶️" // ▶️
        else -> "•"
    }

    /** The smart-home tile's state line (§6) — a distinct, more compact format per domain than [stateLabel]. */
    fun tileStateLine(context: Context, device: JSONObject): String {
        val type = device.optString("type"); val state = device.optString("state")
        val attributes = device.optJSONObject("attributes") ?: JSONObject()
        return when (type) {
            "climate" -> {
                val current = formatTemp(attributes.optDouble("current_temperature", Double.NaN))
                if (state == "off") {
                    if (current != null) context.getString(R.string.smart_home_climate_off, current) else context.getString(R.string.device_state_off)
                } else {
                    val modeLabel = STATE_STRINGS[state]?.let { context.getString(it) } ?: state
                    val target = formatTemp(attributes.optDouble("temperature", Double.NaN))
                    if (target != null && current != null) context.getString(R.string.smart_home_climate_state, modeLabel, target, current) else modeLabel
                }
            }
            "light" -> if (state == "on") {
                val pct = attributes.optInt("brightness_percent", -1)
                if (pct in 0..100) context.getString(R.string.smart_home_light_on_format, pct) else context.getString(R.string.device_state_light_on)
            } else context.getString(R.string.device_state_light_off)
            "cover" -> {
                val pos = attributes.optInt("current_position", -1)
                when {
                    pos in 1..99 -> context.getString(R.string.smart_home_cover_partial, pos)
                    state == "open" -> context.getString(R.string.device_state_open)
                    state == "closed" -> context.getString(R.string.device_state_closed)
                    else -> STATE_STRINGS[state]?.let { context.getString(it) } ?: state
                }
            }
            "fan" -> if (state == "on") {
                val pct = attributes.optInt("percentage", -1)
                if (pct in 0..100) context.getString(R.string.smart_home_fan_speed, pct) else context.getString(R.string.device_state_on)
            } else context.getString(R.string.smart_home_stop)
            "humidifier" -> if (state == "on") {
                val pct = attributes.optInt("humidity", -1)
                if (pct in 0..100) context.getString(R.string.smart_home_humidifier_on, pct) else context.getString(R.string.device_state_on)
            } else context.getString(R.string.smart_home_stop)
            "media_player" -> if (state == "playing") {
                val title = attributes.optString("media_title")
                if (title.isNotBlank()) context.getString(R.string.smart_home_media_playing, title) else stateLabel(context, type, state, attributes)
            } else stateLabel(context, type, state, attributes)
            else -> stateLabel(context, type, state, attributes)
        }
    }
}
