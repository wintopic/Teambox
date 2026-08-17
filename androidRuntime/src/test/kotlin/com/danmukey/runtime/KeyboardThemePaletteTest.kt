package com.danmukey.runtime

import com.danmukey.shared.model.AppThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KeyboardThemePaletteTest {
    @Test
    fun systemThemeTracksConfigurationWhileOverridesStayStable() {
        assertEquals(
            KeyboardThemePalette.Light,
            KeyboardThemePalette.resolve(AppThemeMode.System, systemDarkTheme = false),
        )
        assertEquals(
            KeyboardThemePalette.Dark,
            KeyboardThemePalette.resolve(AppThemeMode.System, systemDarkTheme = true),
        )
        assertEquals(
            KeyboardThemePalette.Light,
            KeyboardThemePalette.resolve(AppThemeMode.Light, systemDarkTheme = true),
        )
        assertEquals(
            KeyboardThemePalette.Dark,
            KeyboardThemePalette.resolve(AppThemeMode.Dark, systemDarkTheme = false),
        )
    }

    @Test
    fun bothPalettesKeepTextAndControlsVisuallyDistinct() {
        listOf(KeyboardThemePalette.Light, KeyboardThemePalette.Dark).forEach { palette ->
            assertNotEquals(palette.background, palette.primaryText)
            assertNotEquals(palette.controlBackground, palette.primaryText)
            assertNotEquals(palette.accent, palette.onAccent)
        }
    }
}
