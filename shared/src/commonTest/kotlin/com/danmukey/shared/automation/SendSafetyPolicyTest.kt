package com.danmukey.shared.automation

import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.TargetCapabilityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendSafetyPolicyTest {
    private val limits = SendSafetyLimits()

    @Test
    fun continuousTasksRequireL3Capability() {
        val violation = SendSafetyPolicy.validateTask(
            mode = SendMode.Continuous,
            capabilityLevel = TargetCapabilityLevel.L2,
            itemCount = 5,
            intervalMs = 5_000L,
            limits = limits,
        )

        assertEquals(SendSafetyViolationCode.TargetCapabilityInsufficient, violation?.code)
    }

    @Test
    fun continuousIntervalCannotGoBelowFiveSeconds() {
        val violation = SendSafetyPolicy.validateTask(
            mode = SendMode.Continuous,
            capabilityLevel = TargetCapabilityLevel.L3,
            itemCount = 5,
            intervalMs = 4_999L,
            limits = limits,
        )

        assertEquals(SendSafetyViolationCode.ContinuousIntervalTooShort, violation?.code)
        assertEquals(5_000L, violation?.limit)
    }

    @Test
    fun continuousTaskCannotExceedTwentyItems() {
        val violation = SendSafetyPolicy.validateTask(
            mode = SendMode.Continuous,
            capabilityLevel = TargetCapabilityLevel.L3,
            itemCount = 21,
            intervalMs = 5_000L,
            limits = limits,
        )

        assertEquals(SendSafetyViolationCode.TaskItemLimitExceeded, violation?.code)
        assertEquals(20L, violation?.limit)
    }

    @Test
    fun targetHourlyQuotaBlocksAllAutomaticSubmissions() {
        val violation = SendSafetyPolicy.validateAttempt(
            mode = SendMode.TapToSend,
            now = 3_600_000L,
            quota = SendQuotaSnapshot(
                submittedCountInWindow = 60L,
                latestAttemptAt = 3_590_000L,
            ),
            limits = limits,
        )

        assertEquals(SendSafetyViolationCode.TargetHourlyLimitReached, violation?.code)
    }

    @Test
    fun continuousTaskCannotBypassCooldownByRestarting() {
        val violation = SendSafetyPolicy.validateAttempt(
            mode = SendMode.Continuous,
            now = 12_000L,
            quota = SendQuotaSnapshot(
                submittedCountInWindow = 1L,
                latestAttemptAt = 10_000L,
            ),
            limits = limits,
        )

        assertEquals(SendSafetyViolationCode.ContinuousCooldownActive, violation?.code)
        assertEquals(15_000L, violation?.retryAt)
    }

    @Test
    fun validTapToSendIsNotSubjectToContinuousCooldown() {
        val violation = SendSafetyPolicy.validateAttempt(
            mode = SendMode.TapToSend,
            now = 12_000L,
            quota = SendQuotaSnapshot(
                submittedCountInWindow = 1L,
                latestAttemptAt = 11_999L,
            ),
            limits = limits,
        )

        assertNull(violation)
    }

    @Test
    fun targetTextLengthIsAHardPrecondition() {
        val violation = SendSafetyPolicy.validateText("123456", maximumLength = 5)

        assertEquals(SendSafetyViolationCode.TextTooLong, violation?.code)
        assertEquals(6L, violation?.actual)
        assertEquals(5L, violation?.limit)
    }
}
