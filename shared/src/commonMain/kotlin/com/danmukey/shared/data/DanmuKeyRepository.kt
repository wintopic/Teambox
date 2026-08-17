package com.danmukey.shared.data

import com.danmukey.shared.db.DanmuKeyDatabase
import com.danmukey.shared.db.Automation_task
import com.danmukey.shared.db.Episode_map
import com.danmukey.shared.db.Target_rule_revision
import com.danmukey.shared.automation.SendQuotaSnapshot
import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.PhraseUsage
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.SectionType
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.model.DiagnosticEvent
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.ContentFollowState
import com.danmukey.shared.model.EpisodeMapping
import com.danmukey.shared.model.SendMode
import com.danmukey.shared.model.SendRecord
import com.danmukey.shared.model.SendResult
import com.danmukey.shared.model.AutomationTaskRecord
import com.danmukey.shared.model.AutomationTaskStatus
import com.danmukey.shared.content.EpisodeTitleNormalizer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class AutomationTaskMetadata(
    val mode: SendMode,
    val expectedPackage: String,
    val targetId: String,
)

class DanmuKeyRepository(
    private val database: DanmuKeyDatabase,
) {
    private val queries = database.danmuKeyQueries
    private val json: Json = ContentJson.instance

    fun ensureSeedData(now: Long) {
        if (queries.countKeyboardPacks().executeAsOne() == 0L) {
            val samplePack = SampleContent.createPack(now)
            savePack(samplePack)
            saveEpisodeMapping(
                targetId = SampleTargets.testHost.id,
                observedTitle = "第一集",
                sectionId = SampleContent.SAMPLE_EPISODE_ONE_ID,
                confidence = 1.0,
                now = now,
            )
        }
        SampleTargets.builtInProfiles.forEach { profile ->
            val stored = queries.selectTargetProfileById(profile.id).executeAsOneOrNull()
            val encodedProfile = json.encodeToString(profile)
            if (
                !hasAuthoritativeRuleState(profile.id) &&
                (
                    stored == null ||
                        stored.profile_json != encodedProfile ||
                        stored.profile_version != profile.profileVersion.toLong()
                    )
            ) {
                saveTargetProfile(profile, now)
            }
        }
    }

    fun loadAllPacks(): List<KeyboardPack> = queries
        .selectAllKeyboardPacks()
        .executeAsList()
        .mapNotNull { loadPack(it.id) }

    fun loadPack(packId: String): KeyboardPack? {
        val pack = queries.selectKeyboardPackById(packId).executeAsOneOrNull() ?: return null
        val sections = queries.selectSectionsForPack(packId).executeAsList().map { section ->
            val groups = queries.selectGroupsForSection(section.id).executeAsList().map { group ->
                val phrases = queries.selectPhrasesForGroup(group.id).executeAsList().map { phrase ->
                    PhraseItem(
                        id = phrase.id,
                        text = phrase.text,
                        tags = decodeTags(phrase.tags_json),
                        source = phrase.source_json,
                        reviewState = enumValueOrDefault(phrase.review_state, ReviewState.Approved),
                        enabled = phrase.enabled != 0L,
                        order = phrase.sort_order.toInt(),
                    )
                }
                PhraseGroup(
                    id = group.id,
                    title = group.title,
                    startMs = group.start_ms,
                    endMs = group.end_ms,
                    phrases = phrases,
                    order = group.sort_order.toInt(),
                )
            }
            KeyboardSection(
                id = section.id,
                title = section.title,
                type = enumValueOrDefault(section.type, SectionType.Custom),
                episodeNumber = section.episode_number?.toInt(),
                groups = groups,
                order = section.sort_order.toInt(),
            )
        }
        return KeyboardPack(
            id = pack.id,
            name = pack.name,
            author = pack.author,
            version = pack.version.toInt(),
            description = pack.description,
            cover = pack.cover_path,
            sections = sections,
            createdAt = pack.created_at,
            updatedAt = pack.updated_at,
        )
    }

    fun savePack(pack: KeyboardPack) {
        val existingEpisodeMappings = loadEpisodeMappings(pack.id)
        val retainedSectionIds = pack.sections.mapTo(hashSetOf(), KeyboardSection::id)
        database.transaction {
            deletePackGraph(pack.id)
            queries.insertKeyboardPack(
                id = pack.id,
                name = pack.name.trim().ifEmpty { "未命名键盘包" },
                author = pack.author.trim(),
                version = pack.version.toLong(),
                description = pack.description,
                cover_path = pack.cover,
                created_at = pack.createdAt,
                updated_at = pack.updatedAt,
            )
            pack.sections.sortedBy(KeyboardSection::order).forEach { section ->
                queries.insertKeyboardSection(
                    id = section.id,
                    pack_id = pack.id,
                    title = section.title,
                    type = section.type.name,
                    episode_number = section.episodeNumber?.toLong(),
                    sort_order = section.order.toLong(),
                )
                section.groups.sortedBy(PhraseGroup::order).forEach { group ->
                    queries.insertPhraseGroup(
                        id = group.id,
                        section_id = section.id,
                        title = group.title,
                        start_ms = group.startMs,
                        end_ms = group.endMs,
                        sort_order = group.order.toLong(),
                    )
                    group.phrases.sortedBy(PhraseItem::order).forEach { phrase ->
                        queries.insertPhrase(
                            id = phrase.id,
                            group_id = group.id,
                            text = phrase.text,
                            tags_json = json.encodeToString(phrase.tags),
                            source_json = phrase.source,
                            review_state = phrase.reviewState.name,
                            enabled = if (phrase.enabled) 1L else 0L,
                            sort_order = phrase.order.toLong(),
                            created_at = pack.createdAt,
                            updated_at = pack.updatedAt,
                        )
                    }
                }
            }
            existingEpisodeMappings
                .filter { it.sectionId in retainedSectionIds }
                .forEach(::upsertEpisodeMapping)
        }
    }

    fun deletePack(packId: String) {
        database.transaction {
            deletePackGraph(packId)
        }
    }

    fun searchPhrases(packId: String, query: String): List<PhraseItem> = queries
        .searchPhrases(packId, query.trim())
        .executeAsList()
        .map { phrase ->
            PhraseItem(
                id = phrase.id,
                text = phrase.text,
                tags = decodeTags(phrase.tags_json),
                source = phrase.source_json,
                reviewState = enumValueOrDefault(phrase.review_state, ReviewState.Approved),
                enabled = phrase.enabled != 0L,
                order = phrase.sort_order.toInt(),
            )
        }

    fun usageForTarget(targetId: String): Map<String, PhraseUsage> = queries
        .selectUsageForTarget(targetId)
        .executeAsList()
        .associate { row ->
            row.phrase_id to PhraseUsage(
                phraseId = row.phrase_id,
                targetId = row.target_id,
                useCount = row.use_count,
                lastUsedAt = row.last_used_at,
            )
        }

    fun recordPhraseUse(phraseId: String, targetId: String, usedAt: Long) {
        val current = usageForTarget(targetId)[phraseId]
        queries.upsertPhraseUsage(
            phrase_id = phraseId,
            target_id = targetId,
            use_count = (current?.useCount ?: 0L) + 1L,
            last_used_at = usedAt,
        )
    }

    fun loadEpisodeMappings(packId: String): List<EpisodeMapping> = queries
        .selectEpisodeMapsForPack(packId)
        .executeAsList()
        .map { row ->
            EpisodeMapping(
                id = row.id,
                targetId = row.target_id,
                normalizedTitle = row.normalized_title,
                sectionId = row.section_id,
                confidence = row.confidence,
                updatedAt = row.updated_at,
            )
        }

    fun saveEpisodeMapping(
        targetId: String,
        observedTitle: String,
        sectionId: String,
        confidence: Double,
        now: Long,
    ): EpisodeMapping {
        val cleanedTargetId = targetId.trim()
        val normalizedTitle = EpisodeTitleNormalizer.normalize(observedTitle)
        require(cleanedTargetId.isNotEmpty()) { "目标 ID 不能为空" }
        require(normalizedTitle.isNotEmpty()) { "识别标题不能为空" }
        require(confidence in 0.0..1.0) { "映射置信度必须在 0 到 1 之间" }
        val existing = queries
            .selectEpisodeMapByTargetTitle(cleanedTargetId, normalizedTitle)
            .executeAsOneOrNull()
        val mapping = EpisodeMapping(
            id = existing?.id ?: ContentIds.next("episode-map", now),
            targetId = cleanedTargetId,
            normalizedTitle = normalizedTitle,
            sectionId = sectionId,
            confidence = confidence,
            updatedAt = now,
        )
        upsertEpisodeMapping(mapping)
        return mapping
    }

    fun resolveEpisodeMapping(
        targetId: String,
        observedTitle: String,
        minimumConfidence: Double = 0.8,
    ): EpisodeMapping? {
        require(minimumConfidence in 0.0..1.0) { "最低置信度必须在 0 到 1 之间" }
        val cleanedTargetId = targetId.trim()
        val normalizedTitle = EpisodeTitleNormalizer.normalize(observedTitle)
        if (cleanedTargetId.isEmpty() || normalizedTitle.isEmpty()) return null
        val exact = queries
            .selectEpisodeMapByTargetTitle(cleanedTargetId, normalizedTitle)
            .executeAsOneOrNull()
        if (exact != null) return exact.toEpisodeMapping().takeIf { it.confidence >= minimumConfidence }

        val matchKey = EpisodeTitleNormalizer.matchKey(observedTitle)
        if (matchKey.isEmpty()) return null
        val equivalent = queries
            .selectEpisodeMapsByTarget(cleanedTargetId)
            .executeAsList()
            .filter { row -> EpisodeTitleNormalizer.matchKey(row.normalized_title) == matchKey }
            .filter { row -> row.confidence >= minimumConfidence }
        if (equivalent.isEmpty() || equivalent.map { it.section_id }.distinct().size != 1) return null
        val row = equivalent.sortedWith(
            compareByDescending<Episode_map> { it.confidence }
                .thenByDescending { it.updated_at }
                .thenBy { it.id },
        ).first()
        return row.toEpisodeMapping()
    }

    private fun Episode_map.toEpisodeMapping(): EpisodeMapping =
        EpisodeMapping(
            id = id,
            targetId = target_id,
            normalizedTitle = normalized_title,
            sectionId = section_id,
            confidence = confidence,
            updatedAt = updated_at,
        )

    fun deleteEpisodeMapping(mappingId: String) {
        queries.deleteEpisodeMap(mappingId)
    }

    fun loadPackContainingSection(sectionId: String): KeyboardPack? {
        val packId = queries.selectPackIdForSection(sectionId).executeAsOneOrNull() ?: return null
        return loadPack(packId)
    }

    fun saveContentFollowState(state: ContentFollowState) {
        require(state.confidence in 0.0..1.0) { "跟随置信度必须在 0 到 1 之间" }
        queries.upsertContentFollowState(
            target_id = state.targetId,
            app_identifier = state.appIdentifier,
            pack_id = state.packId,
            section_id = state.sectionId,
            group_id = state.groupId,
            playback_position_ms = state.playbackPositionMs,
            confidence = state.confidence,
            observed_at = state.observedAt,
        )
    }

    fun loadLatestContentFollowState(
        appIdentifier: String,
        observedSince: Long,
    ): ContentFollowState? = queries
        .selectLatestContentFollowStateForApp(appIdentifier, observedSince)
        .executeAsOneOrNull()
        ?.let { row ->
            ContentFollowState(
                targetId = row.target_id,
                appIdentifier = row.app_identifier,
                packId = row.pack_id,
                sectionId = row.section_id,
                groupId = row.group_id,
                playbackPositionMs = row.playback_position_ms,
                confidence = row.confidence,
                observedAt = row.observed_at,
            )
        }

    fun clearContentFollowState(targetId: String) {
        queries.deleteContentFollowState(targetId)
    }

    fun loadTargetProfiles(): List<TargetProfile> = queries
        .selectTargetProfiles()
        .executeAsList()
        .mapNotNull { runCatching { json.decodeFromString<TargetProfile>(it.profile_json) }.getOrNull() }

    fun saveTargetProfile(profile: TargetProfile, now: Long) {
        queries.upsertTargetProfile(
            id = profile.id,
            platform = profile.platform.name,
            profile_json = json.encodeToString(profile),
            profile_version = profile.profileVersion.toLong(),
            updated_at = now,
        )
    }

    fun deleteTargetProfile(profileId: String): Boolean {
        if (SampleTargets.isBuiltIn(profileId)) return false
        queries.deleteTargetProfile(profileId)
        return true
    }

    fun loadTargetRuleRevisions(): List<TargetRuleRevision> = queries
        .selectTargetRuleRevisions()
        .executeAsList()
        .mapNotNull(::decodeTargetRuleRevision)

    fun loadTargetRuleRevisions(ruleId: String): List<TargetRuleRevision> = queries
        .selectTargetRuleRevisionsByRuleId(ruleId)
        .executeAsList()
        .mapNotNull(::decodeTargetRuleRevision)

    fun importTargetRule(
        imported: ImportedDTarget,
        source: TargetRuleSource,
        now: Long,
    ): TargetRuleRevision {
        val payload = imported.envelope.payload
        val encodedEnvelope = DTargetCodec.encodeStored(imported.envelope)
        val existing = queries
            .selectTargetRuleRevision(payload.ruleId, payload.revision.toLong())
            .executeAsOneOrNull()
            ?.let(::decodeTargetRuleRevision)
        if (existing != null) {
            require(DTargetCodec.encodeStored(existing.envelope) == encodedEnvelope) {
                "同一目标规则版本的内容不一致"
            }
            require(existing.signatureState == imported.signatureState) {
                "不能用较低信任级别覆盖目标规则"
            }
            return existing
        }

        val revision = TargetRuleRevision(
            ruleId = payload.ruleId,
            revision = payload.revision,
            source = source,
            signatureState = imported.signatureState,
            state = imported.initialState,
            envelope = imported.envelope,
            importedAt = now,
        )
        database.transaction {
            if (revision.state == TargetRuleState.Disabled) {
                queries.supersedeActiveTargetRuleRevisions(revision.ruleId, revision.revision.toLong())
                queries.deleteTargetProfile(revision.ruleId)
            }
            upsertTargetRuleRevision(revision)
        }
        return revision
    }

    fun activateTargetRule(ruleId: String, revision: Int, now: Long): TargetRuleRevision {
        val candidate = queries
            .selectTargetRuleRevision(ruleId, revision.toLong())
            .executeAsOneOrNull()
            ?.let(::decodeTargetRuleRevision)
            ?: error("找不到目标规则版本")
        require(candidate.signatureState == TargetRuleSignatureState.Verified) { "未签名规则不能启用" }
        require(candidate.state !in setOf(TargetRuleState.Disabled, TargetRuleState.Expired)) {
            "已停用或过期的目标规则不能启用"
        }
        require(!candidate.envelope.payload.disabled) { "停用规则不能启用" }
        val blockingRevision = loadTargetRuleRevisions(ruleId).firstOrNull { rule ->
            rule.signatureState == TargetRuleSignatureState.Verified &&
                rule.state == TargetRuleState.Disabled &&
                rule.revision >= candidate.revision
        }
        require(blockingRevision == null) { "该目标规则已被较新签名版本停用" }

        val activated = candidate.copy(state = TargetRuleState.Active, activatedAt = now)
        database.transaction {
            queries.supersedeActiveTargetRuleRevisions(ruleId, revision.toLong())
            queries.updateTargetRuleRevisionState(
                state = TargetRuleState.Active.name,
                activated_at = now,
                rule_id = ruleId,
                revision = revision.toLong(),
            )
            saveTargetProfile(candidate.envelope.payload.profile, now)
        }
        return activated
    }

    fun rollbackTargetRule(ruleId: String, now: Long): TargetRuleRevision {
        val revisions = loadTargetRuleRevisions(ruleId)
        val current = revisions.singleOrNull { it.state == TargetRuleState.Active }
            ?: error("当前没有可回滚的活动规则")
        val previous = revisions
            .asSequence()
            .filter { it.state == TargetRuleState.Superseded }
            .filter { it.signatureState == TargetRuleSignatureState.Verified }
            .filter { !it.envelope.payload.disabled && it.activatedAt != null }
            .maxWithOrNull(compareBy<TargetRuleRevision> { it.activatedAt }.thenBy { it.revision })
            ?: error("没有上一版已验签规则")
        val rolledBack = previous.copy(state = TargetRuleState.Active, activatedAt = now)
        database.transaction {
            queries.updateTargetRuleRevisionState(
                state = TargetRuleState.Superseded.name,
                activated_at = current.activatedAt,
                rule_id = current.ruleId,
                revision = current.revision.toLong(),
            )
            queries.updateTargetRuleRevisionState(
                state = TargetRuleState.Active.name,
                activated_at = now,
                rule_id = previous.ruleId,
                revision = previous.revision.toLong(),
            )
            saveTargetProfile(previous.envelope.payload.profile, now)
        }
        return rolledBack
    }

    fun reconcileExpiredTargetRules(now: Long): Int {
        val expired = loadTargetRuleRevisions().filter { revision ->
            revision.state == TargetRuleState.Active &&
                revision.envelope.payload.expiresAt?.let { it <= now } == true
        }
        expired.forEach { revision ->
            database.transaction {
                queries.updateTargetRuleRevisionState(
                    state = TargetRuleState.Expired.name,
                    activated_at = revision.activatedAt,
                    rule_id = revision.ruleId,
                    revision = revision.revision.toLong(),
                )
                queries.deleteTargetProfile(revision.ruleId)
            }
        }
        return expired.size
    }

    fun hasActiveTargetRule(ruleId: String): Boolean = loadTargetRuleRevisions(ruleId)
        .any { it.state == TargetRuleState.Active }

    private fun upsertTargetRuleRevision(revision: TargetRuleRevision) {
        queries.upsertTargetRuleRevision(
            rule_id = revision.ruleId,
            revision = revision.revision.toLong(),
            source = revision.source.name,
            signature_state = revision.signatureState.name,
            state = revision.state.name,
            envelope_json = DTargetCodec.encodeStored(revision.envelope),
            imported_at = revision.importedAt,
            activated_at = revision.activatedAt,
        )
    }

    private fun decodeTargetRuleRevision(row: Target_rule_revision): TargetRuleRevision? = runCatching {
        TargetRuleRevision(
            ruleId = row.rule_id,
            revision = row.revision.toInt(),
            source = enumValueOrDefault(row.source, TargetRuleSource.LocalImport),
            signatureState = enumValueOrDefault(row.signature_state, TargetRuleSignatureState.Unsigned),
            state = enumValueOrDefault(row.state, TargetRuleState.ObservationOnly),
            envelope = DTargetCodec.decodeStored(row.envelope_json),
            importedAt = row.imported_at,
            activatedAt = row.activated_at,
        )
    }.getOrNull()

    fun recordDiagnostic(
        level: DiagnosticLevel,
        eventCode: String,
        createdAt: Long,
        targetId: String? = null,
        taskId: String? = null,
        details: Map<String, String> = emptyMap(),
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            id = ContentIds.next("diagnostic", createdAt),
            level = level,
            eventCode = eventCode,
            targetId = targetId,
            taskId = taskId,
            details = details,
            createdAt = createdAt,
        )
        queries.insertDiagnosticEvent(
            id = event.id,
            level = event.level.name,
            event_code = event.eventCode,
            target_id = event.targetId,
            task_id = event.taskId,
            details_json = json.encodeToString(event.details),
            created_at = event.createdAt,
        )
        return event
    }

    fun loadRecentDiagnostics(limit: Long = 100): List<DiagnosticEvent> = queries
        .selectRecentDiagnosticEvents(limit.coerceIn(1, 1_000))
        .executeAsList()
        .map { row ->
            DiagnosticEvent(
                id = row.id,
                level = enumValueOrDefault(row.level, DiagnosticLevel.Info),
                eventCode = row.event_code,
                targetId = row.target_id,
                taskId = row.task_id,
                details = runCatching {
                    json.decodeFromString<Map<String, String>>(row.details_json)
                }.getOrDefault(emptyMap()),
                createdAt = row.created_at,
            )
        }

    fun pruneDiagnostics(before: Long) {
        queries.deleteDiagnosticsBefore(before)
    }

    fun clearDiagnostics() {
        database.transaction {
            queries.deleteAllDiagnosticEvents()
            queries.deleteFinishedAutomationTasks()
        }
    }

    fun recordSend(
        taskId: String?,
        phraseId: String,
        packId: String,
        targetId: String,
        finalText: String,
        mode: SendMode,
        locatorSource: String?,
        confidence: Double?,
        result: SendResult,
        errorCode: String?,
        startedAt: Long,
        finishedAt: Long?,
    ): SendRecord {
        val eventTime = finishedAt ?: startedAt
        val record = SendRecord(
            id = ContentIds.next("send", eventTime),
            taskId = taskId,
            phraseId = phraseId,
            packId = packId,
            targetId = targetId,
            finalText = finalText,
            mode = mode,
            locatorSource = locatorSource,
            confidence = confidence,
            result = result,
            errorCode = errorCode,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
        queries.insertSendRecord(
            id = record.id,
            task_id = record.taskId,
            phrase_id = record.phraseId,
            pack_id = record.packId,
            target_id = record.targetId,
            final_text = record.finalText,
            mode = record.mode.name,
            locator_source = record.locatorSource,
            confidence = record.confidence,
            result = record.result.storageValue,
            error_code = record.errorCode,
            started_at = record.startedAt,
            finished_at = record.finishedAt,
        )
        return record
    }

    fun loadRecentSendRecords(limit: Long = 100): List<SendRecord> = queries
        .selectRecentSendRecords(limit.coerceIn(1, 1_000))
        .executeAsList()
        .map { row ->
            SendRecord(
                id = row.id,
                taskId = row.task_id,
                phraseId = row.phrase_id,
                packId = row.pack_id,
                targetId = row.target_id,
                finalText = row.final_text,
                mode = enumValueOrDefault(row.mode, SendMode.InsertOnly),
                locatorSource = row.locator_source,
                confidence = row.confidence,
                result = SendResult.fromStorage(row.result),
                errorCode = row.error_code,
                startedAt = row.started_at,
                finishedAt = row.finished_at,
            )
        }

    fun clearSendRecordDetails() {
        queries.clearSendRecordDetails()
    }

    fun pruneClearedSendRecords(before: Long) {
        queries.deleteClearedSendRecordsBefore(before)
    }

    fun pruneExpiredLocalRecords(now: Long) {
        database.transaction {
            queries.deleteDiagnosticsBefore(LocalDataRetentionPolicy.diagnosticCutoff(now))
            queries.deleteAutomationTasksFinishedBefore(LocalDataRetentionPolicy.diagnosticCutoff(now))
            queries.deleteClearedSendRecordsBefore(LocalDataRetentionPolicy.clearedSendSafetyCutoff(now))
        }
    }

    fun loadSendQuotaSnapshot(targetId: String, since: Long): SendQuotaSnapshot = SendQuotaSnapshot(
        submittedCountInWindow = queries
            .countSubmittedSendsForTargetSince(targetId, since)
            .executeAsOne(),
        latestAttemptAt = queries
            .selectLatestSendAttemptAt(targetId)
            .executeAsOne()
            .MAX,
    )

    fun saveAutomationTask(record: AutomationTaskRecord) {
        require(record.plannedCount > 0) { "任务计划数量必须大于零" }
        require(record.completedCount in 0..record.plannedCount) { "任务完成数量超出范围" }
        require(record.composerReopenCount >= 0) { "输入入口重开次数不能为负数" }
        queries.upsertAutomationTask(
            id = record.id,
            pack_id = record.packId,
            preset_json = json.encodeToString(
                AutomationTaskMetadata(
                    mode = record.mode,
                    expectedPackage = record.expectedPackage,
                    targetId = record.targetId,
                ),
            ),
            status = record.status.name,
            current_index = record.completedCount.toLong(),
            planned_count = record.plannedCount.toLong(),
            completed_count = record.completedCount.toLong(),
            composer_reopen_count = record.composerReopenCount.toLong(),
            created_at = record.createdAt,
            started_at = record.startedAt,
            finished_at = record.finishedAt,
        )
    }

    fun loadAutomationTask(taskId: String): AutomationTaskRecord? = queries
        .selectAutomationTaskById(taskId)
        .executeAsOneOrNull()
        ?.let(::decodeAutomationTask)

    fun failInterruptedAutomationTasks(now: Long): List<AutomationTaskRecord> {
        val interrupted = queries.selectRunningAutomationTasks().executeAsList().mapNotNull(::decodeAutomationTask)
        queries.failRunningAutomationTasks(now)
        return interrupted.map { task ->
            task.copy(
                status = AutomationTaskStatus.Failed,
                finishedAt = now,
            )
        }
    }

    private fun decodeTags(value: String): Set<String> = runCatching {
        json.decodeFromString<Set<String>>(value)
    }.getOrDefault(emptySet())

    private fun decodeAutomationTask(row: Automation_task): AutomationTaskRecord? = runCatching {
        val metadata = json.decodeFromString<AutomationTaskMetadata>(row.preset_json)
        AutomationTaskRecord(
            id = row.id,
            packId = row.pack_id,
            mode = metadata.mode,
            expectedPackage = metadata.expectedPackage,
            targetId = metadata.targetId,
            status = enumValueOrDefault(row.status, AutomationTaskStatus.Failed),
            plannedCount = row.planned_count.toInt(),
            completedCount = row.completed_count.toInt(),
            composerReopenCount = row.composer_reopen_count.toInt(),
            createdAt = row.created_at,
            startedAt = row.started_at ?: row.created_at,
            finishedAt = row.finished_at,
        )
    }.getOrNull()

    private fun deletePackGraph(packId: String) {
        queries.deleteEpisodeMapsForPack(packId)
        queries.deletePhraseUsageForPack(packId)
        queries.deletePhrasesForPack(packId)
        queries.deleteGroupsForPack(packId)
        queries.deleteSectionsForPack(packId)
        queries.deleteKeyboardPack(packId)
    }

    private fun hasAuthoritativeRuleState(ruleId: String): Boolean = queries
        .selectTargetRuleRevisionsByRuleId(ruleId)
        .executeAsList()
        .any { revision ->
            revision.signature_state == TargetRuleSignatureState.Verified.name &&
                revision.state in setOf(
                    TargetRuleState.Active.name,
                    TargetRuleState.Disabled.name,
                    TargetRuleState.Expired.name,
                )
        }

    private fun upsertEpisodeMapping(mapping: EpisodeMapping) {
        queries.upsertEpisodeMap(
            id = mapping.id,
            target_id = mapping.targetId,
            normalized_title = mapping.normalizedTitle,
            section_id = mapping.sectionId,
            confidence = mapping.confidence,
            updated_at = mapping.updatedAt,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

object ContentIds {
    fun next(prefix: String, now: Long): String {
        val random = Random.nextLong().toULong().toString(16).takeLast(10)
        return "$prefix-${now.toString(36)}-$random"
    }
}

object SampleContent {
    const val SAMPLE_PACK_ID = "sample-pack"
    const val SAMPLE_EPISODE_ONE_ID = "sample-episode-1"

    fun createPack(now: Long): KeyboardPack = KeyboardPack(
        id = SAMPLE_PACK_ID,
        name = "示例弹幕键盘",
        author = "怪团建",
        version = 1,
        description = "用于测试内容分组、输入和轮换。",
        createdAt = now,
        updatedAt = now,
        sections = listOf(
            KeyboardSection(
                id = "sample-general",
                title = "通用",
                type = SectionType.General,
                order = 0,
                groups = listOf(
                    PhraseGroup(
                        id = "sample-general-group",
                        title = "通用气氛",
                        order = 0,
                        phrases = samplePhrases(
                            "这段太精彩了",
                            "前方高能",
                            "这个转场绝了",
                            "剧情开始加速",
                            "细节拉满",
                        ),
                    ),
                ),
            ),
            KeyboardSection(
                id = SAMPLE_EPISODE_ONE_ID,
                title = "第一集",
                type = SectionType.Episode,
                episodeNumber = 1,
                order = 1,
                groups = listOf(
                    PhraseGroup(
                        id = "sample-scene-1",
                        title = "开场",
                        startMs = 0,
                        endMs = 90_000,
                        order = 0,
                        phrases = samplePhrases(
                            "开场就有东西",
                            "这个伏笔记住了",
                            "镜头语言很讲究",
                        ),
                    ),
                    PhraseGroup(
                        id = "sample-scene-2",
                        title = "推进",
                        startMs = 90_000,
                        endMs = 180_000,
                        order = 1,
                        phrases = samplePhrases(
                            "节奏开始提速",
                            "这一段信息量很大",
                            "接下来要反转了",
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun samplePhrases(vararg values: String): List<PhraseItem> = values.mapIndexed { index, value ->
        PhraseItem(
            id = "sample-phrase-${value.hashCode().toUInt().toString(16)}",
            text = value,
            order = index,
        )
    }
}

object SampleTargets {
    val builtInProfiles: List<TargetProfile>
        get() = listOf(testHost, tencentVideoObservation)

    val builtInProfileIds: Set<String>
        get() = builtInProfiles.mapTo(linkedSetOf(), TargetProfile::id)

    fun isBuiltIn(profileId: String): Boolean = profileId in builtInProfileIds

    val testHost = TargetProfile(
        id = "builtin-test-host",
        displayName = "怪团建测试宿主",
        platform = com.danmukey.shared.model.Platform.Android,
        appIdentifiers = setOf("com.danmukey.testhost"),
        orientations = setOf(
            com.danmukey.shared.model.Orientation.Portrait,
            com.danmukey.shared.model.Orientation.Landscape,
        ),
        capabilityLevel = com.danmukey.shared.model.TargetCapabilityLevel.L3,
        composerEntryLocators = listOf(
            com.danmukey.shared.model.LocatorSpec.Accessibility(
                resourceId = "com.danmukey.testhost:id/open_composer",
                clickable = true,
            ),
        ),
        inputLocators = listOf(
            com.danmukey.shared.model.LocatorSpec.Accessibility(
                resourceId = "com.danmukey.testhost:id/danmu_input",
                editable = true,
            ),
        ),
        submitLocators = listOf(
            com.danmukey.shared.model.LocatorSpec.Accessibility(
                resourceId = "com.danmukey.testhost:id/send_danmu",
                clickable = true,
            ),
        ),
        episodeTitleLocators = listOf(
            com.danmukey.shared.model.LocatorSpec.Accessibility(
                resourceId = "com.danmukey.testhost:id/episode_title",
            ),
        ),
        playbackTimeLocators = listOf(
            com.danmukey.shared.model.LocatorSpec.Accessibility(
                resourceId = "com.danmukey.testhost:id/playback_time",
            ),
        ),
        maxTextLength = 120,
        profileVersion = 1,
    )

    val tencentVideoObservation = TargetProfile(
        id = "builtin-tencent-video-observation-api29-v32123",
        displayName = "腾讯视频 9.04 只观察",
        platform = com.danmukey.shared.model.Platform.Android,
        appIdentifiers = setOf("com.tencent.qqlive"),
        orientations = setOf(
            com.danmukey.shared.model.Orientation.Portrait,
            com.danmukey.shared.model.Orientation.Landscape,
        ),
        capabilityLevel = com.danmukey.shared.model.TargetCapabilityLevel.L0,
        minAppVersionCode = 32123,
        maxAppVersionCode = 32123,
        minSystemApi = 29,
        maxSystemApi = 29,
        inputLocators = emptyList(),
        submitLocators = emptyList(),
        profileVersion = 1,
    )
}
