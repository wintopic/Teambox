package com.danmukey.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.danmukey.shared.data.ContentCodec
import com.danmukey.shared.data.ContentIds
import com.danmukey.shared.data.ContentImport
import com.danmukey.shared.data.ContentImportResult
import com.danmukey.shared.data.ContentPackImport
import com.danmukey.shared.data.ContentTextFormat
import com.danmukey.shared.data.DanmuKeyRepository
import com.danmukey.shared.data.DKeyArchive
import com.danmukey.shared.data.DiagnosticCodec
import com.danmukey.shared.data.ImportFileLimits
import com.danmukey.shared.data.ImportPayloadKind
import com.danmukey.shared.data.LocalDataRetentionPolicy
import com.danmukey.shared.data.SendRecordCodec
import com.danmukey.shared.data.SampleTargets
import com.danmukey.shared.data.DTargetCodec
import com.danmukey.shared.data.RejectAllTargetRuleSignatures
import com.danmukey.shared.data.TargetRuleRevision
import com.danmukey.shared.data.TargetRuleSignatureState
import com.danmukey.shared.data.TargetRuleSignatureVerifier
import com.danmukey.shared.data.TargetRuleSource
import com.danmukey.shared.data.TargetRuleState
import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.EpisodeMapping
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.SectionType
import com.danmukey.shared.model.DiagnosticEvent
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.model.SendRecord
import com.danmukey.shared.content.updatePhraseStates

