package dev.syumai.butler

/**
 * The output voices offered across both voice conversation backends. [apis] lists which
 * [VoiceApi] a given voice is valid for: `marin` works for both Realtime and GPT-Live (it is the
 * documented default for Live and a recommended choice for Realtime); the other nine legacy voices
 * are Realtime-only; the twelve GPT-Live voices are Live-only (Live has no dedicated Japanese
 * voice — Japanese is spoken by prompt instead). Note both APIs fix the voice for a session once it
 * has produced any audio, so a change here only takes effect on the next conversation. [labelRes]
 * points at the localized display name in strings.xml (English/Japanese).
 */
enum class Voice(val id: String, val labelRes: Int, val apis: Set<VoiceApi>) {
    MARIN("marin", R.string.voice_marin, setOf(VoiceApi.REALTIME, VoiceApi.LIVE)),
    CEDAR("cedar", R.string.voice_cedar, setOf(VoiceApi.REALTIME)),
    ALLOY("alloy", R.string.voice_alloy, setOf(VoiceApi.REALTIME)),
    ASH("ash", R.string.voice_ash, setOf(VoiceApi.REALTIME)),
    BALLAD("ballad", R.string.voice_ballad, setOf(VoiceApi.REALTIME)),
    CORAL("coral", R.string.voice_coral, setOf(VoiceApi.REALTIME)),
    ECHO("echo", R.string.voice_echo, setOf(VoiceApi.REALTIME)),
    SAGE("sage", R.string.voice_sage, setOf(VoiceApi.REALTIME)),
    SHIMMER("shimmer", R.string.voice_shimmer, setOf(VoiceApi.REALTIME)),
    VERSE("verse", R.string.voice_verse, setOf(VoiceApi.REALTIME)),
    QUARTZ("quartz", R.string.voice_quartz, setOf(VoiceApi.LIVE)),
    RIPPLE("ripple", R.string.voice_ripple, setOf(VoiceApi.LIVE)),
    VESPER("vesper", R.string.voice_vesper, setOf(VoiceApi.LIVE)),
    WILLOW("willow", R.string.voice_willow, setOf(VoiceApi.LIVE)),
    STONE("stone", R.string.voice_stone, setOf(VoiceApi.LIVE)),
    GLEAM("gleam", R.string.voice_gleam, setOf(VoiceApi.LIVE)),
    MERIDIAN("meridian", R.string.voice_meridian, setOf(VoiceApi.LIVE)),
    BOSSA("bossa", R.string.voice_bossa, setOf(VoiceApi.LIVE)),
    TEMPO("tempo", R.string.voice_tempo, setOf(VoiceApi.LIVE)),
    BEACON("beacon", R.string.voice_beacon, setOf(VoiceApi.LIVE)),
    DELTA("delta", R.string.voice_delta, setOf(VoiceApi.LIVE)),
    CINDER("cinder", R.string.voice_cinder, setOf(VoiceApi.LIVE));

    companion object {
        val DEFAULT = MARIN

        /** Every voice's id, regardless of which api it is valid for. Falls back to [DEFAULT] for an unknown/blank id. */
        fun fromId(id: String?): Voice {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }

        /** Like [fromId], but falls back to [DEFAULT] when [id] does not name a voice valid for [api]. */
        fun fromId(id: String?, api: VoiceApi): Voice {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized && api in it.apis } ?: DEFAULT
        }

        /** The voices valid for [api], in enum declaration order. */
        fun forApi(api: VoiceApi): List<Voice> = entries.filter { api in it.apis }
    }
}
