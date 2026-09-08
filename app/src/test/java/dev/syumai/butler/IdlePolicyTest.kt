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

    @Test fun armShortExpiresAfterShortTimeout() {
        val policy = IdlePolicy(30_000, 5_000)
        policy.armShort()
        policy.update(0, false)
        assertFalse(policy.expired(4_900))
        assertTrue(policy.expired(5_000))
    }

    @Test fun armShortSurvivesTheAssistantsOwnBusyUpdate() {
        val policy = IdlePolicy(30_000, 5_000)
        policy.armShort()
        policy.update(0, false)
        policy.update(1_000, true) // the assistant's own follow-up response (response.created) marks busy
        policy.update(1_000, false) // then goes idle again once playback finishes
        assertFalse(policy.expired(1_000 + 4_999))
        assertTrue(policy.expired(1_000 + 5_000))
    }

    @Test fun disarmShortRestoresTheNormalTimeout() {
        val policy = IdlePolicy(30_000, 5_000)
        policy.armShort()
        policy.disarmShort()
        policy.update(0, false)
        assertFalse(policy.expired(5_000))
        assertFalse(policy.expired(29_999))
        assertTrue(policy.expired(30_000))
    }

    @Test fun defaultConstructionArmShortBehavesExactlyLikeBefore() {
        val policy = IdlePolicy(30_000)
        policy.armShort() // no shortTimeoutMs given, so this must not change anything
        policy.update(0, false)
        assertFalse(policy.expired(29_999))
        assertTrue(policy.expired(30_000))
    }
}
