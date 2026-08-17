package com.danmukey.runtime

import com.danmukey.shared.visual.PixelRect
import kotlin.math.abs

internal data class RaisedHandDetection(
    val bounds: PixelRect,
    val score: Float,
)

internal data class RaisedHandClickCandidate(
    val trackId: Long,
    val bounds: PixelRect,
    val score: Float,
)

/** Tracks right-to-left danmu glyphs so every icon is clicked at most once. */
internal class RaisedHandTargetTracker(
    private val requiredHits: Int = 2,
    private val trackTtlMs: Long = 1_500L,
) {
    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1L

    init {
        require(requiredHits > 0)
        require(trackTtlMs > 0L)
    }

    fun update(detections: List<RaisedHandDetection>, now: Long): List<RaisedHandClickCandidate> {
        tracks.removeAll { now - it.lastSeenAt > trackTtlMs }
        val unmatchedTracks = tracks.toMutableSet()

        detections.sortedByDescending(RaisedHandDetection::score).forEach { detection ->
            val matched = unmatchedTracks
                .asSequence()
                .filter { track -> track.canAccept(detection.bounds, now) }
                .minByOrNull { track -> track.matchingDistance(detection.bounds, now) }
            if (matched == null) {
                tracks += Track(
                    id = nextTrackId++,
                    bounds = detection.bounds,
                    initialCenterX = detection.bounds.centerX,
                    score = detection.score,
                    hits = 1,
                    lastSeenAt = now,
                )
            } else {
                unmatchedTracks.remove(matched)
                matched.update(detection, now)
            }
        }

        return tracks
            .asSequence()
            .filter { track ->
                !track.clicked &&
                    track.hits >= requiredHits &&
                    track.lastSeenAt == now &&
                    track.hasConfirmedLeftwardMotion()
            }
            .sortedWith(compareBy<Track> { it.bounds.centerX }.thenByDescending { it.score })
            .map { track ->
                RaisedHandClickCandidate(
                    trackId = track.id,
                    bounds = track.bounds,
                    score = track.score,
                )
            }
            .toList()
    }

    fun markClicked(trackId: Long) {
        tracks.firstOrNull { it.id == trackId }?.clicked = true
    }

    fun markClickFailed(trackId: Long) {
        tracks.firstOrNull { it.id == trackId }?.let { track ->
            track.clicked = false
            track.hits = 1
        }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1L
    }

    private data class Track(
        val id: Long,
        var bounds: PixelRect,
        val initialCenterX: Int,
        var score: Float,
        var hits: Int,
        var lastSeenAt: Long,
        var velocityXPerMs: Float = 0f,
        var clicked: Boolean = false,
    ) {
        fun hasConfirmedLeftwardMotion(): Boolean {
            val minimumTravel = maxOf(2f, bounds.width * MIN_LEFTWARD_TRAVEL_FRACTION)
            return initialCenterX - bounds.centerX >= minimumTravel
        }

        fun canAccept(candidate: PixelRect, now: Long): Boolean {
            val elapsed = (now - lastSeenAt).coerceAtLeast(1L)
            val predictedX = bounds.centerX + velocityXPerMs * elapsed
            val width = maxOf(bounds.width, candidate.width).coerceAtLeast(1)
            val height = maxOf(bounds.height, candidate.height).coerceAtLeast(1)
            val dxFromLast = candidate.centerX - bounds.centerX
            val sameLane = abs(candidate.centerY - bounds.centerY) <= height * 0.8f + 2f
            val plausibleDirection = dxFromLast >= -width * 6f && dxFromLast <= width * 1.5f
            val nearPrediction = abs(candidate.centerX - predictedX) <= width * 3.5f + 3f
            return sameLane && plausibleDirection && nearPrediction
        }

        fun matchingDistance(candidate: PixelRect, now: Long): Float {
            val elapsed = (now - lastSeenAt).coerceAtLeast(1L)
            val predictedX = bounds.centerX + velocityXPerMs * elapsed
            return abs(candidate.centerX - predictedX) + abs(candidate.centerY - bounds.centerY) * 2f
        }

        fun update(detection: RaisedHandDetection, now: Long) {
            val elapsed = (now - lastSeenAt).coerceAtLeast(1L)
            val observedVelocity = (detection.bounds.centerX - bounds.centerX).toFloat() / elapsed
            velocityXPerMs = if (hits <= 1) {
                observedVelocity
            } else {
                velocityXPerMs * 0.6f + observedVelocity * 0.4f
            }
            bounds = detection.bounds
            score = maxOf(score, detection.score)
            hits += 1
            lastSeenAt = now
        }
    }

    private companion object {
        const val MIN_LEFTWARD_TRAVEL_FRACTION = 0.12f
    }
}
