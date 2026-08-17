package com.danmukey.runtime

import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.LocalTemplateMatcher
import com.danmukey.shared.visual.PixelRect
import com.danmukey.shared.visual.PreparedLocalTemplate
import com.danmukey.shared.visual.ScreenCaptureSource
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Detects the white/grey raised-hand +1 glyph used by Tencent Video danmaku. */
internal class RaisedHandIconDetector(
    sourceTemplate: ArgbFrame,
    private val matcher: LocalTemplateMatcher = LocalTemplateMatcher(),
    private val threshold: Float = 0.78f,
) {
    private val normalizedSourceTemplate = sourceTemplate.trimTransparentPadding()

    private var cachedScaleKey = Int.MIN_VALUE
    private var cachedFrameWidth = -1
    private var cachedFrameHeight = -1
    private var variants: List<Variant> = emptyList()

    // Reused on every pass. The detector is owned and called serially by the controller.
    private var scratchWidth = 0
    private var scratchHeight = 0
    private var frameLumas = ByteArray(0)
    private var frameGradientX = ShortArray(0)
    private var frameGradientY = ShortArray(0)

    init {
        require(threshold in 0f..1f)
    }

    fun detect(frame: ArgbFrame, physicalDensity: Float): List<RaisedHandDetection> {
        require(physicalDensity > 0f)
        ensureVariants(frame, physicalDensity)
        if (variants.isEmpty()) return emptyList()

        prepareFrameFeatures(frame)
        val region = PixelRect(
            left = 0,
            top = 0,
            right = frame.width,
            bottom = (frame.height * SEARCH_HEIGHT_FRACTION).roundToInt()
                .coerceAtLeast(variants.maxOf { it.height })
                .coerceAtMost(frame.height),
        )

        val matches = ArrayList<RaisedHandDetection>(variants.size * 4)
        var bestNeutralGradientScore = 0f
        for (variant in variants) {
            val scan = scanGradientCandidates(frame, region, variant)
            matches.addAll(scan.matches)
            if (scan.bestNeutralScore > bestNeutralGradientScore) {
                bestNeutralGradientScore = scan.bestNeutralScore
            }
        }

        // The luminance matcher rebuilds a full-frame luma buffer per call. Keep it off the normal
        // path and use it only for an ambiguous near-hit, then apply the same strict verifier.
        if (matches.isEmpty() && bestNeutralGradientScore >= SAD_FALLBACK_TRIGGER_SCORE) {
            for (variant in variants) {
                val coarseStride = maxOf(2, minOf(variant.width, variant.height) / 8)
                val sadMatches = matcher.findAll(
                    search = frame,
                    template = variant.preparedTemplate,
                    threshold = threshold,
                    maxResults = MAX_SAD_MATCHES_PER_SCALE,
                    minCenterDistance = (variant.width * SCALE_NMS_FRACTION).roundToInt(),
                    searchRegion = region,
                    coarseStride = coarseStride,
                )
                val fullOffsets = variant.fullGradients.offsets(frame.width)
                val fillOffsets = variant.fillPoints.offsets(frame.width)
                for (sadMatch in sadMatches) {
                    val refined = refineCandidate(
                        left = sadMatch.bounds.left,
                        top = sadMatch.bounds.top,
                        maximumLeft = frame.width - variant.width,
                        maximumTop = region.bottom - variant.height,
                        gradientOffsets = fullOffsets,
                        gradients = variant.fullGradients,
                    )
                    if (refined.score < GRADIENT_SCORE_THRESHOLD) continue
                    val baseIndex = refined.top * frame.width + refined.left
                    if (!isNeutralFill(frame, baseIndex, fillOffsets)) continue
                    matches += RaisedHandDetection(
                        bounds = PixelRect(
                            left = refined.left,
                            top = refined.top,
                            right = refined.left + variant.width,
                            bottom = refined.top + variant.height,
                        ),
                        score = refined.score,
                    )
                }
            }
        }

        return suppressOverlaps(matches, MAX_DETECTIONS)
            .sortedWith(
                compareBy<RaisedHandDetection> { it.bounds.centerX }
                    .thenBy { it.bounds.centerY },
            )
    }

    private fun scanGradientCandidates(
        frame: ArgbFrame,
        region: PixelRect,
        variant: Variant,
    ): VariantScan {
        val maximumLeft = region.right - variant.width
        val maximumTop = region.bottom - variant.height
        if (maximumLeft < region.left || maximumTop < region.top) return VariantScan.EMPTY

        val coarseOffsets = variant.coarseGradients.offsets(frame.width)
        val fullOffsets = variant.fullGradients.offsets(frame.width)
        val fillOffsets = variant.fillPoints.offsets(frame.width)
        val anchors = GradientAnchorCollector(MAX_REFINEMENT_ANCHORS)

        var top = region.top
        while (true) {
            var left = region.left
            while (true) {
                val baseIndex = top * frame.width + left
                val rank = coarseRankAt(baseIndex, coarseOffsets, variant.coarseGradients)
                if (rank > 0.0) anchors.offer(left, top, rank)

                if (left == maximumLeft) break
                left = nextGridPosition(left, maximumLeft, COARSE_STRIDE)
            }
            if (top == maximumTop) break
            top = nextGridPosition(top, maximumTop, COARSE_STRIDE)
        }

        val candidates = ArrayList<RaisedHandDetection>()
        var bestNeutralScore = 0f
        for (index in 0 until anchors.size) {
            val refined = refineCandidate(
                left = anchors.leftAt(index),
                top = anchors.topAt(index),
                maximumLeft = maximumLeft,
                maximumTop = maximumTop,
                gradientOffsets = fullOffsets,
                gradients = variant.fullGradients,
            )
            if (refined.score < SAD_FALLBACK_TRIGGER_SCORE) continue
            val baseIndex = refined.top * frame.width + refined.left
            if (!isNeutralFill(frame, baseIndex, fillOffsets)) continue
            if (refined.score > bestNeutralScore) bestNeutralScore = refined.score
            if (refined.score < GRADIENT_SCORE_THRESHOLD) continue

            candidates += RaisedHandDetection(
                bounds = PixelRect(
                    left = refined.left,
                    top = refined.top,
                    right = refined.left + variant.width,
                    bottom = refined.top + variant.height,
                ),
                score = refined.score,
            )
        }

        return VariantScan(
            matches = suppressOverlaps(candidates, MAX_MATCHES_PER_SCALE),
            bestNeutralScore = bestNeutralScore,
        )
    }

    private fun coarseRankAt(
        baseIndex: Int,
        offsets: IntArray,
        gradients: GradientSamples,
    ): Double {
        var dot = 0L
        var searchEnergy = 0L
        for (index in offsets.indices) {
            val frameIndex = baseIndex + offsets[index]
            val searchX = frameGradientX[frameIndex].toInt()
            val searchY = frameGradientY[frameIndex].toInt()
            dot += gradients.xValues[index].toLong() * searchX +
                gradients.yValues[index].toLong() * searchY
            searchEnergy += searchX.toLong() * searchX + searchY.toLong() * searchY
        }
        if (dot <= 0L || searchEnergy == 0L) return 0.0
        return dot.toDouble() * dot.toDouble() / searchEnergy.toDouble()
    }

    private fun refineCandidate(
        left: Int,
        top: Int,
        maximumLeft: Int,
        maximumTop: Int,
        gradientOffsets: IntArray,
        gradients: GradientSamples,
    ): RefinedCandidate {
        val minimumRefinedLeft = maxOf(0, left - REFINEMENT_RADIUS)
        val maximumRefinedLeft = minOf(maximumLeft, left + REFINEMENT_RADIUS)
        val minimumRefinedTop = maxOf(0, top - REFINEMENT_RADIUS)
        val maximumRefinedTop = minOf(maximumTop, top + REFINEMENT_RADIUS)

        var bestLeft = left
        var bestTop = top
        var bestScore = 0f
        for (refinedTop in minimumRefinedTop..maximumRefinedTop) {
            for (refinedLeft in minimumRefinedLeft..maximumRefinedLeft) {
                val score = gradientScoreAt(
                    baseIndex = refinedTop * scratchWidth + refinedLeft,
                    offsets = gradientOffsets,
                    gradients = gradients,
                )
                if (score > bestScore) {
                    bestScore = score
                    bestLeft = refinedLeft
                    bestTop = refinedTop
                }
            }
        }
        return RefinedCandidate(bestLeft, bestTop, bestScore)
    }

    private fun gradientScoreAt(
        baseIndex: Int,
        offsets: IntArray,
        gradients: GradientSamples,
    ): Float {
        var dot = 0L
        var searchEnergy = 0L
        for (index in offsets.indices) {
            val frameIndex = baseIndex + offsets[index]
            val searchX = frameGradientX[frameIndex].toInt()
            val searchY = frameGradientY[frameIndex].toInt()
            dot += gradients.xValues[index].toLong() * searchX +
                gradients.yValues[index].toLong() * searchY
            searchEnergy += searchX.toLong() * searchX + searchY.toLong() * searchY
        }
        if (dot <= 0L || searchEnergy == 0L || gradients.energy == 0L) return 0f
        val denominator = sqrt(gradients.energy.toDouble() * searchEnergy.toDouble())
        if (denominator == 0.0) return 0f
        return (dot.toDouble() / denominator).toFloat().coerceIn(0f, 1f)
    }

    private fun isNeutralFill(frame: ArgbFrame, baseIndex: Int, fillOffsets: IntArray): Boolean {
        if (fillOffsets.isEmpty()) return false
        var neutralBrightPixels = 0
        var chromaSum = 0L
        for (offset in fillOffsets) {
            val pixelIndex = baseIndex + offset
            val pixel = frame.pixels[pixelIndex]
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
            chromaSum += chroma
            val luma = frameLumas[pixelIndex].toInt() and 0xff
            if (luma >= MIN_FILL_LUMA && chroma <= MAX_NEUTRAL_CHROMA) {
                neutralBrightPixels++
            }
        }
        return neutralBrightPixels.toFloat() / fillOffsets.size >= MIN_NEUTRAL_FILL_FRACTION &&
            chromaSum.toFloat() / fillOffsets.size <= MAX_AVERAGE_CHROMA
    }

    private fun suppressOverlaps(
        candidates: List<RaisedHandDetection>,
        maximumResults: Int,
    ): List<RaisedHandDetection> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending(RaisedHandDetection::score)
        val accepted = ArrayList<RaisedHandDetection>(minOf(maximumResults, sorted.size))
        for (candidate in sorted) {
            var duplicate = false
            for (existing in accepted) {
                val distanceX = existing.bounds.centerX.toLong() - candidate.bounds.centerX
                val distanceY = existing.bounds.centerY.toLong() - candidate.bounds.centerY
                val minimumDistance = maxOf(existing.bounds.width, candidate.bounds.width) *
                    SCALE_NMS_FRACTION.toDouble()
                if ((distanceX * distanceX + distanceY * distanceY).toDouble() <
                    minimumDistance * minimumDistance
                ) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) accepted += candidate
            if (accepted.size == maximumResults) break
        }
        return accepted
    }

    private fun prepareFrameFeatures(frame: ArgbFrame) {
        val pixelCount = frame.width * frame.height
        if (frame.width != scratchWidth || frame.height != scratchHeight) {
            scratchWidth = frame.width
            scratchHeight = frame.height
            frameLumas = ByteArray(pixelCount)
            frameGradientX = ShortArray(pixelCount)
            frameGradientY = ShortArray(pixelCount)
        }

        for (index in frame.pixels.indices) {
            frameLumas[index] = frame.pixels[index].luma().toByte()
        }
        if (frame.width < 3 || frame.height < 3) {
            frameGradientX.fill(0.toShort())
            frameGradientY.fill(0.toShort())
            return
        }

        val lastRow = (frame.height - 1) * frame.width
        for (x in 0 until frame.width) {
            frameGradientX[x] = 0
            frameGradientY[x] = 0
            frameGradientX[lastRow + x] = 0
            frameGradientY[lastRow + x] = 0
        }
        for (y in 1 until frame.height - 1) {
            val row = y * frame.width
            frameGradientX[row] = 0
            frameGradientY[row] = 0
            frameGradientX[row + frame.width - 1] = 0
            frameGradientY[row + frame.width - 1] = 0
            for (x in 1 until frame.width - 1) {
                val index = row + x
                val left = frameLumas[index - 1].toInt() and 0xff
                val right = frameLumas[index + 1].toInt() and 0xff
                val above = frameLumas[index - frame.width].toInt() and 0xff
                val below = frameLumas[index + frame.width].toInt() and 0xff
                frameGradientX[index] = (right - left).toShort()
                frameGradientY[index] = (below - above).toShort()
            }
        }
    }

    private fun ensureVariants(frame: ArgbFrame, physicalDensity: Float) {
        val effectiveDensity = physicalDensity * frame.width / frame.screenWidth.toFloat()
        val scale = effectiveDensity / REFERENCE_DENSITY
        val scaleKey = (scale * 1_000f).roundToInt()
        if (
            scaleKey == cachedScaleKey &&
            frame.width == cachedFrameWidth &&
            frame.height == cachedFrameHeight
        ) {
            return
        }
        cachedScaleKey = scaleKey
        cachedFrameWidth = frame.width
        cachedFrameHeight = frame.height

        val rebuilt = ArrayList<Variant>(TEMPLATE_SCALES.size)
        val dimensions = HashSet<Long>()
        for (variantScale in TEMPLATE_SCALES) {
            val width = (normalizedSourceTemplate.width * scale * variantScale)
                .roundToInt()
                .coerceAtLeast(MIN_TEMPLATE_SIZE)
            val height = (normalizedSourceTemplate.height * scale * variantScale)
                .roundToInt()
                .coerceAtLeast(MIN_TEMPLATE_SIZE)
            if (width > frame.width || height > frame.height) continue
            val dimensionKey = (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
            if (!dimensions.add(dimensionKey)) continue

            val resized = normalizedSourceTemplate.resizeBilinear(width, height)
            val fullGradients = resized.gradientSamples(MAX_FULL_GRADIENT_POINTS)
            if (fullGradients.size < MIN_REQUIRED_GRADIENT_POINTS) continue
            val coarseGradients = resized.gradientSamples(MAX_COARSE_GRADIENT_POINTS)
            val fillPoints = resized.fillSamples(MAX_FILL_POINTS)
            if (coarseGradients.size == 0 || fillPoints.size == 0) continue
            rebuilt += Variant(
                preparedTemplate = matcher.prepare(resized),
                coarseGradients = coarseGradients,
                fullGradients = fullGradients,
                fillPoints = fillPoints,
            )
        }
        variants = rebuilt
    }

    private fun ArgbFrame.gradientSamples(limit: Int): GradientSamples {
        if (width < 3 || height < 3) return GradientSamples.EMPTY
        val lumas = IntArray(pixels.size) { index -> pixels[index].luma() }
        val points = ArrayList<RawGradientPoint>()
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val visible = pixels[index].alpha() >= ALPHA_THRESHOLD ||
                    pixels[index - 1].alpha() >= ALPHA_THRESHOLD ||
                    pixels[index + 1].alpha() >= ALPHA_THRESHOLD ||
                    pixels[index - width].alpha() >= ALPHA_THRESHOLD ||
                    pixels[index + width].alpha() >= ALPHA_THRESHOLD
                if (!visible) continue
                val gradientX = lumas[index + 1] - lumas[index - 1]
                val gradientY = lumas[index + width] - lumas[index - width]
                val magnitudeSquared = gradientX * gradientX + gradientY * gradientY
                if (magnitudeSquared < MIN_TEMPLATE_GRADIENT_SQUARED) continue
                points += RawGradientPoint(x, y, gradientX, gradientY, magnitudeSquared)
            }
        }
        val selected = selectDistributedGradientPoints(points, width, height, limit)
        if (selected.isEmpty()) return GradientSamples.EMPTY
        val xs = IntArray(selected.size)
        val ys = IntArray(selected.size)
        val xValues = ShortArray(selected.size)
        val yValues = ShortArray(selected.size)
        var energy = 0L
        for (index in selected.indices) {
            val point = selected[index]
            xs[index] = point.x
            ys[index] = point.y
            xValues[index] = point.gradientX.toShort()
            yValues[index] = point.gradientY.toShort()
            energy += point.gradientX.toLong() * point.gradientX +
                point.gradientY.toLong() * point.gradientY
        }
        return GradientSamples(xs, ys, xValues, yValues, energy)
    }

    private fun ArgbFrame.fillSamples(limit: Int): PixelSamples {
        val points = ArrayList<RawPixelPoint>()
        for (rowY in 0 until height) {
            val row = rowY * width
            for (x in 0 until width) {
                val pixel = pixels[row + x]
                if (pixel.alpha() >= MIN_FILL_ALPHA && pixel.luma() >= MIN_TEMPLATE_FILL_LUMA) {
                    points += RawPixelPoint(x, rowY)
                }
            }
        }
        if (points.isEmpty()) return PixelSamples.EMPTY
        val sampleCount = minOf(limit, points.size)
        val xs = IntArray(sampleCount)
        val ys = IntArray(sampleCount)
        for (index in 0 until sampleCount) {
            val sourceIndex = if (sampleCount == 1) {
                points.size / 2
            } else {
                (index.toLong() * (points.size - 1) / (sampleCount - 1)).toInt()
            }
            xs[index] = points[sourceIndex].x
            ys[index] = points[sourceIndex].y
        }
        return PixelSamples(xs, ys)
    }

    private fun selectDistributedGradientPoints(
        points: List<RawGradientPoint>,
        width: Int,
        height: Int,
        limit: Int,
    ): List<RawGradientPoint> {
        if (points.size <= limit) return points
        val buckets = Array(GRADIENT_GRID_COLUMNS * GRADIENT_GRID_ROWS) {
            ArrayList<RawGradientPoint>()
        }
        for (point in points) {
            val column = minOf(GRADIENT_GRID_COLUMNS - 1, point.x * GRADIENT_GRID_COLUMNS / width)
            val row = minOf(GRADIENT_GRID_ROWS - 1, point.y * GRADIENT_GRID_ROWS / height)
            buckets[row * GRADIENT_GRID_COLUMNS + column] += point
        }
        for (bucket in buckets) {
            bucket.sortByDescending(RawGradientPoint::magnitudeSquared)
        }

        val selected = ArrayList<RawGradientPoint>(limit)
        val nextIndices = IntArray(buckets.size)
        for (bucketIndex in buckets.indices) {
            if (buckets[bucketIndex].isEmpty()) continue
            selected += buckets[bucketIndex][0]
            nextIndices[bucketIndex] = 1
            if (selected.size == limit) return selected
        }
        while (selected.size < limit) {
            var bestBucket = -1
            var bestMagnitude = -1
            for (bucketIndex in buckets.indices) {
                val nextIndex = nextIndices[bucketIndex]
                if (nextIndex >= buckets[bucketIndex].size) continue
                val magnitude = buckets[bucketIndex][nextIndex].magnitudeSquared
                if (magnitude > bestMagnitude) {
                    bestMagnitude = magnitude
                    bestBucket = bucketIndex
                }
            }
            if (bestBucket < 0) break
            selected += buckets[bestBucket][nextIndices[bestBucket]]
            nextIndices[bestBucket]++
        }
        return selected
    }

    private fun ArgbFrame.trimTransparentPadding(): ArgbFrame {
        var minimumX = width
        var minimumY = height
        var maximumX = -1
        var maximumY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (pixels[row + x].alpha() < ALPHA_THRESHOLD) continue
                minimumX = minOf(minimumX, x)
                minimumY = minOf(minimumY, y)
                maximumX = maxOf(maximumX, x)
                maximumY = maxOf(maximumY, y)
            }
        }
        if (maximumX < minimumX || maximumY < minimumY) return this

        val contentWidth = maximumX - minimumX + 1
        val contentHeight = maximumY - minimumY + 1
        val outputWidth = contentWidth + TEMPLATE_PADDING * 2
        val outputHeight = contentHeight + TEMPLATE_PADDING * 2
        val output = IntArray(outputWidth * outputHeight)
        for (y in 0 until contentHeight) {
            val sourceRow = (minimumY + y) * width + minimumX
            val targetRow = (y + TEMPLATE_PADDING) * outputWidth + TEMPLATE_PADDING
            for (x in 0 until contentWidth) {
                val pixel = pixels[sourceRow + x]
                if (pixel.alpha() >= ALPHA_THRESHOLD) output[targetRow + x] = pixel
            }
        }
        return ArgbFrame(
            width = outputWidth,
            height = outputHeight,
            pixels = output,
            capturedAt = capturedAt,
            source = ScreenCaptureSource.LocalAsset,
        )
    }

    private fun ArgbFrame.resizeBilinear(targetWidth: Int, targetHeight: Int): ArgbFrame {
        if (targetWidth == width && targetHeight == height) return this
        val output = IntArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = ((targetY + 0.5) * height / targetHeight - 0.5)
                .coerceIn(0.0, height - 1.0)
            val y0 = floor(sourceY).toInt()
            val y1 = minOf(height - 1, y0 + 1)
            val fractionY = sourceY - y0
            for (targetX in 0 until targetWidth) {
                val sourceX = ((targetX + 0.5) * width / targetWidth - 0.5)
                    .coerceIn(0.0, width - 1.0)
                val x0 = floor(sourceX).toInt()
                val x1 = minOf(width - 1, x0 + 1)
                val fractionX = sourceX - x0
                val topLeft = pixels[y0 * width + x0]
                val topRight = pixels[y0 * width + x1]
                val bottomLeft = pixels[y1 * width + x0]
                val bottomRight = pixels[y1 * width + x1]
                val alpha = bilinearChannel(
                    topLeft ushr 24,
                    topRight ushr 24,
                    bottomLeft ushr 24,
                    bottomRight ushr 24,
                    fractionX,
                    fractionY,
                )
                if (alpha < ALPHA_THRESHOLD) continue
                val red = bilinearChannel(
                    topLeft ushr 16 and 0xff,
                    topRight ushr 16 and 0xff,
                    bottomLeft ushr 16 and 0xff,
                    bottomRight ushr 16 and 0xff,
                    fractionX,
                    fractionY,
                )
                val green = bilinearChannel(
                    topLeft ushr 8 and 0xff,
                    topRight ushr 8 and 0xff,
                    bottomLeft ushr 8 and 0xff,
                    bottomRight ushr 8 and 0xff,
                    fractionX,
                    fractionY,
                )
                val blue = bilinearChannel(
                    topLeft and 0xff,
                    topRight and 0xff,
                    bottomLeft and 0xff,
                    bottomRight and 0xff,
                    fractionX,
                    fractionY,
                )
                output[targetY * targetWidth + targetX] =
                    alpha shl 24 or (red shl 16) or (green shl 8) or blue
            }
        }
        return ArgbFrame(
            width = targetWidth,
            height = targetHeight,
            pixels = output,
            capturedAt = capturedAt,
            source = ScreenCaptureSource.LocalAsset,
        )
    }

    private fun bilinearChannel(
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        fractionX: Double,
        fractionY: Double,
    ): Int {
        val top = topLeft + (topRight - topLeft) * fractionX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
        return (top + (bottom - top) * fractionY).roundToInt().coerceIn(0, 255)
    }

    private fun nextGridPosition(current: Int, maximum: Int, stride: Int): Int {
        val next = current + stride
        return if (next >= maximum) maximum else next
    }

    private fun Int.alpha(): Int = this ushr 24

    private fun Int.luma(): Int {
        val red = this ushr 16 and 0xff
        val green = this ushr 8 and 0xff
        val blue = this and 0xff
        return (red * 77 + green * 150 + blue * 29) ushr 8
    }

    private data class Variant(
        val preparedTemplate: PreparedLocalTemplate,
        val coarseGradients: GradientSamples,
        val fullGradients: GradientSamples,
        val fillPoints: PixelSamples,
    ) {
        val width: Int get() = preparedTemplate.width
        val height: Int get() = preparedTemplate.height
    }

    private data class VariantScan(
        val matches: List<RaisedHandDetection>,
        val bestNeutralScore: Float,
    ) {
        companion object {
            val EMPTY = VariantScan(emptyList(), 0f)
        }
    }

    private data class RefinedCandidate(val left: Int, val top: Int, val score: Float)

    private data class RawGradientPoint(
        val x: Int,
        val y: Int,
        val gradientX: Int,
        val gradientY: Int,
        val magnitudeSquared: Int,
    )

    private data class RawPixelPoint(val x: Int, val y: Int)

    private data class GradientSamples(
        val xs: IntArray,
        val ys: IntArray,
        val xValues: ShortArray,
        val yValues: ShortArray,
        val energy: Long,
    ) {
        val size: Int get() = xs.size

        fun offsets(frameWidth: Int): IntArray = IntArray(size) { index ->
            ys[index] * frameWidth + xs[index]
        }

        companion object {
            val EMPTY = GradientSamples(IntArray(0), IntArray(0), ShortArray(0), ShortArray(0), 0L)
        }
    }

    private data class PixelSamples(val xs: IntArray, val ys: IntArray) {
        val size: Int get() = xs.size

        fun offsets(frameWidth: Int): IntArray = IntArray(size) { index ->
            ys[index] * frameWidth + xs[index]
        }

        companion object {
            val EMPTY = PixelSamples(IntArray(0), IntArray(0))
        }
    }

    private class GradientAnchorCollector(private val capacity: Int) {
        private val lefts = IntArray(capacity)
        private val tops = IntArray(capacity)
        private val ranks = DoubleArray(capacity)
        var size: Int = 0
            private set

        fun offer(left: Int, top: Int, rank: Double) {
            if (size == capacity && rank <= ranks[size - 1]) return
            var insertionIndex = minOf(size, capacity - 1)
            while (insertionIndex > 0 && rank > ranks[insertionIndex - 1]) {
                if (insertionIndex < capacity) {
                    lefts[insertionIndex] = lefts[insertionIndex - 1]
                    tops[insertionIndex] = tops[insertionIndex - 1]
                    ranks[insertionIndex] = ranks[insertionIndex - 1]
                }
                insertionIndex--
            }
            lefts[insertionIndex] = left
            tops[insertionIndex] = top
            ranks[insertionIndex] = rank
            if (size < capacity) size++
        }

        fun leftAt(index: Int): Int = lefts[index]

        fun topAt(index: Int): Int = tops[index]
    }

    private companion object {
        const val REFERENCE_DENSITY = 2.75f
        const val SEARCH_HEIGHT_FRACTION = 0.70f
        const val GRADIENT_SCORE_THRESHOLD = 0.64f
        const val SAD_FALLBACK_TRIGGER_SCORE = 0.56f
        const val COARSE_STRIDE = 2
        const val REFINEMENT_RADIUS = 1
        const val MAX_REFINEMENT_ANCHORS = 64
        const val MAX_COARSE_GRADIENT_POINTS = 32
        const val MAX_FULL_GRADIENT_POINTS = 192
        const val MIN_REQUIRED_GRADIENT_POINTS = 12
        const val MAX_FILL_POINTS = 64
        const val MAX_MATCHES_PER_SCALE = 24
        const val MAX_SAD_MATCHES_PER_SCALE = 12
        const val MAX_DETECTIONS = 32
        const val SCALE_NMS_FRACTION = 0.65f
        const val ALPHA_THRESHOLD = 48
        const val TEMPLATE_PADDING = 1
        const val MIN_TEMPLATE_SIZE = 8
        const val MIN_TEMPLATE_GRADIENT_SQUARED = 18 * 18
        const val MIN_FILL_ALPHA = 128
        const val MIN_TEMPLATE_FILL_LUMA = 160
        const val MIN_FILL_LUMA = 82
        const val MAX_NEUTRAL_CHROMA = 72
        const val MIN_NEUTRAL_FILL_FRACTION = 0.55f
        const val MAX_AVERAGE_CHROMA = 68f
        const val GRADIENT_GRID_COLUMNS = 6
        const val GRADIENT_GRID_ROWS = 4
        val TEMPLATE_SCALES = floatArrayOf(0.9f, 1f, 1.1f, 1.2f)
    }
}
