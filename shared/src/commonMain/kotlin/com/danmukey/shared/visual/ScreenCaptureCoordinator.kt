package com.danmukey.shared.visual

class CaptureBackoff(
    val baselineIntervalMs: Long = 1_000L,
    val maximumIntervalMs: Long = 16_000L,
    private val stableSuccessesToRecover: Int = 3,
) {
    init {
        require(baselineIntervalMs > 0)
        require(maximumIntervalMs >= baselineIntervalMs)
        require(stableSuccessesToRecover > 0)
    }

    var currentIntervalMs: Long = baselineIntervalMs
        private set
    var nextAllowedAt: Long = Long.MIN_VALUE
        private set
    private var stableSuccesses: Int = 0

    fun retryAt(now: Long): Long? = nextAllowedAt.takeIf { it > now }

    fun recordSuccess(completedAt: Long) {
        stableSuccesses += 1
        if (stableSuccesses >= stableSuccessesToRecover && currentIntervalMs > baselineIntervalMs) {
            currentIntervalMs = maxOf(baselineIntervalMs, currentIntervalMs / 2)
            stableSuccesses = 0
        }
        nextAllowedAt = completedAt.saturatedAdd(currentIntervalMs)
    }

    fun recordIntervalTooShort(observedAt: Long) {
        stableSuccesses = 0
        currentIntervalMs = minOf(maximumIntervalMs, currentIntervalMs.saturatedMultiply(2))
        nextAllowedAt = observedAt.saturatedAdd(currentIntervalMs)
    }

    fun recordFailure(observedAt: Long) {
        stableSuccesses = 0
        nextAllowedAt = observedAt.saturatedAdd(currentIntervalMs)
    }

    private fun Long.saturatedAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private fun Long.saturatedMultiply(factor: Long): Long =
        if (this > Long.MAX_VALUE / factor) Long.MAX_VALUE else this * factor
}

class ScreenCaptureCoordinator(
    private val capturer: RawScreenCapturer,
    private val now: () -> Long,
    private val backoff: CaptureBackoff = CaptureBackoff(),
) {
    suspend fun capture(request: ScreenCaptureRequest): ScreenCaptureResult {
        val requestedAt = now()
        backoff.retryAt(requestedAt)?.let { retryAt ->
            return ScreenCaptureResult.Deferred(retryAt, "capture_backoff_active")
        }

        return when (val result = capturer.capture()) {
            is RawScreenCaptureResult.Success -> runCatching {
                CapturePreprocessor.prepare(result.frame, request)
            }.fold(
                onSuccess = { prepared ->
                    backoff.recordSuccess(now())
                    ScreenCaptureResult.Success(prepared)
                },
                onFailure = {
                    backoff.recordFailure(now())
                    ScreenCaptureResult.Failed("capture_preprocess_failed", recoverable = false)
                },
            )

            RawScreenCaptureResult.IntervalTooShort -> {
                backoff.recordIntervalTooShort(now())
                ScreenCaptureResult.Deferred(backoff.nextAllowedAt, "platform_interval_too_short")
            }

            is RawScreenCaptureResult.Unavailable -> ScreenCaptureResult.Unavailable(result.reason)
            is RawScreenCaptureResult.Failed -> {
                backoff.recordFailure(now())
                ScreenCaptureResult.Failed(result.errorCode, result.recoverable)
            }
        }
    }
}
