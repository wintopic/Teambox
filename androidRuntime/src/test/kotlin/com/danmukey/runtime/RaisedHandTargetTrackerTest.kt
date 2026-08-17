package com.danmukey.runtime

import com.danmukey.shared.visual.PixelRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RaisedHandTargetTrackerTest {
    @Test
    fun rightToLeftTargetRequiresASecondObservationBeforeClicking() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)

        assertTrue(tracker.update(listOf(detection(left = 300, score = 0.91f)), now = 0L).isEmpty())

        val candidate = tracker.update(
            listOf(detection(left = 260, score = 0.87f)),
            now = 100L,
        ).single()

        assertEquals(rect(left = 260), candidate.bounds)
        assertEquals(0.91f, candidate.score)
    }

    @Test
    fun stationaryDetectionsDoNotConfirmARightToLeftTarget() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)

        assertTrue(tracker.update(listOf(detection(left = 200)), now = 0L).isEmpty())
        assertTrue(tracker.update(listOf(detection(left = 200)), now = 100L).isEmpty())
    }

    @Test
    fun rightwardMovementDoesNotConfirmARightToLeftTarget() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)

        assertTrue(tracker.update(listOf(detection(left = 200)), now = 0L).isEmpty())
        assertTrue(tracker.update(listOf(detection(left = 210)), now = 100L).isEmpty())
    }

    @Test
    fun leftwardJitterBelowTheTravelThresholdDoesNotProduceACandidate() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)

        assertTrue(tracker.update(listOf(detection(left = 200)), now = 0L).isEmpty())
        assertTrue(tracker.update(listOf(detection(left = 198)), now = 100L).isEmpty())

        assertEquals(
            197,
            tracker.update(listOf(detection(left = 197)), now = 200L).single().bounds.left,
        )
    }

    @Test
    fun multipleTargetsInTheSameLaneAreTrackedIndependently() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)

        tracker.update(
            listOf(
                detection(left = 500, score = 0.96f),
                detection(left = 300, score = 0.90f),
            ),
            now = 0L,
        )

        val candidates = tracker.update(
            listOf(
                detection(left = 460, score = 0.95f),
                detection(left = 260, score = 0.89f),
            ),
            now = 100L,
        )

        assertEquals(listOf(260, 460), candidates.map { it.bounds.left })
        assertEquals(2, candidates.map { it.trackId }.distinct().size)

        val clicked = candidates.first()
        val pending = candidates.last()
        tracker.markClicked(clicked.trackId)

        val nextCandidates = tracker.update(
            listOf(
                detection(left = 420, score = 0.94f),
                detection(left = 220, score = 0.88f),
            ),
            now = 200L,
        )

        assertEquals(listOf(pending.trackId), nextCandidates.map { it.trackId })
        assertEquals(420, nextCandidates.single().bounds.left)
    }

    @Test
    fun clickedTargetIsNeverOfferedAgainWhileItsTrackIsAlive() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 1_000L)
        tracker.update(listOf(detection(left = 400)), now = 0L)
        val candidate = tracker.update(listOf(detection(left = 360)), now = 100L).single()

        tracker.markClicked(candidate.trackId)

        assertTrue(tracker.update(listOf(detection(left = 320)), now = 200L).isEmpty())
        assertTrue(tracker.update(listOf(detection(left = 280)), now = 300L).isEmpty())
    }

    @Test
    fun trackSurvivesAtTtlBoundaryAndExpiresImmediatelyAfterIt() {
        val tracker = RaisedHandTargetTracker(requiredHits = 2, trackTtlMs = 500L)
        tracker.update(listOf(detection(left = 400)), now = 0L)

        val original = tracker.update(listOf(detection(left = 360)), now = 500L).single()
        tracker.markClicked(original.trackId)

        assertTrue(tracker.update(listOf(detection(left = 320)), now = 1_001L).isEmpty())
        val replacement = tracker.update(listOf(detection(left = 280)), now = 1_101L).single()

        assertNotEquals(original.trackId, replacement.trackId)
        assertEquals(280, replacement.bounds.left)
    }

    private fun detection(left: Int, score: Float = 0.9f): RaisedHandDetection =
        RaisedHandDetection(bounds = rect(left), score = score)

    private fun rect(left: Int): PixelRect = PixelRect(
        left = left,
        top = 100,
        right = left + 24,
        bottom = 119,
    )
}
