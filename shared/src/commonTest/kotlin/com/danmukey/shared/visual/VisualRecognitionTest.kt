package com.danmukey.shared.visual

import com.danmukey.shared.model.LocatorSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VisualRecognitionTest {
    @Test
    fun localTemplateMatcherReturnsExactPatternBounds() {
        val white = -0x1
        val black = -0x1000000
        val searchPixels = IntArray(5 * 4) { white }
        listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2).forEach { (x, y) ->
            searchPixels[y * 5 + x] = black
        }
        val search = frame(5, 4, searchPixels)
        val template = frame(2, 2, IntArray(4) { black })

        val match = assertNotNull(LocalTemplateMatcher().find(search, template, threshold = 0.99f))
        assertEquals(PixelRect(1, 1, 3, 3), match.bounds)
        assertEquals(1f, match.score)
    }

    @Test
    fun transparentTemplatePixelsAreIgnoredByPreparedMatcher() {
        val white = -0x1
        val black = -0x1000000
        val transparent = 0x00000000
        val searchPixels = IntArray(7 * 5) { white }
        listOf(3 to 1, 2 to 2, 3 to 2, 4 to 2, 3 to 3).forEach { (x, y) ->
            searchPixels[y * 7 + x] = black
        }
        val template = frame(
            width = 3,
            height = 3,
            pixels = intArrayOf(
                transparent, black, transparent,
                black, black, black,
                transparent, black, transparent,
            ),
        )
        val matcher = LocalTemplateMatcher()
        val preparedTemplate = matcher.prepare(template)

        assertEquals(5, preparedTemplate.comparedPixelCount)
        val match = assertNotNull(
            matcher.find(
                search = frame(7, 5, searchPixels),
                template = preparedTemplate,
                threshold = 1f,
            ),
        )
        assertEquals(PixelRect(2, 1, 5, 4), match.bounds)
        assertEquals(1f, match.score)
    }

    @Test
    fun coarseStrideRefinesToOddCoordinateInsideSearchRegion() {
        val white = -0x1
        val black = -0x1000000
        val searchPixels = IntArray(11 * 9) { white }
        for (y in 0..2) {
            for (x in 0..2) searchPixels[y * 11 + x] = black
        }
        for (y in 3..5) {
            for (x in 5..7) searchPixels[y * 11 + x] = black
        }
        val matcher = LocalTemplateMatcher()
        val preparedTemplate = matcher.prepare(frame(3, 3, IntArray(9) { black }))

        val match = assertNotNull(
            matcher.find(
                search = frame(11, 9, searchPixels),
                template = preparedTemplate,
                threshold = 0.99f,
                searchRegion = PixelRect(3, 1, 10, 8),
                coarseStride = 4,
            ),
        )

        assertEquals(PixelRect(5, 3, 8, 6), match.bounds)
        assertEquals(1f, match.score)
    }

    @Test
    fun findAllSortsMatchesAndSuppressesNearbyCenters() {
        val white = -0x1
        val black = -0x1000000
        val searchPixels = IntArray(12 * 3) { white }
        listOf(1 to 1, 2 to 1, 8 to 1, 9 to 1).forEach { (x, y) ->
            searchPixels[y * 12 + x] = black
        }
        val matcher = LocalTemplateMatcher()

        val matches = matcher.findAll(
            search = frame(12, 3, searchPixels),
            template = matcher.prepare(frame(1, 1, intArrayOf(black))),
            threshold = 1f,
            maxResults = 2,
            minCenterDistance = 3,
        )

        assertEquals(
            listOf(PixelRect(1, 1, 2, 2), PixelRect(8, 1, 9, 2)),
            matches.map(TemplateMatch::bounds),
        )
        assertEquals(listOf(1f, 1f), matches.map(TemplateMatch::score))
    }

    @Test
    fun coarseFindAllRefinesMultipleOffGridMatches() {
        val white = -0x1
        val black = -0x1000000
        val searchPixels = IntArray(16 * 8) { white }
        listOf(3 to 3, 11 to 3).forEach { (left, top) ->
            for (y in top until top + 2) {
                for (x in left until left + 2) searchPixels[y * 16 + x] = black
            }
        }
        val matcher = LocalTemplateMatcher()

        val matches = matcher.findAll(
            search = frame(16, 8, searchPixels),
            template = matcher.prepare(frame(2, 2, IntArray(4) { black })),
            threshold = 1f,
            maxResults = 2,
            minCenterDistance = 4,
            coarseStride = 4,
        )

        assertEquals(
            listOf(PixelRect(3, 3, 5, 5), PixelRect(11, 3, 13, 5)),
            matches.map(TemplateMatch::bounds),
        )
    }

    @Test
    fun visualLocatorMapsOcrAndTemplateResultsBackToScreen() = runBlocking {
        val capture = PreparedCapture(
            frame = frame(4, 4, IntArray(16) { -0x1 }),
            screenRegion = PixelRect(100, 200, 104, 204),
        )
        val blackTemplate = frame(1, 1, intArrayOf(-0x1000000))
        val engine = VisualLocatorEngine(
            ocrEngine = OfflineOcrEngine {
                listOf(OcrTextHit("立即 发送", PixelRect(1, 1, 3, 2), confidence = 0.91f))
            },
            templateStore = LocalTemplateStore { id -> if (id == "send") blackTemplate else null },
        )

        val ocr = assertNotNull(
            engine.locate(LocatorSpec.OcrText(listOf("发送")), capture) as? VisualLocatorResult.Ocr,
        )
        assertEquals(PixelRect(101, 201, 103, 202), ocr.screenBounds)
        assertEquals(0.91f, ocr.confidence)

        val templateCapture = capture.copy(
            frame = frame(
                4,
                4,
                IntArray(16) { index -> if (index == 2 * 4 + 3) -0x1000000 else -0x1 },
            ),
        )
        val template = assertNotNull(
            engine.locate(
                LocatorSpec.LocalTemplate("send", threshold = 0.99f),
                templateCapture,
            ) as? VisualLocatorResult.Template,
        )
        assertEquals(PixelRect(103, 202, 104, 203), template.screenBounds)
    }

    @Test
    fun fallbackOrderIsAccessibilityThenOcrThenTemplateThenCalibration() {
        val calibration = LocatorSpec.CalibrationPoint(0.5f, 0.5f)
        val template = LocatorSpec.LocalTemplate("send", threshold = 0.92f)
        val accessibility = LocatorSpec.Accessibility(resourceId = "app:id/send")
        val ocr = LocatorSpec.OcrText(listOf("发送"))

        assertEquals(
            listOf(accessibility, ocr, template, calibration),
            listOf(calibration, template, accessibility, ocr).orderedForFallback(),
        )
        assertEquals(calibration, listOf(template, calibration, ocr).firstCalibrationPoint())
    }

    @Test
    fun locateFirstFallsThroughOcrToTemplateAndReturnsNullWhenBothMiss() = runBlocking {
        val black = -0x1000000
        val white = -0x1
        val template = frame(1, 1, intArrayOf(black))
        val engine = VisualLocatorEngine(
            ocrEngine = OfflineOcrEngine { emptyList() },
            templateStore = LocalTemplateStore { template },
        )
        val locators = listOf(
            LocatorSpec.LocalTemplate("send", threshold = 0.99f),
            LocatorSpec.OcrText(listOf("发送")),
        )
        val hitCapture = PreparedCapture(
            frame = frame(2, 2, intArrayOf(white, white, white, black)),
            screenRegion = PixelRect(10, 20, 12, 22),
        )

        val hit = engine.locateFirst(locators, captureProvider = { hitCapture })
        assertIs<VisualLocatorResult.Template>(hit)
        assertEquals(PixelRect(11, 21, 12, 22), hit.screenBounds)

        val missCapture = hitCapture.copy(frame = frame(2, 2, IntArray(4) { white }))
        assertNull(engine.locateFirst(locators, captureProvider = { missCapture }))
    }

    private fun frame(width: Int, height: Int, pixels: IntArray) = ArgbFrame(
        width = width,
        height = height,
        pixels = pixels,
        capturedAt = 1L,
        source = ScreenCaptureSource.Fixture,
    )
}
