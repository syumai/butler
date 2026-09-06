package dev.syumai.butler

/** Pure timing policy. Only genuine activity changes the deadline. */
class IdlePolicy(private val timeoutMs: Long) {
    private var idleSince: Long? = null
    fun update(now: Long, busy: Boolean) {
        if (busy) idleSince = null else if (idleSince == null) idleSince = now
    }
    fun expired(now: Long) = idleSince?.let { now - it >= timeoutMs } ?: false
}
