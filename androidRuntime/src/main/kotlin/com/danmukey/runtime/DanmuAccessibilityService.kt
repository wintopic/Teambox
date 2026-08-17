package com.danmukey.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.danmukey.shared.accessibility.AccessibilityBounds
import com.danmukey.shared.accessibility.AccessibilityFixtureRedactor
import com.danmukey.shared.accessibility.AccessibilityNodeFixture
import com.danmukey.shared.accessibility.AccessibilityTreeFixture
import com.danmukey.shared.data.DanmuKeyRepository
import com.danmukey.shared.content.PlaybackTimeParser
import com.danmukey.shared.content.SceneFollower
import com.danmukey.shared.automation.TargetProfileSelector
import com.danmukey.shared.automation.TargetRuntimeContext
import com.danmukey.shared.model.AndroidDatabaseDriverFactory
import com.danmukey.shared.model.AutomationTaskRecord
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.model.ContentFollowState
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.Orientation
import com.danmukey.shared.model.Platform
import com.danmukey.shared.model.SendResult
import com.danmukey.shared.model.TargetCapabilityLevel
import com.danmukey.shared.model.createDatabase
import com.danmukey.shared.visual.PixelRect
import com.danmukey.shared.visual.RawScreenCaptureResult
import com.danmukey.shared.visual.RawScreenCapturer
import com.danmukey.shared.visual.ScreenCaptureCoordinator
import com.danmukey.shared.visual.VisualLocatorEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DanmuAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var repository: DanmuKeyRepository
    private lateinit var taskController: AndroidTaskController
    private lateinit var accessibilityScreenshotCapturer: AccessibilityScreenshotCapturer
    private lateinit var screenshotExecutor: ExecutorService
    private lateinit var raisedHandAutoClickController: RaisedHandAutoClickController
    private lateinit var accessibilityFixtureStore: AndroidAccessibilityFixtureStore
    private var taskControlView: PlusOneButtonView? = null
    private var calibrationView: FrameLayout? = null
    private var calibrationSession: CalibrationSession? = null
    private var templateCaptureJob: Job? = null
    private var templateActivityLaunchPending = false
    private var taskControlRequested = false
    private var plusOneSending = false
    private var lastTaskStatus = "待命"
    private var currentPackage: String? = null
    private var targetProfiles: List<TargetProfile> = emptyList()
    private var pendingFollowPackage: String? = null
    private var lastFollowObservationAt: Long = 0L
    private var recoveredInterruptedTaskCount: Int = 0
    private val followObservationRunnable = Runnable {
        val packageName = pendingFollowPackage.orEmpty()
        pendingFollowPackage = null
        if (packageName.isNotBlank()) observeContentFollow(packageName)
    }
    private val templateActivityLaunchTimeoutRunnable = Runnable {
        if (!templateActivityLaunchPending) return@Runnable
        templateActivityLaunchPending = false
        if (TemplateCaptureDraftRegistry.peek() != null) {
            showTaskControl()
            updateTaskStatus("系统阻止打开模板选区，请再次点击“打开模板选区”")
        }
    }
    private val resetPlusOneFeedbackRunnable = Runnable {
        plusOneSending = false
        taskControlView?.setActive(false)
    }

    internal val currentForegroundPackage: String?
        get() = currentPackage

    internal fun activeWindowPackage(): String? {
        rootInActiveWindow?.let { root ->
            try {
                root.packageName?.toString()?.takeIf(String::isNotBlank)?.let { return it }
            } finally {
                root.recycle()
            }
        }
        val activeApplicationWindow = windows.orEmpty().firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
        } ?: return null
        val root = activeApplicationWindow.root ?: return null
        return try {
            root.packageName?.toString()?.takeIf(String::isNotBlank)
        } finally {
            root.recycle()
        }
    }

    internal val isScreenInteractive: Boolean
        get() = getSystemService(PowerManager::class.java)?.isInteractive == true

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_RAISED_HAND_AUTO_CLICK -> {
                    if (!requireAutomationConsent()) return
                    setRaisedHandAutoClickEnabled(true)
                    setPlusOneOverlayEnabled(false)
                    hideTaskControl()
                    raisedHandAutoClickController.start()
                }
                ACTION_STOP_RAISED_HAND_AUTO_CLICK -> {
                    setRaisedHandAutoClickEnabled(false)
                    raisedHandAutoClickController.stop("自动识别已关闭")
                    ProjectionCaptureService.stop(this@DanmuAccessibilityService)
                }
                ACTION_SHOW_TASK_CONTROL -> {
                    setPlusOneOverlayEnabled(true)
                    showTaskControl()
                }
                ACTION_HIDE_TASK_CONTROL -> {
                    setPlusOneOverlayEnabled(false)
                    hideTaskControl()
                }
                ACTION_DUMP_NODES -> if (requireAutomationConsent()) dumpInteractiveNodes()
                ACTION_START_CALIBRATION -> startCalibration(
                    requiredPackage = intent.getStringExtra(EXTRA_REQUIRED_TARGET_PACKAGE),
                )
                ACTION_START_TEMPLATE_CAPTURE -> startTemplateCapture(
                    requiredPackage = intent.getStringExtra(EXTRA_REQUIRED_TARGET_PACKAGE),
                )
                ACTION_STOP_TASK -> {
                    setRaisedHandAutoClickEnabled(false)
                    raisedHandAutoClickController.stop("调试命令停止")
                    ProjectionCaptureService.stop(this@DanmuAccessibilityService)
                    taskController.stop("调试命令停止", "debug_stop")
                    cancelCalibration("调试命令停止", showControls = false)
                    templateCaptureJob?.cancel()
                    cancelTemplateCaptureFlow()
                    hideTaskControl()
                }
                ACTION_REVOKE_AUTOMATION_CONSENT -> {
                    setRaisedHandAutoClickEnabled(false)
                    raisedHandAutoClickController.stop("自动操作同意已撤回")
                    ProjectionCaptureService.stop(this@DanmuAccessibilityService)
                    taskController.stop("自动操作同意已撤回", "consent_revoked")
                    cancelCalibration("自动操作同意已撤回", showControls = false)
                    templateCaptureJob?.cancel()
                    cancelTemplateCaptureFlow()
                    hideTaskControl()
                    recordDiagnostic(
                        DiagnosticLevel.Info,
                        "consent_revoked",
                        null,
                        null,
                        emptyMap(),
                    )
                }
                ACTION_TARGET_PROFILES_CHANGED -> reloadTargetProfiles()
                ACTION_CONTENT_FOLLOW_SETTING_CHANGED -> {
                    if (isContentFollowEnabled()) {
                        currentPackage?.let(::scheduleContentFollowObservation)
                    } else {
                        pendingFollowPackage = null
                        handler.removeCallbacks(followObservationRunnable)
                    }
                }
                ACTION_SUBMIT_CURRENT_TEXT -> {
                    if (!requireAutomationConsent()) return
                    if (!automationRuntimeConnected) {
                        updateTaskStatus("任务服务未实时连接，未提交")
                        return
                    }
                    val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
                    val phraseId = intent.getStringExtra(EXTRA_PHRASE_ID).orEmpty()
                    val packId = intent.getStringExtra(EXTRA_PACK_ID).orEmpty()
                    val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                    if (phrase.isNotBlank() && targetPackage.isNotBlank()) {
                        showTaskControl()
                        taskController.startOneShot(
                            phrase = phrase,
                            phraseId = phraseId.ifBlank { "unknown-phrase" },
                            packId = packId.ifBlank { "unknown-pack" },
                            expectedPackage = targetPackage,
                            textAlreadyInserted = true,
                        )
                    }
                }
                ACTION_START_TEST_SEQUENCE -> startTestSequence(
                    requiredPackage = intent.getStringExtra(EXTRA_REQUIRED_TARGET_PACKAGE),
                )
                ACTION_TEMPLATE_SAVED -> {
                    val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID).orEmpty()
                    val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                    val width = intent.getIntExtra(EXTRA_TEMPLATE_WIDTH, 0)
                    val height = intent.getIntExtra(EXTRA_TEMPLATE_HEIGHT, 0)
                    if (templateId.isNotBlank()) {
                        recordDiagnostic(
                            DiagnosticLevel.Info,
                            "local_template_saved",
                            targetPackage.ifBlank { null },
                            null,
                            mapOf(
                                "template_id" to templateId,
                                "width" to width.toString(),
                                "height" to height.toString(),
                            ),
                        )
                    }
                    showTaskControl()
                    updateTaskStatus("本地模板 $templateId 已保存（${width}×${height}）")
                }
                ACTION_TEMPLATE_CAPTURE_ACTIVITY_OPENED -> {
                    templateActivityLaunchPending = false
                    handler.removeCallbacks(templateActivityLaunchTimeoutRunnable)
                    taskControlRequested = false
                    val draft = TemplateCaptureDraftRegistry.peek()
                    recordDiagnostic(
                        DiagnosticLevel.Info,
                        "template_capture_opened",
                        draft?.targetPackage,
                        null,
                        draft?.let {
                            mapOf(
                                "orientation" to it.orientation.name,
                                "source" to it.frame.source.name,
                            )
                        }.orEmpty(),
                    )
                }
                ACTION_QUERY_AUTOMATION_RUNTIME_STATE -> updateAutomationRuntimeConnection(true)
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    raisedHandAutoClickController.onConfigurationChanged()
                    taskController.stop("屏幕已关闭", "screen_off")
                    cancelCalibration("屏幕已关闭，标定已停止", showControls = false)
                    templateCaptureJob?.cancel()
                    cancelTemplateCaptureFlow()
                    hideTaskControl(clearRequest = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isRaisedHandAutoClickEnabled()) {
                        raisedHandAutoClickController.start()
                    }
                    if (isPlusOneOverlayEnabled()) {
                        taskControlRequested = true
                        handler.post(::showTaskControl)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProjectionCapturePrivacy.clearLegacyPersistedFrames(applicationContext)
        val currentTime = System.currentTimeMillis()
        accessibilityFixtureStore = AndroidAccessibilityFixtureStore(applicationContext)
        accessibilityFixtureStore.prune()
        repository = DanmuKeyRepository(
            createDatabase(AndroidDatabaseDriverFactory(applicationContext)),
        )
        repository.ensureSeedData(currentTime)
        repository.reconcileExpiredTargetRules(currentTime)
        repository.pruneExpiredLocalRecords(currentTime)
        val recoveredTasks = repository.failInterruptedAutomationTasks(currentTime)
        recoveredInterruptedTaskCount = recoveredTasks.size
        recoveredTasks.forEach { task ->
            repository.recordDiagnostic(
                level = DiagnosticLevel.Error,
                eventCode = "service_recovered_interrupted_task",
                targetId = task.targetId,
                taskId = task.id,
                details = mapOf(
                    "completed_count" to task.completedCount.toString(),
                    "planned_count" to task.plannedCount.toString(),
                ),
                createdAt = currentTime,
            )
        }
        targetProfiles = repository.loadTargetProfiles()
        screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RaisedHandScreenshot").apply { isDaemon = true }
        }
        accessibilityScreenshotCapturer = AccessibilityScreenshotCapturer(
            service = this,
            executor = screenshotExecutor,
        )
        val runtimeScreenCapturer = RawScreenCapturer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                accessibilityScreenshotCapturer.capture()
            } else {
                ProjectionCaptureSessionRegistry.capture()
            }
        }
        val visualCaptureCoordinator = ScreenCaptureCoordinator(
            capturer = runtimeScreenCapturer,
            now = System::currentTimeMillis,
        )
        taskController = AndroidTaskController(
            service = this,
            profileProvider = ::profileForPackage,
            onStatus = ::updateTaskStatus,
            onDiagnostic = ::recordDiagnostic,
            quotaProvider = repository::loadSendQuotaSnapshot,
            onSendRecord = ::handleSendRecord,
            onTaskStateChanged = ::persistAutomationTask,
            visualCaptureCoordinator = visualCaptureCoordinator,
            visualLocatorEngine = VisualLocatorEngine(
                ocrEngine = { emptyList() },
                templateStore = AndroidLocalTemplateStore(this),
            ),
            overlayRegionsProvider = ::visualOverlayRegions,
        )
        raisedHandAutoClickController = RaisedHandAutoClickController(
            service = this,
            capturer = ProjectionCaptureSessionRegistry,
            onStatus = ::updateTaskStatus,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        val filter = IntentFilter().apply {
            addAction(ACTION_START_RAISED_HAND_AUTO_CLICK)
            addAction(ACTION_STOP_RAISED_HAND_AUTO_CLICK)
            addAction(ACTION_SHOW_TASK_CONTROL)
            addAction(ACTION_HIDE_TASK_CONTROL)
            addAction(ACTION_DUMP_NODES)
            addAction(ACTION_START_CALIBRATION)
            addAction(ACTION_START_TEMPLATE_CAPTURE)
            addAction(ACTION_STOP_TASK)
            addAction(ACTION_REVOKE_AUTOMATION_CONSENT)
            addAction(ACTION_TARGET_PROFILES_CHANGED)
            addAction(ACTION_CONTENT_FOLLOW_SETTING_CHANGED)
            addAction(ACTION_SUBMIT_CURRENT_TEXT)
            addAction(ACTION_START_TEST_SEQUENCE)
            addAction(ACTION_TEMPLATE_SAVED)
            addAction(ACTION_TEMPLATE_CAPTURE_ACTIVITY_OPENED)
            addAction(ACTION_QUERY_AUTOMATION_RUNTIME_STATE)
        }
        ContextCompat.registerReceiver(
            this,
            controlReceiver,
            filter,
            CONTROL_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateAutomationRuntimeConnection(true)
        Log.i(TAG, "Accessibility service connected")
        recordDiagnostic(DiagnosticLevel.Info, "service_connected", null, null, emptyMap())
        if (recoveredInterruptedTaskCount > 0) {
            updateTaskStatus("上次任务因服务中断而失败，未自动恢复")
            recoveredInterruptedTaskCount = 0
        }
        migrateLegacyPlusOneMode()
        if (isRaisedHandAutoClickEnabled() && hasAutomationConsent()) {
            raisedHandAutoClickController.start()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (eventPackage.isTransientAccessibilityPackage()) return

        val rootPackage = rootInActiveWindow?.let { root ->
            try {
                root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        }.orEmpty()
        val foregroundPackage = rootPackage
            .takeUnless { it.isTransientAccessibilityPackage() }
            .orEmpty()
            .ifBlank { eventPackage }
        if (foregroundPackage.isBlank()) return

        if (foregroundPackage != currentPackage) {
            currentPackage = foregroundPackage
            Log.i(TAG, "Foreground package changed to $foregroundPackage")
            taskController.onForegroundPackageChanged(foregroundPackage)
        }
        if (taskControlRequested && taskControlView == null && isScreenInteractive) {
            showTaskControl()
        }
        scheduleContentFollowObservation(foregroundPackage)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        raisedHandAutoClickController.onConfigurationChanged()
        if (calibrationSession != null || calibrationView != null) {
            cancelCalibration("屏幕方向已变化，标定已停止，请重新开始")
            return
        }
        if (templateCaptureJob?.isActive == true || TemplateCaptureDraftRegistry.peek() != null) {
            templateCaptureJob?.cancel()
            cancelTemplateCaptureFlow()
            showTaskControl()
            updateTaskStatus("屏幕方向已变化，请重新采样模板")
            return
        }
        if (taskControlRequested) {
            hideTaskControl(clearRequest = false)
            handler.post {
                if (taskControlRequested) showTaskControl()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!shouldStopAutomationForMemoryTrim(level)) return
        val taskRunning = ::taskController.isInitialized && taskController.isRunning
        val raisedHandAutoClickRunning =
            ::raisedHandAutoClickController.isInitialized && raisedHandAutoClickController.isRunning
        val calibrationRunning = calibrationSession != null || calibrationView != null
        val templateRunning = templateCaptureJob?.isActive == true || TemplateCaptureDraftRegistry.peek() != null
        if (
            !taskRunning &&
            !raisedHandAutoClickRunning &&
            !calibrationRunning &&
            !templateRunning &&
            !taskControlRequested
        ) return
        if (raisedHandAutoClickRunning) {
            setRaisedHandAutoClickEnabled(false)
            raisedHandAutoClickController.stop("系统内存紧张，自动识别已停止")
            ProjectionCaptureService.stop(this)
        }
        if (taskRunning) {
            taskController.stop("系统内存紧张，任务已停止", "memory_trim")
        }
        if (calibrationRunning) {
            cancelCalibration("系统内存紧张，标定已停止", showControls = false)
        }
        templateCaptureJob?.cancel()
        cancelTemplateCaptureFlow()
        hideTaskControl()
        if (::repository.isInitialized) {
            recordDiagnostic(
                DiagnosticLevel.Warning,
                "memory_trim_received",
                currentPackage,
                null,
                mapOf("level" to level.toString()),
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        raisedHandAutoClickController.stop("服务被系统中断")
        ProjectionCaptureService.stop(this)
        taskController.abortForRuntimeLoss("服务被系统中断", "service_interrupted")
        cancelCalibration("标定已因服务中断而停止", showControls = false)
        templateCaptureJob?.cancel()
        cancelTemplateCaptureFlow()
        hideTaskControl()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        raisedHandAutoClickController.stop()
        ProjectionCaptureService.stop(this)
        updateAutomationRuntimeConnection(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        updateAutomationRuntimeConnection(false)
        runCatching { unregisterReceiver(controlReceiver) }
        runCatching { unregisterReceiver(screenStateReceiver) }
        raisedHandAutoClickController.close()
        ProjectionCaptureService.stop(this)
        taskController.abortForRuntimeLoss("服务已关闭", "service_destroyed")
        taskController.close()
        handler.removeCallbacks(followObservationRunnable)
        handler.removeCallbacks(resetPlusOneFeedbackRunnable)
        templateCaptureJob?.cancel()
        cancelTemplateCaptureFlow()
        serviceScope.cancel()
        cancelCalibration("服务已关闭", showControls = false)
        hideTaskControl()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun showTaskControl() {
        taskControlRequested = true
        val existing = taskControlView
        if (existing?.isAttachedToWindow == true) return
        if (existing != null) {
            runCatching { windowManager.removeView(existing) }
            taskControlView = null
        }

        val buttonWidth = dp(PLUS_ONE_BUTTON_WIDTH_DP)
        val buttonHeight = dp(PLUS_ONE_BUTTON_HEIGHT_DP)
        val params = WindowManager.LayoutParams(
            buttonWidth,
            buttonHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val position = plusOneOverlayPosition(buttonWidth, buttonHeight)
            x = position.first
            y = position.second
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = params.x
        var startY = params.y
        var dragging = false
        val button = PlusOneButtonView(this).apply {
            setActive(plusOneSending)
            setOnClickListener { startPlusOne() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        if (!plusOneSending) setActive(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!dragging && deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            if (!plusOneSending) setActive(false)
                            movePlusOneOverlay(
                                params = params,
                                requestedX = startX + deltaX.toInt(),
                                requestedY = startY + deltaY.toInt(),
                                buttonWidth = buttonWidth,
                                buttonHeight = buttonHeight,
                            )
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            savePlusOneOverlayPosition(params.x, params.y)
                            if (!plusOneSending) setActive(false)
                        } else {
                            view.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (!plusOneSending) setActive(false)
                        true
                    }
                    else -> false
                }
            }
        }

        windowManager.addView(button, params)
        taskControlView = button
    }

    private fun hideTaskControl(clearRequest: Boolean = true) {
        if (clearRequest) taskControlRequested = false
        val view = taskControlView
        if (view != null) runCatching { windowManager.removeView(view) }
        taskControlView = null
    }

    private fun startPlusOne() {
        if (plusOneSending || taskController.isRunning) {
            taskControlView?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        if (!requireAutomationConsent()) {
            resetPlusOneFeedbackRunnable.run()
            showPlusOneToast("请先打开怪团建完成授权")
            return
        }
        val activeRoot = rootInActiveWindow
        val rootPackage = activeRoot?.packageName?.toString().orEmpty()
        activeRoot?.recycle()
        val candidates = listOf(rootPackage, currentPackage.orEmpty())
            .filterNot { packageName -> packageName.isTransientAccessibilityPackage() }
            .distinct()
        val resolvedTarget = candidates.firstNotNullOfOrNull { packageName ->
            profileForPackage(packageName)
                ?.takeIf { it.capabilityLevel >= TargetCapabilityLevel.L2 }
                ?.let { packageName to it }
        }
        if (candidates.isEmpty()) {
            updateTaskStatus("请先打开已适配的视频应用")
            resetPlusOneFeedbackRunnable.run()
            showPlusOneToast(lastTaskStatus)
            return
        }
        if (resolvedTarget == null) {
            updateTaskStatus("当前应用尚未完成 +1 适配")
            resetPlusOneFeedbackRunnable.run()
            showPlusOneToast(lastTaskStatus)
            return
        }
        val targetPackage = resolvedTarget.first
        if (currentPackage != targetPackage) {
            currentPackage = targetPackage
            Log.i(TAG, "Foreground package reconciled to $targetPackage for +1")
        }

        handler.removeCallbacks(resetPlusOneFeedbackRunnable)
        plusOneSending = true
        taskControlView?.apply {
            setActive(true)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        taskController.startOneShot(
            phrase = PLUS_ONE_PHRASE,
            phraseId = PLUS_ONE_PHRASE_ID,
            packId = PLUS_ONE_PACK_ID,
            expectedPackage = targetPackage,
            textAlreadyInserted = false,
        )
    }

    private fun movePlusOneOverlay(
        params: WindowManager.LayoutParams,
        requestedX: Int,
        requestedY: Int,
        buttonWidth: Int,
        buttonHeight: Int,
    ) {
        val metrics = realDisplayMetrics()
        params.x = requestedX.coerceIn(0, (metrics.widthPixels - buttonWidth).coerceAtLeast(0))
        params.y = requestedY.coerceIn(0, (metrics.heightPixels - buttonHeight).coerceAtLeast(0))
        taskControlView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
    }

    private fun plusOneOverlayPosition(buttonWidth: Int, buttonHeight: Int): Pair<Int, Int> {
        val metrics = realDisplayMetrics()
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val suffix = if (currentOrientation() == Orientation.Landscape) "landscape" else "portrait"
        val defaultX = (metrics.widthPixels - buttonWidth - dp(12)).coerceAtLeast(0)
        val defaultY = ((metrics.heightPixels - buttonHeight) / 2).coerceAtLeast(0)
        val x = preferences.getInt("${KEY_PLUS_ONE_POSITION_X}_$suffix", defaultX)
            .coerceIn(0, (metrics.widthPixels - buttonWidth).coerceAtLeast(0))
        val y = preferences.getInt("${KEY_PLUS_ONE_POSITION_Y}_$suffix", defaultY)
            .coerceIn(0, (metrics.heightPixels - buttonHeight).coerceAtLeast(0))
        return x to y
    }

    private fun savePlusOneOverlayPosition(x: Int, y: Int) {
        val suffix = if (currentOrientation() == Orientation.Landscape) "landscape" else "portrait"
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putInt("${KEY_PLUS_ONE_POSITION_X}_$suffix", x)
            .putInt("${KEY_PLUS_ONE_POSITION_Y}_$suffix", y)
            .apply()
    }

    private fun isPlusOneOverlayEnabled(): Boolean = getSharedPreferences(
        PREFERENCES_NAME,
        MODE_PRIVATE,
    ).getBoolean(KEY_PLUS_ONE_OVERLAY_ENABLED, false)

    private fun setPlusOneOverlayEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLUS_ONE_OVERLAY_ENABLED, enabled)
            .apply()
    }

    private fun isRaisedHandAutoClickEnabled(): Boolean = getSharedPreferences(
        PREFERENCES_NAME,
        MODE_PRIVATE,
    ).getBoolean(KEY_RAISED_HAND_AUTO_CLICK_ENABLED, false)

    private fun setRaisedHandAutoClickEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RAISED_HAND_AUTO_CLICK_ENABLED, enabled)
            .apply()
    }

    private fun migrateLegacyPlusOneMode() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (preferences.contains(KEY_RAISED_HAND_AUTO_CLICK_ENABLED)) return
        val legacyEnabled = preferences.getBoolean(KEY_PLUS_ONE_OVERLAY_ENABLED, false)
        preferences.edit()
            .putBoolean(KEY_RAISED_HAND_AUTO_CLICK_ENABLED, legacyEnabled)
            .putBoolean(KEY_PLUS_ONE_OVERLAY_ENABLED, false)
            .apply()
    }

    private fun showPlusOneToast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCalibration(requiredPackage: String? = null) {
        if (!requireAutomationConsent()) return
        if (taskController.isRunning) {
            updateTaskStatus("请先停止当前任务再标定")
            return
        }
        val activeRoot = rootInActiveWindow
        val packageName = activeRoot?.packageName?.toString().orEmpty()
            .ifBlank { currentPackage.orEmpty() }
        activeRoot?.recycle()
        if (packageName.isBlank() || packageName == this.packageName) {
            updateTaskStatus("请先切换到需要适配的目标应用")
            return
        }
        if (!acceptRequiredTarget(requiredPackage, packageName, "三点标定")) return
        calibrationSession = CalibrationSession(
            packageName = packageName,
            orientation = currentOrientation(),
        )
        hideTaskControl()
        showCalibrationOverlay()
    }

    private fun startTemplateCapture(requiredPackage: String? = null) {
        if (!requireAutomationConsent()) return
        if (taskController.isRunning) {
            updateTaskStatus("请先停止当前任务再采样模板")
            return
        }
        if (calibrationSession != null) {
            updateTaskStatus("请先结束三点标定再采样模板")
            return
        }
        val activeRoot = rootInActiveWindow
        val targetPackage = activeRoot?.packageName?.toString().orEmpty()
            .ifBlank { currentPackage.orEmpty() }
        activeRoot?.recycle()
        if (targetPackage.isBlank() || targetPackage == packageName) {
            updateTaskStatus("请先切换到需要采样的目标应用")
            return
        }
        if (!acceptRequiredTarget(requiredPackage, targetPackage, "模板采样")) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ProjectionCaptureSessionRegistry.isActive) {
            updateTaskStatus("Android 10 请先在主应用授权本次标定截图会话")
            return
        }

        templateCaptureJob?.cancel()
        clearPendingTemplateDraft()
        hideTaskControl()
        templateCaptureJob = serviceScope.launch {
            delay(TEMPLATE_OVERLAY_RELEASE_MS)
            when (val result = captureTemplateFrame()) {
                is RawScreenCaptureResult.Success -> {
                    TemplateCaptureDraftRegistry.publish(
                        TemplateCaptureDraft(
                            frame = result.frame,
                            targetPackage = targetPackage,
                            orientation = currentOrientation(),
                        ),
                    )
                    recordDiagnostic(
                        DiagnosticLevel.Info,
                        "template_capture_ready",
                        targetPackage,
                        null,
                        mapOf(
                            "orientation" to currentOrientation().name,
                            "source" to result.frame.source.name,
                        ),
                    )
                    showTaskControl()
                    updateTaskStatus("模板画面已准备，请点击“打开模板选区”")
                }

                RawScreenCaptureResult.IntervalTooShort -> restoreControlsAfterTemplateFailure("截图请求过快，请稍后重试")
                is RawScreenCaptureResult.Unavailable -> restoreControlsAfterTemplateFailure(
                    "模板截图不可用：${result.reason}",
                )
                is RawScreenCaptureResult.Failed -> restoreControlsAfterTemplateFailure(
                    "模板截图失败：${result.errorCode}",
                )
            }
        }
    }

    private fun openTemplateCaptureActivity() {
        val draft = TemplateCaptureDraftRegistry.peek()
        if (draft == null) {
            updateTaskStatus("模板画面已过期，请重新采样")
            return
        }
        templateActivityLaunchPending = true
        handler.removeCallbacks(templateActivityLaunchTimeoutRunnable)
        hideTaskControl(clearRequest = false)
        val launched = runCatching {
            startActivity(
                Intent()
                    .setClassName(packageName, TEMPLATE_CAPTURE_ACTIVITY_CLASS)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
            )
        }.isSuccess
        if (!launched) {
            templateActivityLaunchPending = false
            showTaskControl()
            updateTaskStatus("无法打开模板选区页面，请重试")
            return
        }
        handler.postDelayed(templateActivityLaunchTimeoutRunnable, TEMPLATE_ACTIVITY_LAUNCH_TIMEOUT_MS)
    }

    private suspend fun captureTemplateFrame(): RawScreenCaptureResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return captureWithSingleIntervalRetry(
                capturer = accessibilityScreenshotCapturer,
                retryDelayMs = TEMPLATE_SCREENSHOT_RETRY_MS,
                delayBeforeRetry = { delay(it) },
            )
        }
        return ProjectionCaptureSessionRegistry.capture()
    }

    private fun clearPendingTemplateDraft() {
        templateActivityLaunchPending = false
        handler.removeCallbacks(templateActivityLaunchTimeoutRunnable)
        TemplateCaptureDraftRegistry.clear()
    }

    private fun cancelTemplateCaptureFlow() {
        sendBroadcast(
            Intent(ACTION_CANCEL_TEMPLATE_CAPTURE).setPackage(packageName),
            CONTROL_PERMISSION,
        )
        clearPendingTemplateDraft()
    }

    private fun restoreControlsAfterTemplateFailure(message: String) {
        clearPendingTemplateDraft()
        showTaskControl()
        updateTaskStatus(message)
    }

    private fun showCalibrationOverlay() {
        val session = calibrationSession ?: return
        hideCalibrationOverlay()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(42, 0, 0, 0))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP && event.rawY > dp(120)) {
                    captureCalibrationPoint(event.rawX, event.rawY)
                }
                true
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.argb(245, 24, 25, 30))
        }
        panel.addView(
            TextView(this).apply {
                text = session.step.instruction
                setTextColor(Color.WHITE)
                textSize = 15f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(
            Button(this).apply {
                text = "取消"
                isAllCaps = false
                setOnClickListener { cancelCalibration("已取消标定") }
            },
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(overlay, params)
        calibrationView = overlay
    }

    private fun captureCalibrationPoint(rawX: Float, rawY: Float) {
        val session = calibrationSession ?: return
        val metrics = realDisplayMetrics()
        val point = LocatorSpec.CalibrationPoint(
            x = (rawX / metrics.widthPixels).coerceIn(0f, 1f),
            y = (rawY / metrics.heightPixels).coerceIn(0f, 1f),
        )
        when (session.step) {
            CalibrationStep.ComposerEntry -> {
                session.composerEntry = point
                session.step = CalibrationStep.Input
                hideCalibrationOverlay()
                handler.postDelayed(
                    {
                        tapNormalized(point) { success ->
                            if (success) {
                                handler.postDelayed(::showCalibrationOverlay, CALIBRATION_SETTLE_MS)
                            } else {
                                cancelCalibration("无法点击弹幕入口")
                            }
                        }
                    },
                    OVERLAY_RELEASE_MS,
                )
            }

            CalibrationStep.Input -> {
                session.input = point
                session.step = CalibrationStep.Submit
                hideCalibrationOverlay()
                handler.postDelayed(
                    {
                        tapNormalized(point) { success ->
                            if (success) {
                                handler.postDelayed(::showCalibrationOverlay, CALIBRATION_SETTLE_MS)
                            } else {
                                cancelCalibration("无法聚焦输入框")
                            }
                        }
                    },
                    OVERLAY_RELEASE_MS,
                )
            }

            CalibrationStep.Submit -> {
                session.submit = point
                finishCalibration(session)
            }
        }
    }

    private fun finishCalibration(session: CalibrationSession) {
        val composerEntry = session.composerEntry ?: return cancelCalibration("缺少弹幕入口坐标")
        val input = session.input ?: return cancelCalibration("缺少输入框坐标")
        val submit = session.submit ?: return cancelCalibration("缺少发送按钮坐标")
        hideCalibrationOverlay()
        val profileId = "calibrated-${session.packageName}-${session.orientation.name.lowercase()}"
        val nextVersion = targetProfiles
            .filter { it.id == profileId }
            .maxOfOrNull(TargetProfile::profileVersion)
            ?.plus(1) ?: 1
        val profile = TargetProfile(
            id = profileId,
            displayName = "${session.packageName} ${session.orientation.displayName}标定",
            platform = Platform.Android,
            appIdentifiers = setOf(session.packageName),
            orientations = setOf(session.orientation),
            capabilityLevel = TargetCapabilityLevel.L2,
            composerEntryLocators = listOf(composerEntry),
            inputLocators = listOf(
                LocatorSpec.Accessibility(editable = true),
                input,
            ),
            submitLocators = listOf(
                LocatorSpec.Accessibility(textContains = "发送", clickable = true),
                submit,
            ),
            profileVersion = nextVersion,
        )
        repository.saveTargetProfile(profile, System.currentTimeMillis())
        targetProfiles = repository.loadTargetProfiles()
        taskController.onTargetProfilesChanged()
        calibrationSession = null
        recordDiagnostic(
            DiagnosticLevel.Info,
            "calibration_saved",
            profile.id,
            null,
            mapOf("orientation" to session.orientation.name),
        )
        showTaskControl()
        updateTaskStatus("标定已保存，能力等级 L2")
    }

    private fun cancelCalibration(message: String, showControls: Boolean = true) {
        if (calibrationSession == null && calibrationView == null) return
        hideCalibrationOverlay()
        calibrationSession = null
        if (showControls) {
            showTaskControl()
            updateTaskStatus(message)
        }
    }

    private fun hideCalibrationOverlay() {
        val overlay = calibrationView ?: return
        runCatching { windowManager.removeView(overlay) }
        calibrationView = null
    }

    internal fun tapNormalized(
        point: LocatorSpec.CalibrationPoint,
        onComplete: (Boolean) -> Unit,
    ) {
        val metrics = realDisplayMetrics()
        tapPixel(
            x = (point.x * metrics.widthPixels).toInt(),
            y = (point.y * metrics.heightPixels).toInt(),
            onComplete = onComplete,
        )
    }

    internal fun tapPixel(x: Int, y: Int, onComplete: (Boolean) -> Unit) {
        val metrics = realDisplayMetrics()
        val safeX = x.coerceIn(0, metrics.widthPixels - 1).toFloat()
        val safeY = y.coerceIn(0, metrics.heightPixels - 1).toFloat()
        val path = Path().apply { moveTo(safeX, safeY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, CALIBRATION_TAP_DURATION_MS))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            },
            null,
        )
        if (!dispatched) onComplete(false)
    }

    internal fun visualOverlayRegions(): List<PixelRect> {
        val metrics = realDisplayMetrics()
        val full = PixelRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        return buildList {
            taskControlView?.screenBounds(full)?.let(::add)
            calibrationView?.screenBounds(full)?.let(::add)
            windows.orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .forEach { window ->
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    bounds.toPixelRect(full)?.let(::add)
                }
            dimensionPixelSize("status_bar_height")?.takeIf { it > 0 }?.let { height ->
                add(PixelRect(0, 0, metrics.widthPixels, height.coerceAtMost(metrics.heightPixels)))
            }
            dimensionPixelSize("navigation_bar_height")?.takeIf { it > 0 }?.let { height ->
                val top = (metrics.heightPixels - height).coerceAtLeast(0)
                add(PixelRect(0, top, metrics.widthPixels, metrics.heightPixels))
            }
        }.distinct()
    }

    private fun android.view.View.screenBounds(full: PixelRect): PixelRect? {
        if (!isShown || width <= 0 || height <= 0) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
            .toPixelRect(full)
    }

    private fun Rect.toPixelRect(full: PixelRect): PixelRect? {
        val clippedLeft = left.coerceIn(full.left, full.right)
        val clippedTop = top.coerceIn(full.top, full.bottom)
        val clippedRight = right.coerceIn(full.left, full.right)
        val clippedBottom = bottom.coerceIn(full.top, full.bottom)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return PixelRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
    }

    private fun dimensionPixelSize(name: String): Int? {
        val identifier = resources.getIdentifier(name, "dimen", "android")
        return identifier.takeIf { it != 0 }?.let(resources::getDimensionPixelSize)
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics = DisplayMetrics().also { metrics ->
        windowManager.defaultDisplay.getRealMetrics(metrics)
    }

    private fun currentOrientation(): Orientation = when (resources.configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> Orientation.Landscape
        else -> Orientation.Portrait
    }

    private fun startTestSequence(requiredPackage: String? = null) {
        if (!requireAutomationConsent()) return
        val targetPackage = currentPackage.orEmpty()
        if (!acceptRequiredTarget(requiredPackage, targetPackage, "五轮测试")) return
        showTaskControl()
        taskController.startTestSequence(
            phrases = listOf(
                "怪团建测试一",
                "怪团建测试二",
                "怪团建测试三",
                "怪团建测试四",
                "怪团建测试五",
            ),
            expectedPackage = targetPackage,
            rounds = 5,
        )
    }

    private fun acceptRequiredTarget(
        requiredPackage: String?,
        actualPackage: String,
        operation: String,
    ): Boolean {
        if (requiredPackage.isNullOrBlank() || actualPackage == requiredPackage) return true
        showTaskControl()
        updateTaskStatus("${operation}仅允许在自建测试宿主中由 ADB 调试")
        recordDiagnostic(
            DiagnosticLevel.Warning,
            "debug_command_target_rejected",
            actualPackage.ifBlank { null },
            null,
            mapOf("operation" to operation),
        )
        return false
    }

    private fun profileForPackage(packageName: String): TargetProfile? {
        val orientation = currentOrientation()
        val appVersionCode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        }.getOrNull()
        return TargetProfileSelector.select(
            profiles = targetProfiles,
            context = TargetRuntimeContext(
                appIdentifier = packageName,
                orientation = orientation,
                systemApi = Build.VERSION.SDK_INT,
                appVersionCode = appVersionCode,
            ),
        )
    }

    private fun reloadTargetProfiles() {
        val expiredCount = repository.reconcileExpiredTargetRules(System.currentTimeMillis())
        targetProfiles = repository.loadTargetProfiles()
        taskController.onTargetProfilesChanged()
        recordDiagnostic(
            DiagnosticLevel.Info,
            "target_profiles_reloaded",
            null,
            null,
            mapOf(
                "profile_count" to targetProfiles.size.toString(),
                "expired_count" to expiredCount.toString(),
            ),
        )
    }

    private fun scheduleContentFollowObservation(packageName: String) {
        if (!isContentFollowEnabled()) return
        pendingFollowPackage = packageName
        handler.removeCallbacks(followObservationRunnable)
        val now = System.currentTimeMillis()
        val delayMs = (lastFollowObservationAt + CONTENT_FOLLOW_INTERVAL_MS - now)
            .coerceAtLeast(CONTENT_FOLLOW_DEBOUNCE_MS)
        handler.postDelayed(followObservationRunnable, delayMs)
    }

    private fun observeContentFollow(packageName: String) {
        if (!isContentFollowEnabled() || currentPackage != packageName) return
        lastFollowObservationAt = System.currentTimeMillis()
        val profile = profileForPackage(packageName) ?: return
        if (profile.episodeTitleLocators.isEmpty() && profile.playbackTimeLocators.isEmpty()) return
        val root = rootInActiveWindow ?: return
        try {
            val episodeTitle = locateConfiguredText(root, profile.episodeTitleLocators)
            val playbackText = locateConfiguredText(root, profile.playbackTimeLocators)
            val previous = repository.loadLatestContentFollowState(
                appIdentifier = packageName,
                observedSince = lastFollowObservationAt - CONTENT_FOLLOW_STATE_MAX_AGE_MS,
            )
            val mapping = episodeTitle?.let { title ->
                repository.resolveEpisodeMapping(profile.id, title, minimumConfidence = 0.8)
            }
            if (episodeTitle != null && mapping == null) {
                repository.clearContentFollowState(profile.id)
                if (previous != null) notifyContentFollowChanged()
                return
            }
            val sectionId = mapping?.sectionId
                ?: previous?.takeIf { it.targetId == profile.id }?.sectionId
                ?: return
            val pack = repository.loadPackContainingSection(sectionId) ?: return
            val section = pack.sections.firstOrNull { it.id == sectionId } ?: return
            val playbackPositionMs = playbackText?.let(PlaybackTimeParser::parseCurrentPosition)
            val groupId = if (playbackPositionMs != null) {
                SceneFollower.selectGroup(section, playbackPositionMs)?.id
            } else {
                previous?.takeIf { it.sectionId == sectionId }?.groupId
            }
            val state = ContentFollowState(
                targetId = profile.id,
                appIdentifier = packageName,
                packId = pack.id,
                sectionId = sectionId,
                groupId = groupId,
                playbackPositionMs = playbackPositionMs ?: previous?.playbackPositionMs,
                confidence = mapping?.confidence ?: previous?.confidence ?: 0.8,
                observedAt = lastFollowObservationAt,
            )
            repository.saveContentFollowState(state)
            val contextChanged = previous == null ||
                previous.packId != state.packId ||
                previous.sectionId != state.sectionId ||
                previous.groupId != state.groupId
            if (contextChanged) {
                recordDiagnostic(
                    DiagnosticLevel.Info,
                    "content_follow_updated",
                    profile.id,
                    null,
                    buildMap {
                        put("section_id", state.sectionId)
                        state.groupId?.let { put("group_id", it) }
                        put("has_playback", (state.playbackPositionMs != null).toString())
                    },
                )
                notifyContentFollowChanged()
            }
        } finally {
            root.recycle()
        }
    }

    private fun locateConfiguredText(
        root: AccessibilityNodeInfo,
        locators: List<LocatorSpec>,
    ): String? {
        if (locators.isEmpty()) return null
        val match = AndroidAccessibilityLocator.locate(root, locators) ?: return null
        return try {
            match.node.text?.toString()?.trim().orEmpty()
                .ifBlank { match.node.contentDescription?.toString()?.trim().orEmpty() }
                .takeIf(String::isNotBlank)
        } finally {
            match.node.recycle()
        }
    }

    private fun notifyContentFollowChanged() {
        sendBroadcast(
            Intent(ACTION_CONTENT_CONTEXT_CHANGED).setPackage(packageName),
        )
    }

    private fun isContentFollowEnabled(): Boolean = getSharedPreferences(
        KEYBOARD_PREFERENCES,
        MODE_PRIVATE,
    ).getBoolean(KEY_CONTENT_FOLLOW_ENABLED, false)

    private fun updateTaskStatus(message: String) {
        lastTaskStatus = message
        Log.i(TAG, message)
        sendBroadcast(
            Intent(ACTION_TASK_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_TASK_STATUS, message),
            CONTROL_PERMISSION,
        )
    }

    private fun requireAutomationConsent(): Boolean {
        val accepted = hasAutomationConsent()
        if (!accepted) {
            showTaskControl()
            updateTaskStatus("请先在怪团建主应用阅读并同意自动操作说明")
        }
        return accepted
    }

    private fun hasAutomationConsent(): Boolean = getSharedPreferences(
        PREFERENCES_NAME,
        MODE_PRIVATE,
    ).getBoolean(KEY_AUTOMATION_DISCLOSURE_ACCEPTED, false)

    private fun recordDiagnostic(
        level: DiagnosticLevel,
        eventCode: String,
        targetId: String?,
        taskId: String?,
        details: Map<String, String>,
    ) {
        runCatching {
            repository.recordDiagnostic(
                level = level,
                eventCode = eventCode,
                targetId = targetId,
                taskId = taskId,
                details = details,
                createdAt = System.currentTimeMillis(),
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to store diagnostic event=$eventCode", error)
        }
    }

    private fun recordSend(record: AndroidSendRecordDraft) {
        runCatching {
            repository.recordSend(
                taskId = record.taskId,
                phraseId = record.phraseId,
                packId = record.packId,
                targetId = record.targetId,
                finalText = record.finalText,
                mode = record.mode,
                locatorSource = record.locatorSource,
                confidence = record.confidence,
                result = record.result,
                errorCode = record.errorCode,
                startedAt = record.startedAt,
                finishedAt = record.finishedAt,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to store send record task=${record.taskId}", error)
        }
    }

    private fun handleSendRecord(record: AndroidSendRecordDraft) {
        recordSend(record)
        if (record.packId != PLUS_ONE_PACK_ID) return
        handler.removeCallbacks(resetPlusOneFeedbackRunnable)
        plusOneSending = false
        when (record.result) {
            SendResult.Submitted -> {
                taskControlView?.apply {
                    setActive(true)
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                handler.postDelayed(resetPlusOneFeedbackRunnable, PLUS_ONE_SUCCESS_FEEDBACK_MS)
            }
            else -> {
                taskControlView?.setActive(false)
                handler.post { showPlusOneToast(lastTaskStatus.ifBlank { "发送失败，请重试" }) }
            }
        }
    }

    private fun persistAutomationTask(record: AutomationTaskRecord): Boolean = runCatching {
            repository.saveAutomationTask(record)
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist automation task=${record.id} status=${record.status}", error)
        }.getOrDefault(false)

    private fun updateAutomationRuntimeConnection(connected: Boolean) {
        automationRuntimeConnected = connected
        sendBroadcast(
            Intent(ACTION_AUTOMATION_RUNTIME_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_AUTOMATION_RUNTIME_CONNECTED, connected),
            CONTROL_PERMISSION,
        )
    }

    private fun dumpInteractiveNodes() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(PROBE_TAG, "No active accessibility root")
            updateTaskStatus("无法读取当前目标控件")
            return
        }
        try {
            val packageName = root.packageName?.toString().orEmpty()
            val allowedLabels = if (packageName == TEST_HOST_PACKAGE) TEST_HOST_SAFE_LABELS else emptySet()
            val nodes = mutableListOf<AccessibilityNodeFixture>()
            var visited = 0

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (visited >= MAX_PROBE_NODES) return
                visited += 1

                if (node.isClickable || node.isEditable || node.isFocusable) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes += AccessibilityNodeFixture(
                        depth = depth,
                        className = node.className?.toString(),
                        resourceId = node.viewIdResourceName,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        focusable = node.isFocusable,
                        bounds = AccessibilityBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        safeText = AccessibilityFixtureRedactor.safeLabel(
                            node.text,
                            node.isEditable,
                            allowedLabels,
                        ),
                        safeContentDescription = AccessibilityFixtureRedactor.safeLabel(
                            node.contentDescription,
                            node.isEditable,
                            allowedLabels,
                        ),
                    )
                }

                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        visit(child, depth + 1)
                        child.recycle()
                    }
                }
            }

            visit(root, 0)
            val metrics = realDisplayMetrics()
            val capturedAt = System.currentTimeMillis()
            val fixture = AccessibilityTreeFixture(
                capturedAt = capturedAt,
                packageName = packageName,
                appVersionCode = packageVersionCode(packageName),
                systemApi = Build.VERSION.SDK_INT,
                orientation = currentOrientation(),
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                nodes = nodes,
            )
            val fixtureFile = runCatching { accessibilityFixtureStore.save(fixture) }
                .getOrElse { error ->
                    Log.e(PROBE_TAG, "Unable to store redacted accessibility fixture", error)
                    recordDiagnostic(
                        DiagnosticLevel.Error,
                        "node_fixture_save_failed",
                        packageName,
                        null,
                        emptyMap(),
                    )
                    updateTaskStatus("保存脱敏控件样本失败，请重试")
                    return
                }
            Log.i(
                PROBE_TAG,
                "fixture=${fixtureFile.absolutePath} package=$packageName nodes=${nodes.size} visited=$visited",
            )
            recordDiagnostic(
                DiagnosticLevel.Info,
                "node_fixture_saved",
                packageName,
                null,
                mapOf(
                    "node_count" to nodes.size.toString(),
                    "orientation" to currentOrientation().name,
                ),
            )
            updateTaskStatus("已保存脱敏控件 fixture（${nodes.size} 个节点）")
        } finally {
            root.recycle()
        }
    }

    private fun packageVersionCode(packageName: String): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun String.isTransientAccessibilityPackage(): Boolean =
        isBlank() || this == packageName || this == SYSTEM_UI_PACKAGE

    companion object {
        const val ACTION_START_RAISED_HAND_AUTO_CLICK =
            "com.danmukey.action.START_RAISED_HAND_AUTO_CLICK"
        const val ACTION_STOP_RAISED_HAND_AUTO_CLICK =
            "com.danmukey.action.STOP_RAISED_HAND_AUTO_CLICK"
        const val ACTION_SHOW_TASK_CONTROL = "com.danmukey.action.SHOW_TASK_CONTROL"
        const val ACTION_HIDE_TASK_CONTROL = "com.danmukey.action.HIDE_TASK_CONTROL"
        const val ACTION_DUMP_NODES = "com.danmukey.action.DUMP_NODES"
        const val ACTION_START_CALIBRATION = "com.danmukey.action.START_CALIBRATION"
        const val ACTION_START_TEMPLATE_CAPTURE = "com.danmukey.action.START_TEMPLATE_CAPTURE"
        const val ACTION_STOP_TASK = "com.danmukey.action.STOP_TASK"
        const val ACTION_REVOKE_AUTOMATION_CONSENT = "com.danmukey.action.REVOKE_AUTOMATION_CONSENT"
        const val ACTION_TEMPLATE_CAPTURE_ACTIVITY_OPENED =
            "com.danmukey.action.TEMPLATE_CAPTURE_ACTIVITY_OPENED"
        const val ACTION_QUERY_AUTOMATION_RUNTIME_STATE =
            "com.danmukey.action.QUERY_AUTOMATION_RUNTIME_STATE"
        const val ACTION_CANCEL_TEMPLATE_CAPTURE =
            "com.danmukey.action.CANCEL_TEMPLATE_CAPTURE"
        const val ACTION_TARGET_PROFILES_CHANGED = "com.danmukey.action.TARGET_PROFILES_CHANGED"
        const val ACTION_CONTENT_FOLLOW_SETTING_CHANGED = "com.danmukey.action.CONTENT_FOLLOW_SETTING_CHANGED"
        const val ACTION_CONTENT_CONTEXT_CHANGED = "com.danmukey.action.CONTENT_CONTEXT_CHANGED"
        const val ACTION_TASK_STATUS_CHANGED = "com.danmukey.action.TASK_STATUS_CHANGED"
        const val ACTION_AUTOMATION_RUNTIME_STATE_CHANGED =
            "com.danmukey.action.AUTOMATION_RUNTIME_STATE_CHANGED"
        const val ACTION_SUBMIT_CURRENT_TEXT = "com.danmukey.action.SUBMIT_CURRENT_TEXT"
        const val ACTION_START_TEST_SEQUENCE = "com.danmukey.action.START_TEST_SEQUENCE"
        const val ACTION_TEMPLATE_SAVED = "com.danmukey.action.TEMPLATE_SAVED"
        const val CONTROL_PERMISSION = "io.github.wintopic.teambox.permission.CONTROL_SERVICE"
        const val EXTRA_PHRASE = "phrase"
        const val EXTRA_PHRASE_ID = "phrase_id"
        const val EXTRA_PACK_ID = "pack_id"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_TEMPLATE_WIDTH = "template_width"
        const val EXTRA_TEMPLATE_HEIGHT = "template_height"
        const val EXTRA_REQUIRED_TARGET_PACKAGE = "required_target_package"
        const val EXTRA_TASK_STATUS = "task_status"
        const val EXTRA_AUTOMATION_RUNTIME_CONNECTED = "automation_runtime_connected"
        const val KEY_PLUS_ONE_OVERLAY_ENABLED = "plus_one_overlay_enabled"
        const val KEY_RAISED_HAND_AUTO_CLICK_ENABLED = "raised_hand_auto_click_enabled"

        @Volatile
        private var automationRuntimeConnected: Boolean = false

        internal fun isAutomationRuntimeConnected(): Boolean = automationRuntimeConnected

        private const val TAG = "DanmuKeyService"
        private const val PROBE_TAG = "DanmuKeyProbe"
        private const val MAX_PROBE_NODES = 300
        private const val OVERLAY_RELEASE_MS = 100L
        private const val TEMPLATE_OVERLAY_RELEASE_MS = 250L
        private const val TEMPLATE_SCREENSHOT_RETRY_MS = 500L
        private const val TEMPLATE_ACTIVITY_LAUNCH_TIMEOUT_MS = 1_500L
        private const val CALIBRATION_SETTLE_MS = 550L
        private const val CALIBRATION_TAP_DURATION_MS = 80L
        private const val PREFERENCES_NAME = "danmukey_preferences"
        private const val KEY_AUTOMATION_DISCLOSURE_ACCEPTED = "automation_disclosure_accepted"
        private const val KEY_PLUS_ONE_POSITION_X = "plus_one_position_x"
        private const val KEY_PLUS_ONE_POSITION_Y = "plus_one_position_y"
        private const val PLUS_ONE_BUTTON_WIDTH_DP = 82
        private const val PLUS_ONE_BUTTON_HEIGHT_DP = 52
        private const val PLUS_ONE_PHRASE = "+1"
        private const val PLUS_ONE_PHRASE_ID = "builtin-plus-one"
        private const val PLUS_ONE_PACK_ID = "builtin-plus-one"
        private const val PLUS_ONE_SUCCESS_FEEDBACK_MS = 650L
        private const val KEYBOARD_PREFERENCES = "keyboard_mode"
        private const val KEY_CONTENT_FOLLOW_ENABLED = "content_follow_enabled"
        private const val CONTENT_FOLLOW_INTERVAL_MS = 1_000L
        private const val CONTENT_FOLLOW_DEBOUNCE_MS = 150L
        private const val CONTENT_FOLLOW_STATE_MAX_AGE_MS = 5L * 60L * 1_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val TEST_HOST_PACKAGE = "com.danmukey.testhost"
        private const val TEMPLATE_CAPTURE_ACTIVITY_CLASS = "com.danmukey.app.TemplateCaptureActivity"
        private val TEST_HOST_SAFE_LABELS = setOf(
            "切换故障模式",
            "切换测试故障模式",
            "发弹幕",
            "打开弹幕输入入口",
            "发送",
            "发送弹幕",
            "自绘发送控件",
        )
    }

    private enum class CalibrationStep(val instruction: String) {
        ComposerEntry("第 1/3 步：点击弹幕输入入口"),
        Input("第 2/3 步：点击弹幕输入框"),
        Submit("第 3/3 步：点击发送按钮位置（只记录，不发送）"),
    }

    private data class CalibrationSession(
        val packageName: String,
        val orientation: Orientation,
        var step: CalibrationStep = CalibrationStep.ComposerEntry,
        var composerEntry: LocatorSpec.CalibrationPoint? = null,
        var input: LocatorSpec.CalibrationPoint? = null,
        var submit: LocatorSpec.CalibrationPoint? = null,
    )

    private val Orientation.displayName: String
        get() = if (this == Orientation.Portrait) "竖屏" else "横屏"
}

internal fun shouldStopAutomationForMemoryTrim(level: Int): Boolean =
    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
