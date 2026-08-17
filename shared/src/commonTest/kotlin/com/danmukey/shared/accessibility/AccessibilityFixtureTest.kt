package com.danmukey.shared.accessibility

import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessibilityFixtureTest {
    @Test
    fun redactorOnlyKeepsExplicitLabelsAndNeverKeepsEditableContent() {
        val allowed = setOf("发送", "打开弹幕输入入口")

        assertEquals("发送", AccessibilityFixtureRedactor.safeLabel(" 发送\n", false, allowed))
        assertNull(AccessibilityFixtureRedactor.safeLabel("用户正在输入的内容", false, allowed))
        assertNull(AccessibilityFixtureRedactor.safeLabel("发送", true, allowed))
    }

    @Test
    fun fixtureLocatorUsesTheSameStructuralRulesAsTheRuntimeLocator() {
        val fixture = fixture()

        val byId = AccessibilityFixtureLocator.locate(
            fixture,
            listOf(LocatorSpec.Accessibility(resourceId = "app:id/send", clickable = true)),
        )
        val byText = AccessibilityFixtureLocator.locate(
            fixture,
            listOf(LocatorSpec.Accessibility(textContains = "发送", clickable = true)),
        )

        assertNotNull(byId)
        assertEquals("resource_id", byId.source)
        assertNotNull(byText)
        assertEquals("accessibility_tree", byText.source)
        assertNull(
            AccessibilityFixtureLocator.locate(
                fixture,
                listOf(LocatorSpec.Accessibility(resourceId = "app:id/missing")),
            ),
        )
    }

    @Test
    fun fixtureCodecRoundTripsAndRejectsUnknownVersions() {
        val encoded = AccessibilityFixtureCodec.encode(fixture())
        val decoded = AccessibilityFixtureCodec.decode(encoded)

        assertEquals("app", decoded.packageName)
        assertEquals(2, decoded.nodes.size)
        assertTrue("用户正在输入的内容" !in encoded)

        val unsupported = encoded.replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        assertFailsWith<IllegalArgumentException> { AccessibilityFixtureCodec.decode(unsupported) }
    }

    @Test
    fun editableFixtureRejectsStoredText() {
        assertFailsWith<IllegalArgumentException> {
            AccessibilityNodeFixture(
                depth = 1,
                className = "EditText",
                resourceId = "app:id/input",
                editable = true,
                bounds = AccessibilityBounds(0, 0, 100, 40),
                safeText = "不应保存",
            )
        }
    }

    private fun fixture() = AccessibilityTreeFixture(
        capturedAt = 1_000L,
        packageName = "app",
        appVersionCode = 1L,
        systemApi = 29,
        orientation = Orientation.Portrait,
        screenWidth = 1080,
        screenHeight = 2340,
        nodes = listOf(
            AccessibilityNodeFixture(
                depth = 1,
                className = "android.widget.EditText",
                resourceId = "app:id/input",
                editable = true,
                focusable = true,
                bounds = AccessibilityBounds(20, 100, 800, 180),
            ),
            AccessibilityNodeFixture(
                depth = 1,
                className = "android.widget.Button",
                resourceId = "app:id/send",
                clickable = true,
                focusable = true,
                bounds = AccessibilityBounds(820, 100, 1040, 180),
                safeText = "发送",
                safeContentDescription = "发送",
            ),
        ),
    )
}
