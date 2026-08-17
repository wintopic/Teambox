package com.danmukey.shared.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomationStateMachineTest {
    @Test
    fun closedComposerIsReopenedBeforeInputLocation() {
        var state = AutomationTaskState(taskId = "task-1")
        state = AutomationStateMachine.reduce(state, AutomationEvent.Prepare)
        state = AutomationStateMachine.reduce(state, AutomationEvent.Start)
        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerChecked(isOpen = false))
        assertEquals(TaskPhase.ReopeningComposer, state.phase)

        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerReopened(success = true))
        assertEquals(TaskPhase.LocatingInput, state.phase)
        assertEquals(1, state.composerReopenCount)
    }

    @Test
    fun successfulRoundReturnsToComposerCheck() {
        var state = AutomationTaskState(taskId = "task-2")
        val events = listOf(
            AutomationEvent.Prepare,
            AutomationEvent.Start,
            AutomationEvent.ComposerChecked(isOpen = true),
            AutomationEvent.InputLocated(success = true),
            AutomationEvent.ConfirmationGranted,
            AutomationEvent.TextInserted(success = true),
            AutomationEvent.Submitted(success = true),
            AutomationEvent.Verified(success = true),
            AutomationEvent.IntervalElapsed,
        )
        events.forEach { event ->
            state = AutomationStateMachine.reduce(state, event)
        }

        assertEquals(TaskPhase.CheckingComposer, state.phase)
        assertEquals(1, state.completedCount)
        assertNull(state.errorCode)
    }

    @Test
    fun failureDoesNotResumeSilently() {
        var state = AutomationTaskState(taskId = "task-3")
        state = AutomationStateMachine.reduce(state, AutomationEvent.Prepare)
        state = AutomationStateMachine.reduce(state, AutomationEvent.Start)
        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerChecked(isOpen = false))
        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerReopened(success = false))

        assertEquals(TaskPhase.Failed, state.phase)
        assertEquals("composer_reopen_failed", state.errorCode)
        assertEquals(state, AutomationStateMachine.reduce(state, AutomationEvent.Resume))
    }
}
