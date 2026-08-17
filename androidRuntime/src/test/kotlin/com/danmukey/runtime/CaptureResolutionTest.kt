package com.danmukey.runtime

import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.CapturePreprocessor
import com.danmukey.shared.visual.PixelRect
import com.danmukey.shared.visual.ScreenCaptureRequest
import com.danmukey.shared.visual.ScreenCaptureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureResolutionTest {
    @Test
    fun targetsApproximatelyOnePixelPerDpAndPreservesAspectRatio() {
        assertEquals(
            CaptureResolution(width = 360, height = 800),
            chooseCaptureResolution(
                screenWidth = 1_080,
                screenHeight = 2_400,
                density = 3f,
            ),
        )
    }

    @Test
    fun clampsToConfiguredMaximumWidth() {
        assertEquals(
            CaptureResolution(width = 720, height = 332),
            chooseCaptureResolution(
                screenWidth = 2_340,
                screenHeight = 1_080,
                density = 2f,
            ),
        )
    }

    @Test
    fun clampsToMinimumWithoutUpscalingASmallerScreen() {
        assertEquals(
            CaptureResolution(width = 320, height = 711),
            chooseCaptureResolution(
                screenWidth = 1_080,
                screenHeight = 2_400,
                density = 5f,
            ),
        )
        assertEquals(
            CaptureResolution(width = 200, height = 101),
            chooseCaptureResolution(
                screenWidth = 200,
                screenHeight = 101,
                density = 4f,
            ),
        )
    }

    @Test
    fun rejectsInvalidDimensionsDensityAndWidthLimits() {
        assertFailsWith<IllegalArgumentException> {
            chooseCaptureResolution(screenWidth = 0, screenHeight = 100, density = 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            chooseCaptureResolution(screenWidth = 100, screenHeight = 100, density = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            chooseCaptureResolution(
                screenWidth = 100,
                screenHeight = 100,
                density = 1f,
                minimumWidth = 200,
                maximumWidth = 100,
            )
        }
    }

    @Test
    fun reducedCaptureCoordinatesMapBackToPhysicalScreenWithOutwardRounding() {
        val resolution = chooseCaptureResolution(
            screenWidth = 2_340,
            screenHeight = 1_080,
            density = 2f,
        )
        val frame = ArgbFrame(
            width = resolution.width,
            height = resolution.height,
            pixels = IntArray(resolution.width * resolution.height),
            capturedAt = 1L,
            source = ScreenCaptureSource.Fixture,
            screenWidth = 2_340,
            screenHeight = 1_080,
        )

        val capture = CapturePreprocessor.prepare(frame, ScreenCaptureRequest())

        assertEquals(PixelRect(0, 0, 2_340, 1_080), capture.screenRegion)
        assertEquals(PixelRect(3, 3, 7, 7), capture.toScreen(PixelRect(1, 1, 2, 2)))
        assertEquals(
            PixelRect(2_336, 1_076, 2_340, 1_080),
            capture.toScreen(
                PixelRect(
                    left = resolution.width - 1,
                    top = resolution.height - 1,
                    right = resolution.width,
                    bottom = resolution.height,
                ),
            ),
        )
    }
}
