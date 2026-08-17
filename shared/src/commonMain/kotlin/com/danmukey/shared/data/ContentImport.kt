package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.SectionType

enum class ContentTextFormat {
    Csv,
    Txt,
    Srt,
    Ass,
}

data class ImportedContentRow(
    val section: String,
    val group: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
    val tags: Set<String> = emptySet(),
    val enabled: Boolean = true,
)

data class ContentImportResult(
    val rows: List<ImportedContentRow>,
    val warnings: List<String> = emptyList(),
)

object ContentImport {
    fun parse(format: ContentTextFormat, source: String): ContentImportResult {
        ImportFileLimits.requireWithin(
            ImportPayloadKind.ContentText,
            source.encodeToByteArray().size.toLong(),
        )
        return when (format) {
            ContentTextFormat.Csv -> parseCsv(source)
            ContentTextFormat.Txt -> parseTxt(source)
            ContentTextFormat.Srt -> parseSrt(source)
            ContentTextFormat.Ass -> parseAss(source)
        }.limited().cleaned()
    }

    fun toPack(
        result: ContentImportResult,
        name: String,
        author: String,
        now: Long,
        packId: String = ContentIds.next("pack", now),
    ): KeyboardPack {
        val sectionGroups = linkedMapOf<String, LinkedHashMap<String, MutableList<ImportedContentRow>>>()
        result.rows.forEach { row ->
            sectionGroups
                .getOrPut(row.section) { linkedMapOf() }
                .getOrPut(row.group) { mutableListOf() }
                .add(row)
        }
        val sections = sectionGroups.entries.mapIndexed { sectionIndex, (sectionTitle, groups) ->
            val episode = EPISODE_NUMBER.find(sectionTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            KeyboardSection(
                id = ContentIds.next("section", now + sectionIndex),
                title = sectionTitle,
                type = if (episode != null) SectionType.Episode else SectionType.Custom,
                episodeNumber = episode,
                order = sectionIndex,
                groups = groups.entries.mapIndexed { groupIndex, (groupTitle, rows) ->
                    PhraseGroup(
                        id = ContentIds.next("group", now + sectionIndex * 1_000L + groupIndex),
                        title = groupTitle,
                        startMs = rows.mapNotNull(ImportedContentRow::startMs).minOrNull(),
                        endMs = rows.mapNotNull(ImportedContentRow::endMs).maxOrNull(),
                        order = groupIndex,
                        phrases = rows.mapIndexed { phraseIndex, row ->
                            PhraseItem(
                                id = ContentIds.next(
                                    "phrase",
                                    now + sectionIndex * 100_000L + groupIndex * 1_000L + phraseIndex,
                                ),
                                text = row.text,
                                tags = row.tags,
                                source = "import:${row.section}/${row.group}",
                                reviewState = ReviewState.Pending,
                                enabled = row.enabled,
                                order = phraseIndex,
                            )
                        },
                    )
                },
            )
        }
        return KeyboardPack(
            id = packId,
            name = name.trim().ifEmpty { "导入内容" },
            author = author.trim(),
            version = 1,
            sections = sections,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun exportCsv(pack: KeyboardPack): String = buildString {
        appendLine("section,group,start_ms,end_ms,text,tags,enabled")
        pack.sections.sortedBy(KeyboardSection::order).forEach { section ->
            section.groups.sortedBy(PhraseGroup::order).forEach { group ->
                group.phrases.sortedBy(PhraseItem::order).forEach { phrase ->
                    appendLine(
                        listOf(
                            section.title,
                            group.title,
                            group.startMs?.toString().orEmpty(),
                            group.endMs?.toString().orEmpty(),
                            phrase.text,
                            phrase.tags.sorted().joinToString("|"),
                            phrase.enabled.toString(),
                        ).joinToString(",", transform = ::escapeCsv),
                    )
                }
            }
        }
    }

    fun exportTxt(pack: KeyboardPack): String = pack.sections
        .flatMap(KeyboardSection::groups)
        .flatMap(PhraseGroup::phrases)
        .filter(PhraseItem::enabled)
        .joinToString("\n", transform = PhraseItem::text)

    fun exportSrt(pack: KeyboardPack): String {
        var index = 1
        return buildString {
            pack.sections.sortedBy(KeyboardSection::order).forEach { section ->
                section.groups.sortedBy(PhraseGroup::order).forEach { group ->
                    val start = group.startMs ?: return@forEach
                    val end = group.endMs ?: (start + 3_000L)
                    group.phrases.sortedBy(PhraseItem::order).forEach { phrase ->
                        appendLine(index++)
                        appendLine("${formatSrtTime(start)} --> ${formatSrtTime(end)}")
                        appendLine(phrase.text)
                        appendLine()
                    }
                }
            }
        }.trimEnd()
    }

    private fun parseTxt(source: String): ContentImportResult {
        val rows = mutableListOf<ImportedContentRow>()
        var truncated = false
        for (raw in source.lineSequence()) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (rows.size >= ImportFileLimits.MAX_IMPORTED_ROWS) {
                truncated = true
                break
            }
            rows += ImportedContentRow(section = "通用", group = "TXT 导入", text = text)
        }
        return ContentImportResult(
            rows = rows,
            warnings = if (truncated) listOf(importRowLimitWarning()) else emptyList(),
        )
    }

    private fun parseCsv(source: String): ContentImportResult {
        val parsed = parseCsvRecords(source)
        val records = parsed.records
        if (records.isEmpty()) {
            return ContentImportResult(emptyList(), parsed.warnings + "CSV 没有内容")
        }
        val header = records.first().mapIndexed { index, value ->
            value.trim().let { if (index == 0) it.removePrefix("\uFEFF") else it }.lowercase()
        }
        val required = listOf("section", "group", "text")
        val missing = required.filterNot(header::contains)
        if (missing.isNotEmpty()) {
            return ContentImportResult(
                emptyList(),
                parsed.warnings + "CSV 缺少字段：${missing.joinToString()}",
            )
        }
        fun List<String>.field(name: String): String = getOrNull(header.indexOf(name)).orEmpty().trim()
        val warnings = parsed.warnings.toMutableList()
        val rows = records.drop(1).mapIndexedNotNull { index, record ->
            val lineNumber = index + 2
            val text = record.field("text")
            if (text.isBlank()) {
                warnings += "CSV 第 $lineNumber 行文本为空"
                null
            } else {
                fun parseTime(fieldName: String): Long? {
                    val raw = record.field(fieldName)
                    if (raw.isEmpty()) return null
                    val parsedValue = raw.toLongOrNull()
                    if (parsedValue == null || parsedValue < 0L) {
                        warnings += "CSV 第 $lineNumber 行 $fieldName 无效"
                        return null
                    }
                    return parsedValue
                }
                val enabledText = record.field("enabled")
                val enabled = when {
                    enabledText.isEmpty() -> true
                    enabledText.equals("true", ignoreCase = true) || enabledText == "1" -> true
                    enabledText.equals("false", ignoreCase = true) || enabledText == "0" -> false
                    else -> {
                        warnings += "CSV 第 $lineNumber 行 enabled 无效，已按启用处理"
                        true
                    }
                }
                ImportedContentRow(
                    section = record.field("section").ifEmpty { "通用" },
                    group = record.field("group").ifEmpty { "未分组" },
                    startMs = parseTime("start_ms"),
                    endMs = parseTime("end_ms"),
                    text = text,
                    tags = record.field("tags").split('|').map(String::trim).filter(String::isNotEmpty).toSet(),
                    enabled = enabled,
                )
            }
        }
        return ContentImportResult(rows, warnings)
    }

    private fun parseSrt(source: String): ContentImportResult {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return ContentImportResult(emptyList(), listOf("SRT 没有内容"))
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<ImportedContentRow>()
        var blockIndex = 0
        for (block in normalized.splitToSequence(Regex("\n{2,}"))) {
            if (blockIndex >= ImportFileLimits.MAX_IMPORTED_ROWS) {
                warnings += importRowLimitWarning()
                break
            }
            val index = blockIndex++
            val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) {
                warnings += "SRT 第 ${index + 1} 段缺少时间轴"
                continue
            }
            val times = lines[timeIndex].split("-->")
            val start = times.getOrNull(0)?.trim()?.let(::parseSrtTime)
            val end = times.getOrNull(1)?.trim()?.substringBefore(' ')?.let(::parseSrtTime)
            val text = lines.drop(timeIndex + 1).joinToString(" ").trim()
            if (start == null || end == null || text.isBlank()) {
                warnings += "SRT 第 ${index + 1} 段格式无效"
            } else {
                rows += ImportedContentRow(
                    section = "字幕导入",
                    group = "${formatMinuteSecond(start)} - ${formatMinuteSecond(end)}",
                    startMs = start,
                    endMs = end,
                    text = text,
                )
            }
        }
        return ContentImportResult(rows, warnings)
    }

    private fun parseAss(source: String): ContentImportResult {
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<ImportedContentRow>()
        var dialogueCount = 0
        source.lineSequence().forEachIndexed { index, raw ->
            if (!raw.startsWith("Dialogue:", ignoreCase = true)) return@forEachIndexed
            if (dialogueCount >= ImportFileLimits.MAX_IMPORTED_ROWS) {
                if (warnings.lastOrNull() != importRowLimitWarning()) warnings += importRowLimitWarning()
                return@forEachIndexed
            }
            dialogueCount += 1
            val fields = raw.substringAfter(':').trim().split(',', limit = 10)
            if (fields.size < 10) {
                warnings += "ASS 第 ${index + 1} 行字段不足"
                return@forEachIndexed
            }
            val start = parseAssTime(fields[1].trim())
            val end = parseAssTime(fields[2].trim())
            val text = fields[9]
                .replace(Regex("\\\\[Nn]"), " ")
                .replace(Regex("\\{[^}]*}"), "")
                .trim()
            if (start == null || end == null || text.isBlank()) {
                warnings += "ASS 第 ${index + 1} 行格式无效"
            } else {
                rows += ImportedContentRow(
                    section = "字幕导入",
                    group = "${formatMinuteSecond(start)} - ${formatMinuteSecond(end)}",
                    startMs = start,
                    endMs = end,
                    text = text,
                )
            }
        }
        return ContentImportResult(rows, warnings)
    }

    private fun ContentImportResult.cleaned(): ContentImportResult {
        val warnings = warnings.toMutableList()
        val seen = mutableSetOf<String>()
        val cleaned = rows.mapNotNull { row ->
            val text = cleanText(row.text)
            if (text.isBlank()) return@mapNotNull null
            val key = "${row.section}\u0000${row.group}\u0000$text"
            if (!seen.add(key)) {
                warnings += "已忽略重复内容：${text.take(24)}"
                return@mapNotNull null
            }
            if (row.startMs != null && row.endMs != null && row.startMs > row.endMs) {
                warnings += "时间范围异常：${text.take(24)}"
            }
            row.copy(
                section = cleanText(row.section).ifEmpty { "通用" },
                group = cleanText(row.group).ifEmpty { "未分组" },
                text = text,
                tags = row.tags.map(::cleanText).filter(String::isNotEmpty).toSet(),
            )
        }
        return ContentImportResult(cleaned, warnings.distinct())
    }

    private fun ContentImportResult.limited(): ContentImportResult {
        if (rows.size <= ImportFileLimits.MAX_IMPORTED_ROWS) return this
        return ContentImportResult(
            rows = rows.take(ImportFileLimits.MAX_IMPORTED_ROWS),
            warnings = warnings + importRowLimitWarning(),
        )
    }

    private fun importRowLimitWarning(): String =
        "导入内容超过 ${ImportFileLimits.MAX_IMPORTED_ROWS} 条，超出部分已忽略"

    private fun cleanText(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { character -> character == '\n' || !character.isISOControl() }
        .trim()

    private fun parseCsvRecords(source: String): CsvRecordsParseResult {
        val records = mutableListOf<List<String>>()
        val warnings = mutableListOf<String>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        var lineNumber = 1
        var recordStartLine = 1
        fun finishField() {
            record += field.toString()
            field.clear()
        }
        fun finishRecord() {
            finishField()
            if (record.any(String::isNotEmpty)) {
                if (records.size < ImportFileLimits.MAX_IMPORTED_ROWS + 1) {
                    records += record.toList()
                } else if (warnings.lastOrNull() != importRowLimitWarning()) {
                    warnings += importRowLimitWarning()
                }
            }
            record.clear()
        }
        while (index < source.length) {
            val char = source[index]
            when {
                char == '"' && quoted && source.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && source.getOrNull(index + 1) == '\n') index += 1
                    finishRecord()
                    lineNumber += 1
                    recordStartLine = lineNumber
                }
                else -> {
                    field.append(char)
                    if (char == '\n') lineNumber += 1
                }
            }
            index += 1
        }
        if (quoted) {
            warnings += "CSV 第 $recordStartLine 行引号未闭合，已忽略该条记录"
        } else if (field.isNotEmpty() || record.isNotEmpty()) {
            finishRecord()
        }
        return CsvRecordsParseResult(records, warnings)
    }

