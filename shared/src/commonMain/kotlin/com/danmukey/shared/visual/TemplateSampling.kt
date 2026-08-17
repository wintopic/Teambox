package com.danmukey.shared.visual

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object LocalTemplatePolicy {
    const val MIN_EDGE = 8
    const val MAX_EDGE = 360
    const val MAX_PIXELS = 65_536

    private val safeId = Regex("[A-Za-z0-9._-]{1,120}")

    fun isValidId(templateId: String): Boolean = safeId.matches(templateId)

    fun validateDimensions(width: Int, height: Int): String? = when {
        width < MIN_EDGE || height < MIN_EDGE -> "选区至少需要 ${MIN_EDGE}×${MIN_EDGE} 像素"
        width > MAX_EDGE || height > MAX_EDGE -> "选区单边不能超过 $MAX_EDGE 像素"
        width * height > MAX_PIXELS -> "选区像素过多，请缩小到 $MAX_PIXELS 像素以内"
        else -> null
    }
}

data class LocalTemplateInfo(
    val templateId: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val updatedAt: Long,
)

fun mapAspectFitSelectionToFrame(
    viewWidth: Int,
    viewHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): PixelRect? {
    require(viewWidth > 0 && viewHeight > 0)
    require(frameWidth > 0 && frameHeight > 0)

    val scale = min(
        viewWidth.toFloat() / frameWidth.toFloat(),
        viewHeight.toFloat() / frameHeight.toFloat(),
    )
    val displayedWidth = frameWidth * scale
    val displayedHeight = frameHeight * scale
    val offsetX = (viewWidth - displayedWidth) / 2f
    val offsetY = (viewHeight - displayedHeight) / 2f

    val selectionLeft = min(startX, endX).coerceIn(offsetX, offsetX + displayedWidth)
    val selectionTop = min(startY, endY).coerceIn(offsetY, offsetY + displayedHeight)
    val selectionRight = max(startX, endX).coerceIn(offsetX, offsetX + displayedWidth)
    val selectionBottom = max(startY, endY).coerceIn(offsetY, offsetY + displayedHeight)
    if (selectionRight <= selectionLeft || selectionBottom <= selectionTop) return null

    val frameLeft = floor((selectionLeft - offsetX) / scale).toInt().coerceIn(0, frameWidth)
    val frameTop = floor((selectionTop - offsetY) / scale).toInt().coerceIn(0, frameHeight)
    val frameRight = ceil((selectionRight - offsetX) / scale).toInt().coerceIn(0, frameWidth)
    val frameBottom = ceil((selectionBottom - offsetY) / scale).toInt().coerceIn(0, frameHeight)
    if (frameRight <= frameLeft || frameBottom <= frameTop) return null
    return PixelRect(frameLeft, frameTop, frameRight, frameBottom)
}

fun ArgbFrame.crop(bounds: PixelRect): ArgbFrame {
    val fullFrame = PixelRect(0, 0, width, height)
    require(fullFrame.contains(bounds) && !bounds.isEmpty) { "模板选区超出截图范围" }
    val croppedPixels = IntArray(bounds.width * bounds.height)
    repeat(bounds.height) { outputY ->
        val sourceStart = (bounds.top + outputY) * width + bounds.left
        val outputStart = outputY * bounds.width
        pixels.copyInto(
            destination = croppedPixels,
            destinationOffset = outputStart,
            startIndex = sourceStart,
            endIndex = sourceStart + bounds.width,
        )
    }
    return ArgbFrame(
        width = bounds.width,
        height = bounds.height,
        pixels = croppedPixels,
        capturedAt = capturedAt,
        source = source,
    )
}
