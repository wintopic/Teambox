package com.danmukey.shared.model

enum class KeyboardHeightPreset(
    val phraseAreaDp: Int,
    val displayName: String,
) {
    Compact(120, "紧凑"),
    Standard(160, "标准"),
    Tall(220, "加高"),
    ;

    fun next(): KeyboardHeightPreset = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): KeyboardHeightPreset =
            entries.firstOrNull { it.name == value } ?: Standard
    }
}

enum class KeyboardColumnPreset(
    val columnCount: Int,
    val displayName: String,
) {
    Single(1, "单列"),
    Double(2, "双列"),
    Triple(3, "三列"),
    ;

    fun next(): KeyboardColumnPreset = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): KeyboardColumnPreset =
            entries.firstOrNull { it.name == value } ?: Double
    }
}

object KeyboardAppearancePreference {
    const val STORAGE_NAME = "keyboard_mode"
    const val HEIGHT_KEY = "keyboard_height_preset"
    const val COLUMN_KEY = "keyboard_column_preset"
}
