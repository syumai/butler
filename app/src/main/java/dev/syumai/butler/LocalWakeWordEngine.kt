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
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Vosk and Julius both detect ハローバトラー and ヘイバトラー. ヘイバトラー was added to Julius's
// phone-loop grammar (scripts/julius-wake/wake.voca/wake.dict) on 2026-09-17 after a first attempt
// (same day) found its per-word confidence score (CM) alone couldn't separate genuine wake utterances
// from false wakes on real unrelated Japanese speech (LibriVox): both ranges overlapped. A second
// offline sweep (scripts/julius-eval.py, see scripts/julius-wake/README.md's "2026-09-17: Hey Butler"
// section and third_party/julius/README.md) found a *structural* gate does separate them cleanly --
// every false wake sat inside running speech with many surrounding `<garbage>` filler words, while
// genuine wake utterances are short, standalone segments with few fillers -- so JuliusWakeDecoder now
// judges a whole <RECOGOUT> block (JuliusWake.BlockParser) rather than a single <WHYPO> line, gating
// on both CM and total filler count. Recall for a real speaker saying "Hey Butler" specifically is
// unverified: no real-voice recording of "Hey Butler" exists, only rec1's single ヘイバトラー hit,
// which is actually a misrecognized "Hello Butler" utterance -- check on-device before relying on it.
enum class WakePhrase(val label: String, val voskPhrases: List<String>, val juliusWords: List<String>) {
    HELLO_BUTLER("Hello Butler / Hey Butler", listOf("ハロー バトラー", "ヘイ バトラー"), listOf("ハローバトラー", "ヘイバトラー")),
}

/** The on-device decoder that watches the standby microphone stream. Samples are raw 16-bit PCM
 * (int16-scaled), passed through from the microphone buffer untouched; each implementation converts
 * as it needs (Vosk's entry point wants int16-scaled shorts; Julius gets them as-is over adinnet).
 * [VoskWakeDecoder] and [JuliusWakeDecoder] are the two implementations, selected by [WakeEngine]; the
 * interface documents the contract and keeps [WakeModelStore]/[LocalWakeWordEngine] decoupled from
 * either one. */
interface WakeDecoder : AutoCloseable {
    fun accept(samples: ShortArray, count: Int): Boolean
    fun restart()
    fun discardAudio()
    /** Optional hook fired (on the wake worker thread, at most once per detected speech segment — keep
     * it cheap) as soon as a segment of speech begins, well before the phrase itself is fully decoded —
     * used to speculatively warm up latency-sensitive resources (the signaling HTTP connection) that
     * would otherwise only start once the wake phrase is confirmed. [VoskWakeDecoder] has no segment
     * concept (continuous ASR, no VAD) so its implementation never invokes this. */
    var onSpeechStart: (() -> Unit)?
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
 * `["<phrase1>", "<phrase2>", ..., "[unk]"]` (one entry per [WakePhrase.voskPhrases] member), and
 * reports a hit via [VoskWake.hit] against any of those phrases, on final results only. An offline eval
 * on 2026-09-15 against ~92 minutes of unrelated Japanese speech found 25 false wakes, every one at the
 * partial-result stage and none at the final-result stage, so partials are no longer checked; this costs
 * roughly 1s of extra latency versus reacting to the first matching partial. Model assets
 * (`vosk/vosk-model-small-ja-0.22/` under `assets/`) are unpacked to `filesDir` on first use via
 * [AssetUnpacker]; `org.vosk.android.StorageService` is not used since it expects a `uuid` asset and the
 * external files dir, which don't fit this bundling. Japanese pronunciation only. */
class VoskWakeDecoder(context: Context, phrase: WakePhrase) : WakeDecoder {
    companion object {
        private const val MODEL_ASSET_DIR = "vosk/vosk-model-small-ja-0.22"
        @Volatile private var logLevelSet = false
    }
    private val voskPhrases = phrase.voskPhrases
    private val model: Model
    private var recognizer: Recognizer
    // No VAD/segment concept for continuous ASR, so this is stored but never invoked.
    override var onSpeechStart: (() -> Unit)? = null

