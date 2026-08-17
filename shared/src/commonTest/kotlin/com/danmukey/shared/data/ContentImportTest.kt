package com.danmukey.shared.data

import com.danmukey.shared.model.ReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentImportTest {
    @Test
    fun csvSupportsQuotedTextAndTags() {
        val source = """
            section,group,start_ms,end_ms,text,tags,enabled
            第一集,开场,1000,3000,"有逗号,也能导入",剧情|惊讶,true
        """.trimIndent()

        val result = ContentImport.parse(ContentTextFormat.Csv, source)
        assertEquals(1, result.rows.size)
        assertEquals("有逗号,也能导入", result.rows.single().text)
        assertEquals(setOf("剧情", "惊讶"), result.rows.single().tags)
    }

    @Test
    fun srtCreatesTimedPendingContent() {
        val source = """
            1
            00:01:02,000 --> 00:01:05,500
            第一条字幕

            2
            00:01:06,000 --> 00:01:08,000
            第二条字幕
        """.trimIndent()

        val result = ContentImport.parse(ContentTextFormat.Srt, source)
        val pack = ContentImport.toPack(result, "字幕包", "tester", now = 100L, packId = "srt-pack")

        assertEquals(2, result.rows.size)
        assertEquals(62_000L, result.rows.first().startMs)
        assertTrue(pack.sections.first().groups.isNotEmpty())
        assertEquals(ReviewState.Pending, pack.sections.first().groups.first().phrases.first().reviewState)
    }

    @Test
    fun duplicateTextInSameGroupIsReportedAndRemoved() {
        val result = ContentImport.parse(ContentTextFormat.Txt, "重复\n重复\n不同")
        assertEquals(listOf("重复", "不同"), result.rows.map { it.text })
        assertTrue(result.warnings.any { "重复" in it })
    }

    @Test
    fun csvExportCanBeImportedAgain() {
        val original = SampleContent.createPack(now = 100L)
        val csv = ContentImport.exportCsv(original)
        val imported = ContentImport.parse(ContentTextFormat.Csv, csv)
        assertEquals(
            original.sections.flatMap { it.groups }.flatMap { it.phrases }.map { it.text },
            imported.rows.map { it.text },
        )
    }

    @Test
    fun csvHandlesBomAndWarnsAboutInvalidOptionalFields() {
        val source = "\uFEFF" + """
            section,group,start_ms,end_ms,text,tags,enabled
            第一集,开场,-1,not-a-number,测试内容,异常,maybe
        """.trimIndent()

        val result = ContentImport.parse(ContentTextFormat.Csv, source)

        assertEquals(1, result.rows.size)
        assertEquals(null, result.rows.single().startMs)
        assertEquals(null, result.rows.single().endMs)
        assertTrue(result.rows.single().enabled)
        assertTrue(result.warnings.any { "start_ms 无效" in it })
        assertTrue(result.warnings.any { "end_ms 无效" in it })
        assertTrue(result.warnings.any { "enabled 无效" in it })
    }

    @Test
    fun csvSkipsUnterminatedFinalRecord() {
        val source = """
            section,group,text
            通用,正常,可以导入
            通用,异常,"没有闭合
        """.trimIndent()

        val result = ContentImport.parse(ContentTextFormat.Csv, source)

        assertEquals(listOf("可以导入"), result.rows.map { it.text })
        assertTrue(result.warnings.any { "引号未闭合" in it })
    }

    @Test
    fun fiveThousandRowsBuildACompletePackWithUniqueIds() {
        val source = buildString {
            appendLine("section,group,start_ms,end_ms,text,tags,enabled")
            repeat(5_000) { index ->
                appendLine(
                    "第${index % 10 + 1}集,场景${index % 50},${index * 1000},${index * 1000 + 800}," +
                        "大数据量内容$index,批量|回归,true",
                )
            }
        }

        val result = ContentImport.parse(ContentTextFormat.Csv, source)
        val pack = ContentImport.toPack(result, "五千条回归", "test", now = 1_000L, packId = "large-pack")
        val phrases = pack.sections.flatMap { it.groups }.flatMap { it.phrases }
        val ids = buildList {
            add(pack.id)
            pack.sections.forEach { section ->
                add(section.id)
                section.groups.forEach { group ->
                    add(group.id)
                    group.phrases.forEach { phrase -> add(phrase.id) }
                }
            }
        }

        assertEquals(5_000, result.rows.size)
        assertEquals(5_000, phrases.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun textImportStopsAtSharedRowLimit() {
        val source = buildString {
            repeat(ImportFileLimits.MAX_IMPORTED_ROWS + 5) { index ->
                appendLine("批量内容$index")
            }
        }

        val result = ContentImport.parse(ContentTextFormat.Txt, source)

        assertEquals(ImportFileLimits.MAX_IMPORTED_ROWS, result.rows.size)
        assertTrue(result.warnings.any { "超出部分已忽略" in it })
        assertEquals("批量内容0", result.rows.first().text)
        assertEquals("批量内容${ImportFileLimits.MAX_IMPORTED_ROWS - 1}", result.rows.last().text)
    }
}
