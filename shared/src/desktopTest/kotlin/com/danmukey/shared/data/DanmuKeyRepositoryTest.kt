package com.danmukey.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.danmukey.shared.db.DanmuKeyDatabase
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.SendResult
import com.danmukey.shared.model.ContentFollowState
import com.danmukey.shared.model.AutomationTaskRecord
import com.danmukey.shared.model.AutomationTaskStatus
import com.danmukey.shared.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DanmuKeyRepositoryTest {
    @Test
    fun interruptedAutomationTasksBecomeFailedAndAreNeverResumed() {
        val repository = createRepository()
        val running = AutomationTaskRecord(
            id = "task-running",
            packId = "pack-1",
            mode = SendMode.Continuous,
            expectedPackage = "com.example.video",
            targetId = "target-1",
            status = AutomationTaskStatus.Running,
            plannedCount = 5,
            completedCount = 2,
            composerReopenCount = 1,
            createdAt = 1_000L,
            startedAt = 1_000L,
        )
        val completed = running.copy(
            id = "task-completed",
            status = AutomationTaskStatus.Completed,
            completedCount = 5,
            finishedAt = 4_000L,
        )
        repository.saveAutomationTask(running)
        repository.saveAutomationTask(completed)

        val recovered = repository.failInterruptedAutomationTasks(now = 5_000L)

        assertEquals(listOf("task-running"), recovered.map(AutomationTaskRecord::id))
        assertEquals(
            running.copy(status = AutomationTaskStatus.Failed, finishedAt = 5_000L),
            repository.loadAutomationTask("task-running"),
        )
        assertEquals(completed, repository.loadAutomationTask("task-completed"))
        assertTrue(repository.failInterruptedAutomationTasks(now = 6_000L).isEmpty())
    }

    @Test
    fun diagnosticCleanupRemovesOnlyTerminalAutomationTasks() {
        val repository = createRepository()
        val running = automationTask(
            id = "task-running",
            status = AutomationTaskStatus.Running,
            finishedAt = null,
        )
        val failed = automationTask(
            id = "task-failed",
            status = AutomationTaskStatus.Failed,
            finishedAt = 2_000L,
        )
        repository.saveAutomationTask(running)
        repository.saveAutomationTask(failed)

        repository.clearDiagnostics()

        assertEquals(running, repository.loadAutomationTask(running.id))
        assertEquals(null, repository.loadAutomationTask(failed.id))
    }

    @Test
    fun seedContentCanBeLoadedAndRoundTripped() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)

        val pack = repository.loadAllPacks().single()
        assertEquals("示例弹幕键盘", pack.name)
        assertTrue(pack.sections.flatMap { it.groups }.flatMap { it.phrases }.isNotEmpty())

        val encoded = ContentCodec.encode(pack, exportedAt = 2_000L)
        val decoded = ContentCodec.decode(encoded)
        assertEquals(pack, decoded)
    }

    @Test
    fun editedPhraseIsPersistedAndSearchable() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val original = repository.loadAllPacks().single()
        val section = original.sections.first()
        val group = section.groups.first()
        val added = PhraseItem(
            id = "new-phrase",
            text = "只用于数据库回归测试的弹幕",
            order = group.phrases.size,
        )
        val updatedGroup = group.copy(phrases = group.phrases + added)
        val updatedSection = section.copy(groups = listOf(updatedGroup) + section.groups.drop(1))
        repository.savePack(
            original.copy(
                sections = listOf(updatedSection) + original.sections.drop(1),
                updatedAt = 2_000L,
            ),
        )

        val loaded = repository.loadPack(original.id)
        assertNotNull(loaded)
        assertEquals(added, loaded.sections.first().groups.first().phrases.last())
        assertEquals(listOf(added), repository.searchPhrases(original.id, "数据库回归"))
    }

    @Test
    fun usageCounterAccumulatesPerTarget() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val phraseId = repository.loadAllPacks().first()
            .sections.first().groups.first().phrases.first().id

        repository.recordPhraseUse(phraseId, "test-host", usedAt = 2_000L)
        repository.recordPhraseUse(phraseId, "test-host", usedAt = 3_000L)

        val usage = repository.usageForTarget("test-host").getValue(phraseId)
        assertEquals(2L, usage.useCount)
        assertEquals(3_000L, usage.lastUsedAt)
    }

    @Test
    fun episodeMappingsNormalizeResolveAndSurvivePackEdits() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val pack = repository.loadAllPacks().single()
        val episodeSection = pack.sections.single { it.episodeNumber == 1 }

        val saved = repository.saveEpisodeMapping(
            targetId = "com.example.video",
            observedTitle = " 第一季 / 第 1 集 ",
            sectionId = episodeSection.id,
            confidence = 0.95,
            now = 2_000L,
        )
        assertEquals("第一季第1集", saved.normalizedTitle)
        assertEquals(
            episodeSection.id,
            repository.resolveEpisodeMapping("com.example.video", "第一季·第1集")?.sectionId,
        )
        assertEquals(
            null,
            repository.resolveEpisodeMapping(
                "com.example.video",
                "第一季第1集",
                minimumConfidence = 0.99,
            ),
        )

        repository.savePack(pack.copy(description = "编辑后仍保留映射", updatedAt = 3_000L))
        assertEquals(
            saved,
            repository.loadEpisodeMappings(pack.id).single { it.targetId == "com.example.video" },
        )

        repository.deleteEpisodeMapping(saved.id)
        assertTrue(repository.loadEpisodeMappings(pack.id).none { it.targetId == "com.example.video" })
    }

    @Test
    fun equivalentEpisodeTitlesResolveButConflictingAliasesStayAmbiguous() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val pack = repository.loadAllPacks().single()
        val episodeSection = pack.sections.single { it.episodeNumber == 1 }
        val otherSection = pack.sections.single { it.episodeNumber == null }

        repository.saveEpisodeMapping(
            targetId = "com.example.video",
            observedTitle = "庆余年 第二季 第三集",
            sectionId = episodeSection.id,
            confidence = 0.95,
            now = 2_000L,
        )

        assertEquals(
            episodeSection.id,
            repository.resolveEpisodeMapping("com.example.video", "庆余年 S02E03")?.sectionId,
        )
        assertEquals(
            episodeSection.id,
            repository.resolveEpisodeMapping("com.example.video", "庆余年 Ｓ０２Ｅ０３")?.sectionId,
        )
        assertEquals(null, repository.resolveEpisodeMapping("com.example.video", "三体 S02E03"))

        repository.saveEpisodeMapping(
            targetId = "com.example.video",
            observedTitle = "庆余年 Season 2 Episode 03",
            sectionId = otherSection.id,
            confidence = 0.99,
            now = 3_000L,
        )

        assertEquals(
            otherSection.id,
            repository.resolveEpisodeMapping(
                "com.example.video",
                "庆余年 Season 2 Episode 03",
            )?.sectionId,
        )
        assertEquals(
            null,
            repository.resolveEpisodeMapping("com.example.video", "庆余年 第2季 第3话"),
        )
    }

    @Test
    fun contentFollowStateRoundTripsWithFreshnessGate() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val state = ContentFollowState(
            targetId = SampleTargets.testHost.id,
            appIdentifier = "com.danmukey.testhost",
            packId = SampleContent.SAMPLE_PACK_ID,
            sectionId = SampleContent.SAMPLE_EPISODE_ONE_ID,
            groupId = "sample-scene-1",
            playbackPositionMs = 30_000L,
            confidence = 0.98,
            observedAt = 5_000L,
        )

        repository.saveContentFollowState(state)

        assertEquals(state, repository.loadLatestContentFollowState("com.danmukey.testhost", 4_000L))
        assertEquals(null, repository.loadLatestContentFollowState("com.danmukey.testhost", 6_000L))
        assertEquals(
            SampleContent.SAMPLE_PACK_ID,
            repository.loadPackContainingSection(state.sectionId)?.id,
        )
        repository.clearContentFollowState(state.targetId)
        assertEquals(null, repository.loadLatestContentFollowState("com.danmukey.testhost", 0L))
    }

    @Test
    fun mappingsForRemovedSectionsArePrunedDuringPackSave() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val pack = repository.loadAllPacks().single()
        val episodeSection = pack.sections.single { it.episodeNumber == 1 }
        repository.saveEpisodeMapping(
            targetId = "com.example.video",
            observedTitle = "第1集",
            sectionId = episodeSection.id,
            confidence = 1.0,
            now = 2_000L,
        )

        repository.savePack(
            pack.copy(
                sections = pack.sections.filterNot { it.id == episodeSection.id },
                updatedAt = 3_000L,
            ),
        )

        assertTrue(repository.loadEpisodeMappings(pack.id).isEmpty())
    }

    @Test
    fun builtInProfilesCannotBeDeletedButCustomProfilesCan() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)

        SampleTargets.builtInProfileIds.forEach { profileId ->
            assertFalse(repository.deleteTargetProfile(profileId))
        }
        assertTrue(repository.loadTargetProfiles().map { it.id }.containsAll(SampleTargets.builtInProfileIds))

        val custom = SampleTargets.testHost.copy(id = "custom-profile", displayName = "本地标定")
        repository.saveTargetProfile(custom, now = 2_000L)
        assertTrue(repository.deleteTargetProfile(custom.id))
        assertTrue(repository.loadTargetProfiles().none { it.id == custom.id })
    }

    @Test
    fun builtInProfilesUpgradeWhenNoVerifiedRuleOwnsTheirState() {
        val repository = createRepository()
        val stale = SampleTargets.testHost.copy(
            episodeTitleLocators = emptyList(),
            playbackTimeLocators = emptyList(),
        )
        repository.saveTargetProfile(stale, now = 1_000L)

        repository.ensureSeedData(now = 2_000L)

        assertEquals(
            SampleTargets.testHost,
            repository.loadTargetProfiles().single { it.id == SampleTargets.testHost.id },
        )
    }

    @Test
    fun signedDisablePreventsBuiltInProfileFromBeingReseeded() {
        val repository = createRepository()
        repository.ensureSeedData(now = 1_000L)
        val builtIn = SampleTargets.tencentVideoObservation
        repository.importTargetRule(
            importedRule(
                revision = 2,
                ruleId = builtIn.id,
                profile = builtIn.copy(profileVersion = 2),
                disabled = true,
                initialState = TargetRuleState.Disabled,
            ),
            TargetRuleSource.Remote,
            now = 2_000L,
        )

        repository.ensureSeedData(now = 3_000L)

        assertTrue(repository.loadTargetProfiles().none { it.id == builtIn.id })
        assertEquals(
            TargetRuleState.Disabled,
            repository.loadTargetRuleRevisions(builtIn.id).single().state,
        )
    }

    @Test
    fun diagnosticEventsRoundTripWithoutMessageBodies() {
        val repository = createRepository()
        repository.recordDiagnostic(
            level = DiagnosticLevel.Warning,
            eventCode = "input_not_found",
            createdAt = 4_000L,
            targetId = "test-host",
            taskId = "task-1",
            details = mapOf("locator_source" to "accessibility", "orientation" to "portrait"),
        )

        val event = repository.loadRecentDiagnostics().single()
        assertEquals(DiagnosticLevel.Warning, event.level)
        assertEquals("input_not_found", event.eventCode)
        assertEquals("test-host", event.targetId)
        assertEquals("task-1", event.taskId)
        assertEquals("accessibility", event.details["locator_source"])
    }

    @Test
    fun sendRecordsPreserveOnlyToolExecutedTextAndMetadata() {
        val repository = createRepository()
        repository.recordSend(
            taskId = "task-1",
            phraseId = "phrase-1",
            packId = "pack-1",
            targetId = "test-host",
            finalText = "怪团建主动发送的测试内容",
            mode = SendMode.TapToSend,
            locatorSource = "resource_id",
            confidence = 0.99,
            result = SendResult.Submitted,
            errorCode = null,
            startedAt = 5_000L,
            finishedAt = 5_500L,
        )

        val record = repository.loadRecentSendRecords().single()
        assertEquals("怪团建主动发送的测试内容", record.finalText)
        assertEquals(SendMode.TapToSend, record.mode)
        assertEquals("resource_id", record.locatorSource)
        assertEquals(SendResult.Submitted, record.result)
    }

    @Test
    fun sendQuotaCountsSubmittedRecordsAndTracksLatestAttempt() {
        val repository = createRepository()
        fun record(
            result: SendResult,
            startedAt: Long,
            targetId: String = "test-host",
        ) {
            repository.recordSend(
                taskId = "task-$startedAt",
                phraseId = "phrase-$startedAt",
                packId = "pack-1",
                targetId = targetId,
                finalText = "测试内容",
                mode = SendMode.Continuous,
                locatorSource = "resource_id",
                confidence = 1.0,
                result = result,
                errorCode = if (result == SendResult.Failed) "submit_not_found" else null,
                startedAt = startedAt,
                finishedAt = startedAt + 100L,
            )
        }

        record(SendResult.Submitted, startedAt = 1_000L)
        record(SendResult.Submitted, startedAt = 2_000L)
        record(SendResult.Failed, startedAt = 3_000L)
        record(SendResult.Submitted, startedAt = 4_000L, targetId = "other-target")

        val quota = repository.loadSendQuotaSnapshot(targetId = "test-host", since = 1_500L)
        assertEquals(1L, quota.submittedCountInWindow)
        assertEquals(3_100L, quota.latestAttemptAt)
    }

    @Test
    fun clearingVisibleLogsDoesNotResetSafetyQuota() {
        val repository = createRepository()
        repository.recordDiagnostic(
            level = DiagnosticLevel.Info,
            eventCode = "test_event",
            createdAt = 4_000L,
            targetId = "test-host",
        )
        repository.recordSend(
            taskId = "task-1",
            phraseId = "phrase-1",
            packId = "pack-1",
            targetId = "test-host",
            finalText = "清理后不应继续显示的正文",
            mode = SendMode.Continuous,
            locatorSource = "resource_id",
            confidence = 1.0,
            result = SendResult.Submitted,
            errorCode = null,
            startedAt = 5_000L,
            finishedAt = 5_500L,
        )

        repository.clearDiagnostics()
        repository.clearSendRecordDetails()

        assertTrue(repository.loadRecentDiagnostics().isEmpty())
        assertTrue(repository.loadRecentSendRecords().isEmpty())
        val quota = repository.loadSendQuotaSnapshot(targetId = "test-host", since = 0L)
        assertEquals(1L, quota.submittedCountInWindow)
        assertEquals(5_500L, quota.latestAttemptAt)
    }

    @Test
    fun clearedSafetyCountersAreDeletedAfterRetentionWindow() {
        val repository = createRepository()
        fun record(targetId: String, finishedAt: Long) {
            repository.recordSend(
                taskId = "task-$targetId",
                phraseId = "phrase-$targetId",
                packId = "pack-1",
                targetId = targetId,
                finalText = "清理后只保留安全计数",
                mode = SendMode.Continuous,
                locatorSource = "resource_id",
                confidence = 1.0,
                result = SendResult.Submitted,
                errorCode = null,
                startedAt = finishedAt - 100L,
                finishedAt = finishedAt,
            )
        }

        record(targetId = "expired-target", finishedAt = 1_000L)
        record(targetId = "boundary-target", finishedAt = 1_500L)
        record(targetId = "retained-target", finishedAt = 2_000L)
        repository.clearSendRecordDetails()
        repository.pruneClearedSendRecords(before = 1_500L)

        assertTrue(repository.loadRecentSendRecords().isEmpty())
        val expired = repository.loadSendQuotaSnapshot(targetId = "expired-target", since = 0L)
        assertEquals(0L, expired.submittedCountInWindow)
        assertEquals(null, expired.latestAttemptAt)
        val boundary = repository.loadSendQuotaSnapshot(targetId = "boundary-target", since = 0L)
        assertEquals(0L, boundary.submittedCountInWindow)
        assertEquals(null, boundary.latestAttemptAt)
        val retained = repository.loadSendQuotaSnapshot(targetId = "retained-target", since = 0L)
        assertEquals(1L, retained.submittedCountInWindow)
        assertEquals(2_000L, retained.latestAttemptAt)
    }

    @Test
    fun localRetentionPrunesExpiredDiagnosticsAndOnlyClearedSendRecords() {
        val repository = createRepository()
        val day = 24L * 60L * 60L * 1_000L
        val now = 40L * day

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
        repository.saveAutomationTask(
            automationTask(
                id = "expired-task",
                status = AutomationTaskStatus.Completed,
                createdAt = now - 32L * day,
                finishedAt = now - 31L * day,
            ),
        )
        val retainedTask = automationTask(
            id = "retained-task",
            status = AutomationTaskStatus.Completed,
            createdAt = now - 30L * day,
            finishedAt = now - 29L * day,
        )
        repository.saveAutomationTask(retainedTask)

        fun record(targetId: String, finishedAt: Long, text: String) {
            repository.recordSend(
                taskId = "task-$targetId",
                phraseId = "phrase-$targetId",
                packId = "pack-1",
                targetId = targetId,
                finalText = text,
                mode = SendMode.Continuous,
                locatorSource = "resource_id",
                confidence = 1.0,
                result = SendResult.Submitted,
                errorCode = null,
                startedAt = finishedAt - 100L,
                finishedAt = finishedAt,
            )
        }

        record("expired-cleared", now - 25L * 60L * 60L * 1_000L, "过期后已清理正文")
        record("retained-cleared", now - 23L * 60L * 60L * 1_000L, "保留期内已清理正文")
        repository.clearSendRecordDetails()
        record("visible-history", now - 2L * day, "用户尚未清理的历史正文")

        repository.pruneExpiredLocalRecords(now)

        assertEquals(
            listOf("retained_diagnostic"),
            repository.loadRecentDiagnostics().map { it.eventCode },
        )
        assertEquals(
            listOf("用户尚未清理的历史正文"),
            repository.loadRecentSendRecords().map { it.finalText },
        )
        assertEquals(null, repository.loadAutomationTask("expired-task"))
        assertEquals(retainedTask, repository.loadAutomationTask("retained-task"))
        assertEquals(
            null,
            repository.loadSendQuotaSnapshot("expired-cleared", since = 0L).latestAttemptAt,
        )
        assertEquals(
            now - 23L * 60L * 60L * 1_000L,
            repository.loadSendQuotaSnapshot("retained-cleared", since = 0L).latestAttemptAt,
        )
        assertEquals(
            now - 2L * day,
            repository.loadSendQuotaSnapshot("visible-history", since = 0L).latestAttemptAt,
        )
    }

    private fun automationTask(
        id: String,
        status: AutomationTaskStatus,
        createdAt: Long = 1_000L,
        finishedAt: Long?,
    ): AutomationTaskRecord = AutomationTaskRecord(
        id = id,
        packId = "pack-1",
        mode = SendMode.Continuous,
        expectedPackage = "com.example.video",
        targetId = "target-1",
        status = status,
        plannedCount = 5,
        completedCount = if (status == AutomationTaskStatus.Completed) 5 else 2,
        composerReopenCount = 1,
        createdAt = createdAt,
        startedAt = createdAt,
        finishedAt = finishedAt,
    )

    @Test
    fun signedTargetRulesRequireObservationBeforeActivationAndCanRollback() {
        val repository = createRepository()
        val revisionOne = importedRule(revision = 1)
        val revisionTwo = importedRule(revision = 2)

        val importedOne = repository.importTargetRule(revisionOne, TargetRuleSource.LocalImport, now = 1_000L)
        assertEquals(TargetRuleState.Observation, importedOne.state)
        assertTrue(repository.loadTargetProfiles().none { it.id == RULE_ID })

        repository.activateTargetRule(RULE_ID, revision = 1, now = 2_000L)
        assertEquals(1, repository.loadTargetProfiles().single { it.id == RULE_ID }.profileVersion)

        repository.importTargetRule(revisionTwo, TargetRuleSource.Remote, now = 3_000L)
        assertEquals(1, repository.loadTargetProfiles().single { it.id == RULE_ID }.profileVersion)
        repository.activateTargetRule(RULE_ID, revision = 2, now = 4_000L)
        assertEquals(2, repository.loadTargetProfiles().single { it.id == RULE_ID }.profileVersion)

        val rolledBack = repository.rollbackTargetRule(RULE_ID, now = 5_000L)
        assertEquals(1, rolledBack.revision)
        assertEquals(1, repository.loadTargetProfiles().single { it.id == RULE_ID }.profileVersion)
    }

    @Test
    fun unsignedRulesCannotActivateAndSignedDisableRemovesActiveProfile() {
        val repository = createRepository()
        val unsigned = importedRule(
            revision = 1,
            signatureState = TargetRuleSignatureState.Unsigned,
            initialState = TargetRuleState.ObservationOnly,
        )
        repository.importTargetRule(unsigned, TargetRuleSource.LocalImport, now = 1_000L)
        assertFailsWith<IllegalArgumentException> {
            repository.activateTargetRule(RULE_ID, revision = 1, now = 2_000L)
        }

        repository.importTargetRule(importedRule(revision = 2), TargetRuleSource.Remote, now = 3_000L)
        repository.activateTargetRule(RULE_ID, revision = 2, now = 4_000L)
        assertTrue(repository.hasActiveTargetRule(RULE_ID))

        repository.importTargetRule(
            importedRule(revision = 3, disabled = true, initialState = TargetRuleState.Disabled),
            TargetRuleSource.Remote,
            now = 5_000L,
        )
        assertTrue(repository.loadTargetProfiles().none { it.id == RULE_ID })
        assertTrue(repository.loadTargetRuleRevisions(RULE_ID).any { it.state == TargetRuleState.Disabled })
    }

    @Test
    fun activeExpiredRuleIsRemovedDuringReconciliation() {
        val repository = createRepository()
        repository.importTargetRule(
            importedRule(revision = 1, expiresAt = 2_500L),
            TargetRuleSource.Remote,
            now = 1_000L,
        )
        repository.activateTargetRule(RULE_ID, revision = 1, now = 2_000L)

        assertEquals(1, repository.reconcileExpiredTargetRules(now = 3_000L))
        assertTrue(repository.loadTargetProfiles().none { it.id == RULE_ID })
        assertEquals(TargetRuleState.Expired, repository.loadTargetRuleRevisions(RULE_ID).single().state)
    }

    private fun importedRule(
        revision: Int,
        ruleId: String = RULE_ID,
        profile: com.danmukey.shared.model.TargetProfile = SampleTargets.testHost.copy(
            id = ruleId,
            displayName = "测试目标规则 v$revision",
            platform = Platform.Android,
            appIdentifiers = setOf("com.example.video"),
            profileVersion = revision,
        ),
        signatureState: TargetRuleSignatureState = TargetRuleSignatureState.Verified,
        initialState: TargetRuleState = TargetRuleState.Observation,
        disabled: Boolean = false,
        expiresAt: Long? = 10_000L,
    ): ImportedDTarget {
        val payload = DTargetPayload(
            ruleId = ruleId,
            revision = revision,
            issuedAt = 100L,
            expiresAt = expiresAt,
            profile = profile,
            allowedActions = listOf(
                TargetRuleAction.ObserveAccessibility,
                TargetRuleAction.OpenComposer,
                TargetRuleAction.FocusInput,
                TargetRuleAction.SetText,
                TargetRuleAction.ClickSubmit,
                TargetRuleAction.ReadEpisodeTitle,
                TargetRuleAction.ReadPlaybackTime,
            ),
            disabled = disabled,
        )
        return ImportedDTarget(
            envelope = DTargetEnvelope(
                signatureAlgorithm = if (signatureState == TargetRuleSignatureState.Verified) {
                    DTargetEnvelope.SIGNATURE_ALGORITHM
                } else {
                    null
                },
                keyId = if (signatureState == TargetRuleSignatureState.Verified) "test-key" else null,
                signatureHex = if (signatureState == TargetRuleSignatureState.Verified) "00" else null,
                payload = payload,
            ),
            signatureState = signatureState,
            initialState = initialState,
        )
    }

    private fun createRepository(): DanmuKeyRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DanmuKeyDatabase.Schema.create(driver)
        return DanmuKeyRepository(DanmuKeyDatabase(driver))
    }

    companion object {
        private const val RULE_ID = "test-video-rule"
    }
}