    init {
        synchronized(Companion) {
            if (!logLevelSet) { LibVosk.setLogLevel(LogLevel.WARNINGS); logLevelSet = true }
        }
        val dir = AssetUnpacker.unpack(context, MODEL_ASSET_DIR, MODEL_ASSET_DIR, "Vosk model")
        model = Model(dir.absolutePath)
        val grammar = (voskPhrases.map { "\"$it\"" } + "\"[unk]\"").joinToString(",", "[", "]")
        recognizer = Recognizer(model, 16000f, grammar)
    }

    override fun accept(samples: ShortArray, count: Int): Boolean {
        if (!recognizer.acceptWaveForm(samples, count)) return false
        if (voskPhrases.any { VoskWake.hit(recognizer.result, it) }) { recognizer.reset(); return true }
        return false
    }
    override fun restart() { recognizer.reset() }
    override fun discardAudio() { recognizer.reset() }
    override fun close() { recognizer.close(); model.close() }
}

/**
 * Runs the bundled Julius executable (`libjulius-bin.so`, cross-compiled to armeabi-v7a — see
 * `scripts/build-julius-android.sh`/`.md` and `third_party/julius/README.md`) as a child process,
 * feeding it microphone audio over its adinnet protocol and reading recognition results back over its
 * module (XML) protocol, both plain TCP sockets to 127.0.0.1. No JNI: Android 10+'s W^X policy only
 * allows executing files from `nativeLibraryDir` (not app-writable storage), so packaging Julius as a
 * "library" (`app/build.gradle.kts`'s `packaging.jniLibs.useLegacyPackaging = true`, which makes the
 * system extract it to `nativeLibraryDir` instead of leaving it zipped inside the APK) and running it
 * as a subprocess sidesteps needing a native JNI build of libjulius entirely.
 *
 * The two ports are chosen by opening `ServerSocket(0)` momentarily (OS-assigned free port) and closing
 * it again so Julius itself can bind it. Julius blocks at startup waiting for a module client to
 * connect *before* it opens its adinnet server, so the module client is connected first (retrying every
 * [CONNECT_RETRY_MS] for up to [CONNECT_TIMEOUT_MS]), then the adinnet client the same way. Both the
 * child's stdout and stderr are drained by rate-limited logging threads so it never blocks on a full
 * pipe. On any failure during startup, the process is destroyed and the exception propagates.
 *
 * Segmentation of the continuous microphone stream into speech spans (Julius does not cut silence
 * itself for adinnet input — `-nocutsilence`) is delegated to [WakeVad]; each VAD-detected segment is
 * streamed to Julius as adinnet packets (4-byte little-endian byte count, then that many bytes of s16le
 * PCM, chunked to <=1600 samples per packet), terminated by a 0-count packet. Julius may write single
 * command bytes back on that same socket (`'0'` pause / `'1'` resume); a background thread reads and
 * discards them. Results are read line-by-line from the module socket by another background thread and
 * fed to a [JuliusWake.BlockParser] (against [WakePhrase.juliusWords], [WAKE_THRESHOLD] and
 * [MAX_FILLERS]), which judges a whole `<RECOGOUT>` block at once rather than any single `<WHYPO>` line
 * in isolation — see that class's doc comment for why. A hitting block sets [wakeFlag], which [accept]
 * polls and clears.
 *
 * The asset-unpack marker ([AssetUnpacker.unpack]'s `marker` argument) is not just
 * `"julius:${BuildConfig.VERSION_CODE}"`: it also folds in a CRC32 of the `julius/grammar/wake.dict`
 * and `julius/grammar/wake.dfa` asset bytes (see `grammarMarkerSuffix`), so editing the grammar (e.g.
 * adding a wake phrase) without bumping the version code still forces a re-unpack on a device that
 * already has the old grammar unpacked to `filesDir` — otherwise [AssetUnpacker] would see a matching
 * `.ready` marker and keep serving the stale grammar forever.
 */
class JuliusWakeDecoder(context: Context, phrase: WakePhrase) : WakeDecoder {
    companion object {
        private const val ASSET_DIR = "julius"
        private const val EXECUTABLE = "libjulius-bin.so"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CONNECT_RETRY_MS = 100L
        private const val LOG_INTERVAL_MS = 500L
        // See scripts/julius-wake/README.md "Tuning results": with this grammar's small category
        // count, the WAKE word's cmscore1 for genuine hits fell in ~0.04-0.14; 0.05 (Julius's own
        // -cmalpha default) cleanly separated true positives from the (presence-gated, penalty-tuned)
        // negatives in that offline sweep, so it's used unchanged as the live confidence threshold.
        const val WAKE_THRESHOLD = 0.05
        // See scripts/julius-wake/README.md "2026-09-17: Hey Butler": CM alone doesn't separate
        // ヘイバトラー's genuine hits from its false wakes (their ranges overlap), but the total
        // <garbage> filler-word count in the same <RECOGOUT> block does -- every observed false wake
        // had 11+ fillers, every genuine rec1 hit had <=6. 10 was the loosest (most permissive) value
        // swept that still produced zero false wakes across the full negative set (japanese-speech-neg1,
        // ~92 minutes of LibriVox, and the macOS `say` confusable set).
        const val MAX_FILLERS = 10
        // Explicit IPv4 loopback, not InetAddress.getLoopbackAddress(): on this device that resolves
        // to the IPv6 loopback (::1), which nothing is listening on -- Julius's adin_tcpip_standby()
        // binds INADDR_ANY (IPv4 only, confirmed via /proc/net/tcp showing "00000000:<port>" entries,
        // not an IPv6 "00000000000000000000000000000001:<port>" one), so a client connecting to ::1
        // gets ECONNREFUSED on every attempt (verified on-device: this was the actual cause of a
        // connect timeout that first looked like a slow/hung Julius process).
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        // Folded into the AssetUnpacker marker (see the class doc comment) so a grammar-only change
        // (e.g. adding a wake phrase, without bumping BuildConfig.VERSION_CODE) still invalidates an
        // already-unpacked device's `julius/` tree instead of leaving it stuck on the old grammar.
        // CRC32 (not a cryptographic hash) is fine here: this only needs to detect an accidental content
        // mismatch between the app's assets and what's already unpacked to filesDir, not resist tampering.
        private fun grammarMarkerSuffix(context: Context): String {
            val crc = java.util.zip.CRC32()
            for (path in listOf("julius/grammar/wake.dict", "julius/grammar/wake.dfa")) {
                context.assets.open(path).use { input -> crc.update(input.readBytes()) }
            }
            return crc.value.toString(16)
        }
    }

