package dev.syumai.butler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.*
import kotlin.concurrent.thread

enum class WakePhrase(val asset: String, val label: String, val score: Float = 1.5f) {
    HELLO_COMPUTER("hello-computer", "Hello Computer（ハロー・コンピューター）"),
    HEY_BUTLER("hey-butler", "Hey Butler（ヘイ・バトラー）", 3.0f),
    HELLO_BUTLER("hello-butler", "Hello Butler（ハロー・バトラー）", 3.0f),
    HELLO_WORLD("keywords", "Hello World（ハロー・ワールド）"),
}

/** Bundled local inference only. This class has no network or credential dependencies. */
class WakeDecoder(context: Context, threshold: Float = 0.25f, phrase: WakePhrase = WakePhrase.HELLO_COMPUTER) : AutoCloseable {
    private val spotter = KeywordSpotter(context.assets, KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "wake/encoder.onnx", decoder = "wake/decoder.onnx", joiner = "wake/joiner.onnx"),
            tokens = "wake/tokens.txt", numThreads = 1, modelType = "zipformer2", debug = false,
        ),
        keywordsFile = "wake/${phrase.asset}.txt", keywordsScore = phrase.score, keywordsThreshold = threshold,
    ))
    private var stream = spotter.createStream()
    init { check(stream.ptr != 0L) { "Could not create keyword stream" } }
    fun restart() { stream.release(); stream = spotter.createStream(); check(stream.ptr != 0L) }
    fun accept(samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) {
                spotter.reset(stream)
                return true
            }
        }
        return false
    }
    fun discardAudio() { stream.release() }
    override fun close() { stream.release(); spotter.release() }
}

/** Retains weights across conversations; streams are replaced to discard previous audio. */
class WakeModelStore : AutoCloseable {
    private var cached: WakeDecoder? = null
    private var threshold: Float? = null
    private var phrase: WakePhrase? = null
    @Synchronized fun acquire(context: Context, value: Float, selected: WakePhrase = WakePhrase.HELLO_COMPUTER): WakeDecoder {
        if (cached == null || threshold != value || phrase != selected) {
            cached?.close(); cached = null
            cached = WakeDecoder(context, value, selected); threshold = value; phrase = selected
        } else cached!!.restart()
        return cached!!
    }
    @Synchronized fun discardAudio() { cached?.discardAudio() }
    @Synchronized override fun close() { cached?.close(); cached = null }
}

/** Owns the microphone and decoder on one worker; callbacks run after native cleanup. */
class LocalWakeWordEngine(private val context: Context, private val models: WakeModelStore, private val threshold: Float, private val phrase: WakePhrase,
    private val ready: () -> Unit, private val detected: () -> Unit, private val failed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recorder: AudioRecord? = null
    @Volatile private var stopped = false
    private var finished = false
    private val completions = mutableListOf<() -> Unit>()

    @SuppressLint("MissingPermission") // Service starts only after the Activity grants RECORD_AUDIO.
    fun start() {
        thread(name = "Butler-local-wake") {
            var hit = false
            var error = false
            try {
                run {
                    val decoder = models.acquire(context, threshold, phrase)
                    if (!stopped) {
                        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(min > 0)
                        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
                        synchronized(lock) {
                            recorder = mic
                            check(mic.state == AudioRecord.STATE_INITIALIZED)
                            if (!stopped) mic.startRecording()
                        }
                        if (!stopped) {
                            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            main.post { if (!stopped) ready() }
                        }
                        val buffer = ShortArray(1600)
                        while (!stopped) {
                            val count = mic.read(buffer, 0, buffer.size)
                            if (stopped) break
                            check(count > 0)
                            if (decoder.accept(FloatArray(count) { buffer[it] / 32768f })) { hit = true; break }
                        }
                    }
                }
            } catch (_: Exception) { error = !stopped }
            catch (_: LinkageError) { error = !stopped }
            finally {
                models.discardAudio()
                val callbacks = synchronized(lock) {
                    recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
                    finished = true
                    completions.toList().also { completions.clear() }
                }
                main.post {
                    if (!stopped) { if (hit) detected() else if (error) failed() }
                    callbacks.forEach { it() }
                }
            }
        }
    }
    fun stop(completed: () -> Unit = {}) {
        synchronized(lock) {
            stopped = true
            if (finished) main.post(completed) else {
                completions.add(completed)
                // Unblock AudioRecord.read; only the worker releases native resources.
                recorder?.let { runCatching { it.stop() } }
            }
        }
    }
}
