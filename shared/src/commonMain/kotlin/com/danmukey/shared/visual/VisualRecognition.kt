package com.danmukey.shared.visual

import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.NormalizedRect

data class OcrTextHit(
    val text: String,
    val bounds: PixelRect,
    val confidence: Float? = null,
)

fun interface OfflineOcrEngine {
    /** Results stay in memory; callers must not persist full OCR text in diagnostics. */
    suspend fun recognize(frame: ArgbFrame): List<OcrTextHit>
}

fun interface LocalTemplateStore {
    suspend fun load(templateId: String): ArgbFrame?
}

data class TemplateMatch(
    val bounds: PixelRect,
    val score: Float,
)

class PreparedLocalTemplate internal constructor(
    val width: Int,
    val height: Int,
    internal val pointXs: IntArray,
    internal val pointYs: IntArray,
    internal val pointLumas: IntArray,
    internal val sampleCount: Int,
) {
    val comparedPixelCount: Int get() = pointXs.size
}

class LocalTemplateMatcher {
    fun prepare(template: ArgbFrame): PreparedLocalTemplate {
        var visiblePixelCount = 0
        template.pixels.forEach { pixel ->
            if (pixel ushr 24 != 0) visiblePixelCount++
        }

        if (visiblePixelCount == 0) {
            return PreparedLocalTemplate(
                width = template.width,
                height = template.height,
                pointXs = IntArray(0),
                pointYs = IntArray(0),
                pointLumas = IntArray(0),
                sampleCount = 0,
            )
        }

        val sourceXs = IntArray(visiblePixelCount)
        val sourceYs = IntArray(visiblePixelCount)
        val sourceLumas = IntArray(visiblePixelCount)
        var sourceIndex = 0
        for (y in 0 until template.height) {
            val rowStart = y * template.width
            for (x in 0 until template.width) {
                val pixel = template.pixels[rowStart + x]
                if (pixel ushr 24 == 0) continue
                sourceXs[sourceIndex] = x
                sourceYs[sourceIndex] = y
                sourceLumas[sourceIndex] = pixel.luma()
                sourceIndex++
            }
        }

        val sampleCount = minOf(MAX_COARSE_SAMPLES, visiblePixelCount)
        val sampled = BooleanArray(visiblePixelCount)
        val pointXs = IntArray(visiblePixelCount)
        val pointYs = IntArray(visiblePixelCount)
        val pointLumas = IntArray(visiblePixelCount)
        var outputIndex = 0
        for (sampleIndex in 0 until sampleCount) {
            val selectedIndex = if (sampleCount == 1) {
                visiblePixelCount / 2
            } else {
                (sampleIndex.toLong() * (visiblePixelCount - 1) / (sampleCount - 1)).toInt()
            }
            sampled[selectedIndex] = true
            pointXs[outputIndex] = sourceXs[selectedIndex]
            pointYs[outputIndex] = sourceYs[selectedIndex]
            pointLumas[outputIndex] = sourceLumas[selectedIndex]
            outputIndex++
        }
        for (index in 0 until visiblePixelCount) {
            if (sampled[index]) continue
            pointXs[outputIndex] = sourceXs[index]
            pointYs[outputIndex] = sourceYs[index]
            pointLumas[outputIndex] = sourceLumas[index]
            outputIndex++
        }

        return PreparedLocalTemplate(
            width = template.width,
            height = template.height,
            pointXs = pointXs,
            pointYs = pointYs,
            pointLumas = pointLumas,
            sampleCount = sampleCount,
        )
    }

    fun find(
        search: ArgbFrame,
        template: ArgbFrame,
        threshold: Float,
        searchRegion: PixelRect? = null,
        coarseStride: Int = 1,
    ): TemplateMatch? = find(
        search = search,
        template = prepare(template),
        threshold = threshold,
        searchRegion = searchRegion,
        coarseStride = coarseStride,
    )

    fun find(
        search: ArgbFrame,
        template: PreparedLocalTemplate,
        threshold: Float,
        searchRegion: PixelRect? = null,
        coarseStride: Int = 1,
    ): TemplateMatch? = findAll(
        search = search,
        template = template,
        threshold = threshold,
        maxResults = 1,
        minCenterDistance = 0,
        searchRegion = searchRegion,
        coarseStride = coarseStride,
    ).firstOrNull()

    fun findAll(
        search: ArgbFrame,
        template: ArgbFrame,
        threshold: Float,
        maxResults: Int,
        minCenterDistance: Int = 0,
        searchRegion: PixelRect? = null,
        coarseStride: Int = 1,
    ): List<TemplateMatch> = findAll(
        search = search,
        template = prepare(template),
        threshold = threshold,
        maxResults = maxResults,
        minCenterDistance = minCenterDistance,
        searchRegion = searchRegion,
        coarseStride = coarseStride,
    )