    private val blockParser = JuliusWake.BlockParser(phrase.juliusWords, WAKE_THRESHOLD, MAX_FILLERS)
    private val wakeFlag = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var streamFailed = false
    private val threads = mutableListOf<Thread>()
    override var onSpeechStart: (() -> Unit)? = null

    private lateinit var process: Process
    private lateinit var moduleSocket: Socket
    private lateinit var adinnetSocket: Socket
    private lateinit var adinnetOut: OutputStream

    /** Time spent unpacking the Julius asset tree ([AssetUnpacker.unpack]; 0 if it was already unpacked
     * and the `.ready` marker matched) and time from process spawn to both sockets connected —
     * exposed for `WakeInstrumentation` to report; not used by the decoder itself. */
    val unpackMs: Long
    var startupMs: Long = 0
        private set

    // WakeVad calls onSegmentAudio once per 10ms/160-sample frame; batch those into <=1600-sample
    // adinnet packets (as designed, and as scripts/julius-adinnet-client.py -- confirmed working against
    // this exact binary -- also does) rather than writing+flushing one tiny 320-byte packet per frame.
    private val sendBuffer = ShortArray(1600)
    private var sendBufferFill = 0
    private fun bufferAudio(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val take = minOf(sendBuffer.size - sendBufferFill, count - i)
            System.arraycopy(samples, i, sendBuffer, sendBufferFill, take)
            sendBufferFill += take; i += take
            if (sendBufferFill == sendBuffer.size) { sendAdinnet(sendBuffer, sendBuffer.size); sendBufferFill = 0 }
        }
    }
    private fun flushSendBuffer() {
        if (sendBufferFill > 0) { sendAdinnet(sendBuffer, sendBufferFill); sendBufferFill = 0 }
    }

