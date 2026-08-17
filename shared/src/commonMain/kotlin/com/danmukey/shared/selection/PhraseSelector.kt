package com.danmukey.shared.selection

import com.danmukey.shared.model.SelectionPolicy
import kotlin.random.Random

data class PhraseCandidate(
    val id: String,
    val order: Int,
    val enabled: Boolean = true,
    val useCount: Long = 0,
    val lastUsedAt: Long? = null,
)

object PhraseSelector {
    fun select(
        candidates: List<PhraseCandidate>,
        policy: SelectionPolicy,
        recentPhraseIds: Set<String> = emptySet(),
        currentIndex: Int = 0,
        randomSeed: Long = 0,
    ): PhraseCandidate? {
        val enabled = candidates.filter(PhraseCandidate::enabled)
        if (enabled.isEmpty()) return null

        return when (policy) {
            SelectionPolicy.Sequential -> {
                val ordered = enabled.sortedBy(PhraseCandidate::order)
                val start = currentIndex.mod(ordered.size)
                val rotated = ordered.drop(start) + ordered.take(start)
                rotated.firstOrNull { it.id !in recentPhraseIds } ?: ordered[start]
            }
            else -> {
                val withoutRecent = enabled.filterNot { it.id in recentPhraseIds }
                val pool = withoutRecent.ifEmpty { enabled }
                when (policy) {
                    SelectionPolicy.Manual -> pool.minByOrNull(PhraseCandidate::order)
                    SelectionPolicy.Random -> pool[Random(randomSeed).nextInt(pool.size)]
                    SelectionPolicy.LeastRecentlyUsed -> pool.minWithOrNull(
                        compareBy<PhraseCandidate> { it.useCount }
                            .thenBy { it.lastUsedAt ?: Long.MIN_VALUE }
                            .thenBy { it.order },
                    )
                    SelectionPolicy.Sequential -> error("handled above")
                }
            }
        }
    }
}
