package com.danmukey.runtime

import kotlin.math.roundToInt

internal data class CaptureResolution(
    val width: Int,
    val height: Int,
)

/**
 * Keeps screenshots close to one pixel per dp. That is enough for a small UI glyph while avoiding
 * multi-megapixel allocations and full-resolution template searches on every scan.
 */
internal fun chooseCaptureResolution(
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
    minimumWidth: Int = 320,
    maximumWidth: Int = 720,
): CaptureResolution {
    require(screenWidth > 0 && screenHeight > 0)
    require(density > 0f)
    require(minimumWidth > 0 && maximumWidth >= minimumWidth)

    val logicalWidth = (screenWidth / density).roundToInt()
    val targetWidth = logicalWidth
        .coerceAtLeast(minimumWidth.coerceAtMost(screenWidth))
        .coerceAtMost(maximumWidth)
        .coerceAtMost(screenWidth)
    val targetHeight = (screenHeight.toDouble() * targetWidth / screenWidth)
        .roundToInt()
        .coerceAtLeast(1)
    return CaptureResolution(targetWidth, targetHeight)
}
