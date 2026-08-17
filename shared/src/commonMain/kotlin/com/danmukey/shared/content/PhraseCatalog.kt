package com.danmukey.shared.content

import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.ReviewState

enum class PhraseReviewFilter {
    All,
    Pending,
    Approved,
    Rejected,
}

enum class PhraseEnabledFilter {
    All,
    Enabled,
    Disabled,
}

enum class PhraseSortOrder {
    PackOrder,
    Text,
    ReviewState,
}

data class PhraseCatalogQuery(
    val text: String = "",
    val reviewFilter: PhraseReviewFilter = PhraseReviewFilter.All,
    val enabledFilter: PhraseEnabledFilter = PhraseEnabledFilter.All,
    val sortOrder: PhraseSortOrder = PhraseSortOrder.PackOrder,
    val sectionId: String? = null,
    val groupId: String? = null,
)

data class PhraseCatalogEntry(
    val phrase: PhraseItem,
    val sectionId: String,
    val sectionTitle: String,
    val sectionOrder: Int,
    val groupId: String,
    val groupTitle: String,
    val groupOrder: Int,
)

fun KeyboardPack.queryPhrases(query: PhraseCatalogQuery): List<PhraseCatalogEntry> {
    val normalizedQuery = query.text.trim().lowercase()
    val entries = sections.flatMap { section ->
        section.groups.flatMap { group ->
            group.phrases.map { phrase ->
                PhraseCatalogEntry(
                    phrase = phrase,
                    sectionId = section.id,
                    sectionTitle = section.title,
                    sectionOrder = section.order,
                    groupId = group.id,
                    groupTitle = group.title,
                    groupOrder = group.order,
                )
            }
        }
    }.asSequence()
        .filter { entry -> query.sectionId == null || entry.sectionId == query.sectionId }
        .filter { entry -> query.groupId == null || entry.groupId == query.groupId }
        .filter { entry ->
            normalizedQuery.isEmpty() || listOf(
                entry.phrase.text,
                entry.phrase.tags.joinToString(" "),
                entry.sectionTitle,
                entry.groupTitle,
            ).any { value -> normalizedQuery in value.lowercase() }
        }
        .filter { entry ->
            when (query.reviewFilter) {
                PhraseReviewFilter.All -> true
                PhraseReviewFilter.Pending -> entry.phrase.reviewState == ReviewState.Pending
                PhraseReviewFilter.Approved -> entry.phrase.reviewState == ReviewState.Approved
                PhraseReviewFilter.Rejected -> entry.phrase.reviewState == ReviewState.Rejected
            }
        }
        .filter { entry ->
            when (query.enabledFilter) {
                PhraseEnabledFilter.All -> true
                PhraseEnabledFilter.Enabled -> entry.phrase.enabled
                PhraseEnabledFilter.Disabled -> !entry.phrase.enabled
            }
        }
        .toList()

    return when (query.sortOrder) {
        PhraseSortOrder.PackOrder -> entries.sortedWith(
            compareBy<PhraseCatalogEntry> { it.sectionOrder }
                .thenBy { it.groupOrder }
                .thenBy { it.phrase.order }
                .thenBy { it.phrase.id },
        )
        PhraseSortOrder.Text -> entries.sortedWith(
            compareBy<PhraseCatalogEntry> { it.phrase.text.lowercase() }
                .thenBy { it.sectionOrder }
                .thenBy { it.groupOrder }
                .thenBy { it.phrase.order },
        )
        PhraseSortOrder.ReviewState -> entries.sortedWith(
            compareBy<PhraseCatalogEntry> { it.phrase.reviewState.reviewRank }
                .thenByDescending { it.phrase.enabled }
                .thenBy { it.sectionOrder }
                .thenBy { it.groupOrder }
                .thenBy { it.phrase.order },
        )
    }
}

fun KeyboardPack.updatePhraseStates(
    phraseIds: Set<String>,
    enabled: Boolean? = null,
    reviewState: ReviewState? = null,
): Pair<KeyboardPack, Int> {
    if (phraseIds.isEmpty()) return this to 0
    var changedCount = 0
    val updatedSections = sections.map { section ->
        section.copy(
            groups = section.groups.map { group ->
                group.copy(
                    phrases = group.phrases.map { phrase ->
                        if (phrase.id !in phraseIds) {
                            phrase
                        } else {
                            val updated = phrase.copy(
                                enabled = enabled ?: phrase.enabled,
                                reviewState = reviewState ?: phrase.reviewState,
                            )
                            if (updated != phrase) changedCount += 1
                            updated
                        }
                    },
                )
            },
        )
    }
    return copy(sections = updatedSections) to changedCount
}

private val ReviewState.reviewRank: Int
    get() = when (this) {
        ReviewState.Pending -> 0
        ReviewState.Rejected -> 1
        ReviewState.Approved -> 2
    }
