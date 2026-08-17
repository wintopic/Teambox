package com.danmukey.runtime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

internal interface KeyboardEditor {
    fun beginBatchEdit(): Boolean
    fun selectAll(): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun endBatchEdit(): Boolean
    fun performEditorAction(actionId: Int): Boolean
}

internal class AndroidKeyboardEditor(
    private val connection: InputConnection,
) : KeyboardEditor {
    override fun beginBatchEdit(): Boolean = connection.beginBatchEdit()

    override fun selectAll(): Boolean = connection.performContextMenuAction(android.R.id.selectAll)

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        connection.commitText(text, newCursorPosition)

    override fun endBatchEdit(): Boolean = connection.endBatchEdit()

    override fun performEditorAction(actionId: Int): Boolean =
        connection.performEditorAction(actionId)
}

internal enum class ManualSubmitResult {
    Submitted,
    Unsupported,
    Rejected,
}

internal enum class TapToSendUnavailableReason {
    AutomationConsentMissing,
    AccessibilityServiceDisabled,
    AutomationRuntimeDisconnected,
}

internal object KeyboardInputActions {
    fun exceedsMaximumLength(
        text: CharSequence,
        maximumLength: Int?,
    ): Boolean = maximumLength != null && text.length > maximumLength

    fun tapToSendUnavailableReason(
        tapToSendEnabled: Boolean,
        automationConsentAccepted: Boolean,
        accessibilityServiceEnabled: Boolean,
        automationRuntimeConnected: Boolean,
    ): TapToSendUnavailableReason? {
        if (!tapToSendEnabled) return null
        if (!automationConsentAccepted) return TapToSendUnavailableReason.AutomationConsentMissing
        if (!accessibilityServiceEnabled) return TapToSendUnavailableReason.AccessibilityServiceDisabled
        if (!automationRuntimeConnected) return TapToSendUnavailableReason.AutomationRuntimeDisconnected
        return null
    }

    fun insertText(
        editor: KeyboardEditor,
        text: CharSequence,
        clearBeforeInsert: Boolean,
    ): Boolean {
        if (!clearBeforeInsert) {
            return editor.commitText(text, 1)
        }

        editor.beginBatchEdit()
        return try {
            if (!editor.selectAll()) {
                false
            } else {
                editor.commitText(text, 1)
            }
        } finally {
            editor.endBatchEdit()
        }
    }

    fun submitFromKeyboard(
        editor: KeyboardEditor,
        imeOptions: Int,
    ): ManualSubmitResult {
        val actionId = supportedEditorAction(imeOptions) ?: return ManualSubmitResult.Unsupported
        return if (editor.performEditorAction(actionId)) {
            ManualSubmitResult.Submitted
        } else {
            ManualSubmitResult.Rejected
        }
    }

    internal fun supportedEditorAction(imeOptions: Int): Int? =
        when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            -> action

            else -> null
        }
}
