package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class ConversationStateTest {
    @Test fun endFlowFinishesAfterFarewellAudio() {
        val state = ConversationState()
        state.sessionReady()
        assertTrue(state.callStarted())
        state.endRequested()
        val farewell = state.responseDone(false)
        assertTrue(farewell.followup)
        assertFalse(farewell.finish)
        state.responseCreated()
        state.audioStarted()
        val stillPlaying = state.responseDone(false)
        assertFalse("must not finish while the farewell audio is still playing", stillPlaying.finish)
        assertTrue(state.audioStopped())
    }
    @Test fun endFlowFinishesImmediatelyWhenFarewellHasNoAudio() {
        val state = ConversationState()
        assertTrue(state.callStarted())
        state.endRequested()
        val farewell = state.responseDone(false)
        assertTrue(farewell.followup)
        state.responseCreated()
        val done = state.responseDone(false)
        assertTrue(done.finish)
    }
    @Test fun searchFlowFollowsUpOnlyAfterTheToolFinishes() {
        val state = ConversationState()
        state.toolCallStarted()
        val whilePending = state.responseDone(false)
        assertFalse("no follow-up while the tool is still pending", whilePending.followup)
        assertFalse(whilePending.finish)
        state.toolCallFinished()
        assertTrue(state.shouldFollowup())
        assertTrue(state.tryFollowup())
        assertTrue(state.responding)
    }
    @Test fun busyWhilePlayingNotBusyWhenIdle() {
        val state = ConversationState()
        state.sessionReady()
        state.audioStarted()
        assertTrue(state.busy)
        state.audioStopped()
        assertFalse(state.busy)
    }
    @Test fun speechStartedDuringEndingIsIgnored() {
        val state = ConversationState()
        state.endRequested()
        assertFalse(state.speechStarted())
        assertFalse(state.speaking)
    }
}
