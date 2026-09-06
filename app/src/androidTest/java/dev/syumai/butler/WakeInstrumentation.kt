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
    private var tune = false
    private var audioTest = false
    private var chime = false
    private var record = false
    private var recordSeconds = 20
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); tune = arguments?.getString("mode") == "tune"; audioTest = arguments?.getString("mode") == "audio"
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
            if (tune) { tuneKeywords(); finish(Activity.RESULT_OK, Bundle()); return }
            for ((phrase, fixture) in listOf(WakePhrase.HEY_BUTLER to "hey-butler.pcm", WakePhrase.HELLO_BUTLER to "hello-butler.pcm", WakePhrase.HELLO_COMPUTER to "hello-computer.pcm", WakePhrase.HELLO_WORLD to "hello-world.pcm")) {
                WakeModelStore().use { models ->
                    val first = models.acquire(targetContext, 0.25f, phrase)
                    check(feed(first, fixture)) { "${phrase.name} was not detected" }
                    models.discardAudio()
                    val second = models.acquire(targetContext, 0.25f, phrase)
                    check(first === second) { "Model was reloaded" }
                    repeat(20) { check(!second.accept(FloatArray(1600))) { "Audio leaked into next stream" } }
                    check(!feed(second, "ordinary-speech.pcm")) { "Ordinary speech triggered ${phrase.name}" }
                    second.restart()
                    check(feed(second, fixture)) { "Detection failed after restart" }
                    sendStatus(0, Bundle().apply { putString("stream", "${phrase.name}: PASS\n") })
                }
            }
            report.putString("stream", "\nPASS: four phrases, ordinary speech, silence, cached restart; native armeabi-v7a. ${SystemClock.elapsedRealtime()-started}ms\n")
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
    private fun tuneKeywords() {
        WakeDecoder(targetContext).use { decoder ->
            val spotter = WakeDecoder::class.java.getDeclaredField("spotter").apply { isAccessible = true }.get(decoder) as com.k2fsa.sherpa.onnx.KeywordSpotter
            for (name in listOf("hey-butler", "hello-butler")) {
                val bytes = context.assets.open("$name.pcm").use { it.readBytes() }
                val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(pcm.remaining() + 32000) { i -> if (i in 8000 until (8000 + bytes.size / 2)) pcm.get() / 32768f else 0f }
                val lines = targetContext.assets.open("wake/$name.txt").bufferedReader().use { it.readLines() }
                for (line in lines) for (score in listOf(1.5f, 3f, 5f)) for (threshold in listOf(0.25f, 0.1f, 0.05f)) {
                    val stream = spotter.createStream(line.substringBefore(" @") + " :$score #$threshold @test")
                    var hit = false
                    try {
                        for (offset in samples.indices step 1600) {
                            stream.acceptWaveform(samples.copyOfRange(offset, minOf(offset + 1600, samples.size)), 16000)
                            while (spotter.isReady(stream)) { spotter.decode(stream); if (spotter.getResult(stream).keyword.isNotBlank()) { hit = true; spotter.reset(stream) } }
                        }
                    } finally { stream.release() }
                    if (hit) sendStatus(0, Bundle().apply { putString("stream", "$name $line score=$score threshold=$threshold: HIT\n") })
                }
                sendStatus(0, Bundle().apply { putString("stream", "$name tuning done\n") })
            }
        }
    }
    private fun feed(decoder: WakeDecoder, name: String): Boolean {
        val bytes = context.assets.open(name).use { it.readBytes() }
        check(bytes.size >= 3200 && bytes.size % 2 == 0) { "Missing PCM audio in $name" }
        check(bytes.any { it.toInt() != 0 }) { "Silent fixture: $name" }
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining() + 24000) { i -> if (i in 8000 until (8000 + bytes.size / 2)) pcm.get() / 32768f else 0f }
        var hit = false
        for (offset in samples.indices step 1600) hit = decoder.accept(samples.copyOfRange(offset, minOf(offset + 1600, samples.size))) || hit
        return hit
    }
}
