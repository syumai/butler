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

/**
 * All native lifecycle operations are serialized on the main thread. Split into [prepare] (no network
 * I/O, no audio focus — safe to run well ahead of a conversation, e.g. as soon as the wake engine is
 * ready during standby) and [connect] (audio focus + the network-bound signaling exchange), so
 * `AssistantService` can pre-build the peer connection during standby and only pay for the
 * network-bound legs when a conversation actually starts. `signaling`/`event`/`failure` are supplied
 * to [connect] rather than the constructor for the same reason: they aren't known until a conversation
 * is about to begin.
 */
class WebRtcClient(private val context: Context) {
    /** No ICE servers are configured, so gathering is normally fast; this is a safety net in case it
     * never reports COMPLETE for some reason. Measured from [connect], not [prepare] — a client that
     * sat prepared during standby has almost always already finished gathering by the time it's used. */
    private val iceGatherTimeoutMs = 1500L
    private val main = Handler(Looper.getMainLooper())
    private var call: Call? = null
    private var closed = false
    /** Set on any WebRTC failure (SDP/ICE, or an audio-device error) that happens before [connect] is
     * ever called — there is no `failure` callback to report to yet at that point, so [onBroken] fires
     * instead and the caller (`AssistantService`) discards this client and prepares a fresh one rather
     * than trying to reuse something that never finished [prepare] correctly. */
    private var broken = false
    private var onBroken: () -> Unit = {}
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
                dispatch { onFailure(Status(R.string.status_audio_focus_lost)) }
            }
        }, main).build()
    private var hasFocus = false
    /** `audio.mode`/`isSpeakerphoneOn` as they were before [connect] changed them, restored by [close].
     * Left null (and left untouched by [close]) when [connect] is never reached — e.g. a client that
     * was prepared ahead of time but closed again without ever being used for a conversation. */
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null
    /** Guards [maybeConnect] against firing twice from both the ICE-gathering-complete path and the
     * timeout fallback. */
    private var connected = false
    /** True once [connect] has been called; gates [maybeConnect] so ICE gathering completing during
     * standby (while this client just sits [prepare]d) doesn't post the offer early. */
    private var connectRequested = false
    private var iceGatheringComplete = false
    private var iceConnectedLogged = false
    private var signaling: Signaling? = null
    private var onEvent: (JSONObject) -> Unit = {}
    private var onFailure: (Status) -> Unit = {}
    private var onMilestone: (String) -> Unit = {}
    /** [SystemClock.elapsedRealtime] when [prepare] finished, so the service can tell a stale prepared
     * client (sitting unused for a long time, e.g. 30+ minutes) from a fresh one. */
    var preparedAt: Long = 0; private set
    private fun dispatch(block: () -> Unit) { main.post { if (!closed) block() } }
    /** Reports a failure either to the live conversation ([onFailure], once [connect] has been called)
     * or by marking this client [broken] ([onBroken], if the failure happened during [prepare]/standby). */
    private fun reportFailure(status: Int) = dispatch {
        if (connectRequested) onFailure(Status(status)) else { broken = true; onBroken() }
    }
    private fun audioFailure() = reportFailure(R.string.status_audio_io_stopped)
    private fun fail() = reportFailure(R.string.status_realtime_connect_failed)
    private fun observer(created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { dispatch { created(sdp) } }
        override fun onSetSuccess() { dispatch(set) }
        override fun onCreateFailure(error: String?) { fail() }
        override fun onSetFailure(error: String?) { fail() }
    }
    /**
     * Everything that needs no network I/O and no audio focus: the peer connection factory/audio
     * device module (with the track disabled, so no microphone audio leaves the device before session
     * configuration is accepted — WebRTC's AudioRecord itself is only initialised once ICE connects, so
     * this doesn't touch the microphone either, verified on-device), the data channel, and the local
     * SDP offer; ICE gathering is then left to run in the background. Deliberately does not request
     * audio focus or touch `audio.mode`/speakerphone — those belong to [connect], so a client built
     * ahead of time during standby never grabs audio focus before a conversation actually starts.
     */
    fun prepare(onBroken: () -> Unit = {}) {
        this.onBroken = onBroken
        preparedAt = SystemClock.elapsedRealtime()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        // Surface the native audio-processing (AEC/NS/AGC) configuration in logcat for on-device tuning.
        if (BuildConfig.DEBUG) Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
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
                if (state == PeerConnection.IceConnectionState.CONNECTED && !iceConnectedLogged) {
                    iceConnectedLogged = true; dispatch { onMilestone("ice_connected") }
                }
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) fail()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                // Guarded on connectRequested: gathering routinely completes while this client is still
                // just sitting prepared during standby, well before connect() supplies a signaling/event/
                // failure target to actually post the offer to.
                if (state == PeerConnection.IceGatheringState.COMPLETE) dispatch {
                    iceGatheringComplete = true
                    if (connectRequested) maybeConnect()
                }
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
            override fun onStateChange() { if (channel?.state() == DataChannel.State.OPEN) dispatch { onMilestone("channel_open") } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 1_000_000) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                dispatch { runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.onSuccess(onEvent) }
            }
        })
        peer!!.createOffer(observer(created = { offer ->
            peer!!.setLocalDescription(observer(set = {
                // The docs recommend waiting for ICE gathering to complete before posting the offer; connect()
                // checks this flag rather than re-querying iceGatheringState() itself once it's called.
                if (peer?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete = true
            }), offer)
        }), MediaConstraints())
    }
    /**
     * Requests audio focus and switches the audio route (both stay out of [prepare] so a client built
     * ahead of time during standby never grabs either before a conversation actually starts), stores
     * the per-conversation callbacks, then posts the SDP offer as soon as ICE gathering is complete —
     * immediately if [prepare] already finished gathering (the common case for a client that sat
     * prepared during standby), otherwise once it does, with [iceGatherTimeoutMs] as a fallback measured
     * from this call. Returns false (audio focus request aside, without other side effects) if audio
     * focus is unavailable or this client is already [closed]/[broken] — the caller should fall back to
     * a freshly prepared client in the latter case.
     */
    fun connect(signaling: Signaling, event: (JSONObject) -> Unit, failure: (Status) -> Unit, milestone: (String) -> Unit = {}): Boolean {
        if (closed || broken) return false
        this.signaling = signaling; onEvent = event; onFailure = failure; onMilestone = milestone
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) return false
        savedMode = audio.mode; savedSpeaker = audio.isSpeakerphoneOn
        // This is a speaker appliance: route output through media, not the telephony stream.
        audio.mode = AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = true
        connectRequested = true
        if (iceGatheringComplete) maybeConnect() else main.postDelayed({ maybeConnect() }, iceGatherTimeoutMs)
        return true
    }
    /** Fires once, from whichever of onIceGatheringChange(COMPLETE) or the timeout fallback comes first. */
    private fun maybeConnect() {
        if (connected || closed || broken) return
        val description = peer?.localDescription?.description ?: return
        connected = true
        postOffer(description)
    }
    fun enableMicrophone() { track?.setEnabled(true) }
    fun disableMicrophone() { track?.setEnabled(false) }
    private fun postOffer(sdp: String) {
        call = SignalingHttp.newCall(signaling!!.request(sdp))
        call!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { fail() }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    dispatch { onMilestone("http_response") }
                    if (!it.isSuccessful) { fail(); return }
                    val body = it.body?.string() ?: ""
                    dispatch {
                        runCatching { signaling!!.answer(body) }
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
    /** Safe to call on a client that was only ever [prepare]d and never [connect]ed: [hasFocus] is
     * still false (nothing to abandon) and [savedMode]/[savedSpeaker] are still null (audio mode/
     * speakerphone were never touched, so nothing needs restoring). */
    fun close() {
        if (closed) return
        closed = true
        call?.cancel()
        channel?.unregisterObserver(); channel?.close(); channel?.dispose(); channel = null
        peer?.close(); peer?.dispose(); peer = null
        track?.dispose(); source?.dispose(); factory?.dispose(); device?.release()
        if (hasFocus) { audio.abandonAudioFocusRequest(focus); hasFocus = false }
        savedMode?.let { audio.mode = it }
        savedSpeaker?.let { audio.isSpeakerphoneOn = it }
    }
}
