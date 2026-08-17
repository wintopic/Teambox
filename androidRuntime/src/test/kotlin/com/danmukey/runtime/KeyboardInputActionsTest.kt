package com.danmukey.runtime

import android.view.inputmethod.EditorInfo
import com.danmukey.shared.model.KeyboardColumnPreset
import com.danmukey.shared.model.KeyboardHeightPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardInputActionsTest {
    @Test
    fun regularInsertDoesNotTouchExistingSelection() {
        val editor = FakeKeyboardEditor()

        val inserted = KeyboardInputActions.insertText(
            editor = editor,
            text = "弹幕",
            clearBeforeInsert = false,
        )

        assertTrue(inserted)
        assertEquals(listOf("commit:弹幕"), editor.calls)
    }

    @Test
    fun clearBeforeInsertRequiresSelectAllAndAlwaysEndsBatch() {
        val editor = FakeKeyboardEditor(selectAllResult = false)

        val inserted = KeyboardInputActions.insertText(
            editor = editor,
            text = "新内容",
            clearBeforeInsert = true,
        )

        assertFalse(inserted)
        assertEquals(listOf("begin", "selectAll", "end"), editor.calls)
    }

    @Test
    fun clearBeforeInsertReplacesSelectedText() {
        val editor = FakeKeyboardEditor()

        val inserted = KeyboardInputActions.insertText(
            editor = editor,
            text = "新内容",
            clearBeforeInsert = true,
        )

        assertTrue(inserted)
        assertEquals(listOf("begin", "selectAll", "commit:新内容", "end"), editor.calls)
    }

    @Test
    fun manualSubmitOnlyUsesExplicitSendLikeEditorActions() {
        assertEquals(
            EditorInfo.IME_ACTION_SEND,
            KeyboardInputActions.supportedEditorAction(EditorInfo.IME_ACTION_SEND),
        )
        assertEquals(
            EditorInfo.IME_ACTION_GO,
            KeyboardInputActions.supportedEditorAction(EditorInfo.IME_ACTION_GO),
        )
        assertEquals(
            EditorInfo.IME_ACTION_DONE,
            KeyboardInputActions.supportedEditorAction(EditorInfo.IME_ACTION_DONE),
        )
        assertNull(KeyboardInputActions.supportedEditorAction(EditorInfo.IME_ACTION_NEXT))
        assertNull(KeyboardInputActions.supportedEditorAction(EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun unsupportedEditorActionDoesNotCallHost() {
        val editor = FakeKeyboardEditor()

        val result = KeyboardInputActions.submitFromKeyboard(
            editor = editor,
            imeOptions = EditorInfo.IME_ACTION_NEXT,
        )

        assertEquals(ManualSubmitResult.Unsupported, result)
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun hostCanAcceptOrRejectManualSubmit() {
        val accepted = FakeKeyboardEditor(editorActionResult = true)
        val rejected = FakeKeyboardEditor(editorActionResult = false)

        assertEquals(
            ManualSubmitResult.Submitted,
            KeyboardInputActions.submitFromKeyboard(accepted, EditorInfo.IME_ACTION_SEND),
        )
        assertEquals(
            ManualSubmitResult.Rejected,
            KeyboardInputActions.submitFromKeyboard(rejected, EditorInfo.IME_ACTION_SEND),
        )
        assertEquals(listOf("action:${EditorInfo.IME_ACTION_SEND}"), accepted.calls)
        assertEquals(listOf("action:${EditorInfo.IME_ACTION_SEND}"), rejected.calls)
    }

    @Test
    fun keyboardHeightCyclesAcrossThreeBoundedPresets() {
        assertEquals(KeyboardHeightPreset.Standard, KeyboardHeightPreset.Compact.next())
        assertEquals(KeyboardHeightPreset.Tall, KeyboardHeightPreset.Standard.next())
        assertEquals(KeyboardHeightPreset.Compact, KeyboardHeightPreset.Tall.next())
        assertEquals(120, KeyboardHeightPreset.Compact.phraseAreaDp)
        assertEquals(220, KeyboardHeightPreset.Tall.phraseAreaDp)
    }

    @Test
    fun invalidStoredKeyboardHeightFallsBackToStandard() {
        assertEquals(KeyboardHeightPreset.Tall, KeyboardHeightPreset.fromStorage("Tall"))
        assertEquals(KeyboardHeightPreset.Standard, KeyboardHeightPreset.fromStorage("unknown"))
        assertEquals(KeyboardHeightPreset.Standard, KeyboardHeightPreset.fromStorage(null))
    }

    @Test
    fun keyboardColumnsCycleAcrossOneTwoAndThree() {
        assertEquals(KeyboardColumnPreset.Double, KeyboardColumnPreset.Single.next())
        assertEquals(KeyboardColumnPreset.Triple, KeyboardColumnPreset.Double.next())
        assertEquals(KeyboardColumnPreset.Single, KeyboardColumnPreset.Triple.next())
        assertEquals(1, KeyboardColumnPreset.Single.columnCount)
        assertEquals(3, KeyboardColumnPreset.Triple.columnCount)
    }

    @Test
    fun invalidStoredKeyboardColumnsFallBackToDouble() {
        assertEquals(KeyboardColumnPreset.Triple, KeyboardColumnPreset.fromStorage("Triple"))
        assertEquals(KeyboardColumnPreset.Double, KeyboardColumnPreset.fromStorage("unknown"))
        assertEquals(KeyboardColumnPreset.Double, KeyboardColumnPreset.fromStorage(null))
    }

    @Test
    fun tapToSendRequiresConsentAndAccessibilityService() {
        assertEquals(
            TapToSendUnavailableReason.AutomationConsentMissing,
            KeyboardInputActions.tapToSendUnavailableReason(
                tapToSendEnabled = true,
                automationConsentAccepted = false,
                accessibilityServiceEnabled = false,
                automationRuntimeConnected = false,
            ),
        )
        assertEquals(
            TapToSendUnavailableReason.AccessibilityServiceDisabled,
            KeyboardInputActions.tapToSendUnavailableReason(
                tapToSendEnabled = true,
                automationConsentAccepted = true,
                accessibilityServiceEnabled = false,
                automationRuntimeConnected = false,
            ),
        )
        assertNull(
            KeyboardInputActions.tapToSendUnavailableReason(
                tapToSendEnabled = true,
                automationConsentAccepted = true,
                accessibilityServiceEnabled = true,
                automationRuntimeConnected = true,
            ),
        )
    }

    @Test
    fun tapToSendRequiresLiveAutomationRuntimeConnection() {
        assertEquals(
            TapToSendUnavailableReason.AutomationRuntimeDisconnected,
            KeyboardInputActions.tapToSendUnavailableReason(
                tapToSendEnabled = true,
                automationConsentAccepted = true,
                accessibilityServiceEnabled = true,
                automationRuntimeConnected = false,
            ),
        )
    }

    @Test
    fun manualModeNeverNeedsAutomationRuntime() {
        assertNull(
            KeyboardInputActions.tapToSendUnavailableReason(
                tapToSendEnabled = false,
                automationConsentAccepted = false,
                accessibilityServiceEnabled = false,
                automationRuntimeConnected = false,
            ),
        )
    }

    @Test
    fun targetTextLimitUsesExactBoundaryAndNeverTruncates() {
        assertFalse(KeyboardInputActions.exceedsMaximumLength("12345", null))
        assertFalse(KeyboardInputActions.exceedsMaximumLength("12345", 5))
        assertTrue(KeyboardInputActions.exceedsMaximumLength("123456", 5))
    }
}

private class FakeKeyboardEditor(
    private val selectAllResult: Boolean = true,
    private val commitResult: Boolean = true,
    private val editorActionResult: Boolean = true,
) : KeyboardEditor {
    val calls = mutableListOf<String>()

    override fun beginBatchEdit(): Boolean {
        calls += "begin"
        return true
    }

    override fun selectAll(): Boolean {
        calls += "selectAll"
        return selectAllResult
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        calls += "commit:$text"
        return commitResult
    }

    override fun endBatchEdit(): Boolean {
        calls += "end"
        return true
    }

    override fun performEditorAction(actionId: Int): Boolean {
        calls += "action:$actionId"
        return editorActionResult
    }
}
