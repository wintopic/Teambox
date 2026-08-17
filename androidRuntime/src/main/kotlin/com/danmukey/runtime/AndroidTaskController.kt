package com.danmukey.runtime

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.danmukey.shared.automation.AutomationEvent
import com.danmukey.shared.automation.AutomationStateMachine
import com.danmukey.shared.automation.AutomationTaskState
import com.danmukey.shared.automation.SendQuotaSnapshot
import com.danmukey.shared.automation.SendSafetyLimits
import com.danmukey.shared.automation.SendSafetyPolicy
import com.danmukey.shared.automation.SendSafetyViolation
import com.danmukey.shared.automation.SendSafetyViolationCode
import com.danmukey.shared.automation.TaskPhase
import com.danmukey.shared.model.TargetCapabilityLevel
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.model.AutomationTaskRecord
import com.danmukey.shared.model.AutomationTaskStatus
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.SendResult
import com.danmukey.shared.visual.PixelRect
import com.danmukey.shared.visual.PreparedCapture
import com.danmukey.shared.visual.ScreenCaptureCoordinator
import com.danmukey.shared.visual.ScreenCaptureResult
import com.danmukey.shared.visual.VisualLocatorEngine
import com.danmukey.shared.visual.VisualLocatorResult
import com.danmukey.shared.visual.captureRequestForVisualLocator
import com.danmukey.shared.visual.firstCalibrationPoint
import com.danmukey.shared.visual.hasVisualLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AndroidTaskController(
    private val service: DanmuAccessibilityService,
    private val profileProvider: (String) -> TargetProfile?,
    private val onStatus: (String) -> Unit,
    private val onDiagnostic: (
        level: DiagnosticLevel,
        eventCode: String,
        targetId: String?,
        taskId: String?,
        details: Map<String, String>,
    ) -> Unit,
    private val quotaProvider: (targetId: String, since: Long) -> SendQuotaSnapshot,
    private val onSendRecord: (AndroidSendRecordDraft) -> Unit,
    private val onTaskStateChanged: (AutomationTaskRecord) -> Boolean,
    private val visualCaptureCoordinator: ScreenCaptureCoordinator,
    private val visualLocatorEngine: VisualLocatorEngine,
    private val overlayRegionsProvider: () -> List<PixelRect>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val safetyLimits: SendSafetyLimits = SendSafetyLimits(),
) {
    private val handler = Handler(Looper.getMainLooper())
    private val visualScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visualJob: Job? = null
    private var state = AutomationTaskState(taskId = "idle")
    private var request: TaskRequest? = null
    private var stopped = false
    private var roundStartedAt: Long = 0L
    private var currentPhraseId: String = ""
    private var currentPhraseText: String = ""
    private var lastSubmitSource: String? = null
    private var lastSubmitConfidence: Double? = null
    private var roundRecordWritten = true
    private var taskCreatedAt: Long = 0L

    val isRunning: Boolean
        get() = request != null && !state.phase.isTerminal()

    fun close() {
        visualJob?.cancel()
        visualScope.cancel()
    }

    fun startOneShot(
        phrase: String,
        phraseId: String,
        packId: String,
        expectedPackage: String,
        textAlreadyInserted: Boolean,
    ) {
        start(
            TaskRequest(
                phrases = listOf(phrase),
                phraseIds = listOf(phraseId),
                packId = packId,
                mode = SendMode.TapToSend,
                rounds = 1,
                intervalMs = 0L,
                expectedPackage = expectedPackage,
                textAlreadyInserted = textAlreadyInserted,
            ),
        )
    }

    fun startTestSequence(
        phrases: List<String>,
        expectedPackage: String,
        rounds: Int = 5,
        intervalMs: Long = safetyLimits.minimumContinuousIntervalMs,
    ) {
        val cleanedPhrases = phrases.filter(String::isNotBlank).ifEmpty { listOf("怪团建测试") }
        start(
            TaskRequest(
                phrases = cleanedPhrases,
                phraseIds = cleanedPhrases.indices.map { "test-phrase-$it" },
                packId = "builtin-test-sequence",
                mode = SendMode.Continuous,
                rounds = rounds.coerceIn(1, safetyLimits.maximumContinuousItems),
                intervalMs = intervalMs,
                expectedPackage = expectedPackage,
                textAlreadyInserted = false,
            ),
        )
    }

    fun onForegroundPackageChanged(packageName: String) {
        val active = request ?: return
        if (packageName != active.expectedPackage && packageName != service.packageName) {
            stop("目标应用已切换", "target_changed")
        }
    }

    fun onTargetProfilesChanged() {
        val active = request ?: return
        val latest = profileProvider(active.expectedPackage)
        if (latest == null || latest != active.targetProfileSnapshot) {
            stop("目标规则已更新或停用", "target_rule_changed")
        }
    }

    fun stop(reason: String = "用户停止", eventCode: String = "user_stop") {
        val active = request ?: return
        stopped = true
        visualJob?.cancel()
        visualJob = null
        handler.removeCallbacksAndMessages(null)
        recordCurrentRound(
            active = active,
            result = SendResult.Cancelled,
            errorCode = eventCode,
            finishedAt = clock(),
        )
        state = AutomationStateMachine.reduce(state, AutomationEvent.Cancel)
        persistTaskStatus(AutomationTaskStatus.Cancelled, finishedAt = clock())
        Log.i(TAG, "task=${state.taskId} stopped reason=$reason")
        onDiagnostic(
            DiagnosticLevel.Info,
            eventCode,
            active.targetId,
            state.taskId,
            mapOf("phase" to state.phase.name),
        )
        onStatus(reason)
        request = null
    }

    fun abortForRuntimeLoss(
        reason: String,
        eventCode: String,
    ) {
        val active = request ?: return
        stopped = true
        visualJob?.cancel()
        visualJob = null
        handler.removeCallbacksAndMessages(null)
        val finishedAt = clock()
        recordCurrentRound(
            active = active,
            result = SendResult.Failed,
            errorCode = eventCode,
            finishedAt = finishedAt,
        )
        state = AutomationStateMachine.reduce(state, AutomationEvent.Fail(eventCode))
        persistTaskStatus(AutomationTaskStatus.Failed, finishedAt)
        Log.w(TAG, "task=${state.taskId} aborted reason=$reason")
        onDiagnostic(
            DiagnosticLevel.Error,
            eventCode,
            active.targetId,
            state.taskId,
            mapOf("phase" to state.phase.name),
        )
        onStatus(reason)
        request = null
    }

    private fun start(newRequest: TaskRequest) {
        stop("新任务替换旧任务", "task_replaced")
        visualJob?.cancel()
        visualJob = null
        val profile = profileProvider(newRequest.expectedPackage)
        val targetId = profile?.id ?: newRequest.expectedPackage
        val taskViolation = SendSafetyPolicy.validateTask(
            mode = newRequest.mode,
            capabilityLevel = profile?.capabilityLevel ?: TargetCapabilityLevel.L0,
            itemCount = newRequest.rounds,
            intervalMs = newRequest.intervalMs,
            limits = safetyLimits,
        )
        if (profile == null || taskViolation != null) {
            val violation = taskViolation ?: SendSafetyViolation(
                code = SendSafetyViolationCode.TargetCapabilityInsufficient,
            )
            val message = violation.userMessage(clock())
            recordRejectedRequest(newRequest, targetId, violation)
            onDiagnostic(
                DiagnosticLevel.Warning,
                violation.code.eventCode,
                profile?.id,
                null,
                violation.diagnosticDetails() +
                    ("capability" to (profile?.capabilityLevel?.name ?: "missing")),
            )
            onStatus(message)
            return
        }
        stopped = false
        request = newRequest.copy(
            targetId = profile.id,
            targetProfileSnapshot = profile,
        )
        roundStartedAt = 0L
        currentPhraseId = ""
        currentPhraseText = ""
        lastSubmitSource = null
        lastSubmitConfidence = null
        roundRecordWritten = true
        taskCreatedAt = clock()
        state = AutomationTaskState(taskId = "android-$taskCreatedAt")
        state = AutomationStateMachine.reduce(state, AutomationEvent.Prepare)
        state = AutomationStateMachine.reduce(state, AutomationEvent.Start)
        if (!persistTaskStatus(AutomationTaskStatus.Running)) {
            state = AutomationStateMachine.reduce(state, AutomationEvent.Fail("task_state_persist_failed"))
            onDiagnostic(
                DiagnosticLevel.Error,
                "task_state_persist_failed",
                profile.id,
                state.taskId,
                emptyMap(),
            )
            onStatus("无法保存任务安全状态，任务未启动")
            request = null
            return
        }
        onDiagnostic(
            DiagnosticLevel.Info,
            "task_started",
            profile.id,
            state.taskId,
            mapOf(
                "rounds" to newRequest.rounds.toString(),
                "text_already_inserted" to newRequest.textAlreadyInserted.toString(),
            ),
        )
        onStatus("任务已启动 0/${newRequest.rounds}")
        runRound()
    }

    private fun runRound() {
        val active = request ?: return
        if (stopped) return
        val profile = profileProvider(active.expectedPackage)
        if (profile == null || profile != active.targetProfileSnapshot) {
            stop("目标规则已更新或停用", "target_rule_changed")
            return
        }
        val now = clock()
        roundStartedAt = now
        roundRecordWritten = false
        lastSubmitSource = null
        lastSubmitConfidence = null
        val phraseIndex = state.completedCount.mod(active.phrases.size)
        currentPhraseId = active.phraseIds.getOrElse(phraseIndex) { "unknown-phrase" }
        currentPhraseText = active.phrases[phraseIndex]

        if (!service.isScreenInteractive) {
            stop("屏幕已关闭", "screen_off")
            return
        }

        val targetId = active.targetId ?: active.expectedPackage
        val quota = quotaProvider(targetId, now - safetyLimits.targetWindowMs)
        val quotaViolation = SendSafetyPolicy.validateAttempt(
            mode = active.mode,
            now = now,
            quota = quota,
            limits = safetyLimits,
        )
        if (quotaViolation != null) {
            blockCurrentRound(active, quotaViolation)
            return
        }
        val textViolation = SendSafetyPolicy.validateText(currentPhraseText, profile.maxTextLength)
        if (textViolation != null) {
            blockCurrentRound(active, textViolation)
            return
        }
        if (service.currentForegroundPackage != active.expectedPackage) {
            fail("目标应用不在前台", "target_not_foreground")
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            if (active.textAlreadyInserted && state.completedCount == 0) {
                state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerChecked(true))
                state = AutomationStateMachine.reduce(state, AutomationEvent.InputLocated(true))
                state = AutomationStateMachine.reduce(state, AutomationEvent.ConfirmationGranted)
                state = AutomationStateMachine.reduce(state, AutomationEvent.TextInserted(true))
                handler.postDelayed({ submit(profile, currentPhraseText) }, ACTION_SETTLE_MS)
                return
            }
            fail("无法读取目标界面", "accessibility_root_missing")
            return
        }
        val input = AndroidAccessibilityLocator.locate(root, profile.inputLocators)
        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerChecked(input != null))
        if (input != null) {
            input.node.recycle()
            root.recycle()
            locateAndInsert(profile)
            return
        }
        root.recycle()
        if (profile.inputLocators.hasVisualLocator()) {
            locateVisualAndTap(profile, profile.inputLocators, "input_focus") { match, focused ->
                if (stopped || request == null) return@locateVisualAndTap
                if (match != null && focused) {
                    recordLocatorUse(profile, "input_focus", match.sourceName, match.confidence)
                    handler.postDelayed(
                        { locateAndInsert(profile, visualAttempted = true) },
                        UI_SETTLE_MS,
                    )
                } else {
                    reopenComposer(profile)
                }
            }
        } else {
            reopenComposer(profile)
        }
    }

    private fun reopenComposer(profile: TargetProfile) {
        val root = service.rootInActiveWindow
        val entry = root?.let { AndroidAccessibilityLocator.locate(it, profile.composerEntryLocators) }
        val clickedByNode = entry?.node?.let(::performClick) == true
        val calibrationPoint = profile.composerEntryLocators.firstCalibrationPoint()
        val source = entry?.source
        entry?.node?.recycle()
        root?.recycle()
        if (clickedByNode) {
            completeComposerReopen(
                profile,
                clicked = true,
                locatorSource = source ?: "accessibility",
                confidence = entry?.confidence,
            )
            return
        }
        if (profile.composerEntryLocators.hasVisualLocator()) {
            locateVisualAndTap(profile, profile.composerEntryLocators, "composer_entry") { match, clicked ->
                if (stopped || request == null) return@locateVisualAndTap
                if (match != null && clicked) {
                    completeComposerReopen(
                        profile,
                        clicked = true,
                        locatorSource = match.sourceName,
                        confidence = match.confidence,
                    )
                } else {
                    reopenComposerByCalibration(profile, calibrationPoint)
                }
            }
            return
        }
        reopenComposerByCalibration(profile, calibrationPoint)
    }

    private fun reopenComposerByCalibration(
        profile: TargetProfile,
        calibrationPoint: LocatorSpec.CalibrationPoint?,
    ) {
        if (calibrationPoint != null) {
            service.tapNormalized(calibrationPoint) { clicked ->
                completeComposerReopen(
                    profile,
                    clicked,
                    "calibration_point",
                    CALIBRATION_CONFIDENCE.toFloat(),
                )
            }
            return
        }
        completeComposerReopen(profile, clicked = false, locatorSource = "none", confidence = null)
    }

    private fun completeComposerReopen(
        profile: TargetProfile,
        clicked: Boolean,
        locatorSource: String,
        confidence: Float?,
    ) {
        if (stopped || request == null) return
        state = AutomationStateMachine.reduce(state, AutomationEvent.ComposerReopened(clicked))
        if (!clicked) {
            fail("找不到弹幕输入入口", "composer_entry_not_found")
            return
        }
        recordLocatorUse(profile, "composer_entry", locatorSource, confidence)
        onStatus("正在重新打开输入区")
        handler.postDelayed({ locateAndInsert(profile) }, UI_SETTLE_MS)
    }

    private fun locateAndInsert(
        profile: TargetProfile,
        calibrationAttempted: Boolean = false,
        visualAttempted: Boolean = false,
    ) {
        if (stopped) return
        val active = request ?: return
        val root = service.rootInActiveWindow
        val input = root?.let { AndroidAccessibilityLocator.locate(it, profile.inputLocators) }
        if (input == null) {
            root?.recycle()
            if (!visualAttempted && profile.inputLocators.hasVisualLocator()) {
                locateVisualAndTap(profile, profile.inputLocators, "input_focus") { match, focused ->
                    if (stopped || request == null) return@locateVisualAndTap
                    if (match != null && focused) {
                        recordLocatorUse(profile, "input_focus", match.sourceName, match.confidence)
                        handler.postDelayed(
                            {
                                locateAndInsert(
                                    profile,
                                    calibrationAttempted = calibrationAttempted,
                                    visualAttempted = true,
                                )
                            },
                            UI_SETTLE_MS,
                        )
                    } else {
                        locateInputByCalibration(profile, calibrationAttempted)
                    }
                }
                return
            }
            locateInputByCalibration(profile, calibrationAttempted)
            return
        }
        state = AutomationStateMachine.reduce(state, AutomationEvent.InputLocated(true))
        recordLocatorUse(profile, "input", input.source, input.confidence)
        state = AutomationStateMachine.reduce(state, AutomationEvent.ConfirmationGranted)
        val phrase = currentPhraseText
        val inserted = if (active.textAlreadyInserted && state.completedCount == 0) {
            true
        } else {
            setText(input.node, phrase)
        }
        input.node.recycle()
        root?.recycle()
        state = AutomationStateMachine.reduce(state, AutomationEvent.TextInserted(inserted))
        if (!inserted) {
            fail("文字写入失败", "text_insert_failed")
            return
        }
        handler.postDelayed({ submit(profile, phrase) }, ACTION_SETTLE_MS)
    }

    private fun locateInputByCalibration(profile: TargetProfile, calibrationAttempted: Boolean) {
        if (stopped || request == null) return
        val calibrationPoint = profile.inputLocators.firstCalibrationPoint()
        if (!calibrationAttempted && calibrationPoint != null) {
            service.tapNormalized(calibrationPoint) { focused ->
                if (!focused) {
                    state = AutomationStateMachine.reduce(state, AutomationEvent.InputLocated(false))
                    fail("无法聚焦弹幕输入框", "input_focus_failed")
                } else {
                    recordLocatorUse(
                        profile,
                        "input_focus",
                        "calibration_point",
                        CALIBRATION_CONFIDENCE.toFloat(),
                    )
                    handler.postDelayed(
                        {
                            locateAndInsert(
                                profile,
                                calibrationAttempted = true,
                                visualAttempted = true,
                            )
                        },
                        UI_SETTLE_MS,
                    )
                }
            }
            return
        }
        state = AutomationStateMachine.reduce(state, AutomationEvent.InputLocated(false))
        fail("找不到弹幕输入框", "input_not_found")
    }

    private fun submit(profile: TargetProfile, phrase: String) {
        if (stopped) return
        val root = service.rootInActiveWindow
        val submit = root?.let { AndroidAccessibilityLocator.locate(it, profile.submitLocators) }
        val clickedByNode = submit?.node?.let(::performClick) == true
        val calibrationPoint = profile.submitLocators.firstCalibrationPoint()
        val source = submit?.source
        submit?.node?.recycle()
        root?.recycle()
        if (clickedByNode) {
            completeSubmit(
                profile,
                phrase,
                clicked = true,
                locatorSource = source ?: "accessibility",
                confidence = submit?.confidence?.toDouble(),
            )
            return
        }
        if (profile.submitLocators.hasVisualLocator()) {
            locateVisualAndTap(profile, profile.submitLocators, "submit") { match, clicked ->
                if (stopped || request == null) return@locateVisualAndTap
                if (match != null && clicked) {
                    completeSubmit(
                        profile,
                        phrase,
                        clicked = true,
                        locatorSource = match.sourceName,
                        confidence = match.confidence.toDouble(),
                    )
                } else {
                    submitByCalibration(profile, phrase, calibrationPoint)
                }
            }
            return
        }
        submitByCalibration(profile, phrase, calibrationPoint)
    }

    private fun submitByCalibration(
        profile: TargetProfile,
        phrase: String,
        calibrationPoint: LocatorSpec.CalibrationPoint?,
    ) {
        if (stopped || request == null) return
        if (calibrationPoint != null) {
            service.tapNormalized(calibrationPoint) { clicked ->
                completeSubmit(profile, phrase, clicked, "calibration_point", CALIBRATION_CONFIDENCE)
            }
            return
        }
        completeSubmit(profile, phrase, clicked = false, locatorSource = "none", confidence = null)
    }

    private fun completeSubmit(
        profile: TargetProfile,
        phrase: String,
        clicked: Boolean,
        locatorSource: String,
        confidence: Double?,
    ) {
        if (stopped || request == null) return
        state = AutomationStateMachine.reduce(state, AutomationEvent.Submitted(clicked))
        if (!clicked) {
            fail("找不到发送按钮", "submit_not_found")
            return
        }
        lastSubmitSource = locatorSource
        lastSubmitConfidence = confidence
        recordLocatorUse(profile, "submit", locatorSource, confidence?.toFloat())
        onStatus("已提交，正在验证")
        handler.postDelayed({ verify(profile, phrase) }, VERIFY_DELAY_MS)
    }

    private fun verify(profile: TargetProfile, phrase: String) {
        if (stopped) return
        val active = request ?: return
        val root = service.rootInActiveWindow
        if (root == null) {
            fail("提交结果未确认", "submit_not_verified", SendResult.Unconfirmed)
            return
        }
        val inputStillVisible = AndroidAccessibilityLocator.locate(root, profile.inputLocators)
        val inputCleared = inputStillVisible?.node?.text?.toString().orEmpty().isBlank()
        val verified = inputStillVisible == null || inputCleared
        inputStillVisible?.node?.recycle()
        root.recycle()
        state = AutomationStateMachine.reduce(state, AutomationEvent.Verified(verified))
        if (!verified) {
            fail("提交结果未确认", "submit_not_verified", SendResult.Unconfirmed)
            return
        }
        recordCurrentRound(
            active = active,
            result = SendResult.Submitted,
            errorCode = null,
            finishedAt = clock(),
        )
        Log.i(
            TAG,
            "task=${state.taskId} round=${state.completedCount}/${active.rounds} phraseLength=${phrase.length}",
        )
        if (state.completedCount >= active.rounds) {
            state = AutomationStateMachine.reduce(state, AutomationEvent.Complete)
            persistTaskStatus(AutomationTaskStatus.Completed, finishedAt = clock())
            onDiagnostic(
                DiagnosticLevel.Info,
                "task_completed",
                active.targetId,
                state.taskId,
                mapOf(
                    "completed_count" to state.completedCount.toString(),
                    "composer_reopen_count" to state.composerReopenCount.toString(),
                ),
            )
            onStatus("任务完成 ${state.completedCount}/${active.rounds}")
            request = null
            return
        }
        persistTaskStatus(AutomationTaskStatus.Running)
        onStatus("等待下一轮 ${state.completedCount}/${active.rounds}")
        handler.postDelayed(
            {
                state = AutomationStateMachine.reduce(state, AutomationEvent.IntervalElapsed)
                runRound()
            },
            active.intervalMs,
        )
    }

    private fun fail(
        message: String,
        eventCode: String,
        result: SendResult = SendResult.Failed,
    ) {
        val active = request
        if (active != null) {
            recordCurrentRound(
                active = active,
                result = result,
                errorCode = eventCode,
                finishedAt = clock(),
            )
        }
        state = AutomationStateMachine.reduce(state, AutomationEvent.Fail(message))
        persistTaskStatus(AutomationTaskStatus.Failed, finishedAt = clock())
        Log.w(TAG, "task=${state.taskId} failed message=$message")
        onDiagnostic(
            DiagnosticLevel.Error,
            eventCode,
            active?.targetId,
            state.taskId,
            mapOf(
                "phase" to state.phase.name,
                "completed_count" to state.completedCount.toString(),
            ),
        )
        onStatus(message)
        request = null
    }

    private fun blockCurrentRound(active: TaskRequest, violation: SendSafetyViolation) {
        val now = clock()
        val message = violation.userMessage(now)
        recordCurrentRound(
            active = active,
            result = SendResult.Blocked,
            errorCode = violation.code.eventCode,
            finishedAt = now,
        )
        state = AutomationStateMachine.reduce(state, AutomationEvent.Fail(violation.code.eventCode))
        persistTaskStatus(AutomationTaskStatus.Blocked, finishedAt = now)
        onDiagnostic(
            DiagnosticLevel.Warning,
            violation.code.eventCode,
            active.targetId,
            state.taskId,
            violation.diagnosticDetails(),
        )
        onStatus(message)
        request = null
    }

    private fun recordRejectedRequest(
        rejected: TaskRequest,
        targetId: String,
        violation: SendSafetyViolation,
    ) {
        val now = clock()
        onSendRecord(
            AndroidSendRecordDraft(
                taskId = "android-blocked-$now",
                phraseId = rejected.phraseIds.firstOrNull().orEmpty().ifBlank { "unknown-phrase" },
                packId = rejected.packId,
                targetId = targetId,
                finalText = rejected.phrases.firstOrNull().orEmpty(),
                mode = rejected.mode,
                locatorSource = null,
                confidence = null,
                result = SendResult.Blocked,
                errorCode = violation.code.eventCode,
                startedAt = now,
                finishedAt = now,
            ),
        )
    }

    private fun recordCurrentRound(
        active: TaskRequest,
        result: SendResult,
        errorCode: String?,
        finishedAt: Long,
    ) {
        if (roundRecordWritten || roundStartedAt <= 0L || currentPhraseText.isBlank()) return
        roundRecordWritten = true
        onSendRecord(
            AndroidSendRecordDraft(
                taskId = state.taskId,
                phraseId = currentPhraseId.ifBlank { "unknown-phrase" },
                packId = active.packId,
                targetId = active.targetId ?: active.expectedPackage,
                finalText = currentPhraseText,
                mode = active.mode,
                locatorSource = lastSubmitSource,
                confidence = lastSubmitConfidence,
                result = result,
                errorCode = errorCode,
                startedAt = roundStartedAt,
                finishedAt = finishedAt,
            ),
        )
    }

    private fun persistTaskStatus(
        status: AutomationTaskStatus,
        finishedAt: Long? = null,
    ): Boolean {
        val active = request ?: return false
        val startedAt = taskCreatedAt.takeIf { it > 0L } ?: clock()
        return onTaskStateChanged(
            AutomationTaskRecord(
                id = state.taskId,
                packId = active.packId,
                mode = active.mode,
                expectedPackage = active.expectedPackage,
                targetId = active.targetId ?: active.expectedPackage,
                status = status,
                plannedCount = active.rounds,
                completedCount = state.completedCount.coerceIn(0, active.rounds),
                composerReopenCount = state.composerReopenCount.coerceAtLeast(0),
                createdAt = startedAt,
                startedAt = startedAt,
                finishedAt = finishedAt,
            ),
        )
    }

    private fun SendSafetyViolation.userMessage(now: Long): String = when (code) {
        SendSafetyViolationCode.TargetCapabilityInsufficient ->
            "当前目标能力不足，逐条提交需要 L2，连续任务需要 L3"
        SendSafetyViolationCode.TaskItemCountInvalid -> "任务没有可执行内容"
        SendSafetyViolationCode.TaskItemLimitExceeded ->
            "单次连续任务最多 ${limit ?: safetyLimits.maximumContinuousItems} 条"
        SendSafetyViolationCode.ContinuousIntervalTooShort ->
            "连续任务间隔不能低于 ${(limit ?: safetyLimits.minimumContinuousIntervalMs) / 1_000L} 秒"
        SendSafetyViolationCode.TargetHourlyLimitReached ->
            "当前目标已达到每小时 ${limit ?: safetyLimits.maximumSubmittedPerTargetWindow} 条上限"
        SendSafetyViolationCode.ContinuousCooldownActive -> {
            val remainingMs = ((retryAt ?: now) - now).coerceAtLeast(0L)
            val remainingSeconds = (remainingMs + 999L) / 1_000L
            "请等待 $remainingSeconds 秒后再开始连续任务"
        }
        SendSafetyViolationCode.TextTooLong ->
            "内容长度 ${actual ?: 0} 超过目标允许的 ${limit ?: 0} 字"
    }

    private fun SendSafetyViolation.diagnosticDetails(): Map<String, String> = buildMap {
        actual?.let { put("actual", it.toString()) }
        limit?.let { put("limit", it.toString()) }
        retryAt?.let { put("retry_at", it.toString()) }
    }

    private fun locateVisualAndTap(
        profile: TargetProfile,
        specs: List<LocatorSpec>,
        role: String,
        onComplete: (VisualLocatorResult?, Boolean) -> Unit,
    ) {
        locateVisual(profile, specs, role) { match ->
            if (match == null || stopped || request == null) {
                onComplete(match, false)
                return@locateVisual
            }
            service.tapPixel(match.screenBounds.centerX, match.screenBounds.centerY) { clicked ->
                onComplete(match, clicked)
            }
        }
    }

    private fun locateVisual(
        profile: TargetProfile,
        specs: List<LocatorSpec>,
        role: String,
        onComplete: (VisualLocatorResult?) -> Unit,
    ) {
        if (!specs.hasVisualLocator()) {
            onComplete(null)
            return
        }
        visualJob?.cancel()
        visualJob = visualScope.launch {
            val match = visualLocatorEngine.locateFirst(
                locators = specs,
                captureProvider = { locator ->
                    if (stopped || request == null) null else obtainVisualCapture(profile, locator, role)
                },
                onRecognitionFailure = { _, _ ->
                    onDiagnostic(
                        DiagnosticLevel.Warning,
                        "visual_recognition_failed",
                        profile.id,
                        state.taskId,
                        mapOf("role" to role),
                    )
                },
            )
            if (stopped || request == null) return@launch
            if (match != null) {
                onComplete(match)
            } else {
                onComplete(null)
            }
        }
    }

    private suspend fun obtainVisualCapture(
        profile: TargetProfile,
        locator: LocatorSpec,
        role: String,
    ): PreparedCapture? {
        val captureRequest = captureRequestForVisualLocator(
            locator = locator,
            ignoredRegions = profile.ignoredVisualRegions,
            overlayRegions = overlayRegionsProvider(),
        )
        var result = visualCaptureCoordinator.capture(captureRequest)
        if (result is ScreenCaptureResult.Deferred) {
            val waitMs = (result.retryAt - clock()).coerceIn(1L, MAX_VISUAL_BACKOFF_WAIT_MS)
            onStatus("视觉定位正在退避 ${waitMs}ms")
            delay(waitMs)
            if (stopped || request == null) return null
            result = visualCaptureCoordinator.capture(captureRequest)
        }
        return when (result) {
            is ScreenCaptureResult.Success -> result.capture
            is ScreenCaptureResult.Deferred -> {
                recordVisualCaptureIssue(profile, role, result.reason)
                null
            }
            is ScreenCaptureResult.Unavailable -> {
                recordVisualCaptureIssue(profile, role, result.reason)
                null
            }
            is ScreenCaptureResult.Failed -> {
                recordVisualCaptureIssue(profile, role, result.errorCode)
                null
            }
        }
    }

    private fun recordVisualCaptureIssue(profile: TargetProfile, role: String, reason: String) {
        onDiagnostic(
            DiagnosticLevel.Warning,
            "visual_capture_unavailable",
            profile.id,
            state.taskId,
            mapOf("role" to role, "reason" to reason),
        )
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val clicked = parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = if (clicked) null else parent.parent
            parent.recycle()
            if (clicked) return true
            parent = next
            depth += 1
        }
        return false
    }

    private val VisualLocatorResult.sourceName: String
        get() = when (this) {
            is VisualLocatorResult.Ocr -> "ocr_text"
            is VisualLocatorResult.Template -> "local_template"
        }

    private fun recordLocatorUse(
        profile: TargetProfile,
        role: String,
        source: String,
        confidence: Float? = null,
    ) {
        when (source) {
            "calibration_point" -> onDiagnostic(
                DiagnosticLevel.Info,
                "calibration_locator_used",
                profile.id,
                state.taskId,
                mapOf("role" to role),
            )
            "ocr_text", "local_template" -> onDiagnostic(
                DiagnosticLevel.Info,
                "visual_locator_used",
                profile.id,
                state.taskId,
                buildMap {
                    put("role", role)
                    put("source", source)
                    confidence?.let { put("confidence", it.toString()) }
                },
            )
        }
    }

    private fun TaskPhase.isTerminal(): Boolean = this in setOf(
        TaskPhase.Cancelled,
        TaskPhase.Failed,
        TaskPhase.Completed,
    )

    private data class TaskRequest(
        val phrases: List<String>,
        val phraseIds: List<String>,
        val packId: String,
        val mode: SendMode,
        val rounds: Int,
        val intervalMs: Long,
        val expectedPackage: String,
        val textAlreadyInserted: Boolean,
        val targetId: String? = null,
        val targetProfileSnapshot: TargetProfile? = null,
    )

    companion object {
        private const val TAG = "DanmuKeyTask"
        private const val UI_SETTLE_MS = 450L
        private const val ACTION_SETTLE_MS = 180L
        private const val VERIFY_DELAY_MS = 500L
        private const val MAX_VISUAL_BACKOFF_WAIT_MS = 16_000L
        private const val CALIBRATION_CONFIDENCE = 0.75
    }
}

internal data class AndroidSendRecordDraft(
    val taskId: String,
    val phraseId: String,
    val packId: String,
    val targetId: String,
    val finalText: String,
    val mode: SendMode,
    val locatorSource: String?,
    val confidence: Double?,
    val result: SendResult,
    val errorCode: String?,
    val startedAt: Long,
    val finishedAt: Long,
)
