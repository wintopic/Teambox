package com.danmukey.runtime

import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.RawScreenCaptureResult
import com.danmukey.shared.visual.RawScreenCapturer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Process-local bridge between the foreground MediaProjection service and visual locators. */
object ProjectionCaptureSessionRegistry : RawScreenCapturer {
    private val lock = Any()
    private val pending = linkedSetOf<CompletableDeferred<RawScreenCaptureResult>>()
    private val stateListeners = linkedMapOf<Long, (Boolean) -> Unit>()
    private var nextStateListenerId = 0L
    private var requestFrame: (() -> Unit)? = null

    @Volatile
    var isActive: Boolean = false
        private set

    fun attach(requestFrame: () -> Unit) {
        val (replaced, stateChanged) = synchronized(lock) {
            val old = pending.toList()
            pending.clear()
            this.requestFrame = requestFrame
            val changed = !isActive
            isActive = true
            old to changed
        }
        replaced.forEach { it.complete(RawScreenCaptureResult.Unavailable("projection_session_replaced")) }
        if (stateChanged) notifyStateListeners(true)
    }

    fun detach(reason: String = "projection_session_stopped") {
        val (waiting, stateChanged) = synchronized(lock) {
            requestFrame = null
            val changed = isActive
            isActive = false
            pending.toList().also { pending.clear() } to changed
        }
        waiting.forEach { it.complete(RawScreenCaptureResult.Unavailable(reason)) }
        if (stateChanged) notifyStateListeners(false)
    }

    fun addStateListener(listener: (Boolean) -> Unit): () -> Unit {
        val listenerId = synchronized(lock) {
            val id = nextStateListenerId++
            stateListeners[id] = listener
            listener(isActive)
            id
        }
        return {
            synchronized(lock) { stateListeners.remove(listenerId) }
        }
    }

    fun publish(frame: ArgbFrame) {
        val waiting = synchronized(lock) {
            pending.toList().also { pending.clear() }
        }
        waiting.forEach { it.complete(RawScreenCaptureResult.Success(frame)) }
    }

    override suspend fun capture(): RawScreenCaptureResult {
        val deferred = CompletableDeferred<RawScreenCaptureResult>()
        val trigger = synchronized(lock) {
            val current = requestFrame
            if (current != null) pending += deferred
            current
        } ?: return RawScreenCaptureResult.Unavailable("projection_session_inactive")

        runCatching(trigger).onFailure {
            synchronized(lock) { pending -= deferred }
            return RawScreenCaptureResult.Failed("projection_capture_request_failed")
        }
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { deferred.await() }
            ?: RawScreenCaptureResult.Failed("projection_capture_timeout")
                .also { synchronized(lock) { pending -= deferred } }
    }

    private fun notifyStateListeners(active: Boolean) {
        val listeners = synchronized(lock) { stateListeners.values.toList() }
        listeners.forEach { listener -> runCatching { listener(active) } }
    }

    private const val CAPTURE_TIMEOUT_MS = 2_500L
}
