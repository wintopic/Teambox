package com.danmukey.shared.model

enum class AppThemeMode {
    System,
    Light,
    Dark;

    fun usesDarkTheme(systemDarkTheme: Boolean): Boolean = when (this) {
        System -> systemDarkTheme
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromStorage(value: String?): AppThemeMode = entries
            .firstOrNull { it.name == value }
            ?: System
    }
}

object AppThemePreference {
    const val STORAGE_NAME = "danmukey_preferences"
    const val STORAGE_KEY = "theme_mode"
}
