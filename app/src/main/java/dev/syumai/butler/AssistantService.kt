package dev.syumai.butler

import android.app.*
import android.content.Intent
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class AssistantService : Service() {
    companion object {
        var status = "マイク停止中"; private set
        var transcript = ""; private set
        var citations = ""; private set
        var approval: JSONObject? = null; private set
        var conversing = false; private set
        const val START = "start"; const val TALK = "talk"; const val END = "end"; const val STOP = "stop"
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
    private var tools = ToolClient()
    private var generation = 0
    private var wakeGeneration = 0
    private var ready = false
    private var speaking = false
    private var responding = false
    private var playing = false
    private var pending = 0
    private var calls = 0
    private var followup = false
    private var startedAt = 0L
    private var busySince = 0L
    private var idle = IdlePolicy(30_000)
    private val handled = mutableSetOf<String>()
    private val mcpPending = mutableSetOf<String>()
    private val ticker = object : Runnable {
        override fun run() {
            if (realtime != null) {
                val now = SystemClock.elapsedRealtime()
                val busy = !ready || speaking || responding || playing || pending > 0 || mcpPending.isNotEmpty() || approval != null
                idle.update(now, busy)
                if (busy) { if (busySince == 0L) busySince = now } else busySince = 0
                if ((!ready && now - startedAt > 30_000) || (busySince > 0 && now - busySince > 120_000)) finish("接続・応答がタイムアウトしました")
                else if (idle.expired(now)) finish()
            }
            main.postDelayed(this, 250)
        }
    }
    override fun onCreate() {
        super.onCreate(); settings = Settings(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("assistant", "音声アシスタント", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "assistant").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Butler").setContentText("音声サービスが動作中").setContentIntent(open).addAction(0, "停止", stop).build())
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
                    approval = null; status = "ツールを処理中"; maybeFollowup()
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
    private fun waitForWake(message: String? = null) {
        stopWake {
            if (!settings.enabled) { status = message ?: "マイク停止中"; stopSelf(); return@stopWake }
            status = "端末内の音声検知を準備中…"
            val id = wakeGeneration
            wake = LocalWakeWordEngine(this, wakeModels, settings.get("wakeThreshold", "0.25").toFloatOrNull()?.coerceIn(0.05f, 0.9f) ?: 0.25f, settings.wakePhrase,
                ready = { if (id == wakeGeneration) status = message ?: "${settings.wakePhrase.label} と呼んでください（端末内検知）" },
                detected = { if (id == wakeGeneration) { wake = null; WakeChime.play(this); begin(woken = true) } },
                failed = { if (id == wakeGeneration) { wake = null; status = "端末内検知を開始できません。マイク権限を確認してください" } })
            wake!!.start()
        }
    }
    private fun begin(woken: Boolean = false) {
        if (realtime != null || preparing) return
        try {
            if (settings.secret("openai").isBlank()) {
                val message = if (woken) "呼びかけを検知しました。設定でOpenAI APIキーを登録してください" else "設定でOpenAI APIキーを登録してください"
                if (settings.enabled) waitForWake(message) else status = "設定でOpenAI APIキーを登録してください"
                return
            }
            preparing = true
            stopWake {
            preparing = false
            conversing = true
            generation++; transcript = ""; citations = ""; approval = null
            ready = false; speaking = false; responding = false; playing = false; pending = 0; calls = 0
            followup = false; handled.clear(); mcpPending.clear(); startedAt = SystemClock.elapsedRealtime(); busySince = 0
            idle = IdlePolicy(settings.timeoutSeconds * 1000); tools = ToolClient()
            status = "接続中…"
            val id = generation
            realtime = RealtimeClient(this, settings, { if (generation == id) runCatching { onEvent(it) }.onFailure { error ->
                android.util.Log.e("Butler", "Event ${it.optString("type")}: ${error.javaClass.simpleName} at ${error.stackTrace.take(6).joinToString()}")
                finish("音声イベントを処理できませんでした")
            } }, { if (generation == id) finish(it) })
            runCatching { realtime!!.start() }.onFailure { finish("音声接続を開始できませんでした") }
            }
        } catch (_: Exception) { finish("音声接続を開始できませんでした") }
    }
    private fun definitions(): JSONArray {
        val defs = JSONArray().put(JSONObject().put("type", "function").put("name", "search_web")
            .put("description", "最新情報をインターネットで検索する")
            .put("parameters", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")))
            .put(JSONObject().put("type", "function").put("name", "end_conversation").put("description", "ユーザーが会話の終了を求めたときに終了する")
                .put("parameters", JSONObject("""{"type":"object","properties":{}}""")))
        val url = settings.get("mcpUrl")
        if (url.isNotBlank()) {
            val mcp = JSONObject().put("type", "mcp").put("server_label", "configured_server").put("server_url", url).put("require_approval", "always")
            settings.secret("mcpToken").takeIf { it.isNotBlank() }?.let { mcp.put("authorization", it) }
            defs.put(mcp)
        }
        return defs
    }
    private fun onEvent(e: JSONObject) {
        when (e.optString("type")) {
            "session.created" -> realtime?.send(JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime").put("tools", definitions())))
            "session.updated" -> { if (!ready) { ready = true; realtime?.enableMicrophone(); status = "お話しください" } }
            "input_audio_buffer.speech_started" -> { speaking = true; status = "聞いています"; idle.update(SystemClock.elapsedRealtime(), true) }
            "input_audio_buffer.speech_stopped" -> speaking = false
            "response.created" -> { idle.update(SystemClock.elapsedRealtime(), true); responding = true; transcript = ""; status = "応答中" }
            "output_audio_buffer.started" -> playing = true
            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> { playing = false; if (!responding) status = "お話しください" }
            "response.output_audio_transcript.delta" -> transcript = (transcript + e.optString("delta")).takeLast(4000)
            "response.function_call_arguments.done" -> {
                val callId = e.getString("call_id")
                if (!handled.add(callId)) return
                if (++calls > 10) { finish("ツール呼び出しの上限に達しました"); return }
                if (e.optString("name") == "end_conversation") { finish(); return }
                pending++; followup = true; status = "検索中…"
                val id = generation
                val toolClient = tools
                worker.execute {
                    val result = runCatching {
                        check(e.optString("name") == "search_web")
                        toolClient.search(settings, JSONObject(e.getString("arguments")).getString("query"))
                    }.getOrElse { JSONObject().put("error", "検索できませんでした。結果を推測しないでください。") }
                    main.post {
                        if (generation != id) return@post
                        citations = result.optJSONArray("content")?.toString() ?: ""
                        realtime?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "function_call_output").put("call_id", callId).put("output", result.toString())))
                        pending--; maybeFollowup()
                    }
                }
            }
            "conversation.item.done" -> {
                val item = e.optJSONObject("item") ?: return
                if (item.optString("type") == "mcp_approval_request") { approval = item; status = "画面でツール実行を確認してください" }
            }
            "response.mcp_call_arguments.done", "response.mcp_call.in_progress" -> {
                if (mcpPending.add(e.optString("item_id")) && ++calls > 10) { finish("ツール呼び出しの上限に達しました"); return }
                followup = true
            }
            "response.output_item.done" -> {
                val item = e.optJSONObject("item") ?: return
                if (item.optString("type") == "mcp_call") { mcpPending.remove(item.optString("id")); followup = true; maybeFollowup() }
            }
            "response.mcp_call.failed" -> { mcpPending.remove(e.optString("item_id")); maybeFollowup() }
            "mcp_list_tools.failed" -> status = "MCPに接続できませんでした"
            "response.done" -> {
                responding = false
                val response = e.optJSONObject("response")
                if (response?.optString("status") in listOf("failed", "incomplete")) {
                    val code = response?.optJSONObject("status_details")?.optJSONObject("error")?.optString("code").orEmpty()
                    android.util.Log.w("Butler", "Response failed: ${code.take(80)}")
                    status = "音声応答に失敗しました。もう一度話しかけてください"
                    return
                }
                maybeFollowup(); if (!playing && pending == 0 && approval == null) status = "お話しください" }
            "error" -> {
                val code = e.optJSONObject("error")?.optString("code").orEmpty()
                when (code) {
                    // VAD can start a reply while a tool follow-up request is in flight.
                    // Keep the active response and its audio connection alive.
                    "conversation_already_has_active_response" -> responding = true
                    "response_cancel_not_active" -> Unit
                    else -> finish("APIエラー。キー・モデル・MCP設定を確認してください")
                }
            }
        }
    }
    private fun maybeFollowup() {
        if (followup && !responding && pending == 0 && mcpPending.isEmpty() && approval == null) {
            followup = false; responding = true; realtime?.send(JSONObject().put("type", "response.create"))
        }
    }
    private fun finish(message: String? = null) {
        generation++; preparing = false; conversing = false; tools.cancel(); realtime?.close(); realtime = null
        transcript = ""; citations = ""; approval = null
        waitForWake(message)
    }
    override fun onDestroy() {
        destroyed = true; preparing = false; conversing = false; generation++; main.removeCallbacksAndMessages(null); tools.cancel(); realtime?.close(); realtime = null
        wakeGeneration++
        val oldWake = wake; wake = null
        val releaseModel = { kotlin.concurrent.thread(name = "Butler-release-model") { wakeModels.close() }; Unit }
        if (oldWake != null) oldWake.stop(releaseModel) else releaseModel()
        worker.shutdownNow(); wakeLock?.let { if (it.isHeld) it.release() }
        transcript = ""; citations = ""; approval = null; status = "マイク停止中"
        super.onDestroy()
    }
}
