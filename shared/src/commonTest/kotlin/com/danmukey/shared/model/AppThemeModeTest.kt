package com.danmukey.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppThemeModeTest {
    @Test
    fun storageFallsBackToSystemForMissingOrUnknownValues() {
        assertEquals(AppThemeMode.System, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.System, AppThemeMode.fromStorage("unknown"))
        assertEquals(AppThemeMode.Light, AppThemeMode.fromStorage("Light"))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromStorage("Dark"))
    }

    @Test
    fun explicitModesOverrideSystemAndSystemModeTracksIt() {
        assertTrue(AppThemeMode.System.usesDarkTheme(systemDarkTheme = true))
        assertFalse(AppThemeMode.System.usesDarkTheme(systemDarkTheme = false))
        assertFalse(AppThemeMode.Light.usesDarkTheme(systemDarkTheme = true))
        assertTrue(AppThemeMode.Dark.usesDarkTheme(systemDarkTheme = false))
    }
}
