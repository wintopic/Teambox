package com.danmukey.shared.visual

import com.danmukey.shared.model.NormalizedRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CapturePreprocessorTest {
    @Test
    fun cropMasksIgnoredAndPhysicalOverlayRegionsOnLowResolutionFrame() {
        val frame = fixtureFrame(width = 4, height = 4, screenWidth = 8, screenHeight = 8)
        val prepared = CapturePreprocessor.prepare(
            frame,
            ScreenCaptureRequest(
                cropRegion = NormalizedRect(0.25f, 0.25f, 1f, 1f),
                ignoredRegions = listOf(NormalizedRect(0.5f, 0.5f, 1f, 1f)),
                overlayRegions = listOf(PixelRect(2, 2, 4, 4)),
                maskColor = -1,
            ),
        )

        assertEquals(PixelRect(2, 2, 8, 8), prepared.screenRegion)
        assertEquals(3, prepared.frame.width)
        assertEquals(3, prepared.frame.height)
        assertEquals(6, prepared.frame.screenWidth)
        assertEquals(6, prepared.frame.screenHeight)
        assertEquals(-1, prepared.frame[0, 0])
        assertEquals(frame[2, 1], prepared.frame[1, 0])
        assertEquals(-1, prepared.frame[1, 1])
        assertEquals(-1, prepared.frame[2, 2])
        assertEquals(PixelRect(4, 4, 6, 6), prepared.toScreen(PixelRect(1, 1, 2, 2)))
    }

    @Test
    fun fullFrameWithoutMasksIsZeroCopyAndStillMapsToPhysicalScreen() {
        val frame = fixtureFrame(width = 4, height = 3, screenWidth = 8, screenHeight = 6)

        val prepared = CapturePreprocessor.prepare(frame, ScreenCaptureRequest())

        assertSame(frame, prepared.frame)
        assertSame(frame.pixels, prepared.frame.pixels)
        assertEquals(PixelRect(0, 0, 8, 6), prepared.screenRegion)
        assertEquals(PixelRect(2, 2, 6, 4), prepared.toScreen(PixelRect(1, 1, 3, 2)))
    }

    @Test
    fun normalizedBoundsRoundOutwardWithoutLeavingFrame() {
        val rect = NormalizedRect(0.11f, 0.11f, 0.89f, 0.89f).toPixelRect(10, 10)
        assertEquals(PixelRect(1, 1, 9, 9), rect)
        assertTrue(PixelRect(0, 0, 10, 10).contains(rect))
    }

    private fun fixtureFrame(
        width: Int,
        height: Int,
        screenWidth: Int = width,
        screenHeight: Int = height,
    ): ArgbFrame = ArgbFrame(
        width = width,
        height = height,
        pixels = IntArray(width * height) { it },
        capturedAt = 1L,
        source = ScreenCaptureSource.Fixture,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
    )
}
