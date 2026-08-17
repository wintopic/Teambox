package com.danmukey.runtime

import android.accessibilityservice.AccessibilityService
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.RawScreenCaptureResult
import com.danmukey.shared.visual.RawScreenCapturer
import com.danmukey.shared.visual.ScreenCaptureSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AccessibilityScreenshotCapturerTest {
    @Test
    fun platformFailureCodesMapToStablePrivacySafeResults() {
        assertSame(
            RawScreenCaptureResult.IntervalTooShort,
            accessibilityScreenshotFailureResult(
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            ),
        )
        assertEquals(
            "accessibility_screenshot_access_revoked",
            assertIs<RawScreenCaptureResult.Unavailable>(
                accessibilityScreenshotFailureResult(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
                ),
            ).reason,
        )
        assertEquals(
            "secure_window",
            assertIs<RawScreenCaptureResult.Unavailable>(
                accessibilityScreenshotFailureResult(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
                ),
            ).reason,
        )
        assertEquals(
            "invalid_display",
            assertIs<RawScreenCaptureResult.Unavailable>(
                accessibilityScreenshotFailureResult(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
                ),
            ).reason,
        )
        assertEquals(
            "accessibility_screenshot_error_999",
            assertIs<RawScreenCaptureResult.Failed>(
                accessibilityScreenshotFailureResult(999),
            ).errorCode,
        )
    }

    @Test
    fun intervalTooShortRetriesExactlyOnceAfterConfiguredDelay() = runBlocking {
        val results = ArrayDeque<RawScreenCaptureResult>().apply {
            add(RawScreenCaptureResult.IntervalTooShort)
            add(RawScreenCaptureResult.Success(frame()))
        }
        val delays = mutableListOf<Long>()
        var captureCount = 0

        val result = captureWithSingleIntervalRetry(
            capturer = RawScreenCapturer {
                captureCount += 1
                results.removeFirst()
            },
            retryDelayMs = 500L,
            delayBeforeRetry = { delays += it },
        )

        assertIs<RawScreenCaptureResult.Success>(result)
        assertEquals(2, captureCount)
        assertEquals(listOf(500L), delays)
    }

    @Test
    fun nonIntervalResultReturnsWithoutDelayOrSecondCapture() = runBlocking {
        val expected = RawScreenCaptureResult.Unavailable("secure_window")
        var captureCount = 0
        val delays = mutableListOf<Long>()

        val result = captureWithSingleIntervalRetry(
            capturer = RawScreenCapturer {
                captureCount += 1
                expected
            },
            retryDelayMs = 500L,
            delayBeforeRetry = { delays += it },
        )

        assertSame(expected, result)
        assertEquals(1, captureCount)
        assertEquals(emptyList(), delays)
    }

    private fun frame() = ArgbFrame(
        width = 1,
        height = 1,
        pixels = intArrayOf(0),
        capturedAt = 1L,
        source = ScreenCaptureSource.Accessibility,
    )
}
