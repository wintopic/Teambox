package com.danmukey.shared.content

import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup

object PlaybackTimeParser {
    private val clockPattern = Regex("\\d{1,3}(?::\\d{1,2}){1,2}")

    fun parseCurrentPosition(text: String): Long? {
        val clock = clockPattern.find(text)?.value ?: return null
        val parts = clock.split(':').mapNotNull(String::toLongOrNull)
        if (parts.size !in 2..3) return null
        if (parts.last() !in 0L..59L) return null
        if (parts.size == 3 && parts[1] !in 0L..59L) return null
        val seconds = if (parts.size == 2) {
            parts[0] * 60L + parts[1]
        } else {
            parts[0] * 3_600L + parts[1] * 60L + parts[2]
        }
        return seconds * 1_000L
    }
}

object SceneFollower {
    fun selectGroup(section: KeyboardSection, playbackPositionMs: Long): PhraseGroup? = section.groups
        .asSequence()
        .filter { group -> group.startMs != null || group.endMs != null }
        .filter { group -> (group.startMs ?: 0L) <= playbackPositionMs }
        .filter { group -> group.endMs == null || playbackPositionMs <= group.endMs }
        .sortedWith(
            compareByDescending<PhraseGroup> { it.startMs ?: 0L }
                .thenBy(PhraseGroup::order),
        )
        .firstOrNull()
}
