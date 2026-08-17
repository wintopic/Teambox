package com.danmukey.runtime

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.RawScreenCaptureResult
import com.danmukey.shared.visual.RawScreenCapturer
import com.danmukey.shared.visual.ScreenCaptureSource
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AccessibilityScreenshotCapturer(
    private val service: AccessibilityService,
    private val executor: Executor,
    private val now: () -> Long = System::currentTimeMillis,
) : RawScreenCapturer {
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override suspend fun capture(): RawScreenCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return RawScreenCaptureResult.Unavailable("accessibility_screenshot_requires_api_30")
        }
        return captureApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureApi30(): RawScreenCaptureResult = suspendCancellableCoroutine { continuation ->
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val result = runCatching {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val hardwareBitmap = checkNotNull(
                                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace),
                                ) { "无法读取无障碍截图缓冲区" }
                                try {
                                    val screenWidth = hardwareBitmap.width
                                    val screenHeight = hardwareBitmap.height
                                    val target = chooseCaptureResolution(
                                        screenWidth = screenWidth,
                                        screenHeight = screenHeight,
                                        density = service.resources.displayMetrics.density,
                                    )
                                    val bitmap = Bitmap.createBitmap(
                                        target.width,
                                        target.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                    try {
                                        Canvas(bitmap).drawBitmap(
                                            hardwareBitmap,
                                            null,
                                            Rect(0, 0, target.width, target.height),
                                            scalePaint,
                                        )
                                        val pixels = IntArray(bitmap.width * bitmap.height)
                                        bitmap.getPixels(
                                            pixels,
                                            0,
                                            bitmap.width,
                                            0,
                                            0,
                                            bitmap.width,
                                            bitmap.height,
                                        )
                                        RawScreenCaptureResult.Success(
                                            ArgbFrame(
                                                width = bitmap.width,
                                                height = bitmap.height,
                                                pixels = pixels,
                                                capturedAt = now(),
                                                source = ScreenCaptureSource.Accessibility,
                                                screenWidth = screenWidth,
                                                screenHeight = screenHeight,
                                            ),
                                        )
                                    } finally {
                                        bitmap.recycle()
                                    }
                                } finally {
                                    hardwareBitmap.recycle()
                                }
                            } finally {
                                buffer.close()
                            }
                        }.getOrElse {
                            RawScreenCaptureResult.Failed("accessibility_screenshot_decode_failed")
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onFailure(errorCode: Int) {
                        val result = accessibilityScreenshotFailureResult(errorCode)
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }.onFailure {
            if (continuation.isActive) {
                continuation.resume(RawScreenCaptureResult.Failed("accessibility_screenshot_request_failed"))
            }
        }
    }
}

internal fun accessibilityScreenshotFailureResult(errorCode: Int): RawScreenCaptureResult =
    when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            RawScreenCaptureResult.IntervalTooShort
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            RawScreenCaptureResult.Unavailable("accessibility_screenshot_access_revoked")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            RawScreenCaptureResult.Unavailable("secure_window")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            RawScreenCaptureResult.Unavailable("invalid_display")
        else -> RawScreenCaptureResult.Failed("accessibility_screenshot_error_$errorCode")
    }

internal suspend fun captureWithSingleIntervalRetry(
    capturer: RawScreenCapturer,
    retryDelayMs: Long,
    delayBeforeRetry: suspend (Long) -> Unit,
): RawScreenCaptureResult {
    require(retryDelayMs >= 0L)
    val first = capturer.capture()
    if (first !is RawScreenCaptureResult.IntervalTooShort) return first
    delayBeforeRetry(retryDelayMs)
    return capturer.capture()
}