    fun findAll(
        search: ArgbFrame,
        template: PreparedLocalTemplate,
        threshold: Float,
        maxResults: Int,
        minCenterDistance: Int = 0,
        searchRegion: PixelRect? = null,
        coarseStride: Int = 1,
    ): List<TemplateMatch> {
        require(threshold in 0f..1f)
        require(maxResults > 0) { "maxResults must be positive" }
        require(minCenterDistance >= 0) { "minCenterDistance cannot be negative" }
        require(coarseStride > 0) { "coarseStride must be positive" }
        if (template.width > search.width || template.height > search.height) return emptyList()
        if (template.comparedPixelCount == 0) return emptyList()

        val fullSearch = PixelRect(0, 0, search.width, search.height)
        val region = if (searchRegion == null) {
            fullSearch
        } else {
            fullSearch.intersection(searchRegion) ?: return emptyList()
        }
        if (region.width < template.width || region.height < template.height) return emptyList()

        val minimumLeft = region.left
        val minimumTop = region.top
        val maximumLeft = region.right - template.width
        val maximumTop = region.bottom - template.height
        val candidateWidth = maximumLeft - minimumLeft + 1
        val candidateHeight = maximumTop - minimumTop + 1
        val candidateCount = candidateWidth * candidateHeight
        val searchLumas = IntArray(search.pixels.size)
        for (index in search.pixels.indices) {
            searchLumas[index] = search.pixels[index].luma()
        }
        val searchOffsets = IntArray(template.comparedPixelCount)
        for (index in searchOffsets.indices) {
            searchOffsets[index] = template.pointYs[index] * search.width + template.pointXs[index]
        }

        val maximumAcceptedDifference =
            ((1f - threshold) * 255f * template.comparedPixelCount).toLong()
        val resultLimit = minOf(maxResults, candidateCount)
        val candidateCapacity = if (minCenterDistance == 0) {
            resultLimit
        } else {
            maxOf(
                resultLimit,
                minOf(
                    resultLimit.toLong() * NMS_CANDIDATE_MULTIPLIER,
                    MAX_NMS_CANDIDATES.toLong(),
                ).toInt(),
            )
        }
        val collector = MatchCandidateCollector(
            searchWidth = search.width,
            searchLumas = searchLumas,
            searchOffsets = searchOffsets,
            template = template,
            maximumAcceptedDifference = maximumAcceptedDifference,
            capacity = candidateCapacity,
            minimumLeft = minimumLeft,
            minimumTop = minimumTop,
            candidateWidth = candidateWidth,
            visited = if (coarseStride > 1) BooleanArray(candidateCount) else null,
        )

        if (coarseStride > 1) {
            val refinementAnchorLimit = minOf(
                MAX_REFINEMENT_ANCHORS,
                maxOf(
                    MIN_REFINEMENT_ANCHORS,
                    minOf(resultLimit.toLong() * REFINEMENT_ANCHORS_PER_RESULT, Int.MAX_VALUE.toLong())
                        .toInt(),
                ),
            )
            val anchorLefts = IntArray(refinementAnchorLimit)
            val anchorTops = IntArray(refinementAnchorLimit)
            val anchorDifferences = LongArray(refinementAnchorLimit)
            var anchorCount = 0
            var top = minimumTop
            while (true) {
                var left = minimumLeft
                while (true) {
                    val coarseDifference = differenceAt(
                        searchLumas = searchLumas,
                        searchOffsets = searchOffsets,
                        templateLumas = template.pointLumas,
                        baseIndex = top * search.width + left,
                        startIndex = 0,
                        endIndex = template.sampleCount,
                        initialDifference = 0L,
                        differenceLimit = Long.MAX_VALUE,
                    )
                    var insertionIndex = anchorCount
                    while (insertionIndex > 0 &&
                        coarseDifference < anchorDifferences[insertionIndex - 1]
                    ) {
                        insertionIndex--
                    }
                    if (insertionIndex < refinementAnchorLimit) {
                        val lastIndex = minOf(anchorCount, refinementAnchorLimit - 1)
                        for (index in lastIndex downTo insertionIndex + 1) {
                            anchorLefts[index] = anchorLefts[index - 1]
                            anchorTops[index] = anchorTops[index - 1]
                            anchorDifferences[index] = anchorDifferences[index - 1]
                        }
                        anchorLefts[insertionIndex] = left
                        anchorTops[insertionIndex] = top
                        anchorDifferences[insertionIndex] = coarseDifference
                        if (anchorCount < refinementAnchorLimit) anchorCount++
                    }

                    if (left == maximumLeft) break
                    left = nextGridPosition(left, maximumLeft, coarseStride)
                }
                if (top == maximumTop) break
                top = nextGridPosition(top, maximumTop, coarseStride)
            }

            val refinementRadius = coarseStride - 1L
            for (anchorIndex in 0 until anchorCount) {
                val refinementLeft = maxOf(
                    minimumLeft.toLong(),
                    anchorLefts[anchorIndex].toLong() - refinementRadius,
                ).toInt()
                val refinementTop = maxOf(
                    minimumTop.toLong(),
                    anchorTops[anchorIndex].toLong() - refinementRadius,
                ).toInt()
                val refinementRight = minOf(
                    maximumLeft.toLong(),
                    anchorLefts[anchorIndex].toLong() + refinementRadius,
                ).toInt()
                val refinementBottom = minOf(
                    maximumTop.toLong(),
                    anchorTops[anchorIndex].toLong() + refinementRadius,
                ).toInt()
                for (refinedTop in refinementTop..refinementBottom) {
                    for (refinedLeft in refinementLeft..refinementRight) {
                        collector.evaluate(refinedLeft, refinedTop)
                    }
                }
            }
        } else {
            for (top in minimumTop..maximumTop) {
                for (left in minimumLeft..maximumLeft) {
                    collector.evaluate(left, top)
                }
            }
        }

        return collector.toMatches(
            maxResults = resultLimit,
            minCenterDistance = minCenterDistance,
        )
    }

