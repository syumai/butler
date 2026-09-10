package dev.syumai.butler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

enum class WakePhrase(val label: String, val voskPhrase: String) {
    HELLO_BUTLER("Hello Butler", "ハロー バトラー"),
}

/** The on-device decoder that watches the standby microphone stream. Samples are raw 16-bit PCM
 * (int16-scaled), passed through from the microphone buffer untouched; the implementation converts
 * as it needs (Vosk's float entry point wants int16-scaled floats). [VoskWakeDecoder] is the only
 * implementation; the interface documents the contract and keeps [WakeModelStore]/[LocalWakeWordEngine]
 * decoupled from it. */
interface WakeDecoder : AutoCloseable {
    fun accept(samples: ShortArray, count: Int): Boolean
    fun restart()
    fun discardAudio()
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
 * files dir, which don't fit this bundling. Japanese pronunciation only. */
class VoskWakeDecoder(context: Context, phrase: WakePhrase) : WakeDecoder {
    companion object {
        private const val MODEL_ASSET_DIR = "vosk/vosk-model-small-ja-0.22"
        private const val MARKER = ".ready"
        @Volatile private var logLevelSet = false
    }
    private val voskPhrase = phrase.voskPhrase
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

/** Retains the model weights across conversations; the recognizer is reset to discard previous audio. */
class WakeModelStore : AutoCloseable {
    private var cached: WakeDecoder? = null
    private var phrase: WakePhrase? = null
    @Synchronized fun acquire(context: Context, selected: WakePhrase = WakePhrase.HELLO_BUTLER): WakeDecoder {
        if (cached == null || phrase != selected) {
            cached?.close(); cached = null
            cached = VoskWakeDecoder(context, selected)
            phrase = selected
        } else cached!!.restart()
        return cached!!
    }
    @Synchronized fun discardAudio() { cached?.discardAudio() }
    @Synchronized override fun close() { cached?.close(); cached = null }
}

/** Owns the microphone and decoder on one worker; callbacks run after native cleanup. */
class LocalWakeWordEngine(private val context: Context, private val models: WakeModelStore, private val phrase: WakePhrase,
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
                    val decoder = models.acquire(context, phrase)
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
