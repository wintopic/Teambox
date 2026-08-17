package com.danmukey.shared.visual

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScreenCaptureCoordinatorTest {
    @Test
    fun intervalErrorsBackOffAndStableSuccessesRecover() = runBlocking {
        var now = 0L
        val results = ArrayDeque<RawScreenCaptureResult>().apply {
            add(RawScreenCaptureResult.Success(frame(0L)))
            add(RawScreenCaptureResult.IntervalTooShort)
            add(RawScreenCaptureResult.Success(frame(3_000L)))
            add(RawScreenCaptureResult.Success(frame(5_000L)))
            add(RawScreenCaptureResult.Success(frame(7_000L)))
        }
        val backoff = CaptureBackoff(baselineIntervalMs = 1_000L, maximumIntervalMs = 8_000L)
        val coordinator = ScreenCaptureCoordinator(
            capturer = RawScreenCapturer { results.removeFirst() },
            now = { now },
            backoff = backoff,
        )

        assertIs<ScreenCaptureResult.Success>(coordinator.capture(ScreenCaptureRequest()))
        assertEquals(1_000L, assertIs<ScreenCaptureResult.Deferred>(coordinator.capture(ScreenCaptureRequest())).retryAt)

        now = 1_000L
        val platformDeferred = assertIs<ScreenCaptureResult.Deferred>(coordinator.capture(ScreenCaptureRequest()))
        assertEquals(3_000L, platformDeferred.retryAt)
        assertEquals(2_000L, backoff.currentIntervalMs)

        now = 3_000L
        assertIs<ScreenCaptureResult.Success>(coordinator.capture(ScreenCaptureRequest()))
        now = 5_000L
        assertIs<ScreenCaptureResult.Success>(coordinator.capture(ScreenCaptureRequest()))
        now = 7_000L
        assertIs<ScreenCaptureResult.Success>(coordinator.capture(ScreenCaptureRequest()))
        assertEquals(1_000L, backoff.currentIntervalMs)
        assertEquals(8_000L, backoff.nextAllowedAt)
    }

    private fun frame(capturedAt: Long) = ArgbFrame(
        width = 1,
        height = 1,
        pixels = intArrayOf(0),
        capturedAt = capturedAt,
        source = ScreenCaptureSource.Fixture,
    )
}
