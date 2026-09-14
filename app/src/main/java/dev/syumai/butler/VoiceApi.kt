package dev.syumai.butler

/**
 * The voice conversation backend: OpenAI's Realtime API (`/v1/realtime/calls`, the default) or the
 * newer GPT-Live API (`/v1/live/sessions`). Selected in Settings; stored as [id] under the `voiceApi`
 * key. See [Voice] for which output voices are valid for which api.
 */
enum class VoiceApi(val id: String) {
    REALTIME("realtime"),
    LIVE("live");

    companion object {
        val DEFAULT = REALTIME
        fun fromId(id: String?): VoiceApi {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}
