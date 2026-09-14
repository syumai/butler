package dev.syumai.butler

/**
 * Pure per-conversation state machine for the GPT-Live backend — same spirit as [ConversationState],
 * but adapted to Live's event shape: there is no turn-complete/speech-started-stopped/output-audio-done
 * event, only transcript fragments (`session.input_transcript.delta` / `session.output_transcript.delta`)
 * that may arrive out of order, and a delegated Responses run (`session.delegation.created` /
 * `response.created` / `response.completed`|`failed`|`incomplete`) instead of a single response
 * lifecycle. Time is passed in as `now` (`SystemClock.elapsedRealtime()`-style ms) rather than read
 * from the clock directly, so this class stays pure Kotlin and unit-testable. AssistantService keeps
 * all Android/side-effecting code (sending JSON, status text, threads, finish()/waitForWake()) to
 * itself, delegating flag transitions to this class.
 */
class LiveConversationState {
    companion object {
        /** How long after the last transcript fragment we still count the speaker as speaking. */
        const val SPEECH_HOLD_MS = 2000L
        /** How long the conversation must stay quiet (no farewell audio, no pending work) after
         * end_conversation before [readyToClose] allows sending session.close. */
        const val FAREWELL_QUIET_MS = 3000L
        /** Minimum gap between session.instructions.append interruption nudges (see [shouldNudge]). */
        const val NUDGE_INTERVAL_MS = 5000L
    }

    /** Result of [responseFinished]: whether to send a response.create follow-up, and whether to finish(). */
    data class Outcome(val followup: Boolean, val finish: Boolean)

    var ready = false; private set
    /** A delegated Responses run (backend work) is in flight. */
    var responding = false; private set
    /** Executable tool calls currently running on the worker thread. */
    var pending = 0; private set
    var followup = false; private set
    var ending = false; private set
    var endRequestedAt: Long? = null; private set
    var calls = 0; private set
    var lastUserSpeechAt: Long? = null; private set
    var lastAssistantSpeechAt: Long? = null; private set
    var lastNudgeAt: Long? = null; private set
    var closeRequested = false; private set

    /** session.started. True the first time the session becomes ready — the service should enable the mic. */
    fun sessionStarted(): Boolean {
        if (ready) return false
        ready = true
        return true
    }

    /** session.input_transcript.delta. Recorded even while ending — [userSpeaking] is what suppresses it. */
    fun userSpeech(now: Long) { lastUserSpeechAt = now }

    /** session.output_transcript.delta. */
    fun assistantSpeech(now: Long) { lastAssistantSpeechAt = now }

    /** Within [SPEECH_HOLD_MS] of the last user transcript fragment. Always false while ending, so the
     * end-of-conversation idle policy is not disarmed by a farewell exchange's own tail activity. */
    fun userSpeaking(now: Long): Boolean {
        if (ending) return false
        val at = lastUserSpeechAt ?: return false
        return now - at <= SPEECH_HOLD_MS
    }

    /** Within [SPEECH_HOLD_MS] of the last assistant transcript fragment. */
    fun assistantSpeaking(now: Long): Boolean {
        val at = lastAssistantSpeechAt ?: return false
        return now - at <= SPEECH_HOLD_MS
    }

    /** session.input_transcript.delta while the assistant is speaking: whether to send a session.instructions.append
     *  nudge telling the model to stop and listen. True at most once per NUDGE_INTERVAL_MS, never while ending. */
    fun shouldNudge(now: Long): Boolean {
        if (ending || !assistantSpeaking(now)) return false
        val at = lastNudgeAt
        if (at != null && now - at < NUDGE_INTERVAL_MS) return false
        lastNudgeAt = now
        return true
    }

    /** session.delegation.created: backend work began. */
    fun delegationCreated() { responding = true }

    /** The nested response.created event once a delegated Responses run starts streaming. */
    fun responseCreated() { responding = true }

    /**
     * The nested response.completed / response.failed / response.incomplete event. On failure,
     * finishing is decided immediately by whether the end flow was in progress; otherwise a
     * follow-up may be due, and finishing is decided later, on a timer, by [readyToClose].
     */
    fun responseFinished(failed: Boolean): Outcome {
        responding = false
        if (failed) return Outcome(followup = false, finish = ending)
        return Outcome(followup = tryFollowup(), finish = false)
    }

    /** Registers a tool call against the shared 10-call limit. False means the limit was exceeded
     * and the caller should finish() instead of dispatching the call. */
    fun callStarted(): Boolean = ++calls <= 10

    /** An executable tool (anything but end_conversation) started running in the background. */
    fun toolCallStarted() { pending++; followup = true }

    fun toolCallFinished() { pending-- }

    /** end_conversation was requested. */
    fun endRequested(now: Long) { ending = true; endRequestedAt = now; followup = true }

    fun shouldFollowup(): Boolean = followup && !responding && pending == 0

    /**
     * Mirrors AssistantService's maybeLiveFollowup(): if a follow-up is due, marks it sent
     * (followup = false, responding = true) and returns true so the caller sends response.create.
     */
    fun tryFollowup(): Boolean {
        if (!shouldFollowup()) return false
        followup = false; responding = true
        return true
    }

    /** Whether the conversation counts as busy for the idle/silence-timeout policy. */
    fun busy(now: Long): Boolean = !ready || responding || pending > 0 || userSpeaking(now) || assistantSpeaking(now) || ending

    /**
     * True once the end_conversation flow has quieted down enough to send session.close: no
     * follow-up or backend work left, and both the time since end_conversation was requested and the
     * time since the assistant last spoke have cleared [FAREWELL_QUIET_MS] (a farewell audio delta
     * keeps pushing this out until it actually finishes).
     */
    fun readyToClose(now: Long): Boolean {
        val requestedAt = endRequestedAt ?: return false
        if (!ending || responding || pending > 0 || followup) return false
        if (now - requestedAt < FAREWELL_QUIET_MS) return false
        val lastAssistant = lastAssistantSpeechAt ?: return true
        return now - lastAssistant >= FAREWELL_QUIET_MS
    }

    /** session.close was sent — call once so it is not sent again while waiting for session.closed. */
    fun closeRequested() { closeRequested = true }
}
