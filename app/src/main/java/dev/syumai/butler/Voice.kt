package dev.syumai.butler

/**
 * The OpenAI Realtime API's output voices. OpenAI recommends marin or cedar for the most natural
 * results; the others are also supported by the API. Note the API fixes the voice for a session
 * once it has produced any audio, so a change here only takes effect on the next conversation.
 */
enum class Voice(val id: String, val label: String) {
    MARIN("marin", "Marin（推奨・落ち着いた声）"),
    CEDAR("cedar", "Cedar（推奨・自然な会話調）"),
    ALLOY("alloy", "Alloy"),
    ASH("ash", "Ash"),
    BALLAD("ballad", "Ballad"),
    CORAL("coral", "Coral"),
    ECHO("echo", "Echo"),
    SAGE("sage", "Sage"),
    SHIMMER("shimmer", "Shimmer"),
    VERSE("verse", "Verse");

    companion object {
        val DEFAULT = MARIN
        fun fromId(id: String?): Voice {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}
