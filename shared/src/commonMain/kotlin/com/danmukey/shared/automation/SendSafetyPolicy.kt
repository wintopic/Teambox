package com.danmukey.shared.automation

import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.TargetCapabilityLevel

data class SendSafetyLimits(
    val minimumContinuousIntervalMs: Long = 5_000L,
    val maximumContinuousItems: Int = 20,
    val maximumSubmittedPerTargetWindow: Long = 60L,
    val targetWindowMs: Long = 60L * 60L * 1_000L,
) {
    init {
        require(minimumContinuousIntervalMs > 0L)
        require(maximumContinuousItems > 0)
        require(maximumSubmittedPerTargetWindow > 0L)
        require(targetWindowMs > 0L)
    }
}

data class SendQuotaSnapshot(
    val submittedCountInWindow: Long,
    val latestAttemptAt: Long?,
) {
    init {
        require(submittedCountInWindow >= 0L)
    }
}

enum class SendSafetyViolationCode(val eventCode: String) {
    TargetCapabilityInsufficient("target_capability_insufficient"),
    TaskItemCountInvalid("task_item_count_invalid"),
    TaskItemLimitExceeded("task_item_limit_exceeded"),
    ContinuousIntervalTooShort("continuous_interval_too_short"),
    TargetHourlyLimitReached("target_hourly_limit_reached"),
    ContinuousCooldownActive("continuous_cooldown_active"),
    TextTooLong("text_too_long"),
}

data class SendSafetyViolation(
    val code: SendSafetyViolationCode,
    val actual: Long? = null,
    val limit: Long? = null,
    val retryAt: Long? = null,
)

object SendSafetyPolicy {
    fun validateTask(
        mode: SendMode,
        capabilityLevel: TargetCapabilityLevel,
        itemCount: Int,
        intervalMs: Long,
        limits: SendSafetyLimits = SendSafetyLimits(),
    ): SendSafetyViolation? {
        if (itemCount <= 0) {
            return SendSafetyViolation(
                code = SendSafetyViolationCode.TaskItemCountInvalid,
                actual = itemCount.toLong(),
                limit = 1L,
            )
        }

        val requiredCapability = when (mode) {
            SendMode.InsertOnly -> TargetCapabilityLevel.L1
            SendMode.TapToSend -> TargetCapabilityLevel.L2
            SendMode.Continuous -> TargetCapabilityLevel.L3
        }
        if (capabilityLevel < requiredCapability) {
            return SendSafetyViolation(
                code = SendSafetyViolationCode.TargetCapabilityInsufficient,
                actual = capabilityLevel.ordinal.toLong(),
                limit = requiredCapability.ordinal.toLong(),
            )
        }

        if (mode != SendMode.Continuous) return null
        if (itemCount > limits.maximumContinuousItems) {
            return SendSafetyViolation(
                code = SendSafetyViolationCode.TaskItemLimitExceeded,
                actual = itemCount.toLong(),
                limit = limits.maximumContinuousItems.toLong(),
            )
        }
        if (intervalMs < limits.minimumContinuousIntervalMs) {
            return SendSafetyViolation(
                code = SendSafetyViolationCode.ContinuousIntervalTooShort,
                actual = intervalMs,
                limit = limits.minimumContinuousIntervalMs,
            )
        }
        return null
    }

    fun validateAttempt(
        mode: SendMode,
        now: Long,
        quota: SendQuotaSnapshot,
        limits: SendSafetyLimits = SendSafetyLimits(),
    ): SendSafetyViolation? {
        if (quota.submittedCountInWindow >= limits.maximumSubmittedPerTargetWindow) {
            return SendSafetyViolation(
                code = SendSafetyViolationCode.TargetHourlyLimitReached,
                actual = quota.submittedCountInWindow,
                limit = limits.maximumSubmittedPerTargetWindow,
            )
        }

        val latestAttemptAt = quota.latestAttemptAt
        if (mode == SendMode.Continuous && latestAttemptAt != null) {
            val retryAt = latestAttemptAt + limits.minimumContinuousIntervalMs
            if (now < retryAt) {
                return SendSafetyViolation(
                    code = SendSafetyViolationCode.ContinuousCooldownActive,
                    actual = (now - latestAttemptAt).coerceAtLeast(0L),
                    limit = limits.minimumContinuousIntervalMs,
                    retryAt = retryAt,
                )
            }
        }
        return null
    }

    fun validateText(text: String, maximumLength: Int?): SendSafetyViolation? {
        val limit = maximumLength ?: return null
        if (text.length <= limit) return null
        return SendSafetyViolation(
            code = SendSafetyViolationCode.TextTooLong,
            actual = text.length.toLong(),
            limit = limit.toLong(),
        )
    }
}
