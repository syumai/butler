package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class LiveConversationStateTest {
    @Test fun endFlowFollowsUpThenWaitsForQuietBeforeReadyToClose() {
        val state = LiveConversationState()
        state.sessionStarted()
        assertTrue(state.callStarted())
        state.endRequested(1_000L)
        assertTrue(state.followup)
        assertFalse("not ready to close before the farewell quiet period even starts", state.readyToClose(1_000L))
        assertTrue(state.tryFollowup())
        state.delegationCreated()
        state.responseCreated()
        val outcome = state.responseFinished(false)
        assertFalse("no further follow-up once the farewell response completed", outcome.followup)
        assertFalse(outcome.finish)
        // Farewell transcript keeps arriving: readyToClose stays false until it's been quiet for a while.
        state.assistantSpeech(1_500L)
        assertFalse(state.readyToClose(2_000L))
        assertFalse(state.readyToClose(1_500L + LiveConversationState.FAREWELL_QUIET_MS - 1))
        assertTrue(state.readyToClose(1_500L + LiveConversationState.FAREWELL_QUIET_MS))
    }
    @Test fun endFlowReadyToCloseWaitsOutTheInitialQuietPeriodWhenThereWasNoFarewellAudio() {
        val state = LiveConversationState()
        assertTrue(state.callStarted())
        state.endRequested(1_000L)
        state.tryFollowup()
        state.responseCreated()
        state.responseFinished(false)
        assertFalse(state.readyToClose(1_000L + LiveConversationState.FAREWELL_QUIET_MS - 1))
        assertTrue(state.readyToClose(1_000L + LiveConversationState.FAREWELL_QUIET_MS))
    }
    @Test fun toolFollowUpOnlyAfterTheToolFinishesAndTheResponseCompleted() {
        val state = LiveConversationState()
        state.toolCallStarted()
        assertFalse(state.shouldFollowup())
        state.delegationCreated()
        val whilePending = state.responseFinished(false)
        assertFalse("no follow-up while the tool is still pending", whilePending.followup)
        assertFalse(state.shouldFollowup())
        state.toolCallFinished()
        assertTrue(state.shouldFollowup())
        assertTrue(state.tryFollowup())
        assertTrue(state.responding)
    }
    @Test fun busyWhileRespondingOrPendingNotBusyWhenIdle() {
        val state = LiveConversationState()
        state.sessionStarted()
        assertFalse(state.busy(0L))
        state.delegationCreated()
        assertTrue(state.busy(0L))
        state.responseFinished(false)
        assertFalse(state.busy(0L))
        state.toolCallStarted()
        assertTrue(state.busy(0L))
        state.toolCallFinished()
        assertFalse(state.busy(0L))
    }
    @Test fun speechHoldKeepsBusyForAWhileAfterTheLastFragment() {
        val state = LiveConversationState()
        state.sessionStarted()
        state.userSpeech(1_000L)
        assertTrue(state.userSpeaking(1_000L))
        assertTrue(state.userSpeaking(1_000L + LiveConversationState.SPEECH_HOLD_MS))
        assertFalse(state.userSpeaking(1_000L + LiveConversationState.SPEECH_HOLD_MS + 1))
        assertTrue(state.busy(1_000L))
        assertFalse(state.busy(1_000L + LiveConversationState.SPEECH_HOLD_MS + 1))
        state.assistantSpeech(2_000L)
        assertTrue(state.assistantSpeaking(2_000L))
        assertFalse(state.assistantSpeaking(2_000L + LiveConversationState.SPEECH_HOLD_MS + 1))
    }
    @Test fun userSpeakingIsSuppressedWhileEndingEvenWithARecentFragment() {
        val state = LiveConversationState()
        state.userSpeech(1_000L)
        state.endRequested(1_000L)
        assertFalse("must not disarm the end-of-conversation idle policy", state.userSpeaking(1_000L))
        // busy() still reports busy during ending, via the ending flag itself, not userSpeaking.
        assertTrue(state.busy(1_000L))
    }
    @Test fun callStartedEnforcesTheSharedTenCallLimit() {
        val state = LiveConversationState()
        repeat(10) { assertTrue(state.callStarted()) }
        assertFalse(state.callStarted())
    }
    @Test fun failedResponseDuringTheEndFlowFinishes() {
        val state = LiveConversationState()
        state.endRequested(0L)
        state.tryFollowup()
        state.delegationCreated()
        val outcome = state.responseFinished(true)
        assertFalse(outcome.followup)
        assertTrue(outcome.finish)
    }
    @Test fun failedResponseOutsideTheEndFlowDoesNotFinish() {
        val state = LiveConversationState()
        state.delegationCreated()
        val outcome = state.responseFinished(true)
        assertFalse(outcome.followup)
        assertFalse(outcome.finish)
    }
    @Test fun closeRequestedIsSentOnlyOnce() {
        val state = LiveConversationState()
        assertFalse(state.closeRequested)
        state.closeRequested()
        assertTrue(state.closeRequested)
    }
    @Test fun shouldNudgeFiresWhenTheAssistantSpokeWithinTheSpeechHold() {
        val state = LiveConversationState()
        state.assistantSpeech(1_000L)
        assertTrue(state.shouldNudge(1_000L))
        // A fresh state (no prior nudge) still fires right at the edge of the speech-hold window.
        val atTheHoldEdge = LiveConversationState()
        atTheHoldEdge.assistantSpeech(1_000L)
        assertTrue(atTheHoldEdge.shouldNudge(1_000L + LiveConversationState.SPEECH_HOLD_MS))
    }
    @Test fun shouldNudgeDoesNotFireWhenTheAssistantIsSilent() {
        val state = LiveConversationState()
        assertFalse("no assistant speech recorded at all", state.shouldNudge(1_000L))
        state.assistantSpeech(1_000L)
        assertFalse("outside the speech hold window", state.shouldNudge(1_000L + LiveConversationState.SPEECH_HOLD_MS + 1))
    }
    @Test fun shouldNudgeFiresAtMostOncePerNudgeInterval() {
        val state = LiveConversationState()
        state.assistantSpeech(1_000L)
        assertTrue(state.shouldNudge(1_000L))
        assertFalse("too soon after the last nudge", state.shouldNudge(1_000L + LiveConversationState.NUDGE_INTERVAL_MS - 1))
        state.assistantSpeech(1_000L + LiveConversationState.NUDGE_INTERVAL_MS)
        assertTrue(state.shouldNudge(1_000L + LiveConversationState.NUDGE_INTERVAL_MS))
    }
    @Test fun shouldNudgeNeverFiresWhileEnding() {
        val state = LiveConversationState()
        state.assistantSpeech(1_000L)
        state.endRequested(1_000L)
        assertFalse(state.shouldNudge(1_000L))
    }
}