    private fun escapeCsv(value: String): String = if (
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    ) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

    private fun parseSrtTime(value: String): Long? {
        val match = SRT_TIME.matchEntire(value) ?: return null
        return match.groupValues[1].toLong() * 3_600_000L +
            match.groupValues[2].toLong() * 60_000L +
            match.groupValues[3].toLong() * 1_000L +
            match.groupValues[4].padEnd(3, '0').take(3).toLong()
    }

    private fun parseAssTime(value: String): Long? {
        val match = ASS_TIME.matchEntire(value) ?: return null
        return match.groupValues[1].toLong() * 3_600_000L +
            match.groupValues[2].toLong() * 60_000L +
            match.groupValues[3].toLong() * 1_000L +
            match.groupValues[4].padEnd(3, '0').take(3).toLong()
    }

    private fun formatSrtTime(value: Long): String {
        val hours = value / 3_600_000L
        val minutes = value / 60_000L % 60L
        val seconds = value / 1_000L % 60L
        val millis = value % 1_000L
        return "${hours.two()}:${minutes.two()}:${seconds.two()},${millis.three()}"
    }

    private fun formatMinuteSecond(value: Long): String =
        "${(value / 60_000L).two()}:${(value / 1_000L % 60L).two()}"

    private fun Long.two(): String = toString().padStart(2, '0')
    private fun Long.three(): String = toString().padStart(3, '0')

    private val EPISODE_NUMBER = Regex("第\\s*(\\d+)\\s*集")
    private val SRT_TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.:](\\d{1,3})")
    private val ASS_TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[.](\\d{1,3})")

    private data class CsvRecordsParseResult(
        val records: List<List<String>>,
        val warnings: List<String>,
    )
}
