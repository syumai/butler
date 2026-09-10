package dev.syumai.butler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

enum class WakePhrase(val asset: String, val label: String, val score: Float = 1.5f, val voskPhrase: String? = null) {
    HELLO_COMPUTER("hello-computer", "Hello Computer"),
    HEY_BUTLER("hey-butler", "Hey Butler", 3.0f),
    HELLO_BUTLER("hello-butler", "Hello Butler", 3.0f, voskPhrase = "ハロー バトラー"),
    HELLO_WORLD("keywords", "Hello World"),
}

/** Which on-device engine decodes the microphone stream. Vosk is the default (see Settings.wakeEngine);
 * sherpa-onnx stays selectable as a revert path and for its English-pronunciation coverage. */
enum class WakeEngine(val id: String) {
    SHERPA("sherpa"), VOSK("vosk");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: VOSK }
}

/** One engine's keyword decoder. Samples are raw 16-bit PCM (int16-scaled), passed through from the
 * microphone buffer untouched; each implementation converts as it needs (sherpa wants normalized
 * floats, Vosk's float entry point wants int16-scaled floats). Bundled local inference only — no
 * network or credential dependencies. */
interface WakeDecoder : AutoCloseable {
    fun accept(samples: ShortArray, count: Int): Boolean
    fun restart()
    fun discardAudio()
}

class SherpaWakeDecoder(context: Context, threshold: Float = 0.25f, phrase: WakePhrase = WakePhrase.HELLO_COMPUTER) : WakeDecoder {
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
    override fun restart() { stream.release(); stream = spotter.createStream(); check(stream.ptr != 0L) }
    override fun accept(samples: ShortArray, count: Int): Boolean {
        stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) {
                spotter.reset(stream)
                return true
            }
        }
        return false
    }
    override fun discardAudio() { stream.release() }
    override fun close() { stream.release(); spotter.release() }
}

/** Pure keyword-adjacency check over a Vosk partial/final result JSON, no Android imports so it is
 * unit-testable on the JVM. A hit requires [phrase]'s words to appear as a contiguous run in the
 * recognized token list — a bare "バトラー", or "ハロー [unk] バトラー", must not match. */
object VoskWake {
    fun hit(resultJson: String, phrase: String): Boolean {
        val text = runCatching {
            val json = JSONObject(resultJson)
            if (json.has("partial")) json.getString("partial") else json.optString("text")
        }.getOrDefault("")
        if (text.isBlank()) return false
        val tokens = text.trim().split(Regex("\\s+"))
        val words = phrase.trim().split(Regex("\\s+"))
        if (words.isEmpty()) return false
        return tokens.indices.any { start ->
            start + words.size <= tokens.size && (0 until words.size).all { tokens[start + it] == words[it] }
        }
    }
}

/** Runs Vosk as a continuous small-vocabulary ASR restricted to the runtime grammar
 * `["<phrase>", "[unk]"]`, and reports a hit via [VoskWake.hit] on every partial/final result. Model
 * assets (`vosk/vosk-model-small-ja-0.22/` under `assets/`) are unpacked to `filesDir` on first use;
 * `org.vosk.android.StorageService` is not used since it expects a `uuid` asset and the external
 * files dir, which don't fit this bundling. Japanese pronunciation only — [phrase] must have a
 * non-null [WakePhrase.voskPhrase]. */
class VoskWakeDecoder(context: Context, phrase: WakePhrase) : WakeDecoder {
    companion object {
        private const val MODEL_ASSET_DIR = "vosk/vosk-model-small-ja-0.22"
        private const val MARKER = ".ready"
        @Volatile private var logLevelSet = false
    }
    private val voskPhrase = requireNotNull(phrase.voskPhrase) { "Vosk does not support ${phrase.name}" }
    private val model: Model
    private var recognizer: Recognizer

    init {
        synchronized(Companion) {
            if (!logLevelSet) { LibVosk.setLogLevel(LogLevel.WARNINGS); logLevelSet = true }
        }
        val dir = unpackModel(context)
        model = Model(dir.absolutePath)
        recognizer = Recognizer(model, 16000f, "[\"$voskPhrase\", \"[unk]\"]")
    }

    private fun unpackModel(context: Context): File {
        val dest = File(context.filesDir, MODEL_ASSET_DIR)
        val marker = File(dest, MARKER)
        if (marker.exists() && marker.readText() == MODEL_ASSET_DIR) return dest
        val started = System.currentTimeMillis()
        val temp = File(context.filesDir, "$MODEL_ASSET_DIR.tmp")
        try {
            temp.deleteRecursively()
            temp.mkdirs()
            copyAssetDir(context, MODEL_ASSET_DIR, temp)
            File(temp, MARKER).writeText(MODEL_ASSET_DIR)
            dest.deleteRecursively()
            check(temp.renameTo(dest)) { "Could not install unpacked Vosk model" }
        } catch (e: Exception) {
            temp.deleteRecursively(); dest.deleteRecursively()
            throw e
        }
        Log.i("Butler", "Vosk model unpacked in ${System.currentTimeMillis() - started}ms")
        return dest
    }

    private fun copyAssetDir(context: Context, assetPath: String, destRoot: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // A leaf file: assets.list() on a file path returns an empty array too, so try opening it.
            val relative = assetPath.removePrefix("$MODEL_ASSET_DIR/")
            val out = File(destRoot, relative)
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            return
        }
        for (entry in entries) copyAssetDir(context, "$assetPath/$entry", destRoot)
    }

    override fun accept(samples: ShortArray, count: Int): Boolean {
        val ended = recognizer.acceptWaveForm(samples, count)
        val json = if (ended) recognizer.result else recognizer.partialResult
        if (VoskWake.hit(json, voskPhrase)) { recognizer.reset(); return true }
        return false
    }
    override fun restart() { recognizer.reset() }
    override fun discardAudio() { recognizer.reset() }
    override fun close() { recognizer.close(); model.close() }
}

/** Retains weights across conversations; streams/recognizers are reset to discard previous audio. */
class WakeModelStore : AutoCloseable {
    private var cached: WakeDecoder? = null
    private var engine: WakeEngine? = null
    private var threshold: Float? = null
    private var phrase: WakePhrase? = null
    @Synchronized fun acquire(context: Context, value: Float, selected: WakePhrase = WakePhrase.HELLO_COMPUTER, engine: WakeEngine = WakeEngine.SHERPA): WakeDecoder {
        if (cached == null || this.engine != engine || threshold != value || phrase != selected) {
            cached?.close(); cached = null
            cached = when (engine) {
                WakeEngine.SHERPA -> SherpaWakeDecoder(context, value, selected)
                WakeEngine.VOSK -> VoskWakeDecoder(context, selected)
            }
            this.engine = engine; threshold = value; phrase = selected
        } else cached!!.restart()
        return cached!!
    }
    @Synchronized fun discardAudio() { cached?.discardAudio() }
    @Synchronized override fun close() { cached?.close(); cached = null }
}

/** Owns the microphone and decoder on one worker; callbacks run after native cleanup. */
class LocalWakeWordEngine(private val context: Context, private val models: WakeModelStore, private val threshold: Float, private val phrase: WakePhrase, private val engine: WakeEngine,
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
                    val decoder = models.acquire(context, threshold, phrase, engine)
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
                            if (decoder.accept(buffer, count)) { hit = true; break }
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
