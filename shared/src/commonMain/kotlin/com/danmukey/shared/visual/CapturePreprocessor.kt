package com.danmukey.shared.visual

import com.danmukey.shared.model.NormalizedRect
import kotlin.math.ceil
import kotlin.math.floor

object CapturePreprocessor {
    fun prepare(frame: ArgbFrame, request: ScreenCaptureRequest): PreparedCapture {
        val fullFrame = PixelRect(0, 0, frame.width, frame.height)
        val fullScreen = PixelRect(0, 0, frame.screenWidth, frame.screenHeight)
        val crop = request.cropRegion?.toPixelRect(frame.width, frame.height) ?: fullFrame
        require(!crop.isEmpty) { "截图裁剪区域不能为空" }

        val masks = buildList {
            request.ignoredRegions.forEach { add(it.toPixelRect(frame.width, frame.height)) }
            request.overlayRegions.forEach { overlay ->
                fullScreen.intersection(overlay)?.toFrameRect(frame)?.let(::add)
            }
        }.mapNotNull(crop::intersection)

        val screenRegion = crop.toScreenRect(frame)
        if (crop == fullFrame && masks.isEmpty()) {
            return PreparedCapture(frame = frame, screenRegion = screenRegion)
        }

        val output = if (crop == fullFrame) {
            frame.pixels.copyOf()
        } else {
            IntArray(crop.width * crop.height).also { croppedPixels ->
                repeat(crop.height) { outputY ->
                    val sourceStart = (crop.top + outputY) * frame.width + crop.left
                    val outputStart = outputY * crop.width
                    frame.pixels.copyInto(
                        destination = croppedPixels,
                        destinationOffset = outputStart,
                        startIndex = sourceStart,
                        endIndex = sourceStart + crop.width,
                    )
                }
            }
        }

        masks.forEach { clipped ->
            val local = clipped.translate(-crop.left, -crop.top)
            for (y in local.top until local.bottom) {
                val rowStart = y * crop.width
                for (x in local.left until local.right) {
                    output[rowStart + x] = request.maskColor
                }
            }
        }

        return PreparedCapture(
            frame = ArgbFrame(
                width = crop.width,
                height = crop.height,
                pixels = output,
                capturedAt = frame.capturedAt,
                source = frame.source,
                screenWidth = screenRegion.width,
                screenHeight = screenRegion.height,
            ),
            screenRegion = screenRegion,
        )
    }

    private fun PixelRect.toScreenRect(frame: ArgbFrame): PixelRect = PixelRect(
        left = left.scaleFloor(frame.width, frame.screenWidth),
        top = top.scaleFloor(frame.height, frame.screenHeight),
        right = right.scaleCeil(frame.width, frame.screenWidth),
        bottom = bottom.scaleCeil(frame.height, frame.screenHeight),
    )

    private fun PixelRect.toFrameRect(frame: ArgbFrame): PixelRect? {
        val mapped = PixelRect(
            left = left.scaleFloor(frame.screenWidth, frame.width),
            top = top.scaleFloor(frame.screenHeight, frame.height),
            right = right.scaleCeil(frame.screenWidth, frame.width),
            bottom = bottom.scaleCeil(frame.screenHeight, frame.height),
        )
        return PixelRect(0, 0, frame.width, frame.height).intersection(mapped)
    }

    private fun Int.scaleFloor(sourceSize: Int, targetSize: Int): Int =
        (toLong() * targetSize / sourceSize).toInt()

    private fun Int.scaleCeil(sourceSize: Int, targetSize: Int): Int =
        ((toLong() * targetSize + sourceSize - 1L) / sourceSize).toInt()
}

fun NormalizedRect.toPixelRect(width: Int, height: Int): PixelRect {
    require(width > 0 && height > 0)
    return PixelRect(
        left = floor(left * width).toInt().coerceIn(0, width),
        top = floor(top * height).toInt().coerceIn(0, height),
        right = ceil(right * width).toInt().coerceIn(0, width),
        bottom = ceil(bottom * height).toInt().coerceIn(0, height),
    )
}
