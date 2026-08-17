package com.danmukey.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityGuidanceTest {
    @Test
    fun restrictedSettingsGuideOnlyAppearsOnAndroid13OrNewer() {
        assertFalse(shouldShowRestrictedSettingsGuide(systemApi = 32, accessibilityEnabled = false))
        assertTrue(shouldShowRestrictedSettingsGuide(systemApi = 33, accessibilityEnabled = false))
        assertTrue(shouldShowRestrictedSettingsGuide(systemApi = 35, accessibilityEnabled = false))
    }

    @Test
    fun enabledAccessibilityDoesNotKeepShowingRestrictionRecovery() {
        assertFalse(shouldShowRestrictedSettingsGuide(systemApi = 35, accessibilityEnabled = true))
    }
}
