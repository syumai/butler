package dev.syumai.butler

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.math.PI

/** Short ascending two-note chime played on wake word detection, for immediate feedback. Synthesized in code; no asset file. */
object WakeChime {
    private const val SAMPLE_RATE = 48000
    @Volatile var lastPlaybackFrames = 0; private set
    private val pcm: ShortArray by lazy { synthesize() }

    private fun synthesize(): ShortArray {
        val notes = listOf(440.0 to 130, 660.0 to 180)
        val gapMs = 40
        val fadeMs = 10
        val frames = notes.sumOf { (SAMPLE_RATE * it.second / 1000) } + SAMPLE_RATE * gapMs / 1000
        val out = ShortArray(frames * 2)
        var frame = 0
        for ((freq, ms) in notes) {
            val n = SAMPLE_RATE * ms / 1000
            val fade = SAMPLE_RATE * fadeMs / 1000
            for (i in 0 until n) {
                var amp = 0.45
                if (i < fade) amp *= i.toDouble() / fade
                else if (i >= n - fade) amp *= (n - i).toDouble() / fade
                val sample = (amp * sin(2 * PI * freq * i / SAMPLE_RATE) * Short.MAX_VALUE).toInt().toShort()
                out[(frame + i) * 2] = sample; out[(frame + i) * 2 + 1] = sample
            }
            frame += n
        }
        return out
    }

    fun play(context: Context) = runCatching {
        val samples = pcm
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setBufferSizeInBytes(samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(samples, 0, samples.size)
        track.play()
        val durationMs = samples.size / 2 * 1000L / SAMPLE_RATE
        thread {
            runCatching {
                Thread.sleep(durationMs + 100)
                lastPlaybackFrames = track.playbackHeadPosition
                track.release()
            }.onFailure { Log.w("WakeChime", "release failed", it) }
        }
    }.onFailure { Log.w("WakeChime", "play failed", it) }
}
