package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class IdlePolicyTest {
    @Test fun playbackAndToolsDoNotConsumeIdleTime() {
        val policy = IdlePolicy(30_000)
        policy.update(0, false)
        policy.update(29_000, true)
        assertFalse(policy.expired(100_000))
        policy.update(100_000, false)
        assertFalse(policy.expired(129_999))
        assertTrue(policy.expired(130_000))
    }
    @Test fun pollingDoesNotExtendDeadline() {
        val policy = IdlePolicy(5_000)
        policy.update(10, false)
        policy.update(4_999, false)
        assertTrue(policy.expired(5_010))
    }
}
