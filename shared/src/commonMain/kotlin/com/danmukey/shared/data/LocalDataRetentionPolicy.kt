package com.danmukey.shared.data

object LocalDataRetentionPolicy {
    const val DIAGNOSTIC_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    const val CLEARED_SEND_SAFETY_RETENTION_MS = 24L * 60L * 60L * 1_000L

    fun diagnosticCutoff(now: Long): Long = cutoff(now, DIAGNOSTIC_RETENTION_MS)

    fun clearedSendSafetyCutoff(now: Long): Long = cutoff(now, CLEARED_SEND_SAFETY_RETENTION_MS)

    private fun cutoff(now: Long, retentionMs: Long): Long =
        if (now <= retentionMs) 0L else now - retentionMs
}
