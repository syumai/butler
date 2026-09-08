package dev.syumai.butler

import android.app.*
import android.content.Intent
import android.os.*
import dev.syumai.butler.tools.ToolRegistry
import org.json.JSONObject
import java.util.concurrent.Executors

class AssistantService : Service() {
    companion object {
        var status: Status = Status(R.string.status_mic_stopped); private set
        var transcript = ""; private set
        var citations = ""; private set
        var approval: JSONObject? = null; private set
        var conversing = false; private set
        const val START = "start"; const val TALK = "talk"; const val END = "end"; const val STOP = "stop"
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
    private var realtime: RealtimeClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var client = ToolClient()
    private lateinit var registry: ToolRegistry
    private var generation = 0
    private var wakeGeneration = 0
    private var state = ConversationState()
    private var chimeOnReady = false
    private var startedAt = 0L
    private var busySince = 0L
    private var idle = IdlePolicy(30_000)
    private val handled = mutableSetOf<String>()
    private val ticker = object : Runnable {
        override fun run() {
            if (realtime != null) {
                val now = SystemClock.elapsedRealtime()
                val busy = state.busy
                idle.update(now, busy)
                if (busy) { if (busySince == 0L) busySince = now } else busySince = 0
                if ((!state.ready && now - startedAt > 30_000) || (busySince > 0 && now - busySince > 120_000)) finish(Status(R.string.status_timeout))
                else if (idle.expired(now)) finish()
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
        main.post(ticker)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { settings.enabled = false; stopSelf() }
            TALK -> begin()
            END -> finish()
            "approve", "reject" -> {
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
            wake = LocalWakeWordEngine(this, wakeModels, settings.get("wakeThreshold", "0.25").toFloatOrNull()?.coerceIn(0.05f, 0.9f) ?: 0.25f, settings.wakePhrase,
                ready = { if (id == wakeGeneration) status = message ?: Status(R.string.status_say_wake_phrase, settings.wakePhrase.label) },
                detected = { if (id == wakeGeneration) { wake = null; begin(woken = true) } },
                failed = { if (id == wakeGeneration) { wake = null; status = Status(R.string.status_wake_detect_failed) } })
            wake!!.start()
        }
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
            generation++; transcript = ""; citations = ""; approval = null
            state = ConversationState()
            chimeOnReady = woken
            handled.clear(); startedAt = SystemClock.elapsedRealtime(); busySince = 0
            idle = IdlePolicy(settings.timeoutSeconds * 1000, POST_ACTION_IDLE_MS); client = ToolClient(); registry = ToolRegistry(this, settings, client)
            status = Status(R.string.status_connecting)
            val id = generation
            realtime = RealtimeClient(this, settings, { if (generation == id) runCatching { onEvent(it) }.onFailure { error ->
                android.util.Log.e("Butler", "Event ${it.optString("type")}: ${error.javaClass.simpleName} at ${error.stackTrace.take(6).joinToString()}")
                finish(Status(R.string.status_event_process_failed))
            } }, { if (generation == id) finish(it) })
            runCatching { realtime!!.start() }.onFailure { finish(Status(R.string.status_connect_failed)) }
            }
        } catch (_: Exception) { finish(Status(R.string.status_connect_failed)) }
    }
    private fun onEvent(e: JSONObject) {
        when (e.optString("type")) {
            "session.created" -> realtime?.send(JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime").put("tools", registry.definitions())))
            "session.updated" -> if (state.sessionReady()) {
                realtime?.enableMicrophone()
                if (chimeOnReady) { chimeOnReady = false; WakeChime.play(this) }
                status = Status(R.string.status_please_speak)
            }
            "input_audio_buffer.speech_started" -> if (state.speechStarted()) {
                status = Status(R.string.status_listening); idle.update(SystemClock.elapsedRealtime(), true); idle.disarmShort()
            }
            "input_audio_buffer.speech_stopped" -> state.speechStopped()
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
                worker.execute {
                    var args: JSONObject? = null
                    val result = runCatching {
                        args = JSONObject(e.getString("arguments"))
                        tool?.execute(args!!) ?: error("unknown tool $name")
                    }.getOrElse { JSONObject().put("error", getString(R.string.tool_error_generic)) }
                    if (BuildConfig.DEBUG) android.util.Log.i("Butler", "tool $name args=$args -> ${result.toString().take(400)}")
                    main.post {
                        if (generation != id) return@post
                        // A device operation just completed: if the user says nothing else, end the conversation
                        // quickly (POST_ACTION_IDLE_MS) instead of waiting out the full silence timeout. Armed on
                        // the main thread (like every other idle access) and only for the current conversation.
                        if ((name == "control_devices" && result.optBoolean("done")) ||
                            (name == "home_assistant" && result.optString("type") == "action_done")) idle.armShort()
                        citations = result.optJSONArray("content")?.toString() ?: ""
                        realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "function_call_output").put("call_id", callId).put("output", result.toString())))
                        state.toolCallFinished(); maybeFollowup()
                    }
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
    private fun maybeFollowup() {
        if (state.tryFollowup()) realtime?.send(JSONObject().put("type", "response.create"))
    }
    private fun finish(message: Status? = null) {
        generation++; preparing = false; conversing = false; client.cancel(); realtime?.close(); realtime = null
        transcript = ""; citations = ""; approval = null; chimeOnReady = false
        waitForWake(message)
    }
    override fun onDestroy() {
        destroyed = true; preparing = false; conversing = false; generation++; main.removeCallbacksAndMessages(null); client.cancel(); realtime?.close(); realtime = null
        wakeGeneration++
        val oldWake = wake; wake = null
        val releaseModel = { kotlin.concurrent.thread(name = "Butler-release-model") { wakeModels.close() }; Unit }
        if (oldWake != null) oldWake.stop(releaseModel) else releaseModel()
        worker.shutdownNow(); wakeLock?.let { if (it.isHeld) it.release() }
        transcript = ""; citations = ""; approval = null; chimeOnReady = false; status = Status(R.string.status_mic_stopped)
        super.onDestroy()
    }
}
