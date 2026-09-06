package dev.syumai.butler

/**
 * The OpenAI Realtime API's output voices. OpenAI recommends marin or cedar for the most natural
 * results; the others are also supported by the API. Note the API fixes the voice for a session
 * once it has produced any audio, so a change here only takes effect on the next conversation.
 * [labelRes] points at the localized display name in strings.xml (English/Japanese).
 */
enum class Voice(val id: String, val labelRes: Int) {
    MARIN("marin", R.string.voice_marin),
    CEDAR("cedar", R.string.voice_cedar),
    ALLOY("alloy", R.string.voice_alloy),
    ASH("ash", R.string.voice_ash),
    BALLAD("ballad", R.string.voice_ballad),
    CORAL("coral", R.string.voice_coral),
    ECHO("echo", R.string.voice_echo),
    SAGE("sage", R.string.voice_sage),
    SHIMMER("shimmer", R.string.voice_shimmer),
    VERSE("verse", R.string.voice_verse);

    companion object {
        val DEFAULT = MARIN
        fun fromId(id: String?): Voice {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}
