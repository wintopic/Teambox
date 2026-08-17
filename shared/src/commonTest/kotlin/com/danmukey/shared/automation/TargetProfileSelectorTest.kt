package com.danmukey.shared.automation

import com.danmukey.shared.model.Orientation
import com.danmukey.shared.model.Platform
import com.danmukey.shared.model.TargetProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetProfileSelectorTest {
    @Test
    fun selectsHighestCompatibleProfileForExactOrientation() {
        val portraitV1 = profile("portrait-v1", Orientation.Portrait, version = 1)
        val portraitV2 = profile("portrait-v2", Orientation.Portrait, version = 2)
        val landscapeV9 = profile("landscape-v9", Orientation.Landscape, version = 9)

        val selected = TargetProfileSelector.select(
            listOf(landscapeV9, portraitV1, portraitV2),
            TargetRuntimeContext("video.app", Orientation.Portrait, systemApi = 29),
        )

        assertEquals("portrait-v2", selected?.id)
    }

    @Test
    fun rejectsOutOfRangeSystemAndAppVersions() {
        val profile = profile("bounded", Orientation.Portrait, version = 1).copy(
            minSystemApi = 30,
            minAppVersionCode = 100,
            maxAppVersionCode = 200,
        )
        assertNull(
            TargetProfileSelector.select(
                listOf(profile),
                TargetRuntimeContext("video.app", Orientation.Portrait, systemApi = 29, appVersionCode = 150),
            ),
        )
        assertNull(
            TargetProfileSelector.select(
                listOf(profile),
                TargetRuntimeContext("video.app", Orientation.Portrait, systemApi = 30, appVersionCode = 201),
            ),
        )
    }

    @Test
    fun prefersUsableCalibratedCapabilityOverObservationProfileOnVersionTie() {
        val observation = profile("observation", Orientation.Landscape, version = 1).copy(
            capabilityLevel = com.danmukey.shared.model.TargetCapabilityLevel.L0,
        )
        val calibrated = profile("calibrated", Orientation.Landscape, version = 1).copy(
            capabilityLevel = com.danmukey.shared.model.TargetCapabilityLevel.L2,
        )

        val selected = TargetProfileSelector.select(
            listOf(observation, calibrated),
            TargetRuntimeContext("video.app", Orientation.Landscape, systemApi = 29),
        )

        assertEquals("calibrated", selected?.id)
    }

    private fun profile(id: String, orientation: Orientation, version: Int) = TargetProfile(
        id = id,
        displayName = id,
        platform = Platform.Android,
        appIdentifiers = setOf("video.app"),
        orientations = setOf(orientation),
        inputLocators = emptyList(),
        submitLocators = emptyList(),
        profileVersion = version,
    )
}
