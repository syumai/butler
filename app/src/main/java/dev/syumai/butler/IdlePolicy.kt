package dev.syumai.butler

/** Pure timing policy. Only genuine activity changes the deadline.
 *
 * [armShort] is called after a device operation so a silent user ends the conversation quickly;
 * [disarmShort] when the user speaks again. While armed, [expired] uses [shortTimeoutMs] instead of
 * [timeoutMs]; [update] never touches the flag itself, since the assistant's own follow-up response
 * after the tool call marks busy (via `response.created`) and that must not disarm it — only the
 * user actually speaking (`input_audio_buffer.speech_started`) should. */
class IdlePolicy(private val timeoutMs: Long, private val shortTimeoutMs: Long = timeoutMs) {
    private var idleSince: Long? = null
    private var shortArmed = false
    fun update(now: Long, busy: Boolean) {
        if (busy) idleSince = null else if (idleSince == null) idleSince = now
    }
    fun armShort() { shortArmed = true }
    fun disarmShort() { shortArmed = false }
    fun expired(now: Long) = idleSince?.let { now - it >= (if (shortArmed) shortTimeoutMs else timeoutMs) } ?: false
}