    private val vad = WakeVad(
        // Speculative latency hook (§ SignalingHttp.warm): fires on every detected speech segment, not
        // just ones that turn out to be the wake phrase, so it must stay cheap — it does here, since
        // onSpeechStart itself is null except while a conversation is in standby.
        onSegmentStart = { onSpeechStart?.invoke() },
        onSegmentAudio = { samples, count -> bufferAudio(samples, count) },
        onSegmentEnd = { flushSendBuffer(); sendAdinnetEnd() },
    )

    init {
        val unpackStarted = System.currentTimeMillis()
        val marker = "$ASSET_DIR:${BuildConfig.VERSION_CODE}:${grammarMarkerSuffix(context)}"
        val assetsDir = AssetUnpacker.unpack(context, ASSET_DIR, marker, "Julius assets")
        unpackMs = System.currentTimeMillis() - unpackStarted
        val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
        check(binary.exists()) { "Julius executable not found at ${binary.absolutePath}" }
        val hmm = File(assetsDir, "model/jnas-tri-3k16-gid.binhmm")
        val hlist = File(assetsDir, "model/logicalTri-3k16-gid.bin")
        val gramPrefix = File(assetsDir, "grammar/wake")
        val adPort = freePort()
        val modulePort = freePort()
        val command = listOf(
            binary.absolutePath,
            "-h", hmm.absolutePath,
            "-hlist", hlist.absolutePath,
            "-gram", gramPrefix.absolutePath,
            "-input", "adinnet",
            "-adport", adPort.toString(),
            "-module", modulePort.toString(),
            "-nocutsilence",
            "-n", "1",
            "-output", "1",
            "-penalty1", "-0.8",
            "-penalty2", "-0.8",
        )
        val spawnStarted = System.currentTimeMillis()
        try {
            process = ProcessBuilder(command).start()
            threads += logThread(process.inputStream, "julius/out")
            threads += logThread(process.errorStream, "julius/err")
            // Julius waits for the module client before it opens its adinnet server.
            moduleSocket = connectWithRetry(modulePort)
            adinnetSocket = connectWithRetry(adPort)
            adinnetOut = adinnetSocket.getOutputStream()
        } catch (e: Exception) {
            if (::process.isInitialized) process.destroy()
            throw e
        }
        startupMs = System.currentTimeMillis() - spawnStarted
        threads += thread(name = "Butler-julius-module") { readModule() }
        threads += thread(name = "Butler-julius-adcmd") { readAdinnetCommands() }
    }

    private fun freePort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

