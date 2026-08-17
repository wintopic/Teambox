package com.danmukey.shared.content

import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.SectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseCatalogTest {
    @Test
    fun querySearchesTextTagsAndContextAcrossPack() {
        val pack = pack()

        assertEquals(listOf("p1"), pack.queryPhrases(PhraseCatalogQuery(text = "高能")).map { it.phrase.id })
        assertEquals(listOf("p2"), pack.queryPhrases(PhraseCatalogQuery(text = "反转")).map { it.phrase.id })
        assertEquals(
            listOf("p3"),
            pack.queryPhrases(PhraseCatalogQuery(text = "第二集")).map { it.phrase.id },
        )
        assertEquals(
            listOf("p2", "p1"),
            pack.queryPhrases(PhraseCatalogQuery(groupId = "g1")).map { it.phrase.id },
        )
    }

    @Test
    fun queryFiltersReviewAndEnabledStateAndSorts() {
        val pack = pack()
        val filtered = pack.queryPhrases(
            PhraseCatalogQuery(
                reviewFilter = PhraseReviewFilter.Pending,
                enabledFilter = PhraseEnabledFilter.Disabled,
            ),
        )
        assertEquals(listOf("p2"), filtered.map { it.phrase.id })

        val textSorted = pack.queryPhrases(PhraseCatalogQuery(sortOrder = PhraseSortOrder.Text))
        assertEquals(listOf("p3", "p2", "p1"), textSorted.map { it.phrase.id })
    }

    @Test
    fun batchStateUpdateTouchesOnlySelectedPhrases() {
        val (updated, changedCount) = pack().updatePhraseStates(
            phraseIds = setOf("p1", "p2"),
            enabled = false,
            reviewState = ReviewState.Rejected,
        )
        val phrases = updated.sections.flatMap { it.groups }.flatMap { it.phrases }.associateBy { it.id }

        assertEquals(2, changedCount)
        assertFalse(phrases.getValue("p1").enabled)
        assertEquals(ReviewState.Rejected, phrases.getValue("p1").reviewState)
        assertFalse(phrases.getValue("p2").enabled)
        assertEquals(ReviewState.Rejected, phrases.getValue("p2").reviewState)
        assertTrue(phrases.getValue("p3").enabled)
        assertEquals(ReviewState.Approved, phrases.getValue("p3").reviewState)
    }

    private fun pack(): KeyboardPack = KeyboardPack(
        id = "pack",
        name = "测试包",
        author = "test",
        version = 1,
        createdAt = 1L,
        updatedAt = 1L,
        sections = listOf(
            KeyboardSection(
                id = "s1",
                title = "第一集",
                type = SectionType.Episode,
                episodeNumber = 1,
                order = 0,
                groups = listOf(
                    PhraseGroup(
                        id = "g1",
                        title = "开场",
                        order = 0,
                        phrases = listOf(
                            PhraseItem(
                                id = "p1",
                                text = "C前方高能",
                                tags = setOf("气氛"),
                                reviewState = ReviewState.Approved,
                                enabled = true,
                                order = 1,
                            ),
                            PhraseItem(
                                id = "p2",
                                text = "B剧情反转",
                                tags = setOf("反转"),
                                reviewState = ReviewState.Pending,
                                enabled = false,
                                order = 0,
                            ),
                        ),
                    ),
                ),
            ),
            KeyboardSection(
                id = "s2",
                title = "第二集",
                type = SectionType.Episode,
                episodeNumber = 2,
                order = 1,
                groups = listOf(
                    PhraseGroup(
                        id = "g2",
                        title = "结尾",
                        order = 0,
                        phrases = listOf(
                            PhraseItem(
                                id = "p3",
                                text = "A安静收尾",
                                reviewState = ReviewState.Approved,
                                enabled = true,
                                order = 0,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