    private inner class MatchCandidateCollector(
        private val searchWidth: Int,
        private val searchLumas: IntArray,
        private val searchOffsets: IntArray,
        private val template: PreparedLocalTemplate,
        private val maximumAcceptedDifference: Long,
        private val capacity: Int,
        private val minimumLeft: Int,
        private val minimumTop: Int,
        private val candidateWidth: Int,
        private val visited: BooleanArray?,
    ) {
        private val lefts = IntArray(capacity)
        private val tops = IntArray(capacity)
        private val differences = LongArray(capacity)
        private var count = 0

        fun evaluate(left: Int, top: Int) {
            visited?.let { evaluated ->
                val visitedIndex = (top - minimumTop) * candidateWidth + left - minimumLeft
                if (evaluated[visitedIndex]) return
                evaluated[visitedIndex] = true
            }

            val differenceLimit = if (count < capacity) {
                maximumAcceptedDifference
            } else {
                minOf(maximumAcceptedDifference, differences[0])
            }
            val baseIndex = top * searchWidth + left
            val sampledDifference = differenceAt(
                searchLumas = searchLumas,
                searchOffsets = searchOffsets,
                templateLumas = template.pointLumas,
                baseIndex = baseIndex,
                startIndex = 0,
                endIndex = template.sampleCount,
                initialDifference = 0L,
                differenceLimit = differenceLimit,
            )
            if (sampledDifference > differenceLimit) return
            val exactDifference = differenceAt(
                searchLumas = searchLumas,
                searchOffsets = searchOffsets,
                templateLumas = template.pointLumas,
                baseIndex = baseIndex,
                startIndex = template.sampleCount,
                endIndex = template.comparedPixelCount,
                initialDifference = sampledDifference,
                differenceLimit = differenceLimit,
            )
            if (exactDifference > differenceLimit) return

            if (count < capacity) {
                lefts[count] = left
                tops[count] = top
                differences[count] = exactDifference
                count++
                siftUp(count - 1)
            } else if (isBetterThan(exactDifference, left, top, 0)) {
                lefts[0] = left
                tops[0] = top
                differences[0] = exactDifference
                siftDown(0)
            }
        }

        fun toMatches(maxResults: Int, minCenterDistance: Int): List<TemplateMatch> {
            if (count == 0) return emptyList()
            val order = MutableList(count) { it }
            order.sortWith(
                compareBy<Int>({ differences[it] }, { tops[it] }, { lefts[it] }),
            )
            val accepted = ArrayList<TemplateMatch>(minOf(maxResults, count))
            val acceptedLefts = IntArray(minOf(maxResults, count))
            val acceptedTops = IntArray(minOf(maxResults, count))
            val minimumDistanceSquared = minCenterDistance.toLong() * minCenterDistance
            for (candidateIndex in order) {
                var suppressed = false
                if (minimumDistanceSquared > 0L) {
                    for (acceptedIndex in accepted.indices) {
                        val dx = lefts[candidateIndex].toLong() - acceptedLefts[acceptedIndex]
                        val dy = tops[candidateIndex].toLong() - acceptedTops[acceptedIndex]
                        if (dx * dx + dy * dy < minimumDistanceSquared) {
                            suppressed = true
                            break
                        }
                    }
                }
                if (suppressed) continue

                acceptedLefts[accepted.size] = lefts[candidateIndex]
                acceptedTops[accepted.size] = tops[candidateIndex]
                accepted += TemplateMatch(
                    bounds = PixelRect(
                        left = lefts[candidateIndex],
                        top = tops[candidateIndex],
                        right = lefts[candidateIndex] + template.width,
                        bottom = tops[candidateIndex] + template.height,
                    ),
                    score = 1f - differences[candidateIndex].toFloat() /
                        (255f * template.comparedPixelCount),
                )
                if (accepted.size == maxResults) break
            }
            return accepted
        }

        private fun isBetterThan(difference: Long, left: Int, top: Int, index: Int): Boolean =
            difference < differences[index] ||
                difference == differences[index] && (
                    top < tops[index] || top == tops[index] && left < lefts[index]
                    )

        private fun siftUp(startIndex: Int) {
            var index = startIndex
            while (index > 0) {
                val parent = (index - 1) / 2
                if (!isWorseThan(index, parent)) break
                swap(index, parent)
                index = parent
            }
        }

        private fun siftDown(startIndex: Int) {
            var index = startIndex
            while (true) {
                val leftChild = index * 2 + 1
                if (leftChild >= count) return
                val rightChild = leftChild + 1
                val worseChild = if (rightChild < count && isWorseThan(rightChild, leftChild)) {
                    rightChild
                } else {
                    leftChild
                }
                if (!isWorseThan(worseChild, index)) return
                swap(index, worseChild)
                index = worseChild
            }
        }

        private fun isWorseThan(first: Int, second: Int): Boolean =
            differences[first] > differences[second] ||
                differences[first] == differences[second] && (
                    tops[first] > tops[second] ||
                        tops[first] == tops[second] && lefts[first] > lefts[second]
                    )

        private fun swap(first: Int, second: Int) {
            var temporaryInt = lefts[first]
            lefts[first] = lefts[second]
            lefts[second] = temporaryInt
            temporaryInt = tops[first]
            tops[first] = tops[second]
            tops[second] = temporaryInt
            val temporaryLong = differences[first]
            differences[first] = differences[second]
            differences[second] = temporaryLong
        }
    }