    private fun connectWithRetry(port: Int): Socket {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw IOException("Julius process exited before connecting (exit=${process.exitValue()})")
            try {
                return Socket(LOOPBACK, port).apply { tcpNoDelay = true }
            } catch (e: IOException) {
                last = e
                Thread.sleep(CONNECT_RETRY_MS)
            }
        }
        throw IOException("Timed out connecting to Julius on port $port", last)
    }

    private fun logThread(stream: InputStream, tag: String): Thread = thread(name = "Butler-$tag") {
        var lastLog = 0L
        try {
            BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                val now = System.currentTimeMillis()
                if (now - lastLog > LOG_INTERVAL_MS) { Log.d("Butler", "$tag: $line"); lastLog = now }
            }
        } catch (_: IOException) { /* stream closed on shutdown */ }
    }

    @Synchronized private fun sendAdinnet(samples: ShortArray, count: Int) {
        if (closed) return
        val buffer = ByteBuffer.allocate(4 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(count * 2)
        for (i in 0 until count) buffer.putShort(samples[i])
        writeAdinnet(buffer.array())
    }
    @Synchronized private fun sendAdinnetEnd() {
        if (closed) return
        writeAdinnet(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
    }
    private fun writeAdinnet(bytes: ByteArray) {
        try {
            adinnetOut.write(bytes); adinnetOut.flush()
        } catch (e: IOException) {
            streamFailed = true
            throw e
        }
    }

    private fun readAdinnetCommands() {
        try {
            val input = adinnetSocket.getInputStream()
            val buf = ByteArray(64)
            while (input.read(buf) >= 0) { /* discard pause/resume command bytes */ }
            if (!closed) streamFailed = true
        } catch (_: IOException) { if (!closed) streamFailed = true }
    }

    private fun readModule() {
        try {
            val reader = BufferedReader(InputStreamReader(moduleSocket.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (blockParser.feed(line)) wakeFlag.set(true)
            }
            if (!closed) streamFailed = true
        } catch (_: IOException) { if (!closed) streamFailed = true }
    }

    private fun checkAlive() {
        if (!process.isAlive || streamFailed) throw IOException("Julius process or its sockets are no longer running")
    }

    override fun accept(samples: ShortArray, count: Int): Boolean {
        checkAlive()
        vad.accept(samples, count)
        checkAlive()
        return wakeFlag.compareAndSet(true, false)
    }
    override fun restart() { vad.reset(); wakeFlag.set(false) }
    override fun discardAudio() { vad.reset(); wakeFlag.set(false) }

    override fun close() {
        closed = true
        runCatching { adinnetOut.close() }
        runCatching { adinnetSocket.close() }
        runCatching { moduleSocket.close() }
        runCatching { process.destroy() }
        for (t in threads) runCatching { t.join(2000) }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }
}

/** Retains the model weights across conversations; the recognizer is reset to discard previous audio.
 * A change of [WakeEngine] (as well as of [WakePhrase]) rebuilds the decoder, since the two engines'
 * decoders are not interchangeable. */
class WakeModelStore : AutoCloseable {
    private var cached: WakeDecoder? = null
    private var phrase: WakePhrase? = null
    private var engine: WakeEngine? = null
    @Synchronized fun acquire(context: Context, selected: WakePhrase, wakeEngine: WakeEngine): WakeDecoder {
        if (cached == null || phrase != selected || engine != wakeEngine) {
            cached?.close(); cached = null
            cached = when (wakeEngine) {
                WakeEngine.VOSK -> VoskWakeDecoder(context, selected)
                WakeEngine.JULIUS -> JuliusWakeDecoder(context, selected)
            }
            phrase = selected; engine = wakeEngine
        } else cached!!.restart()
        return cached!!
    }
    @Synchronized fun discardAudio() { cached?.discardAudio() }
    @Synchronized override fun close() { cached?.close(); cached = null }
}

/** Owns the microphone and decoder on one worker; callbacks run after native cleanup. [speech] is the
 * optional speech-onset hook (see [WakeDecoder.onSpeechStart]) delivered on the main thread, like the
 * other callbacks, and guarded by [stopped] the same way. */
class LocalWakeWordEngine(private val context: Context, private val models: WakeModelStore, private val phrase: WakePhrase, private val engine: WakeEngine,
    private val ready: () -> Unit, private val detected: () -> Unit, private val failed: () -> Unit, private val speech: () -> Unit = {}) {
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
            var decoder: WakeDecoder? = null
            try {
                run {
                    decoder = models.acquire(context, phrase, engine)
                    decoder!!.onSpeechStart = { main.post { if (!stopped) speech() } }
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
                            if (decoder!!.accept(buffer, count)) { hit = true; break }
                        }
                    }
                }
            } catch (_: Exception) { error = !stopped }
            catch (_: LinkageError) { error = !stopped }
            finally {
                decoder?.onSpeechStart = null
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
