package dev.syumai.butler

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs real bundled native inference on the target ABI, without API keys or microphone audio. */
class WakeInstrumentation : Instrumentation() {
    private var tune = false
    private var audioTest = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); tune = arguments?.getString("mode") == "tune"; audioTest = arguments?.getString("mode") == "audio"; start() }
    override fun onStart() {
        if (audioTest) { AudioSmoke.run(this); return }
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