    private fun differenceAt(
        searchLumas: IntArray,
        searchOffsets: IntArray,
        templateLumas: IntArray,
        baseIndex: Int,
        startIndex: Int,
        endIndex: Int,
        initialDifference: Long,
        differenceLimit: Long,
    ): Long {
        var difference = initialDifference
        for (index in startIndex until endIndex) {
            difference += kotlin.math.abs(
                searchLumas[baseIndex + searchOffsets[index]] - templateLumas[index],
            )
            if (difference > differenceLimit) return difference
        }
        return difference
    }

    private fun nextGridPosition(current: Int, maximum: Int, stride: Int): Int {
        val next = current.toLong() + stride
        return if (next >= maximum) maximum else next.toInt()
    }

    private fun Int.luma(): Int {
        val red = this ushr 16 and 0xff
        val green = this ushr 8 and 0xff
        val blue = this and 0xff
        return (red * 77 + green * 150 + blue * 29) ushr 8
    }

    private companion object {
        const val MAX_COARSE_SAMPLES = 64
        const val MIN_REFINEMENT_ANCHORS = 8
        const val MAX_REFINEMENT_ANCHORS = 64
        const val REFINEMENT_ANCHORS_PER_RESULT = 4L
        const val NMS_CANDIDATE_MULTIPLIER = 16L
        const val MAX_NMS_CANDIDATES = 4_096
    }
}

sealed interface VisualLocatorResult {
    val screenBounds: PixelRect
    val confidence: Float

    data class Ocr(
        override val screenBounds: PixelRect,
        override val confidence: Float,
        val matchedText: String,
    ) : VisualLocatorResult

    data class Template(
        override val screenBounds: PixelRect,
        override val confidence: Float,
        val templateId: String,
    ) : VisualLocatorResult
}

