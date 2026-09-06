package dev.syumai.butler

/**
 * Pure per-conversation state machine: the flags that decide idle/busy status, whether a follow-up
 * response.create should be sent, and when the end_conversation flow should finish. No Android
 * imports — same spirit as [IdlePolicy]. AssistantService creates a fresh instance in begin() and
 * keeps all Android/side-effecting code (sending JSON, status text, threads, finish()/waitForWake())
 * to itself, delegating flag transitions to this class.
 */
class ConversationState {
    /** Result of [responseDone]: whether to send a response.create follow-up, and whether to finish(). */
    data class Outcome(val followup: Boolean, val finish: Boolean)

    var ready = false; private set
    var speaking = false; private set
    var responding = false; private set
    var playing = false; private set
    var pending = 0; private set
    var followup = false; private set
    var ending = false; private set
    var calls = 0; private set
    /** Whether an MCP tool call is awaiting the user's on-screen approve/reject. */
    var approvalPending = false
    private val mcpPending = mutableSetOf<String>()

    val busy: Boolean
        get() = !ready || speaking || responding || playing || pending > 0 || mcpPending.isNotEmpty() || approvalPending

    fun shouldFollowup(): Boolean = followup && !responding && pending == 0 && mcpPending.isEmpty() && !approvalPending

    /**
     * Mirrors the service's maybeFollowup(): if a follow-up is due, marks it sent (followup = false,
     * responding = true) and returns true so the caller sends the actual response.create.
     */
    fun tryFollowup(): Boolean {
        if (!shouldFollowup()) return false
        followup = false; responding = true
        return true
    }

    /** session.updated. True the first time the session becomes ready — the service should enable the mic. */
    fun sessionReady(): Boolean {
        if (ready) return false
        ready = true
        return true
    }

    /** input_audio_buffer.speech_started. False (no-op) once the conversation is ending. */
    fun speechStarted(): Boolean {
        if (ending) return false
        speaking = true
        return true
    }

    fun speechStopped() { speaking = false }

    fun responseCreated() { responding = true }

    fun audioStarted() { playing = true }

    /** output_audio_buffer.stopped/cleared. True when the ending flow should finish right now. */
    fun audioStopped(): Boolean {
        playing = false
        return ending && !responding
    }

    /**
     * response.function_call_arguments.done, before branching on the tool name. Registers the call
     * against the shared 10-call limit (function-call tools and MCP calls share one counter). False
     * means the limit was exceeded and the caller should finish() instead of dispatching the call.
     */
    fun callStarted(): Boolean = ++calls <= 10

    /** An executable tool (anything but end_conversation) started running in the background. */
    fun toolCallStarted() { pending++; followup = true }

    fun toolCallFinished() { pending-- }

    /** end_conversation was requested. */
    fun endRequested() { ending = true; followup = true }

    /**
     * response.mcp_call_arguments.done / response.mcp_call.in_progress. False means the shared call
     * limit was exceeded and the caller should finish(); followup is left untouched in that case,
     * matching the original short-circuit (`add(...) && ++calls > 10`).
     */
    fun mcpCallStarted(itemId: String): Boolean {
        if (mcpPending.add(itemId) && ++calls > 10) return false
        followup = true
        return true
    }

    /** response.output_item.done for a finished mcp_call. */
    fun mcpCallFinished(itemId: String) { mcpPending.remove(itemId); followup = true }

    /** response.mcp_call.failed. */
    fun mcpCallFailed(itemId: String) { mcpPending.remove(itemId) }

    /** VAD can start a reply while a tool follow-up request is in flight; keep the active response alive. */
    fun markResponding() { responding = true }

    /**
     * response.done. Order matters: the follow-up check runs before the ending-finish check, so an
     * in-flight follow-up (e.g. the end_conversation farewell) is not cut short — see commit 57a2fbd.
     */
    fun responseDone(failed: Boolean): Outcome {
        responding = false
        if (failed) return Outcome(followup = false, finish = ending)
        val doFollowup = tryFollowup()
        val doFinish = ending && !responding && !playing
        return Outcome(followup = doFollowup, finish = doFinish)
    }
}