class DanmuKeyAppController(
    private val repository: DanmuKeyRepository,
    private val now: () -> Long,
    private val targetRuleVerifier: TargetRuleSignatureVerifier = RejectAllTargetRuleSignatures,
    private val onTargetConfigurationChanged: () -> Unit = {},
    private val onClearDiagnosticArtifacts: () -> Int = { 0 },
) {
    var packs by mutableStateOf<List<KeyboardPack>>(emptyList())
        private set
    var selectedPackId by mutableStateOf<String?>(null)
        private set
    var selectedSectionId by mutableStateOf<String?>(null)
        private set
    var selectedGroupId by mutableStateOf<String?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var diagnostics by mutableStateOf<List<DiagnosticEvent>>(emptyList())
        private set
    var targetProfiles by mutableStateOf<List<TargetProfile>>(emptyList())
        private set
    var targetRuleRevisions by mutableStateOf<List<TargetRuleRevision>>(emptyList())
        private set
    var sendRecords by mutableStateOf<List<SendRecord>>(emptyList())
        private set
    var episodeMappings by mutableStateOf<List<EpisodeMapping>>(emptyList())
        private set

    val selectedPack: KeyboardPack?
        get() = packs.firstOrNull { it.id == selectedPackId }

    val selectedSection: KeyboardSection?
        get() = selectedPack?.sections?.firstOrNull { it.id == selectedSectionId }

    val selectedGroup: PhraseGroup?
        get() = selectedSection?.groups?.firstOrNull { it.id == selectedGroupId }

    init {
        repository.ensureSeedData(now())
        refresh()
    }

    fun refresh(preferredPackId: String? = selectedPackId) {
        val currentTime = now()
        repository.pruneExpiredLocalRecords(currentTime)
        val expiredRuleCount = repository.reconcileExpiredTargetRules(currentTime)
        packs = repository.loadAllPacks()
        diagnostics = repository.loadRecentDiagnostics()
        sendRecords = repository.loadRecentSendRecords()
        targetProfiles = repository.loadTargetProfiles()
        targetRuleRevisions = repository.loadTargetRuleRevisions()
        val pack = packs.firstOrNull { it.id == preferredPackId } ?: packs.firstOrNull()
        selectedPackId = pack?.id
        episodeMappings = pack?.let { repository.loadEpisodeMappings(it.id) }.orEmpty()
        val section = pack?.sections?.firstOrNull { it.id == selectedSectionId }
            ?: pack?.sections?.minByOrNull(KeyboardSection::order)
        selectedSectionId = section?.id
        val group = section?.groups?.firstOrNull { it.id == selectedGroupId }
            ?: section?.groups?.minByOrNull(PhraseGroup::order)
        selectedGroupId = group?.id
        if (expiredRuleCount > 0) onTargetConfigurationChanged()
    }

    fun selectPack(packId: String) {
        selectedPackId = packId
        val pack = selectedPack
        episodeMappings = pack?.let { repository.loadEpisodeMappings(it.id) }.orEmpty()
        selectedSectionId = pack?.sections?.minByOrNull(KeyboardSection::order)?.id
        selectedGroupId = selectedSection?.groups?.minByOrNull(PhraseGroup::order)?.id
    }

    fun selectSection(sectionId: String) {
        selectedSectionId = sectionId
        selectedGroupId = selectedSection?.groups?.minByOrNull(PhraseGroup::order)?.id
    }

    fun selectGroup(groupId: String) {
        selectedGroupId = groupId
    }

    fun createPack(name: String, author: String = "本机用户") {
        val timestamp = now()
        val sectionId = ContentIds.next("section", timestamp)
        val groupId = ContentIds.next("group", timestamp + 1)
        val pack = KeyboardPack(
            id = ContentIds.next("pack", timestamp),
            name = name.trim().ifEmpty { "新键盘包" },
            author = author,
            version = 1,
            createdAt = timestamp,
            updatedAt = timestamp,
            sections = listOf(
                KeyboardSection(
                    id = sectionId,
                    title = "通用",
                    type = SectionType.General,
                    order = 0,
                    groups = listOf(
                        PhraseGroup(
                            id = groupId,
                            title = "默认分组",
                            order = 0,
                        ),
                    ),
                ),
            ),
        )
        repository.savePack(pack)
        selectedSectionId = sectionId
        selectedGroupId = groupId
        refresh(pack.id)
        statusMessage = "已创建 ${pack.name}"
    }

    fun deleteSelectedPack() {
        val pack = selectedPack ?: return
        repository.deletePack(pack.id)
        selectedPackId = null
        selectedSectionId = null
        selectedGroupId = null
        refresh()
        statusMessage = "已删除 ${pack.name}"
    }

    fun updateSelectedPack(name: String, author: String, description: String) {
        val pack = selectedPack ?: return
        val cleanedName = name.trim()
        if (cleanedName.isEmpty()) {
            statusMessage = "键盘包名称不能为空"
            return
        }
        save(
            pack.copy(
                name = cleanedName,
                author = author.trim().ifEmpty { "本机用户" },
                description = description.trim(),
            ),
            now(),
        )
        refresh(pack.id)
        statusMessage = "已更新键盘包信息"
    }

    fun addSection(title: String, episodeNumber: Int? = null) {
        val pack = selectedPack ?: return
        val timestamp = now()
        val groupId = ContentIds.next("group", timestamp + 1)
        val section = KeyboardSection(
            id = ContentIds.next("section", timestamp),
            title = title.trim().ifEmpty { "新标签" },
            type = if (episodeNumber != null) SectionType.Episode else SectionType.Custom,
            episodeNumber = episodeNumber,
            order = pack.sections.size,
            groups = listOf(
                PhraseGroup(
                    id = groupId,
                    title = "默认分组",
                    order = 0,
                ),
            ),
        )
        save(pack.copy(sections = pack.sections + section), timestamp)
        selectedSectionId = section.id
        selectedGroupId = groupId
        refresh(pack.id)
    }

    fun updateSelectedSection(title: String, episodeNumber: Int?) {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) {
            statusMessage = "标签名称不能为空"
            return
        }
        if (episodeNumber != null && episodeNumber <= 0) {
            statusMessage = "集数必须大于 0"
            return
        }
        val updated = section.copy(
            title = cleanedTitle,
            type = if (episodeNumber != null) {
                SectionType.Episode
            } else if (section.type == SectionType.Episode) {
                SectionType.Custom
            } else {
                section.type
            },
            episodeNumber = episodeNumber,
        )
        save(pack.replaceSection(updated), now())
        refresh(pack.id)
        statusMessage = "已更新标签"
    }

    fun deleteSelectedSection() {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val remaining = pack.sections
            .filterNot { it.id == section.id }
            .mapIndexed { index, item -> item.copy(order = index) }
        save(pack.copy(sections = remaining), now())
        selectedSectionId = null
        selectedGroupId = null
        refresh(pack.id)
        statusMessage = "已删除标签 ${section.title}"
    }

    fun addGroup(title: String) {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val timestamp = now()
        val group = PhraseGroup(
            id = ContentIds.next("group", timestamp),
            title = title.trim().ifEmpty { "新场景" },
            order = section.groups.size,
        )
        val updatedSection = section.copy(groups = section.groups + group)
        save(pack.replaceSection(updatedSection), timestamp)
        selectedGroupId = group.id
        refresh(pack.id)
    }

    fun updateSelectedGroup(title: String, startMs: Long?, endMs: Long?) {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val group = selectedGroup ?: return
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) {
            statusMessage = "场景名称不能为空"
            return
        }
        if (startMs != null && startMs < 0L || endMs != null && endMs < 0L) {
            statusMessage = "场景时间不能为负数"
            return
        }
        if (startMs != null && endMs != null && startMs > endMs) {
            statusMessage = "场景开始时间不能晚于结束时间"
            return
        }
        val updated = group.copy(title = cleanedTitle, startMs = startMs, endMs = endMs)
        save(pack.replaceGroup(section.id, updated), now())
        refresh(pack.id)
        statusMessage = "已更新场景"
    }

    fun deleteSelectedGroup() {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val group = selectedGroup ?: return
        val remaining = section.groups
            .filterNot { it.id == group.id }
            .mapIndexed { index, item -> item.copy(order = index) }
        save(pack.replaceSection(section.copy(groups = remaining)), now())
        selectedGroupId = null
        refresh(pack.id)
        statusMessage = "已删除场景 ${group.title}"
    }

    fun addPhrase(text: String, tags: Set<String> = emptySet()) {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        val group = selectedGroup ?: return
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            statusMessage = "弹幕内容不能为空"
            return
        }
        val timestamp = now()
        val phrase = PhraseItem(
            id = ContentIds.next("phrase", timestamp),
            text = cleaned,
            tags = tags.map(String::trim).filter(String::isNotEmpty).toSet(),
            order = group.phrases.size,
        )
        val updatedGroup = group.copy(phrases = group.phrases + phrase)
        save(pack.replaceGroup(section.id, updatedGroup), timestamp)
        refresh(pack.id)
        statusMessage = "已添加一条内容"
    }

    fun updatePhrase(phraseId: String, text: String? = null, enabled: Boolean? = null) {
        val pack = selectedPack ?: return
        var changed = false
        val updated = pack.copy(
            sections = pack.sections.map { section ->
                section.copy(
                    groups = section.groups.map { group ->
                        group.copy(
                            phrases = group.phrases.map { phrase ->
                                if (phrase.id != phraseId) {
                                    phrase
                                } else {
                                    val replacement = phrase.copy(
                                        text = text?.trim()?.takeIf(String::isNotEmpty) ?: phrase.text,
                                        enabled = enabled ?: phrase.enabled,
                                    )
                                    if (replacement != phrase) changed = true
                                    replacement
                                }
                            },
                        )
                    },
                )
            },
        )
        if (changed) {
            save(updated, now())
            refresh(pack.id)
        }
    }

    fun reviewPhrase(phraseId: String, approved: Boolean) {
        val state = if (approved) ReviewState.Approved else ReviewState.Rejected
        updatePhraseStates(setOf(phraseId), reviewState = state)
    }

    fun setPhrasesEnabled(phraseIds: Set<String>, enabled: Boolean) {
        updatePhraseStates(phraseIds, enabled = enabled)
    }

    fun reviewPhrases(phraseIds: Set<String>, approved: Boolean) {
        updatePhraseStates(
            phraseIds,
            reviewState = if (approved) ReviewState.Approved else ReviewState.Rejected,
        )
    }

    fun deletePhrase(phraseId: String) {
        val pack = selectedPack ?: return
        var deleted = false
        val updated = pack.copy(
            sections = pack.sections.map { section ->
                section.copy(
                    groups = section.groups.map { group ->
                        val remaining = group.phrases.filterNot { phrase ->
                            (phrase.id == phraseId).also { matched -> if (matched) deleted = true }
                        }.mapIndexed { index, phrase -> phrase.copy(order = index) }
                        group.copy(phrases = remaining)
                    },
                )
            },
        )
        if (deleted) {
            save(updated, now())
            refresh(pack.id)
            statusMessage = "已删除一条内容"
        }
    }

    fun saveEpisodeMapping(targetId: String, observedTitle: String, confidence: Double) {
        val pack = selectedPack ?: return
        val section = selectedSection ?: return
        if (section.type != SectionType.Episode) {
            statusMessage = "剧集标题只能映射到剧集标签"
            return
        }
        runCatching {
            repository.saveEpisodeMapping(
                targetId = targetId,
                observedTitle = observedTitle,
                sectionId = section.id,
                confidence = confidence,
                now = now(),
            )
        }.onSuccess {
            episodeMappings = repository.loadEpisodeMappings(pack.id)
            statusMessage = "已保存剧集映射 ${it.normalizedTitle} → ${section.title}"
        }.onFailure {
            statusMessage = "剧集映射保存失败：${it.message ?: "参数无效"}"
        }
    }

    fun deleteEpisodeMapping(mappingId: String) {
        val pack = selectedPack ?: return
        repository.deleteEpisodeMapping(mappingId)
        episodeMappings = repository.loadEpisodeMappings(pack.id)
        statusMessage = "已删除剧集映射"
    }

    fun importJson(text: String) {
        runCatching {
            ImportFileLimits.requireWithin(
                ImportPayloadKind.ContentText,
                text.encodeToByteArray().size.toLong(),
            )
            importPack(ContentCodec.decode(text))
        }.onFailure {
            statusMessage = "导入失败：${it.message ?: "格式错误"}"
        }
    }

    fun importDKey(bytes: ByteArray) {
        runCatching {
            importPack(DKeyArchive.decode(bytes))
        }.onFailure {
            statusMessage = "导入失败：${it.message ?: "内容包损坏"}"
        }
    }

    fun importDTarget(text: String) {
        runCatching {
            ImportFileLimits.requireWithin(
                ImportPayloadKind.TargetRule,
                text.encodeToByteArray().size.toLong(),
            )
            val imported = DTargetCodec.decodeAndVerify(text, targetRuleVerifier, now())
            repository.importTargetRule(imported, TargetRuleSource.LocalImport, now())
            refresh()
            onTargetConfigurationChanged()
            statusMessage = when {
                imported.initialState == TargetRuleState.Disabled ->
                    "已导入已验签停用规则，目标已安全停用"
                imported.signatureState == TargetRuleSignatureState.Verified ->
                    "已导入已验签目标规则；请先观察验证，再手动启用"
                else -> "已导入未签名目标规则，仅允许观察，不能启用"
            }
        }.onFailure {
            statusMessage = "目标规则导入失败：${it.message ?: "格式或签名错误"}"
        }
    }

    fun importText(format: ContentTextFormat, text: String, suggestedName: String): ContentImportResult =
        runCatching {
            val result = ContentImport.parse(format, text)
            if (result.rows.isEmpty()) {
                statusMessage = result.warnings.firstOrNull() ?: "没有可导入内容"
                return@runCatching result
            }
            val timestamp = now()
            val pack = ContentImport.toPack(
                result = result,
                name = suggestedName.substringBeforeLast('.').ifBlank { "导入内容" },
                author = "本机导入",
                now = timestamp,
            )
            repository.savePack(pack)
            refresh(pack.id)
            statusMessage = "已导入 ${result.rows.size} 条，${result.warnings.size} 条提示"
            result
        }.getOrElse { error ->
            val message = error.message ?: "格式错误"
            statusMessage = "导入失败：$message"
            ContentImportResult(emptyList(), listOf(message))
        }

    fun exportJson(): Pair<String, String>? = selectedPack?.let { pack ->
        "${safeFileName(pack.name)}.json" to ContentCodec.encode(pack, now())
    }

    fun exportCsv(): Pair<String, String>? = selectedPack?.let { pack ->
        "${safeFileName(pack.name)}.csv" to ContentImport.exportCsv(pack)
    }

    fun exportDKey(): Pair<String, ByteArray>? = selectedPack?.let { pack ->
        "${safeFileName(pack.name)}.dkey" to DKeyArchive.encode(pack, now())
    }

    fun refreshDiagnostics() {
        diagnostics = repository.loadRecentDiagnostics()
        sendRecords = repository.loadRecentSendRecords()
    }

    fun clearDiagnostics() {
        repository.clearDiagnostics()
        diagnostics = emptyList()
        statusMessage = runCatching(onClearDiagnosticArtifacts).fold(
            onSuccess = { clearedArtifacts ->
                if (clearedArtifacts > 0) {
                    "已清除运行诊断和 $clearedArtifacts 个脱敏控件样本"
                } else {
                    "已清除运行诊断"
                }
            },
            onFailure = {
                "运行诊断已清除，但脱敏控件样本清理失败，请重试"
            },
        )
    }

    fun clearSendRecords() {
        repository.clearSendRecordDetails()
        repository.pruneClearedSendRecords(LocalDataRetentionPolicy.clearedSendSafetyCutoff(now()))
        sendRecords = emptyList()
        statusMessage = "已清除发送正文与定位详情，最小安全计数最多保留 24 小时"
    }

    fun exportDiagnosticsJson(): Pair<String, String> {
        val timestamp = now()
        return "danmukey-diagnostics-$timestamp.json" to DiagnosticCodec.encode(diagnostics, timestamp)
    }

    fun exportSendRecordsJson(): Pair<String, String> {
        val timestamp = now()
        return "danmukey-send-records-$timestamp.json" to SendRecordCodec.encode(sendRecords, timestamp)
    }

    fun deleteTargetProfile(profileId: String) {
        if (SampleTargets.isBuiltIn(profileId)) {
            statusMessage = "内置目标配置不能删除；如需安全停用，请导入已验签停用规则"
            return
        }
        if (repository.hasActiveTargetRule(profileId)) {
            statusMessage = "活动签名规则不能作为本地标定删除，请先回滚或导入停用规则"
            return
        }
        if (!repository.deleteTargetProfile(profileId)) {
            statusMessage = "内置目标配置不能删除"
            return
        }
        targetProfiles = repository.loadTargetProfiles()
        onTargetConfigurationChanged()
        statusMessage = "已删除目标配置"
    }

    fun activateTargetRule(ruleId: String, revision: Int) {
        runCatching {
            repository.activateTargetRule(ruleId, revision, now())
            refresh()
            onTargetConfigurationChanged()
            statusMessage = "已启用目标规则 $ruleId v$revision"
        }.onFailure {
            statusMessage = "目标规则不能启用：${it.message ?: "状态无效"}"
        }
    }

    fun rollbackTargetRule(ruleId: String) {
        runCatching {
            val restored = repository.rollbackTargetRule(ruleId, now())
            refresh()
            onTargetConfigurationChanged()
            statusMessage = "已回滚到目标规则 ${restored.ruleId} v${restored.revision}"
        }.onFailure {
            statusMessage = "目标规则无法回滚：${it.message ?: "没有可用旧版本"}"
        }
    }

    fun consumeStatusMessage() {
        statusMessage = null
    }

    fun reportStatus(message: String) {
        statusMessage = message
    }

    fun reportError(action: String, error: Throwable) {
        statusMessage = "$action：${error.message ?: "未知错误"}"
    }

    private fun save(pack: KeyboardPack, timestamp: Long) {
        repository.savePack(pack.copy(updatedAt = timestamp, version = pack.version + 1))
    }

    private fun updatePhraseStates(
        phraseIds: Set<String>,
        enabled: Boolean? = null,
        reviewState: ReviewState? = null,
    ) {
        val pack = selectedPack ?: return
        if (phraseIds.isEmpty()) {
            statusMessage = "请先选择内容"
            return
        }
        val (updated, changedCount) = pack.updatePhraseStates(
            phraseIds = phraseIds,
            enabled = enabled,
            reviewState = reviewState,
        )
        if (changedCount == 0) {
            statusMessage = "所选内容已经是目标状态"
            return
        }
        save(updated, now())
        refresh(pack.id)
        statusMessage = "已批量更新 $changedCount 条内容"
    }

    private fun importPack(decoded: KeyboardPack) {
        val timestamp = now()
        val imported = ContentPackImport.prepare(decoded, packs, timestamp)
        repository.savePack(imported)
        refresh(imported.id)
        statusMessage = "已导入 ${imported.name}"
    }

    private fun KeyboardPack.replaceSection(section: KeyboardSection): KeyboardPack = copy(
        sections = sections.map { if (it.id == section.id) section else it },
    )

    private fun KeyboardPack.replaceGroup(sectionId: String, group: PhraseGroup): KeyboardPack = copy(
        sections = sections.map { section ->
            if (section.id == sectionId) {
                section.copy(groups = section.groups.map { if (it.id == group.id) group else it })
            } else {
                section
            }
        },
    )

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "danmukey" }
}