class VisualLocatorEngine(
    private val ocrEngine: OfflineOcrEngine,
    private val templateStore: LocalTemplateStore,
    private val templateMatcher: LocalTemplateMatcher = LocalTemplateMatcher(),
) {
    suspend fun locate(locator: LocatorSpec, capture: PreparedCapture): VisualLocatorResult? = when (locator) {
        is LocatorSpec.OcrText -> locateOcr(locator, capture)
        is LocatorSpec.LocalTemplate -> locateTemplate(locator, capture)
        is LocatorSpec.Accessibility,
        is LocatorSpec.CalibrationPoint,
        -> null
    }

    suspend fun locateFirst(
        locators: List<LocatorSpec>,
        captureProvider: suspend (LocatorSpec) -> PreparedCapture?,
        onRecognitionFailure: (LocatorSpec, Throwable) -> Unit = { _, error -> throw error },
    ): VisualLocatorResult? {
        for (locator in locators.visualLocatorsInFallbackOrder()) {
            val capture = captureProvider(locator) ?: continue
            val match = try {
                locate(locator, capture)
            } catch (error: Throwable) {
                onRecognitionFailure(locator, error)
                null
            }
            if (match != null) return match
        }
        return null
    }

    private suspend fun locateOcr(
        locator: LocatorSpec.OcrText,
        capture: PreparedCapture,
    ): VisualLocatorResult.Ocr? {
        val targets = locator.texts.map(::normalizeText).filter(String::isNotEmpty)
        val frameBounds = PixelRect(0, 0, capture.frame.width, capture.frame.height)
        return ocrEngine.recognize(capture.frame)
            .asSequence()
            .filter { hit -> frameBounds.contains(hit.bounds) && !hit.bounds.isEmpty }
            .mapNotNull { hit ->
                val normalized = normalizeText(hit.text)
                val matched = targets.firstOrNull(normalized::contains) ?: return@mapNotNull null
                Triple(hit, matched, hit.confidence ?: 1f)
            }
            .maxByOrNull { it.third }
            ?.let { (hit, _, confidence) ->
                VisualLocatorResult.Ocr(
                    screenBounds = capture.toScreen(hit.bounds),
                    confidence = confidence.coerceIn(0f, 1f),
                    matchedText = hit.text,
                )
            }
    }

    private suspend fun locateTemplate(
        locator: LocatorSpec.LocalTemplate,
        capture: PreparedCapture,
    ): VisualLocatorResult.Template? {
        val template = templateStore.load(locator.templateId) ?: return null
        val match = templateMatcher.find(capture.frame, template, locator.threshold) ?: return null
        return VisualLocatorResult.Template(
            screenBounds = capture.toScreen(match.bounds),
            confidence = match.score,
            templateId = locator.templateId,
        )
    }

    private fun normalizeText(text: String): String = text
        .trim()
        .lowercase()
        .filterNot(Char::isWhitespace)
}

enum class LocatorFallbackStage {
    Accessibility,
    OcrText,
    LocalTemplate,
    CalibrationPoint,
}

val LocatorSpec.fallbackStage: LocatorFallbackStage
    get() = when (this) {
        is LocatorSpec.Accessibility -> LocatorFallbackStage.Accessibility
        is LocatorSpec.OcrText -> LocatorFallbackStage.OcrText
        is LocatorSpec.LocalTemplate -> LocatorFallbackStage.LocalTemplate
        is LocatorSpec.CalibrationPoint -> LocatorFallbackStage.CalibrationPoint
    }

fun List<LocatorSpec>.orderedForFallback(): List<LocatorSpec> = withIndex()
    .sortedWith(compareBy<IndexedValue<LocatorSpec>>({ it.value.fallbackStage.ordinal }, { it.index }))
    .map(IndexedValue<LocatorSpec>::value)

fun List<LocatorSpec>.visualLocatorsInFallbackOrder(): List<LocatorSpec> = orderedForFallback().filter {
    it is LocatorSpec.OcrText || it is LocatorSpec.LocalTemplate
}

fun List<LocatorSpec>.firstCalibrationPoint(): LocatorSpec.CalibrationPoint? =
    orderedForFallback().filterIsInstance<LocatorSpec.CalibrationPoint>().firstOrNull()

fun List<LocatorSpec>.hasVisualLocator(): Boolean = any {
    it is LocatorSpec.OcrText || it is LocatorSpec.LocalTemplate
}

fun captureRequestForVisualLocator(
    locator: LocatorSpec,
    ignoredRegions: List<NormalizedRect>,
    overlayRegions: List<PixelRect>,
): ScreenCaptureRequest = ScreenCaptureRequest(
    cropRegion = when (locator) {
        is LocatorSpec.OcrText -> locator.region
        is LocatorSpec.LocalTemplate -> locator.region
        is LocatorSpec.Accessibility,
        is LocatorSpec.CalibrationPoint,
        -> null
    },
    ignoredRegions = ignoredRegions,
    overlayRegions = overlayRegions,
)
