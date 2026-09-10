package dev.syumai.butler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Runs real bundled native inference on the target ABI, without API keys or microphone audio. */
class WakeInstrumentation : Instrumentation() {
    private var audioTest = false
    private var chime = false
    private var record = false
    private var recordSeconds = 20
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); audioTest = arguments?.getString("mode") == "audio"
        chime = arguments?.getString("mode") == "chime"; record = arguments?.getString("mode") == "record"
        recordSeconds = (arguments?.getString("seconds")?.toIntOrNull() ?: 20).coerceIn(3, 120); start()
    }
    override fun onStart() {
        if (audioTest) { AudioSmoke.run(this); return }
        if (record) { recordAudio(); return }
        if (chime) {
            WakeChime.play(targetContext)
            SystemClock.sleep(600)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPASS: chime played, frames=${WakeChime.lastPlaybackFrames}\n") })
            return
        }
        val report = Bundle()
        try {
            val started = SystemClock.elapsedRealtime()
            // Negative fixtures that must never trigger the wake phrase: English ordinary speech,
            // Japanese ordinary speech, and a short Japanese near-miss ("バター取って") chosen
            // because the model hears a bare "バトラー" in it.
            val negatives = listOf("ordinary-speech.pcm", "ordinary-speech-ja.pcm", "near-miss-ja.pcm")
            WakeModelStore().use { models ->
                val loadStarted = SystemClock.elapsedRealtime()
                val first = models.acquire(targetContext, WakePhrase.HELLO_BUTLER)
                sendStatus(0, Bundle().apply { putString("stream", "Vosk model load/unpack: ${SystemClock.elapsedRealtime() - loadStarted}ms\n") })
                check(feed(first, "hello-butler-ja.pcm")) { "HELLO_BUTLER was not detected (hello-butler-ja.pcm)" }
                models.discardAudio()
                val second = models.acquire(targetContext, WakePhrase.HELLO_BUTLER)
                check(first === second) { "Model was reloaded" }
                repeat(20) { check(!second.accept(ShortArray(1600), 1600)) { "Audio leaked into next stream" } }
                for (negative in negatives) {
                    check(!feed(second, negative)) { "$negative triggered HELLO_BUTLER" }
                }
                // English-pronunciation fixtures: not asserted either way, just fed through to make
                // sure they don't crash the decoder. See third_party/vosk/README.md's limitation note.
                for (english in listOf("hello-butler.pcm", "hey-butler.pcm")) feed(second, english)
                second.restart()
                check(feed(second, "hello-butler-ja-2.pcm")) { "Detection failed after restart (hello-butler-ja-2.pcm)" }
                sendStatus(0, Bundle().apply { putString("stream", "HELLO_BUTLER (hello-butler-ja.pcm, hello-butler-ja-2.pcm): PASS\n") })
            }
            report.putString("stream", "\nPASS: HELLO_BUTLER (Japanese pronunciation, two fixtures), three negative fixtures (English + two Japanese), two English-pronunciation fixtures fed through unasserted, silence, cached restart; native armeabi-v7a. ${SystemClock.elapsedRealtime()-started}ms\n")
            report.putString("wake_test", "passed")
            finish(Activity.RESULT_OK, report)
        } catch (e: Throwable) {
            report.putString("stream", "\nFAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            report.putString("wake_test", "failed")
            finish(Activity.RESULT_CANCELED, report)
        }
    }
    @SuppressLint("MissingPermission") // Instrumentation runs only after RECORD_AUDIO is granted for the test target.
    private fun recordAudio() {
        sendStatus(0, Bundle().apply { putString("stream", "recording $recordSeconds s ...\n") })
        var initFailed = false
        var bytes: ByteArray? = null
        thread(name = "Butler-record") {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
            try {
                if (mic.state != AudioRecord.STATE_INITIALIZED) { initFailed = true; return@thread }
                mic.startRecording()
                val total = 16000 * recordSeconds
                val out = ShortArray(total)
                val buffer = ShortArray(1600)
                var filled = 0
                while (filled < total) {
                    val count = mic.read(buffer, 0, minOf(buffer.size, total - filled))
                    if (count <= 0) break
                    System.arraycopy(buffer, 0, out, filled, count)
                    filled += count
                }
                mic.stop()
                bytes = ByteBuffer.allocate(filled * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    for (i in 0 until filled) putShort(out[i])
                }.array()
            } finally { mic.release() }
        }.join()
        if (initFailed) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: mic failed to initialize\n") })
            return
        }
        val data = bytes ?: ByteArray(0)
        var peak = 0
        val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shorts.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(shorts.get().toInt()))
        if (peak == 0) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: peak amplitude is 0\n") })
            return
        }
        val file = File(targetContext.getExternalFilesDir(null), "record.pcm")
        file.writeBytes(data)
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: recorded ${data.size} bytes, peak=$peak, path=${file.absolutePath}\n") })
    }
    private fun feed(decoder: WakeDecoder, name: String): Boolean {
        val bytes = context.assets.open(name).use { it.readBytes() }
        check(bytes.size >= 3200 && bytes.size % 2 == 0) { "Missing PCM audio in $name" }
        check(bytes.any { it.toInt() != 0 }) { "Silent fixture: $name" }
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining() + 24000) { i -> if (i in 8000 until (8000 + bytes.size / 2)) pcm.get() else 0 }
        var hit = false
        for (offset in samples.indices step 1600) {
            val chunk = samples.copyOfRange(offset, minOf(offset + 1600, samples.size))
            hit = decoder.accept(chunk, chunk.size) || hit
        }
        return hit
    }
}
