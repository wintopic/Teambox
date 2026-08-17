package com.danmukey.shared.visual

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateSamplingTest {
    @Test
    fun aspectFitSelectionMapsThroughLetterboxing() {
        assertEquals(
            PixelRect(100, 20, 300, 180),
            mapAspectFitSelectionToFrame(
                viewWidth = 200,
                viewHeight = 200,
                frameWidth = 400,
                frameHeight = 200,
                startX = 50f,
                startY = 60f,
                endX = 150f,
                endY = 140f,
            ),
        )
        assertNull(
            mapAspectFitSelectionToFrame(
                viewWidth = 200,
                viewHeight = 200,
                frameWidth = 400,
                frameHeight = 200,
                startX = 10f,
                startY = 10f,
                endX = 20f,
                endY = 20f,
            ),
        )
    }

    @Test
    fun cropCopiesOnlyTheSelectedPixels() {
        val frame = ArgbFrame(
            width = 4,
            height = 3,
            pixels = IntArray(12) { it },
            capturedAt = 10L,
            source = ScreenCaptureSource.Fixture,
        )
        val cropped = frame.crop(PixelRect(1, 1, 3, 3))

        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertContentEquals(intArrayOf(5, 6, 9, 10), cropped.pixels)
    }

    @Test
    fun localTemplatePolicyKeepsIdsAndSamplesBounded() {
        assertTrue(LocalTemplatePolicy.isValidId("testhost-submit.v1"))
        assertFalse(LocalTemplatePolicy.isValidId("发送按钮"))
        assertNull(LocalTemplatePolicy.validateDimensions(320, 180))
        assertTrue(LocalTemplatePolicy.validateDimensions(4, 20) != null)
        assertTrue(LocalTemplatePolicy.validateDimensions(361, 20) != null)
        assertTrue(LocalTemplatePolicy.validateDimensions(300, 300) != null)
    }
}
