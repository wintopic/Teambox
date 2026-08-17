package com.danmukey.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.danmukey.shared.data.ContentCodec
import com.danmukey.shared.data.DanmuKeyRepository
import com.danmukey.shared.data.SampleContent
import com.danmukey.shared.db.DanmuKeyDatabase
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmuKeyAppControllerTest {
    @Test
    fun invalidJsonPackIsRejectedBeforePersistence() {
        val repository = createRepository()
        val controller = DanmuKeyAppController(repository = repository, now = { 1_000L })
        val packsBeforeImport = repository.loadAllPacks()
        val invalid = SampleContent.createPack(100L).copy(name = " ")

        controller.importJson(ContentCodec.encode(invalid, exportedAt = 200L))

        assertEquals(packsBeforeImport, repository.loadAllPacks())
        assertTrue(controller.statusMessage.orEmpty().startsWith("导入失败：内容包名称不能为空"))
    }

    @Test
    fun initializationAppliesSharedLocalDataRetentionPolicy() {
        val day = 24L * 60L * 60L * 1_000L
        val now = 40L * day
        val repository = createRepository()

        repository.recordDiagnostic(
            level = DiagnosticLevel.Info,
            eventCode = "expired_diagnostic",
            createdAt = now - 31L * day,
        )
        repository.recordDiagnostic(
            level = DiagnosticLevel.Info,
            eventCode = "retained_diagnostic",
            createdAt = now - 29L * day,
        )
        recordSend(repository, targetId = "expired-target", finishedAt = now - 25L * 60L * 60L * 1_000L)
        recordSend(repository, targetId = "retained-target", finishedAt = now - 23L * 60L * 60L * 1_000L)
        repository.clearSendRecordDetails()

        val controller = DanmuKeyAppController(repository = repository, now = { now })

        assertEquals(listOf("retained_diagnostic"), controller.diagnostics.map { it.eventCode })
        assertEquals(
            null,
            repository.loadSendQuotaSnapshot("expired-target", since = 0L).latestAttemptAt,
        )
        assertEquals(
            now - 23L * 60L * 60L * 1_000L,
            repository.loadSendQuotaSnapshot("retained-target", since = 0L).latestAttemptAt,
        )
    }

    @Test
    fun clearingDiagnosticsAlsoClearsPlatformDiagnosticArtifacts() {
        val repository = createRepository()
        repository.recordDiagnostic(
            level = DiagnosticLevel.Info,
            eventCode = "test_diagnostic",
            createdAt = 1_000L,
        )
        var clearCalls = 0
        val controller = DanmuKeyAppController(
            repository = repository,
            now = { 2_000L },
            onClearDiagnosticArtifacts = {
                clearCalls += 1
                3
            },
        )

        controller.clearDiagnostics()

        assertEquals(1, clearCalls)
        assertTrue(repository.loadRecentDiagnostics().isEmpty())
        assertTrue(controller.diagnostics.isEmpty())
        assertEquals("已清除运行诊断和 3 个脱敏控件样本", controller.statusMessage)
    }

    private fun recordSend(repository: DanmuKeyRepository, targetId: String, finishedAt: Long) {
        repository.recordSend(
            taskId = "task-$targetId",
            phraseId = "phrase-$targetId",
            packId = "pack-1",
            targetId = targetId,
            finalText = "待清理记录",
            mode = SendMode.Continuous,
            locatorSource = "resource_id",
            confidence = 1.0,
            result = SendResult.Submitted,
            errorCode = null,
            startedAt = finishedAt - 100L,
            finishedAt = finishedAt,
        )
    }

    private fun createRepository(): DanmuKeyRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DanmuKeyDatabase.Schema.create(driver)
        return DanmuKeyRepository(DanmuKeyDatabase(driver))
    }
}
