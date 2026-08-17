package com.danmukey.runtime

import com.danmukey.shared.model.AppThemeMode

internal data class KeyboardThemePalette(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val controlBackground: Int,
    val accent: Int,
    val onAccent: Int,
    val success: Int,
    val warning: Int,
    val error: Int,
) {
    companion object {
        val Light = KeyboardThemePalette(
            background = 0xFFF7F7FA.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            primaryText = 0xFF1E1E23.toInt(),
            secondaryText = 0xFF50505C.toInt(),
            controlBackground = 0xFFE1E3EC.toInt(),
            accent = 0xFF4C5BD2.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            success = 0xFF347846.toInt(),
            warning = 0xFF965F23.toInt(),
            error = 0xFFAA3737.toInt(),
        )

        val Dark = KeyboardThemePalette(
            background = 0xFF121318.toInt(),
            surface = 0xFF1C1D23.toInt(),
            primaryText = 0xFFF1F1F5.toInt(),
            secondaryText = 0xFFB8B8C2.toInt(),
            controlBackground = 0xFF30323B.toInt(),
            accent = 0xFF9CA7FF.toInt(),
            onAccent = 0xFF11121A.toInt(),
            success = 0xFF78D68F.toInt(),
            warning = 0xFFFFC176.toInt(),
            error = 0xFFFF8B8B.toInt(),
        )

        fun resolve(mode: AppThemeMode, systemDarkTheme: Boolean): KeyboardThemePalette =
            if (mode.usesDarkTheme(systemDarkTheme)) Dark else Light
    }
}
