package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.SectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContentPackImportTest {
    @Test
    fun collidingGraphIdsAreRemappedAsOneIndependentCopy() {
        val original = SampleContent.createPack(100L)
        val imported = ContentPackImport.prepare(
            decoded = original,
            existing = listOf(original),
            timestamp = 200L,
        )

        assertNotEquals(original.id, imported.id)
        assertEquals(original.phraseTexts(), imported.phraseTexts())
        val allIds = listOf(original, imported).flatMap { it.allIds() }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun oversizedPackGraphIsRejectedBeforePersistence() {
        val original = SampleContent.createPack(100L)
        val oversized = original.copy(
            sections = List(ImportFileLimits.MAX_PACK_SECTIONS + 1) { index ->
                KeyboardSection(
                    id = "section-$index",
                    title = "分区$index",
                    type = SectionType.Custom,
                    order = index,
                )
            },
        )

        assertFailsWith<IllegalArgumentException> {
            ContentPackImport.prepare(oversized, existing = emptyList(), timestamp = 200L)
        }
    }

    @Test
    fun oversizedSinglePhraseIsRejectedBeforePersistence() {
        val original = SampleContent.createPack(100L)
        val section = original.sections.first()
        val group = section.groups.first()
        val phrase = group.phrases.first().copy(
            text = "字".repeat(ImportFileLimits.MAX_PHRASE_CHARACTERS + 1),
        )
        val oversized = original.copy(
            sections = listOf(
                section.copy(
                    groups = listOf(group.copy(phrases = listOf(phrase))),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ContentPackImport.prepare(oversized, existing = emptyList(), timestamp = 200L)
        }
    }

    @Test
    fun duplicateIdsInsideImportedGraphAreRejected() {
        val original = SampleContent.createPack(100L)
        val section = original.sections.first()
        val group = section.groups.first()
        val duplicated = original.copy(
            sections = listOf(
                section.copy(
                    groups = listOf(
                        group.copy(
                            phrases = listOf(
                                group.phrases.first(),
                                group.phrases.last().copy(id = group.phrases.first().id),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ContentPackImport.prepare(duplicated, existing = emptyList(), timestamp = 200L)
        }
    }

    @Test
    fun blankGraphFieldsAreRejected() {
        val original = SampleContent.createPack(100L)
        val section = original.sections.first()
        val group = section.groups.first()

        listOf(
            original.copy(id = " "),
            original.copy(name = "\t"),
            original.copy(sections = listOf(section.copy(id = ""))),
            original.copy(sections = listOf(section.copy(title = " "))),
            original.copy(sections = listOf(section.copy(groups = listOf(group.copy(id = "\n"))))),
            original.copy(sections = listOf(section.copy(groups = listOf(group.copy(title = ""))))),
            original.copy(
                sections = listOf(
                    section.copy(
                        groups = listOf(
                            group.copy(phrases = listOf(group.phrases.first().copy(id = " "))),
                        ),
                    ),
                ),
            ),
            original.copy(
                sections = listOf(
                    section.copy(
                        groups = listOf(
                            group.copy(phrases = listOf(group.phrases.first().copy(text = "\t"))),
                        ),
                    ),
                ),
            ),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                ContentPackImport.prepare(invalid, existing = emptyList(), timestamp = 200L)
            }
        }
    }

    @Test
    fun invalidVersionsAndTimesAreRejected() {
        val original = SampleContent.createPack(100L)
        val section = original.sections.first()
        val invalidGroups = listOf(
            PhraseGroup(id = "negative-start", title = "负开始", startMs = -1L, order = 0),
            PhraseGroup(id = "negative-end", title = "负结束", endMs = -1L, order = 0),
            PhraseGroup(id = "reversed", title = "倒序", startMs = 2L, endMs = 1L, order = 0),
        )

        val invalidPacks = buildList {
            add(original.copy(version = 0))
            add(original.copy(createdAt = -1L))
            add(original.copy(updatedAt = -1L))
            add(original.copy(createdAt = 101L, updatedAt = 100L))
            invalidGroups.forEach { group ->
                add(original.copy(sections = listOf(section.copy(groups = listOf(group)))))
            }
        }

        invalidPacks.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                ContentPackImport.prepare(invalid, existing = emptyList(), timestamp = 200L)
            }
        }
    }

    @Test
    fun validPackWithoutCollisionsKeepsItsGraphIds() {
        val original = SampleContent.createPack(100L)

        val imported = ContentPackImport.prepare(
            decoded = original,
            existing = emptyList(),
            timestamp = 200L,
        )

        assertEquals(original.allIds(), imported.allIds())
        assertEquals(200L, imported.updatedAt)
    }

    private fun KeyboardPack.phraseTexts(): List<String> = sections
        .flatMap { it.groups }
        .flatMap { it.phrases }
        .map { it.text }

    private fun KeyboardPack.allIds(): List<String> = buildList {
        add(id)
        sections.forEach { section ->
            add(section.id)
            section.groups.forEach { group ->
                add(group.id)
                group.phrases.forEach { phrase -> add(phrase.id) }
            }
        }
    }
}
