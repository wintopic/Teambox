package com.danmukey.shared.accessibility

import com.danmukey.shared.data.SampleTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccessibilityFixtureRegressionTest {
    @Test
    fun redmiNote7TestHostFixtureMatchesEveryBuiltInLocator() {
        val fixtureText = loadFixture()
        val fixture = AccessibilityFixtureCodec.decode(fixtureText)
        val profile = SampleTargets.testHost

        assertEquals("com.danmukey.testhost", fixture.packageName)
        assertEquals(29, fixture.systemApi)
        assertNotNull(AccessibilityFixtureLocator.locate(fixture, profile.composerEntryLocators))
        assertNotNull(AccessibilityFixtureLocator.locate(fixture, profile.inputLocators))
        assertNotNull(AccessibilityFixtureLocator.locate(fixture, profile.submitLocators))
    }

    @Test
    fun capturedFixtureContainsNoEditableOrKnownTestContent() {
        val fixtureText = loadFixture()
        val fixture = AccessibilityFixtureCodec.decode(fixtureText)

        assertTrue(
            fixture.nodes.filter { it.editable }.all {
                it.safeText == null && it.safeContentDescription == null
            },
        )
        assertFalse("SECRET_USER_TEXT_123" in fixtureText)
        assertFalse("怪键盘测试一" in fixtureText)
        assertTrue(
            fixture.nodes.all { node ->
                node.bounds.left >= 0 &&
                    node.bounds.top >= 0 &&
                    node.bounds.right <= fixture.screenWidth &&
                    node.bounds.bottom <= fixture.screenHeight
            },
        )
    }

    private fun loadFixture(): String = checkNotNull(
        javaClass.classLoader.getResourceAsStream(
            "accessibility/testhost-redmi-note7-api29-portrait.json",
        ),
    ) { "缺少测试宿主无障碍 fixture" }
        .bufferedReader()
        .use { it.readText() }
}
