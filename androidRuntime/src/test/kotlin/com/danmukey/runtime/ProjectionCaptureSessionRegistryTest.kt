package com.danmukey.runtime

import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.RawScreenCaptureResult
import com.danmukey.shared.visual.ScreenCaptureSource
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionCaptureSessionRegistryTest {
    @AfterTest
    fun tearDown() {
        ProjectionCaptureSessionRegistry.detach("test_cleanup")
    }

    @Test
    fun activeSessionPublishesOnlyAnExplicitlyRequestedFrame() {
        runBlocking {
            val frame = ArgbFrame(
                width = 2,
                height = 1,
                pixels = intArrayOf(1, 2),
                capturedAt = 10L,
                source = ScreenCaptureSource.MediaProjection,
            )
            ProjectionCaptureSessionRegistry.attach {
                ProjectionCaptureSessionRegistry.publish(frame)
            }

            assertTrue(ProjectionCaptureSessionRegistry.isActive)
            val result = assertIs<RawScreenCaptureResult.Success>(
                ProjectionCaptureSessionRegistry.capture(),
            )
            assertEquals(2, result.frame.width)
            assertTrue(result.frame.pixels.contentEquals(intArrayOf(1, 2)))
        }
    }

    @Test
    fun inactiveSessionDoesNotCapture() {
        runBlocking {
            ProjectionCaptureSessionRegistry.detach("not_started")
            assertFalse(ProjectionCaptureSessionRegistry.isActive)
            assertIs<RawScreenCaptureResult.Unavailable>(ProjectionCaptureSessionRegistry.capture())
        }
    }

    @Test
    fun stateListenerReceivesCurrentStateAndLaterTransitions() {
        ProjectionCaptureSessionRegistry.detach("not_started")
        val states = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = states::add

        val unsubscribe = ProjectionCaptureSessionRegistry.addStateListener(listener)
        ProjectionCaptureSessionRegistry.attach {}
        ProjectionCaptureSessionRegistry.detach("stopped")
        unsubscribe()
        ProjectionCaptureSessionRegistry.attach {}

        assertEquals(listOf(false, true, false), states)
    }
}
