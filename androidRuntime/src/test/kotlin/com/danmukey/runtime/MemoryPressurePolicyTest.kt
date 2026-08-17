package com.danmukey.runtime

import android.content.ComponentCallbacks2
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryPressurePolicyTest {
    @Test
    fun onlyRunningLowOrWorseStopsAutomation() {
        assertFalse(shouldStopAutomationForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(shouldStopAutomationForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldStopAutomationForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldStopAutomationForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }
}
