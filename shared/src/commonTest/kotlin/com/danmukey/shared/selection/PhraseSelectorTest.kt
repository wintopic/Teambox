package com.danmukey.shared.selection

import com.danmukey.shared.model.SelectionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseSelectorTest {
    private val candidates = listOf(
        PhraseCandidate(id = "a", order = 0, useCount = 4, lastUsedAt = 400),
        PhraseCandidate(id = "b", order = 1, useCount = 1, lastUsedAt = 200),
        PhraseCandidate(id = "c", order = 2, useCount = 1, lastUsedAt = 100),
    )

    @Test
    fun leastRecentlyUsedPrefersLowerCountThenOlderUse() {
        val selected = PhraseSelector.select(
            candidates = candidates,
            policy = SelectionPolicy.LeastRecentlyUsed,
        )

        assertEquals("c", selected?.id)
    }

    @Test
    fun recentItemsAreExcludedWhenAlternativesExist() {
        val selected = PhraseSelector.select(
            candidates = candidates,
            policy = SelectionPolicy.Sequential,
            recentPhraseIds = setOf("a", "b"),
        )

        assertEquals("c", selected?.id)
    }

    @Test
    fun recentFilterFallsBackWhenAllItemsWereUsed() {
        val selected = PhraseSelector.select(
            candidates = candidates,
            policy = SelectionPolicy.Sequential,
            recentPhraseIds = setOf("a", "b", "c"),
            currentIndex = 1,
        )

        assertEquals("b", selected?.id)
    }

    @Test
    fun sequentialSelectionStartsAtPointerThenSkipsRecentItems() {
        val selected = PhraseSelector.select(
            candidates = candidates,
            policy = SelectionPolicy.Sequential,
            recentPhraseIds = setOf("b"),
            currentIndex = 1,
        )

        assertEquals("c", selected?.id)
    }
}
