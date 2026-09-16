package dev.syumai.butler

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.*
import dev.syumai.butler.tools.Tool
import dev.syumai.butler.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class AssistantService : Service() {
    companion object {
        var status: Status = Status(R.string.status_mic_stopped); private set
        var transcript = ""; private set
        /** The current user turn's speech-to-text, live-updated as the model transcribes it (§9); shown
         * as the "you" caption line by ConversationView. Cleared at the start of each new user turn — see
         * the Realtime/Live handling below — and whenever a conversation begins/ends. */
        var userTranscript = ""; private set
        var citations = ""; private set
        var approval: JSONObject? = null; private set
        var conversing = false; private set
        /** Set (main thread) just before a `get_home_weather` tool call runs, so MainActivity's tick can
         * switch the pager to the matching page and dock the conversation overlay over it (§9). Only the
         * serial needs to be observed for change; [viewRequest] itself doesn't reset between requests. */
        var viewRequest = -1; private set
        var viewRequestSerial = 0; private set
        /** Bumped (main thread) whenever a device operation just completed, alongside [IdlePolicy.armShort]
         * below — MainActivity's tick uses a change here to refresh SmartHomePage/MusicPage (§9). */
        var homeStateSerial = 0; private set
        const val START = "start"; const val TALK = "talk"; const val END = "end"; const val STOP = "stop"
        const val VIEW_WEATHER = 1
        /** After a device operation completes, how long to wait for the user to say anything else before
         * ending the conversation on its own (short-circuiting the normal, longer silence timeout). */
        private const val POST_ACTION_IDLE_MS = 5_000L
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var wake: LocalWakeWordEngine? = null
    private val wakeModels = WakeModelStore()
    private var preparing = false
    private var destroyed = false
    private var realtime: WebRtcClient? = null
    /** A WebRTC peer connection built (PeerConnectionFactory/ADM/data channel/offer, ICE gathering
     * started) but not yet connected to a signaling backend — see WebRtcClient.prepare(). Built as soon
     * as the wake engine reports ready (so standby is the only time this competes with Julius startup
     * for CPU), consumed by begin() so the ~370ms ICE-gathering leg of the connect timeline is already
     * done by the time a conversation actually starts. Null while a conversation is in progress. */
    private var prepared: WebRtcClient? = null
    /** SystemClock.elapsedRealtime() of the last [preparePeer] call, throttling rebuilds triggered by
     * [networkCallback] (connectivity flapping should not rebuild on every single callback). */
    private var lastPrepareAt = 0L
    /** How stale a [prepared] client may be before [begin] discards it and builds a fresh one instead:
     * host ICE candidates gathered long ago may no longer be reachable (e.g. the device slept/roamed). */
    private val preparedMaxAgeMs = 30 * 60_000L
    /** SystemClock.elapsedRealtime() when the wake engine's detected() callback fired, the latency-log
     * baseline for a woken conversation (see [logLatency]); begin() itself is the baseline for a TALK-
     * triggered one. Debug builds only. */
    private var wakeDetectedAt = 0L
    private var latencyBaseMs = 0L
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    /** Host ICE candidates in a [prepared] client's already-gathered offer point at addresses on
     * whatever network was active at prepare() time; a network change while it's sitting idle in
     * standby can make them stale, so any change (available/lost/link properties) rebuilds it — throttled
     * via [lastPrepareAt] since these can fire in quick bursts. Registered for the service's lifetime. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { rebuildPrepared() } }
        override fun onLost(network: Network) { main.post { rebuildPrepared() } }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { main.post { rebuildPrepared() } }
    }
    /** True for the current conversation when settings.voiceApi selected GPT-Live over Realtime. */
    private var live = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var client = ToolClient()
    private lateinit var registry: ToolRegistry
    private var generation = 0
    private var wakeGeneration = 0
    private var state = ConversationState()
    private var liveState = LiveConversationState()
    /** Accumulated url_citation annotations for the current Live conversation, deduplicated by url. */
    private var liveAnnotations = JSONArray()
    /** The synthetic "document" whose text liveAnnotations' start/end indices point into (one line per citation). */
    private var liveCitationText = ""
    /** True once an output_transcript delta has been seen for the current userTranscript; the next
     * input_transcript delta after that is the start of a new user turn, so it clears userTranscript
     * first instead of appending (Live has no explicit speech-started event to key off of, unlike
     * Realtime's input_audio_buffer.speech_started). */
    private var liveAwaitingNewUserTurn = false
    private var chimeOnReady = false
    private var startedAt = 0L
    private var busySince = 0L
    private var idle = IdlePolicy(30_000)
    private val handled = mutableSetOf<String>()
    /** Debug-only (see docs/architecture.md): the last non-blank `debug.butler.say` value already
     * injected as a user turn, so the ticker only acts on it once. Reset in [begin]. */
    private var lastDebugSay = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (realtime != null) {
                val now = SystemClock.elapsedRealtime()
                val busy = if (live) liveState.busy(now) else state.busy
                val ready = if (live) liveState.ready else state.ready
                idle.update(now, busy)
                if (BuildConfig.DEBUG && ready) checkDebugSay()
                if (busy) { if (busySince == 0L) busySince = now } else busySince = 0
                if ((!ready && now - startedAt > 30_000) || (busySince > 0 && now - busySince > 120_000)) finish(Status(R.string.status_timeout))
                else if (idle.expired(now)) finish()
                else if (live && liveState.readyToClose(now) && !liveState.closeRequested) {
                    liveState.closeRequested()
                    realtime?.send(JSONObject().put("type", "session.close"))
                    val id = generation
                    // Fallback in case session.closed never arrives.
                    main.postDelayed({ if (generation == id) finish() }, 3000)
                }
            }
            main.postDelayed(this, 250)
        }
    }
    override fun onCreate() {
        super.onCreate(); settings = Settings(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("assistant", getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "assistant").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Butler").setContentText(getString(R.string.notification_content_text)).setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_stop), stop).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Butler:Listening").apply { acquire() }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        main.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { settings.enabled = false; stopSelf() }
            TALK -> begin()
            END -> finish()
            "approve", "reject" -> {
                // MCP approve/reject is Realtime-only; approval is never set during a Live conversation.
                approval?.let {
                    realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "mcp_approval_response").put("approval_request_id", it.getString("id"))
                        .put("approve", intent.action == "approve")))
                    approval = null; state.approvalPending = false; status = Status(R.string.status_processing_tool); maybeFollowup()
                }
            }
            else -> { if (realtime == null && wake == null && !preparing) waitForWake() }
        }
        return START_NOT_STICKY
    }
    private fun stopWake(completed: () -> Unit = {}) {
        val id = ++wakeGeneration
        val old = wake
        if (old == null) { if (!destroyed) completed(); return }
        old.stop {
            if (id == wakeGeneration) { wake = null; if (!destroyed) completed() }
        }
    }
    private fun waitForWake(message: Status? = null) {
        stopWake {
            if (!settings.enabled) { status = message ?: Status(R.string.status_mic_stopped); stopSelf(); return@stopWake }
            status = Status(R.string.status_preparing_wake)
            val id = wakeGeneration
            wake = LocalWakeWordEngine(this, wakeModels, settings.wakePhrase, settings.wakeEngine,
                ready = { if (id == wakeGeneration) {
                    status = message ?: Status(R.string.status_say_wake_phrase, settings.wakePhrase.label)
                    // Prime the pooled signaling connection as soon as standby begins, well ahead of
                    // any wake detection — see SignalingHttp's doc comment.
                    SignalingHttp.warm()
                    // Built right after the wake engine's own AudioRecord starts, not before, so Julius'
                    // process startup and this don't compete for CPU during the more latency-sensitive
                    // wake-detector init.
                    preparePeer()
                } },
                detected = { if (id == wakeGeneration) { wakeDetectedAt = SystemClock.elapsedRealtime(); wake = null; begin(woken = true) } },
                failed = { if (id == wakeGeneration) { wake = null; status = Status(R.string.status_wake_detect_failed) } },
                // Speculative re-warm on speech onset: the pooled connection from the ready callback
                // above may have gone idle by the time the user actually speaks the wake phrase.
                speech = { if (id == wakeGeneration) SignalingHttp.warm() })
            wake!!.start()
        }
    }
    /** Builds and [WebRtcClient.prepare]s a fresh standby peer connection, replacing [prepared] on
     * success. A [WebRtcClient.prepare] failure (synchronous) or an async SDP/ICE failure reported via
     * onBroken just leaves [prepared] unset — begin() falls back to building one inline in that case, at
     * the cost of the ICE-gathering time this whole mechanism exists to hide. */
    private fun preparePeer() {
        if (prepared != null || realtime != null) return
        lastPrepareAt = SystemClock.elapsedRealtime()
        val client = WebRtcClient(this)
        runCatching { client.prepare(onBroken = { if (prepared === client) prepared = null }) }
            .onSuccess { prepared = client }
            .onFailure { if (BuildConfig.DEBUG) android.util.Log.w("Butler", "WebRtcClient.prepare() failed", it) }
    }
    /** Host ICE candidates in an already-gathered offer point at addresses on whatever network was
     * active at prepare() time; discard and rebuild [prepared] on any connectivity change while it's
     * sitting idle in standby, throttled to avoid rebuilding on every event in a connectivity flap. Only
     * acts while actually in standby with a prepared client — a live conversation's [realtime] client is
     * left alone. */
    private fun rebuildPrepared() {
        if (prepared == null || realtime != null) return
        if (SystemClock.elapsedRealtime() - lastPrepareAt < 2_000) return
        if (BuildConfig.DEBUG) android.util.Log.d("Butler", "latency: rebuilding prepared WebRTC client (network change)")
        prepared?.close(); prepared = null
        preparePeer()
    }
    /** Picks up [prepared] if it's present and not older than [preparedMaxAgeMs], else builds and
     * prepares a fresh client inline (paying its ICE-gathering cost synchronously here instead). Debug
     * builds log which path was taken. */
    private fun takePreparedClient(): WebRtcClient {
        val existing = prepared
        val fresh = existing != null && SystemClock.elapsedRealtime() - existing.preparedAt < preparedMaxAgeMs
        if (existing != null && fresh) {
            prepared = null
            if (BuildConfig.DEBUG) android.util.Log.d("Butler", "latency: using prepared WebRTC client")
            return existing
        }
        existing?.close(); prepared = null
        if (BuildConfig.DEBUG) android.util.Log.d("Butler", "latency: building WebRTC client inline (" + (if (existing == null) "none prepared" else "stale") + ")")
        val client = WebRtcClient(this)
        client.prepare()
        return client
    }
    /** Debug-only (see docs/architecture.md "Debug timing log"): logs one connect-timeline milestone,
     * tag Butler prefix "latency:", with ms elapsed since [wakeDetectedAt] (a woken conversation) or
     * since begin() itself (the TALK path) — see [latencyBaseMs], set in begin(). */
    private fun logLatency(tag: String) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.d("Butler", "latency: $tag t=${SystemClock.elapsedRealtime() - latencyBaseMs}ms")
    }
    private fun begin(woken: Boolean = false) {
        if (realtime != null || preparing) return
        try {
            if (settings.secret("openai").isBlank()) {
                val message = if (woken) Status(R.string.status_wake_detected_need_key) else Status(R.string.status_need_openai_key)
                if (settings.enabled) waitForWake(message) else status = Status(R.string.status_need_openai_key)
                return
            }
            preparing = true
            stopWake {
            preparing = false
            conversing = true
            generation++; transcript = ""; userTranscript = ""; citations = ""; approval = null; liveAwaitingNewUserTurn = false; lastDebugSay = ""
            live = settings.voiceApi == VoiceApi.LIVE
            state = ConversationState()
            liveState = LiveConversationState()
            liveAnnotations = JSONArray(); liveCitationText = ""
            chimeOnReady = woken
            handled.clear(); startedAt = SystemClock.elapsedRealtime(); busySince = 0
            idle = IdlePolicy(settings.timeoutSeconds * 1000, POST_ACTION_IDLE_MS); client = ToolClient(); registry = ToolRegistry(this, settings, client)
            status = Status(R.string.status_connecting)
            val id = generation
            latencyBaseMs = if (woken) wakeDetectedAt else startedAt
            logLatency("begin")
            val signaling: Signaling = if (live) LiveSignaling(this, settings, registry) else RealtimeSignaling(this, settings)
            val onWebRtcEvent: (JSONObject) -> Unit = { if (generation == id) runCatching { if (live) onLiveEvent(it) else onEvent(it) }.onFailure { error ->
                android.util.Log.e("Butler", "Event ${it.optString("type")}: ${error.javaClass.simpleName} at ${error.stackTrace.take(6).joinToString()}")
                finish(Status(R.string.status_event_process_failed))
            } }
            val onWebRtcFailure: (Status) -> Unit = { if (generation == id) finish(it) }
            val onMilestone: (String) -> Unit = { if (generation == id) logLatency(it) }
            runCatching {
                val client = takePreparedClient()
                realtime = client
                logLatency("connect() called")
                check(client.connect(signaling, onWebRtcEvent, onWebRtcFailure, onMilestone)) { "WebRTC connect() failed (no audio focus, or client broken)" }
            }.onFailure { realtime?.close(); realtime = null; finish(Status(R.string.status_connect_failed)) }
            }
        } catch (_: Exception) { finish(Status(R.string.status_connect_failed)) }
    }
    /** Debug-only (see docs/architecture.md): reads `debug.butler.say` via reflection, same
     * `SystemProperties.get` pattern as MainActivity's `debug.butler.*` overrides. Returns "" (never
     * null) so blank and "property unset/unreadable" are the same "nothing pending" case. */
    private fun debugSayProperty(): String = if (!BuildConfig.DEBUG) "" else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.butler.say") as String
    }.getOrDefault("")
    /** Debug-only hook (called from [ticker] once per tick while a conversation is active and its
     * session is ready): `adb shell setprop debug.butler.say "<text>"` injects that text as the
     * user's turn, so the conversation flow can be exercised from adb without a microphone. A blank
     * property is "nothing pending" and also clears [lastDebugSay], so clearing the property and
     * setting the same text again re-injects it. Realtime only — GPT-Live has no verified client
     * event for injecting a text user turn, so the Live case just logs a warning. */
    private fun checkDebugSay() {
        val say = debugSayProperty()
        if (say.isBlank()) { lastDebugSay = ""; return }
        if (say == lastDebugSay) return
        lastDebugSay = say
        if (live) { android.util.Log.w("Butler", "debug.butler.say is not supported for the Live voice API; ignoring"); return }
        android.util.Log.i("Butler", "debug say: $say")
        userTranscript = say
        val itemSent = realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
            .put("type", "message").put("role", "user").put("content", JSONArray().put(JSONObject()
                .put("type", "input_text").put("text", say)))))
        val responseSent = realtime?.send(JSONObject().put("type", "response.create"))
        android.util.Log.i("Butler", "debug say sent: item=$itemSent response=$responseSent")
    }
    private fun onEvent(e: JSONObject) {
        if (BuildConfig.DEBUG) e.optString("type").let { type ->
            if (!type.endsWith(".delta")) android.util.Log.d("Butler", "rt " + when (type) {
                "error" -> "$type ${e.optJSONObject("error")?.toString()?.take(300)}"
                "response.done" -> "$type ${e.optJSONObject("response")?.optString("status")}"
                else -> type
            })
        }
        when (e.optString("type")) {
            // Tools go in a follow-up session.update once session.created arrives, not in the initial
            // call config: sending them with the call was tried on 2026-09-17 and delayed session.created
            // itself by ~320ms on device (measured from the data channel opening), ~200ms more than this
            // round trip costs, so the original ordering is kept — see docs/architecture.md "Latency".
            "session.created" -> {
                logLatency("session.created")
                realtime?.send(JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime").put("tools", registry.definitions())))
            }
            "session.updated" -> if (state.sessionReady()) {
                realtime?.enableMicrophone()
                if (chimeOnReady) { chimeOnReady = false; WakeChime.play(this) }
                status = Status(R.string.status_please_speak)
                logLatency("ready/chime")
            }
            "input_audio_buffer.speech_started" -> if (state.speechStarted()) {
                userTranscript = ""
                status = Status(R.string.status_listening); idle.update(SystemClock.elapsedRealtime(), true); idle.disarmShort()
            }
            "input_audio_buffer.speech_stopped" -> state.speechStopped()
            "conversation.item.input_audio_transcription.delta" -> userTranscript = (userTranscript + e.optString("delta")).takeLast(4000)
            "conversation.item.input_audio_transcription.completed" -> userTranscript = e.optString("transcript")
            "response.created" -> { idle.update(SystemClock.elapsedRealtime(), true); state.responseCreated(); transcript = ""; status = Status(R.string.status_responding) }
            "output_audio_buffer.started" -> state.audioStarted()
            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                if (state.audioStopped()) { finish(); return }
                if (!state.responding) status = Status(R.string.status_please_speak)
            }
            "response.output_audio_transcript.delta" -> transcript = (transcript + e.optString("delta")).takeLast(4000)
            "response.function_call_arguments.done" -> {
                val callId = e.getString("call_id")
                if (!handled.add(callId)) return
                if (!state.callStarted()) { finish(Status(R.string.status_tool_call_limit)); return }
                if (e.optString("name") == "end_conversation") {
                    state.endRequested(); status = Status(R.string.status_ending_conversation)
                    realtime?.disableMicrophone()
                    realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "function_call_output").put("call_id", callId).put("output", "{\"ok\":true}")))
                    maybeFollowup()
                    val id = generation
                    main.postDelayed({ if (generation == id && state.ending) finish() }, 20_000)
                    return
                }
                val name = e.optString("name")
                val tool = registry.find(name)
                state.toolCallStarted(); status = Status(tool?.busyStatus ?: R.string.status_searching)
                val id = generation
                runToolAsync(tool, name, e.getString("arguments"), id) { result ->
                    // A device operation just completed: if the user says nothing else, end the conversation
                    // quickly (POST_ACTION_IDLE_MS) instead of waiting out the full silence timeout. Armed on
                    // the main thread (like every other idle access) and only for the current conversation.
                    if ((name == "control_devices" && result.optBoolean("done")) ||
                        (name == "home_assistant" && result.optString("type") == "action_done")) { idle.armShort(); homeStateSerial++ }
                    citations = result.optJSONArray("content")?.toString() ?: ""
                    realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "function_call_output").put("call_id", callId).put("output", result.toString())))
                    state.toolCallFinished(); maybeFollowup()
                }
            }
            "conversation.item.done" -> {
                val item = e.optJSONObject("item") ?: return
                if (item.optString("type") == "mcp_approval_request") { approval = item; state.approvalPending = true; status = Status(R.string.status_approval_confirm) }
            }
            "response.mcp_call_arguments.done", "response.mcp_call.in_progress" -> {
                if (!state.mcpCallStarted(e.optString("item_id"))) { finish(Status(R.string.status_tool_call_limit)); return }
            }
            "response.output_item.done" -> {
                val item = e.optJSONObject("item") ?: return
                if (item.optString("type") == "mcp_call") { state.mcpCallFinished(item.optString("id")); maybeFollowup() }
            }
            "response.mcp_call.failed" -> { state.mcpCallFailed(e.optString("item_id")); maybeFollowup() }
            "mcp_list_tools.failed" -> status = Status(R.string.status_mcp_connect_failed)
            "response.done" -> {
                val response = e.optJSONObject("response")
                val failed = response?.optString("status") in listOf("failed", "incomplete")
                if (failed) {
                    val code = response?.optJSONObject("status_details")?.optJSONObject("error")?.optString("code").orEmpty()
                    android.util.Log.w("Butler", "Response failed: ${code.take(80)}")
                    status = Status(R.string.status_response_failed_retry)
                }
                val outcome = state.responseDone(failed)
                if (outcome.followup) realtime?.send(JSONObject().put("type", "response.create"))
                if (outcome.finish) finish()
                else if (!failed && !state.playing && state.pending == 0 && !state.approvalPending) status = Status(R.string.status_please_speak)
            }
            "error" -> {
                val code = e.optJSONObject("error")?.optString("code").orEmpty()
                when (code) {
                    // VAD can start a reply while a tool follow-up request is in flight.
                    // Keep the active response and its audio connection alive.
                    "conversation_already_has_active_response" -> state.markResponding()
                    "response_cancel_not_active" -> Unit
                    else -> finish(Status(R.string.status_api_error))
                }
            }
        }
    }
    /**
     * GPT-Live event handling. Live has no turn-complete/speech-started-stopped/output-audio-done
     * events, only transcript fragments and a delegated Responses run per backend turn — see
     * [LiveConversationState]'s doc comment for the shape this mirrors from the Realtime handling above.
     */
    private fun onLiveEvent(e: JSONObject) {
        if (BuildConfig.DEBUG) android.util.Log.d("Butler", "live " + when (val type = e.optString("type")) {
            "response.event" -> "$type/${e.optJSONObject("event")?.optString("type")}"
            "session.input_transcript.delta", "session.output_transcript.delta" -> "$type ${e.optString("delta").take(80)}"
            "session.closed", "error" -> "$type ${e.toString().take(300)}"
            else -> type
        })
        when (e.optString("type")) {
            "session.started" -> if (liveState.sessionStarted()) {
                realtime?.enableMicrophone()
                if (chimeOnReady) { chimeOnReady = false; WakeChime.play(this) }
                status = Status(R.string.status_please_speak)
                logLatency("ready/chime")
                if (BuildConfig.DEBUG) android.util.Log.i("Butler", "Live session ${e.optJSONObject("session")?.optString("id")}")
            }
            "session.input_transcript.delta" -> {
                if (liveAwaitingNewUserTurn) { userTranscript = ""; liveAwaitingNewUserTurn = false }
                userTranscript = (userTranscript + e.optString("delta")).takeLast(4000)
                val now = SystemClock.elapsedRealtime()
                liveState.userSpeech(now)
                if (liveState.shouldNudge(now)) {
                    realtime?.send(JSONObject().put("type", "session.instructions.append")
                        .put("delegation_id", JSONObject.NULL).put("content", getString(R.string.prompt_live_interrupt_nudge)))
                    if (BuildConfig.DEBUG) android.util.Log.i("Butler", "live nudge sent")
                }
                if (!liveState.ending) {
                    status = Status(R.string.status_listening)
                    idle.update(now, true); idle.disarmShort()
                }
            }
            "session.output_transcript.delta" -> {
                liveAwaitingNewUserTurn = true
                liveState.assistantSpeech(SystemClock.elapsedRealtime())
                transcript = (transcript + e.optString("delta")).takeLast(4000)
                if (!liveState.ending) status = Status(R.string.status_responding)
            }
            "session.delegation.created" -> { liveState.delegationCreated(); status = Status(R.string.status_processing_tool) }
            "response.event" -> onLiveResponseEvent(e.optJSONObject("event") ?: return)
            "session.closed" -> {
                android.util.Log.i("Butler", "Live session closed: ${e.optString("reason")}")
                finish()
            }
            "error" -> {
                val error = e.optJSONObject("error")
                android.util.Log.w("Butler", "Live error ${error?.optString("code")}: ${error?.optString("message").orEmpty().take(200)}")
                // A client_event_id means this refers to one rejected client command, not the session itself.
                if (!e.has("client_event_id")) finish(Status(R.string.status_api_error))
            }
            // session.updated, session.usage.updated, session.input_audio.muted/unmuted: nothing to do.
        }
    }
    private fun onLiveResponseEvent(inner: JSONObject) {
        when (inner.optString("type")) {
            "response.created" -> liveState.responseCreated()
            "response.output_item.done" -> {
                val item = inner.optJSONObject("item") ?: return
                if (BuildConfig.DEBUG) android.util.Log.d("Butler", "live backend item ${item.optString("type")}: " + when (item.optString("type")) {
                    "message" -> item.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty().take(300)
                    "function_call" -> "${item.optString("name")} ${item.optString("arguments").take(300)}"
                    else -> ""
                })
                if (item.optString("type") == "function_call") onLiveFunctionCall(item)
            }
            "response.output_text.annotation.added" -> {
                val annotation = inner.optJSONObject("annotation") ?: return
                if (annotation.optString("type") == "url_citation") addLiveCitation(annotation)
            }
            "response.completed" -> {
                val outcome = liveState.responseFinished(false)
                if (outcome.followup) realtime?.send(JSONObject().put("type", "response.create"))
                if (outcome.finish) finish()
                else if (!liveState.busy(SystemClock.elapsedRealtime())) status = Status(R.string.status_please_speak)
            }
            "response.failed", "response.incomplete" -> {
                val code = inner.optJSONObject("response")?.optJSONObject("error")?.optString("code").orEmpty()
                android.util.Log.w("Butler", "Live response ${inner.optString("type")}: ${code.take(80)}")
                status = Status(R.string.status_response_failed_retry)
                val outcome = liveState.responseFinished(true)
                if (outcome.finish) finish()
            }
        }
    }
    private fun onLiveFunctionCall(item: JSONObject) {
        val callId = item.optString("call_id")
        if (!handled.add(callId)) return
        if (!liveState.callStarted()) { finish(Status(R.string.status_tool_call_limit)); return }
        val name = item.optString("name")
        if (name == "end_conversation") {
            val now = SystemClock.elapsedRealtime()
            liveState.endRequested(now); status = Status(R.string.status_ending_conversation)
            realtime?.disableMicrophone()
            realtime?.send(JSONObject().put("type", "response.item.create").put("item", JSONObject()
                .put("type", "function_call_output").put("call_id", callId).put("output", "{\"ok\":true}")))
            maybeLiveFollowup()
            return
        }
        val tool = registry.find(name)
        liveState.toolCallStarted(); status = Status(tool?.busyStatus ?: R.string.status_searching)
        val id = generation
        runToolAsync(tool, name, item.optString("arguments"), id) { result ->
            if ((name == "control_devices" && result.optBoolean("done")) ||
                (name == "home_assistant" && result.optString("type") == "action_done")) { idle.armShort(); homeStateSerial++ }
            realtime?.send(JSONObject().put("type", "response.item.create").put("item", JSONObject()
                .put("type", "function_call_output").put("call_id", callId).put("output", result.toString())))
            liveState.toolCallFinished(); maybeLiveFollowup()
        }
    }
    /** Runs a tool off the main thread and posts its result back on main, shared by the Realtime and
     * Live function-call dispatch paths (which differ only in the outer envelope they send it in). */
    private fun runToolAsync(tool: Tool?, name: String, argumentsJson: String, id: Int, onResult: (JSONObject) -> Unit) {
        // Called on the main thread (both onEvent and onLiveFunctionCall run there), so this is set
        // synchronously before the tool actually runs on the worker below — MainActivity's tick
        // compares viewRequestSerial to switch the pager and dock the conversation overlay (§9).
        if (name == "get_home_weather") { viewRequest = VIEW_WEATHER; viewRequestSerial++ }
        worker.execute {
            var args: JSONObject? = null
            val result = runCatching {
                args = JSONObject(argumentsJson.ifBlank { "{}" })
                tool?.execute(args!!) ?: error("unknown tool $name")
            }.getOrElse { JSONObject().put("error", getString(R.string.tool_error_generic)) }
            if (BuildConfig.DEBUG) android.util.Log.i("Butler", "tool $name args=$args -> ${result.toString().take(400)}")
            main.post { if (generation == id) onResult(result) }
        }
    }
    /** Collects one url_citation annotation (deduplicated by url) into [liveAnnotations], appending its
     * title as a new line of [liveCitationText] so the indices line up, then republishes [citations] as
     * a single output_text-shaped part — the same shape MainActivity.showSources() already parses from
     * ToolClient.search's content array — so the Sources dialog renders it unchanged. */
    private fun addLiveCitation(annotation: JSONObject) {
        val url = annotation.optString("url")
        if (url.isBlank()) return
        for (i in 0 until liveAnnotations.length()) {
            if (liveAnnotations.getJSONObject(i).optString("url") == url) return
        }
        val title = annotation.optString("title").ifBlank { url }
        if (liveCitationText.isNotEmpty()) liveCitationText += "\n"
        val start = liveCitationText.length
        liveCitationText += title
        liveAnnotations.put(JSONObject().put("type", "url_citation").put("url", url)
            .put("title", annotation.optString("title")).put("start_index", start).put("end_index", liveCitationText.length))
        citations = JSONArray().put(JSONObject().put("type", "output_text").put("text", liveCitationText).put("annotations", liveAnnotations)).toString()
    }
    private fun maybeFollowup() {
        if (state.tryFollowup()) realtime?.send(JSONObject().put("type", "response.create"))
    }
    private fun maybeLiveFollowup() {
        if (liveState.tryFollowup()) realtime?.send(JSONObject().put("type", "response.create"))
    }
    private fun finish(message: Status? = null) {
        generation++; preparing = false; conversing = false; client.cancel(); realtime?.close(); realtime = null
        transcript = ""; userTranscript = ""; citations = ""; approval = null; chimeOnReady = false
        waitForWake(message)
    }
    override fun onDestroy() {
        destroyed = true; preparing = false; conversing = false; generation++; main.removeCallbacksAndMessages(null); client.cancel(); realtime?.close(); realtime = null
        prepared?.close(); prepared = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        wakeGeneration++
        val oldWake = wake; wake = null
        val releaseModel = { kotlin.concurrent.thread(name = "Butler-release-model") { wakeModels.close() }; Unit }
        if (oldWake != null) oldWake.stop(releaseModel) else releaseModel()
        worker.shutdownNow(); wakeLock?.let { if (it.isHeld) it.release() }
        transcript = ""; userTranscript = ""; citations = ""; approval = null; chimeOnReady = false; status = Status(R.string.status_mic_stopped)
        super.onDestroy()
    }
}
