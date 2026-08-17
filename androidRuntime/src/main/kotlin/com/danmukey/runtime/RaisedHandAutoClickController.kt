package com.danmukey.runtime

import android.graphics.BitmapFactory
import android.util.Log
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.CaptureBackoff
import com.danmukey.shared.visual.RawScreenCapturer
import com.danmukey.shared.visual.ScreenCaptureCoordinator
import com.danmukey.shared.visual.ScreenCaptureRequest
import com.danmukey.shared.visual.ScreenCaptureResult
import com.danmukey.shared.visual.ScreenCaptureSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class RaisedHandAutoClickController(
    private val service: DanmuAccessibilityService,
    capturer: RawScreenCapturer,
    private val onStatus: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureCoordinator = ScreenCaptureCoordinator(
        capturer = capturer,
        now = clock,
        backoff = CaptureBackoff(
            baselineIntervalMs = SCAN_INTERVAL_MS,
            maximumIntervalMs = MAX_CAPTURE_BACKOFF_MS,
            stableSuccessesToRecover = 2,
        ),
    )
    private val detector = RaisedHandIconDetector(loadTemplate())
    private val tracker = RaisedHandTargetTracker()
    private var scanJob: Job? = null
    private var clickInFlight = false
    private var observedPackage: String? = null
    private var clickCount = 0
    private var lastReportedState: String? = null

    val isRunning: Boolean
        get() = scanJob?.isActive == true

    fun start() {
        if (scanJob?.isActive == true) return
        tracker.reset()
        clickInFlight = false
        observedPackage = null
        reportState("自动识别已开启")
        scanJob = scope.launch {
            while (isActive) {
                val loopStartedAt = clock()
                val packageName = service.activeWindowPackage()
                if (!service.isScreenInteractive || !packageName.isEligibleTargetPackage()) {
                    if (observedPackage != packageName) tracker.reset()
                    observedPackage = packageName
                    delay(IDLE_INTERVAL_MS)
                    continue
                }
                if (observedPackage != packageName) {
                    observedPackage = packageName
                    tracker.reset()
                }

                when (val result = captureCoordinator.capture(ScreenCaptureRequest())) {
                    is ScreenCaptureResult.Success -> handleCapture(result, TARGET_PACKAGE)
                    is ScreenCaptureResult.Deferred -> {
                        val waitMs = (result.retryAt - clock()).coerceIn(1L, MAX_CAPTURE_BACKOFF_MS)
                        delay(waitMs)
                        continue
                    }
                    is ScreenCaptureResult.Unavailable -> {
                        val message = if (result.reason == "projection_session_inactive") {
                            "请回到怪团建授权屏幕识别"
                        } else {
                            "屏幕识别暂不可用"
                        }
                        reportState(message)
                        delay(UNAVAILABLE_RETRY_MS)
                        continue
                    }
                    is ScreenCaptureResult.Failed -> {
                        reportState("屏幕识别正在重试")
                        delay(FAILURE_RETRY_MS)
                        continue
                    }
                }

                val remaining = SCAN_INTERVAL_MS - (clock() - loopStartedAt)
                if (remaining > 0L) delay(remaining)
            }
        }
    }

    fun stop(status: String? = null) {
        scanJob?.cancel()
        scanJob = null
        tracker.reset()
        clickInFlight = false
        observedPackage = null
        status?.let(::reportState)
    }

    fun onConfigurationChanged() {
        tracker.reset()
        observedPackage = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun handleCapture(
        result: ScreenCaptureResult.Success,
        capturedPackage: String,
    ) {
        val detections = withContext(Dispatchers.Default) {
            detector.detect(
                frame = result.capture.frame,
                physicalDensity = service.resources.displayMetrics.density,
            )
        }
        if (service.activeWindowPackage() != capturedPackage) {
            tracker.reset()
            return
        }
        val candidates = tracker.update(detections, clock())
        val candidate = candidates.firstOrNull() ?: return
        if (clickInFlight) return

        val screenBounds = result.capture.toScreen(candidate.bounds)
        val tapX = screenBounds.left + (screenBounds.width * PERSON_TAP_X_FRACTION).roundToInt()
        val tapY = screenBounds.centerY
        clickInFlight = true
        tracker.markClicked(candidate.trackId)
        service.tapPixel(tapX, tapY) { clicked ->
            clickInFlight = false
            if (!clicked) {
                tracker.markClickFailed(candidate.trackId)
                reportState("自动点击失败，正在重试")
                return@tapPixel
            }
            clickCount += 1
            Log.i(TAG, "auto-click count=$clickCount package=$capturedPackage score=${candidate.score}")
            if (clickCount == 1 || clickCount % STATUS_EVERY_CLICKS == 0) {
                reportState("已自动点击 $clickCount 次")
            }
        }
    }

    private fun loadTemplate(): ArgbFrame {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = checkNotNull(
            BitmapFactory.decodeResource(
                service.resources,
                R.drawable.raised_hand_plus_one_template,
                options,
            ),
        ) { "无法加载举手图标模板" }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ArgbFrame(
                width = bitmap.width,
                height = bitmap.height,
                pixels = pixels,
                capturedAt = 0L,
                source = ScreenCaptureSource.LocalAsset,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun String?.isEligibleTargetPackage(): Boolean = this == TARGET_PACKAGE

    private fun reportState(message: String) {
        if (lastReportedState == message) return
        lastReportedState = message
        onStatus(message)
    }

    private companion object {
        const val TAG = "RaisedHandAutoClick"
        const val TARGET_PACKAGE = "com.tencent.qqlive"
        const val SCAN_INTERVAL_MS = 360L
        const val IDLE_INTERVAL_MS = 600L
        const val FAILURE_RETRY_MS = 600L
        const val UNAVAILABLE_RETRY_MS = 1_000L
        const val MAX_CAPTURE_BACKOFF_MS = 2_880L
        const val PERSON_TAP_X_FRACTION = 0.28f
        const val STATUS_EVERY_CLICKS = 10
    }
}
