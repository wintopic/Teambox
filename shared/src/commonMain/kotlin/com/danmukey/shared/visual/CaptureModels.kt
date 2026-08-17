package com.danmukey.shared.visual

import com.danmukey.shared.model.NormalizedRect

enum class ScreenCaptureSource {
    Accessibility,
    MediaProjection,
    LocalAsset,
    Fixture,
}

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0)
        require(top >= 0)
        require(right >= left)
        require(bottom >= top)
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width == 0 || height == 0
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun intersection(other: PixelRect): PixelRect? {
        val intersectedLeft = maxOf(left, other.left)
        val intersectedTop = maxOf(top, other.top)
        val intersectedRight = minOf(right, other.right)
        val intersectedBottom = minOf(bottom, other.bottom)
        if (intersectedRight <= intersectedLeft || intersectedBottom <= intersectedTop) return null
        return PixelRect(intersectedLeft, intersectedTop, intersectedRight, intersectedBottom)
    }

    fun translate(dx: Int, dy: Int): PixelRect = PixelRect(
        left = left + dx,
        top = top + dy,
        right = right + dx,
        bottom = bottom + dy,
    )

    fun contains(other: PixelRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

data class ArgbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val capturedAt: Long,
    val source: ScreenCaptureSource,
    val screenWidth: Int = width,
    val screenHeight: Int = height,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(pixels.size == width * height)
        require(screenWidth > 0)
        require(screenHeight > 0)
    }

    operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width)
        require(y in 0 until height)
        return pixels[y * width + x]
    }
}

data class ScreenCaptureRequest(
    val cropRegion: NormalizedRect? = null,
    val ignoredRegions: List<NormalizedRect> = emptyList(),
    val overlayRegions: List<PixelRect> = emptyList(),
    val maskColor: Int = MASK_COLOR_BLACK,
) {
    companion object {
        const val MASK_COLOR_BLACK: Int = -0x1000000
    }
}

data class PreparedCapture(
    val frame: ArgbFrame,
    val screenRegion: PixelRect,
) {
    init {
        require(!screenRegion.isEmpty)
    }

    fun toScreen(localBounds: PixelRect): PixelRect {
        require(PixelRect(0, 0, frame.width, frame.height).contains(localBounds)) {
            "局部识别结果超出截图范围"
        }
        val mappedLeft = localBounds.left.scaleFloor(frame.width, screenRegion.width)
        val mappedTop = localBounds.top.scaleFloor(frame.height, screenRegion.height)
        return PixelRect(
            left = screenRegion.left + mappedLeft,
            top = screenRegion.top + mappedTop,
            right = screenRegion.left + if (localBounds.width == 0) {
                mappedLeft
            } else {
                localBounds.right.scaleCeil(frame.width, screenRegion.width)
            },
            bottom = screenRegion.top + if (localBounds.height == 0) {
                mappedTop
            } else {
                localBounds.bottom.scaleCeil(frame.height, screenRegion.height)
            },
        )
    }

    private fun Int.scaleFloor(sourceSize: Int, targetSize: Int): Int =
        (toLong() * targetSize / sourceSize).toInt()

    private fun Int.scaleCeil(sourceSize: Int, targetSize: Int): Int =
        ((toLong() * targetSize + sourceSize - 1L) / sourceSize).toInt()
}

sealed interface RawScreenCaptureResult {
    data class Success(val frame: ArgbFrame) : RawScreenCaptureResult
    data object IntervalTooShort : RawScreenCaptureResult
    data class Unavailable(val reason: String) : RawScreenCaptureResult
    data class Failed(val errorCode: String, val recoverable: Boolean = true) : RawScreenCaptureResult
}

fun interface RawScreenCapturer {
    suspend fun capture(): RawScreenCaptureResult
}

sealed interface ScreenCaptureResult {
    data class Success(val capture: PreparedCapture) : ScreenCaptureResult
    data class Deferred(val retryAt: Long, val reason: String) : ScreenCaptureResult
    data class Unavailable(val reason: String) : ScreenCaptureResult
    data class Failed(val errorCode: String, val recoverable: Boolean) : ScreenCaptureResult
}
