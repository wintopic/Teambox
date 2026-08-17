package com.danmukey.shared.automation

import kotlinx.serialization.Serializable

@Serializable
enum class TaskPhase {
    Draft,
    Ready,
    CheckingComposer,
    ReopeningComposer,
    LocatingInput,
    WaitingForConfirmation,
    Inserting,
    Submitting,
    Verifying,
    WaitingInterval,
    Paused,
    Cancelled,
    Failed,
    Completed,
}

@Serializable
data class AutomationTaskState(
    val taskId: String,
    val phase: TaskPhase = TaskPhase.Draft,
    val resumePhase: TaskPhase? = null,
    val completedCount: Int = 0,
    val composerReopenCount: Int = 0,
    val errorCode: String? = null,
)

sealed interface AutomationEvent {
    data object Prepare : AutomationEvent
    data object Start : AutomationEvent
    data class ComposerChecked(val isOpen: Boolean) : AutomationEvent
    data class ComposerReopened(val success: Boolean) : AutomationEvent
    data class InputLocated(val success: Boolean) : AutomationEvent
    data object ConfirmationGranted : AutomationEvent
    data class TextInserted(val success: Boolean) : AutomationEvent
    data class Submitted(val success: Boolean) : AutomationEvent
    data class Verified(val success: Boolean) : AutomationEvent
    data object IntervalElapsed : AutomationEvent
    data object Pause : AutomationEvent
    data object Resume : AutomationEvent
    data object Cancel : AutomationEvent
    data object Complete : AutomationEvent
    data class Fail(val code: String) : AutomationEvent
}

object AutomationStateMachine {
    fun reduce(
        state: AutomationTaskState,
        event: AutomationEvent,
    ): AutomationTaskState {
        if (event is AutomationEvent.Cancel && state.phase.isTerminal.not()) {
            return state.copy(phase = TaskPhase.Cancelled, resumePhase = null)
        }
        if (event is AutomationEvent.Fail && state.phase.isTerminal.not()) {
            return state.copy(
                phase = TaskPhase.Failed,
                resumePhase = null,
                errorCode = event.code,
            )
        }
        if (event is AutomationEvent.Pause && state.phase.canPause) {
            return state.copy(phase = TaskPhase.Paused, resumePhase = state.phase)
        }
        if (event is AutomationEvent.Resume && state.phase == TaskPhase.Paused) {
            return state.copy(
                phase = state.resumePhase ?: TaskPhase.CheckingComposer,
                resumePhase = null,
            )
        }

        return when (state.phase) {
            TaskPhase.Draft -> when (event) {
                AutomationEvent.Prepare -> state.copy(phase = TaskPhase.Ready)
                else -> state
            }

            TaskPhase.Ready -> when (event) {
                AutomationEvent.Start -> state.copy(phase = TaskPhase.CheckingComposer)
                else -> state
            }

            TaskPhase.CheckingComposer -> when (event) {
                is AutomationEvent.ComposerChecked -> state.copy(
                    phase = if (event.isOpen) {
                        TaskPhase.LocatingInput
                    } else {
                        TaskPhase.ReopeningComposer
                    },
                )
                else -> state
            }

            TaskPhase.ReopeningComposer -> when (event) {
                is AutomationEvent.ComposerReopened -> if (event.success) {
                    state.copy(
                        phase = TaskPhase.LocatingInput,
                        composerReopenCount = state.composerReopenCount + 1,
                    )
                } else {
                    state.fail("composer_reopen_failed")
                }
                else -> state
            }

            TaskPhase.LocatingInput -> when (event) {
                is AutomationEvent.InputLocated -> if (event.success) {
                    state.copy(phase = TaskPhase.WaitingForConfirmation)
                } else {
                    state.fail("input_not_found")
                }
                else -> state
            }

            TaskPhase.WaitingForConfirmation -> when (event) {
                AutomationEvent.ConfirmationGranted -> state.copy(phase = TaskPhase.Inserting)
                else -> state
            }

            TaskPhase.Inserting -> when (event) {
                is AutomationEvent.TextInserted -> if (event.success) {
                    state.copy(phase = TaskPhase.Submitting)
                } else {
                    state.fail("insert_failed")
                }
                else -> state
            }

            TaskPhase.Submitting -> when (event) {
                is AutomationEvent.Submitted -> if (event.success) {
                    state.copy(phase = TaskPhase.Verifying)
                } else {
                    state.fail("submit_failed")
                }
                else -> state
            }

            TaskPhase.Verifying -> when (event) {
                is AutomationEvent.Verified -> if (event.success) {
                    state.copy(
                        phase = TaskPhase.WaitingInterval,
                        completedCount = state.completedCount + 1,
                    )
                } else {
                    state.fail("verification_failed")
                }
                else -> state
            }

            TaskPhase.WaitingInterval -> when (event) {
                AutomationEvent.IntervalElapsed -> state.copy(phase = TaskPhase.CheckingComposer)
                AutomationEvent.Complete -> state.copy(phase = TaskPhase.Completed)
                else -> state
            }

            TaskPhase.Paused,
            TaskPhase.Cancelled,
            TaskPhase.Failed,
            TaskPhase.Completed,
            -> state
        }
    }

    private fun AutomationTaskState.fail(code: String) = copy(
        phase = TaskPhase.Failed,
        resumePhase = null,
        errorCode = code,
    )

    private val TaskPhase.isTerminal: Boolean
        get() = this == TaskPhase.Cancelled || this == TaskPhase.Failed || this == TaskPhase.Completed

    private val TaskPhase.canPause: Boolean
        get() = this !in setOf(
            TaskPhase.Draft,
            TaskPhase.Ready,
            TaskPhase.Paused,
            TaskPhase.Cancelled,
            TaskPhase.Failed,
            TaskPhase.Completed,
        )
}
