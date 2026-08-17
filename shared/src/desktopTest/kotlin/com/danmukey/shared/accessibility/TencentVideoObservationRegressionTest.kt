package com.danmukey.shared.accessibility

import com.danmukey.shared.data.SampleTargets
import com.danmukey.shared.model.Orientation
import com.danmukey.shared.model.TargetCapabilityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TencentVideoObservationRegressionTest {
    @Test
    fun landscapeCapabilityMatrixKeepsVisibleAndHiddenPlayerStatesDistinct() {
        val controlsVisible = loadFixture(LANDSCAPE_CONTROLS_VISIBLE_FIXTURE)
        val controlsHidden = loadFixture(LANDSCAPE_CONTROLS_HIDDEN_FIXTURE)

        listOf(controlsVisible, controlsHidden).forEach { fixture ->
            assertSanitizedFixture(fixture, Orientation.Landscape, width = 2340, height = 1080)
        }

        assertEquals(29, controlsVisible.nodes.size)
        assertEquals(9, controlsHidden.nodes.size)
        assertTrue(
            controlsVisible.nodes.any { node ->
                node.clickable &&
                    !node.editable &&
                    node.bounds.left == 721 &&
                    node.bounds.top == 962 &&
                    node.bounds.right == 1266 &&
                    node.bounds.bottom == 1039
            },
            "控件显示状态应保留横屏底部弹幕入口的结构候选",
        )
        assertFalse(
            controlsHidden.nodes.any { node ->
                node.bounds.left == 721 &&
                    node.bounds.top == 962 &&
                    node.bounds.right == 1266 &&
                    node.bounds.bottom == 1039
            },
        )
        assertEquals(
            listOf("android.widget.ScrollView"),
            controlsHidden.nodes.filter { it.editable }.mapNotNull { it.className },
            "控件隐藏状态的顶部可编辑 ScrollView 不能误判为弹幕输入框",
        )
    }

    @Test
    fun portraitCapabilityMatrixExposesPlayerControlsButNoDanmuComposer() {
        val controlsVisible = loadFixture(PORTRAIT_CONTROLS_VISIBLE_FIXTURE)
        val controlsHidden = loadFixture(PORTRAIT_CONTROLS_HIDDEN_FIXTURE)

        listOf(controlsVisible, controlsHidden).forEach { fixture ->
            assertSanitizedFixture(fixture, Orientation.Portrait, width = 1080, height = 2340)
            assertTrue(fixture.nodes.none { it.editable })
            assertFalse(
                fixture.nodes.any { node ->
                    node.clickable &&
                        node.bounds.top < PORTRAIT_PLAYER_BOTTOM &&
                        node.bounds.right - node.bounds.left >= 300 &&
                        node.bounds.bottom - node.bounds.top in 40..140
                },
                "竖屏播放器区域不应声明未经观察确认的弹幕输入入口",
            )
        }

        assertEquals(61, controlsVisible.nodes.size)
        assertEquals(53, controlsHidden.nodes.size)
        assertEquals(8, controlsVisible.nodes.count { it.clickable && it.bounds.top < PORTRAIT_PLAYER_BOTTOM })
        assertEquals(1, controlsHidden.nodes.count { it.clickable && it.bounds.top < PORTRAIT_PLAYER_BOTTOM })
    }

    @Test
    fun builtInTencentVideoProfileIsStrictlyObservationOnly() {
        val profile = SampleTargets.tencentVideoObservation
        val controlsVisible = loadFixture(LANDSCAPE_CONTROLS_VISIBLE_FIXTURE)

        assertEquals(TargetCapabilityLevel.L0, profile.capabilityLevel)
        assertEquals(setOf("com.tencent.qqlive"), profile.appIdentifiers)
        assertEquals(32123L, profile.minAppVersionCode)
        assertEquals(32123L, profile.maxAppVersionCode)
        assertTrue(profile.composerEntryLocators.isEmpty())
        assertTrue(profile.inputLocators.isEmpty())
        assertTrue(profile.submitLocators.isEmpty())
        assertNull(AccessibilityFixtureLocator.locate(controlsVisible, profile.composerEntryLocators))
        assertNull(AccessibilityFixtureLocator.locate(controlsVisible, profile.inputLocators))
        assertNull(AccessibilityFixtureLocator.locate(controlsVisible, profile.submitLocators))
    }

    private fun assertSanitizedFixture(
        fixture: AccessibilityTreeFixture,
        orientation: Orientation,
        width: Int,
        height: Int,
    ) {
        assertEquals("com.tencent.qqlive", fixture.packageName)
        assertEquals(32123L, fixture.appVersionCode)
        assertEquals(29, fixture.systemApi)
        assertEquals(orientation, fixture.orientation)
        assertEquals(width, fixture.screenWidth)
        assertEquals(height, fixture.screenHeight)
        assertTrue(fixture.nodes.all { it.safeText == null && it.safeContentDescription == null })
        assertTrue(
            fixture.nodes.mapNotNull { it.resourceId }.all { it == QQLIVE_OBFUSCATED_RESOURCE_ID },
            "只允许已观察到但不可作为稳定定位依据的腾讯视频混淆资源 ID",
        )
        assertTrue(
            fixture.nodes.all { node ->
                node.bounds.left >= 0 &&
                    node.bounds.top >= 0 &&
                    node.bounds.right <= fixture.screenWidth &&
                    node.bounds.bottom <= fixture.screenHeight
            },
        )
    }

    private fun loadFixture(resource: String): AccessibilityTreeFixture = AccessibilityFixtureCodec.decode(
        checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "缺少腾讯视频无障碍 fixture：$resource" }
            .bufferedReader()
            .use { it.readText() },
    )

    private companion object {
        const val PORTRAIT_PLAYER_BOTTOM = 706
        const val QQLIVE_OBFUSCATED_RESOURCE_ID = "com.tencent.qqlive:id/arg"
        const val LANDSCAPE_CONTROLS_VISIBLE_FIXTURE =
            "accessibility/qqlive-redmi-note7-api29-landscape-controls-visible.json"
        const val LANDSCAPE_CONTROLS_HIDDEN_FIXTURE =
            "accessibility/qqlive-redmi-note7-api29-landscape-controls-hidden.json"
        const val PORTRAIT_CONTROLS_VISIBLE_FIXTURE =
            "accessibility/qqlive-redmi-note7-api29-portrait-controls-visible.json"
        const val PORTRAIT_CONTROLS_HIDDEN_FIXTURE =
            "accessibility/qqlive-redmi-note7-api29-portrait-controls-hidden.json"
    }
}
