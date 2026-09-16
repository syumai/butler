package dev.syumai.butler

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.syumai.butler.tools.ToolRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * HTTP signaling for one WebRTC session, pluggable so [WebRtcClient] itself stays backend-agnostic.
 * [request] builds the full OkHttp request (including the Authorization header and the session
 * config) for the given local SDP offer; [answer] extracts the remote SDP answer from the response
 * body (parsing errors are the caller's responsibility to catch).
 */
interface Signaling {
    fun request(sdp: String): Request
    fun answer(body: String): String
}

/** Realtime API signaling: `POST /v1/realtime/calls`, multipart body, the answer is the raw response body. */
class RealtimeSignaling(private val context: Context, private val settings: Settings, private val tools: JSONArray? = null) : Signaling {
    override fun request(sdp: String): Request {
        val session = JSONObject().put("type", "realtime").put("model", settings.get("model", "gpt-realtime-2.1"))
            .put("output_modalities", JSONArray().put("audio"))
            .put("instructions", context.getString(R.string.prompt_realtime_instructions) + "\n\n" + SessionContext.text(context, settings))
            .put("audio", JSONObject().put("output", JSONObject().put("voice", settings.voice(VoiceApi.REALTIME).id))
                .put("input", JSONObject().put("turn_detection", JSONObject().put("type", "semantic_vad").put("create_response", true).put("interrupt_response", true))
                    // Streams conversation.item.input_audio_transcription.delta/.completed events (§9,
                    // AssistantService.userTranscript) so the conversation overlay can show what the user
                    // is saying, not just the assistant's reply. Verified 2026-09-16 against
                    // https://developers.openai.com/api/docs/guides/realtime-transcription.
                    .put("transcription", JSONObject().put("model", "gpt-live-transcribe"))))
        tools?.let { session.put("tools", it) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("sdp", sdp)
            .addFormDataPart("session", session.toString()).build()
        return Request.Builder().url("https://api.openai.com/v1/realtime/calls")
            .header("Authorization", "Bearer ${settings.secret("openai")}").post(body).build()
    }
    override fun answer(body: String): String = body
}

/**
 * GPT-Live signaling: `POST /v1/live/sessions`, JSON body (`{"session":..., "transport":{"type":
 * "webrtc","sdp":...}}`), the answer is `transport.sdp` in the JSON response. [sessionId] is filled
 * in by [answer] from `session.id`, for logging only.
 */
class LiveSignaling(private val context: Context, private val settings: Settings, private val registry: ToolRegistry) : Signaling {
    var sessionId: String? = null; private set
    override fun request(sdp: String): Request {
        val sessionContext = SessionContext.text(context, settings)
        val session = JSONObject().put("model", settings.get("liveModel", "gpt-live-1"))
            .put("instructions", context.getString(R.string.prompt_live_instructions) + "\n\n" + sessionContext)
            .put("audio", JSONObject().put("output", JSONObject().put("voice", settings.voice(VoiceApi.LIVE).id)))
            .put("delegation", JSONObject().put("type", "responses").put("responses", JSONObject()
                .put("model", settings.get("searchModel", "gpt-5.6-luna"))
                .put("instructions", context.getString(R.string.prompt_live_backend_instructions) + "\n\n" + sessionContext)
                .put("tools", registry.liveDefinitions())
                .put("tool_choice", "auto").put("parallel_tool_calls", false)))
        if (BuildConfig.DEBUG) {
            val tools = registry.liveDefinitions()
            val names = (0 until tools.length()).joinToString(" ") { tools.getJSONObject(it).optString("name").ifBlank { tools.getJSONObject(it).optString("type") } }
            android.util.Log.d("Butler", "live session: model=${session.optString("model")} voice=${settings.voice(VoiceApi.LIVE).id} backend=${settings.get("searchModel", "gpt-5.6-luna")} tools=$names")
        }
        val body = JSONObject().put("session", session).put("transport", JSONObject().put("type", "webrtc").put("sdp", sdp))
        return Request.Builder().url("https://api.openai.com/v1/live/sessions")
            .header("Authorization", "Bearer ${settings.secret("openai")}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
    override fun answer(body: String): String {
        val json = JSONObject(body)
        sessionId = json.optJSONObject("session")?.optString("id")
        return json.getJSONObject("transport").getString("sdp")
    }
}

/**
 * Process-wide, kept-alive OkHttp client for Realtime/Live signaling calls. Previously each
 * [WebRtcClient] built and tore down its own `OkHttpClient` (including `connectionPool.evictAll()` on
 * close), so every conversation paid for a brand-new DNS + TCP + TLS handshake to api.openai.com — on
 * device this was measured at ~440ms of the ~1.58s wake-to-ready timeline. Sharing one client (and
 * never evicting its pool) lets that connection survive between conversations; [warm] additionally
 * primes it speculatively before a conversation is even requested.
 */
object SignalingHttp {
    // A handful of idle connections is plenty for one signaling host; 10 minutes comfortably spans the
    // gap between standby conversations without holding a socket open forever.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
        .callTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory { if (BuildConfig.DEBUG) DebugEventListener() else EventListener.NONE }
        .build()

    fun newCall(request: Request): Call = client.newCall(request)

    @Volatile private var lastWarmAt = 0L
    /**
     * Fire-and-forget: issues one cheap unauthenticated request to api.openai.com so the pooled TLS/h2
     * connection is already up before the real signaling POST needs it. Never blocks the caller and
     * never surfaces failure (best-effort only — the real POST still works cold if this didn't land in
     * time), and throttled to once per [WARM_INTERVAL_MS] (via [WarmThrottle.shouldWarm]) so it isn't
     * re-issued on every standby tick/speech segment.
     */
    fun warm() {
        val now = SystemClock.elapsedRealtime()
        if (!WarmThrottle.shouldWarm(now, lastWarmAt)) return
        lastWarmAt = now
        val request = Request.Builder().url("https://api.openai.com/v1/models").head().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
}

/** Pure throttle decision for [SignalingHttp.warm], factored out so it's unit-testable on the JVM
 * without Android's `SystemClock` (same spirit as `IdlePolicy`: the caller passes `now`/`lastAt` in). */
object WarmThrottle {
    const val WARM_INTERVAL_MS = 60_000L
    /** True if it's been at least [WARM_INTERVAL_MS] since [lastAt] (0 meaning "never warmed yet"). */
    fun shouldWarm(now: Long, lastAt: Long, intervalMs: Long = WARM_INTERVAL_MS): Boolean = lastAt == 0L || now - lastAt >= intervalMs
}

/**
 * Debug-only OkHttp instrumentation (tag `Butler`, prefix `latency:`) logging connection-reuse timing
 * for one signaling call, so `adb logcat -s Butler | grep latency:` shows whether the POST to
 * `/v1/realtime/calls` reused [SignalingHttp]'s warmed pooled connection or paid for a fresh
 * DNS+TCP+TLS handshake. [freshConnect] is set in [connectStart]; if it's still false by
 * [connectionAcquired], the connection came from the pool rather than being dialed for this call.
 */
private class DebugEventListener : EventListener() {
    private var callStartNanos = 0L
    private var freshConnect = false
    private fun ms() = (System.nanoTime() - callStartNanos) / 1_000_000
    override fun callStart(call: Call) { callStartNanos = System.nanoTime(); freshConnect = false }
    override fun dnsStart(call: Call, domainName: String) { Log.d("Butler", "latency: http dnsStart t=${ms()}ms") }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { freshConnect = true; Log.d("Butler", "latency: http connectStart t=${ms()}ms") }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { Log.d("Butler", "latency: http secureConnectEnd t=${ms()}ms") }
    override fun connectionAcquired(call: Call, connection: Connection) { Log.d("Butler", "latency: http connectionAcquired t=${ms()}ms reused=${!freshConnect}") }
    override fun responseHeadersEnd(call: Call, response: Response) { Log.d("Butler", "latency: http responseHeadersEnd t=${ms()}ms") }
}

/** All native lifecycle operations are serialized on the main thread. */
class WebRtcClient(private val context: Context, private val signaling: Signaling,
    private val event: (JSONObject) -> Unit, private val failure: (Status) -> Unit) {
    /** No ICE servers are configured, so gathering is normally fast; this is a safety net in case it
     * never reports COMPLETE for some reason. */
    private val iceGatherTimeoutMs = 1500L
    private val main = Handler(Looper.getMainLooper())
    private var call: Call? = null
    private var closed = false
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var device: JavaAudioDeviceModule? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val outputAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(outputAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                dispatch { failure(Status(R.string.status_audio_focus_lost)) }
            }
        }, main).build()
    private var hasFocus = false
    private val oldMode = audio.mode
    private val oldSpeaker = audio.isSpeakerphoneOn
    /** Guards [connect] against firing twice from both the ICE-gathering-complete path and the timeout fallback. */
    private var connected = false
    private fun dispatch(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun audioFailure() = dispatch { failure(Status(R.string.status_audio_io_stopped)) }
    private fun fail() = dispatch { failure(Status(R.string.status_realtime_connect_failed)) }
    private fun observer(created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { dispatch { created(sdp) } }
        override fun onSetSuccess() { dispatch(set) }
        override fun onCreateFailure(error: String?) { fail() }
        override fun onSetFailure(error: String?) { fail() }
    }
    fun start() {
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        check(hasFocus) { "Audio focus unavailable" }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        // Surface the native audio-processing (AEC/NS/AGC) configuration in logcat for on-device tuning.
        if (BuildConfig.DEBUG) Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
        // This is a speaker appliance: route output through media, not the telephony stream.
        audio.mode = AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = true
        device = JavaAudioDeviceModule.builder(context)
            // Match the target ROM's primary input/output profiles. Output is mono: the device has no hardware
            // AEC, and on-device barge-in trials at high volume got through more often with mono playback
            // (less echo for the software AEC3 to cancel) than with stereo.
            .setInputSampleRate(16000).setOutputSampleRate(48000).setUseStereoOutput(false)
            .setAudioAttributes(outputAttributes)
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) { audioFailure() }
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) { audioFailure() }
                override fun onWebRtcAudioTrackError(error: String) { audioFailure() }
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) { audioFailure() }
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) { audioFailure() }
                override fun onWebRtcAudioRecordError(error: String) { audioFailure() }
            }).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(device).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) fail()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) dispatch { maybeConnect() }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { (receiver?.track() as? AudioTrack)?.setEnabled(true) }
        }) ?: error("WebRTC unavailable")
        source = factory!!.createAudioSource(MediaConstraints())
        track = factory!!.createAudioTrack("butler-mic", source)
        // No microphone audio leaves the device before session configuration is accepted.
        track!!.setEnabled(false)
        peer!!.addTrack(track, listOf("butler"))
        channel = peer!!.createDataChannel("oai-events", DataChannel.Init())
        channel!!.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 1_000_000) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                dispatch { runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.onSuccess(event) }
            }
        })
        peer!!.createOffer(observer(created = { offer ->
            peer!!.setLocalDescription(observer(set = {
                // The docs recommend waiting for ICE gathering to complete before posting the offer.
                if (peer?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) maybeConnect()
                else main.postDelayed({ maybeConnect() }, iceGatherTimeoutMs)
            }), offer)
        }), MediaConstraints())
    }
    /** Fires once, from whichever of onIceGatheringChange(COMPLETE) or the timeout fallback comes first. */
    private fun maybeConnect() {
        if (connected || closed) return
        val description = peer?.localDescription?.description ?: return
        connected = true
        connect(description)
    }
    fun enableMicrophone() { track?.setEnabled(true) }
    fun disableMicrophone() { track?.setEnabled(false) }
    private fun connect(sdp: String) {
        call = SignalingHttp.newCall(signaling.request(sdp))
        call!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { fail() }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { fail(); return }
                    val body = it.body?.string() ?: ""
                    dispatch {
                        runCatching { signaling.answer(body) }
                            .onSuccess { answer -> peer?.setRemoteDescription(observer(), SessionDescription(SessionDescription.Type.ANSWER, answer)) }
                            .onFailure { fail() }
                    }
                }
            }
        })
    }
    fun send(value: JSONObject): Boolean {
        val dc = channel ?: return false
        return dc.state() == DataChannel.State.OPEN && dc.send(DataChannel.Buffer(ByteBuffer.wrap(value.toString().toByteArray()), false))
    }
    fun close() {
        if (closed) return
        closed = true
        call?.cancel()
        channel?.unregisterObserver(); channel?.close(); channel?.dispose(); channel = null
        peer?.close(); peer?.dispose(); peer = null
        track?.dispose(); source?.dispose(); factory?.dispose(); device?.release()
        if (hasFocus) { audio.abandonAudioFocusRequest(focus); hasFocus = false }
        audio.mode = oldMode; audio.isSpeakerphoneOn = oldSpeaker
        // SignalingHttp is process-wide and kept alive across conversations (see its doc comment), so
        // close() only needs to cancel this client's own in-flight call, not tear down the shared pool.
    }
}
