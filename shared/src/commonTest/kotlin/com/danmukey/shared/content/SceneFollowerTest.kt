package com.danmukey.shared.content

import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.SectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SceneFollowerTest {
    @Test
    fun playbackParserReadsFirstClockWithoutUsingDuration() {
        assertEquals(83_000L, PlaybackTimeParser.parseCurrentPosition("01:23 / 45:00"))
        assertEquals(3_723_000L, PlaybackTimeParser.parseCurrentPosition("1:02:03"))
        assertNull(PlaybackTimeParser.parseCurrentPosition("直播中"))
        assertNull(PlaybackTimeParser.parseCurrentPosition("01:99"))
    }

    @Test
    fun sceneFollowerHighlightsOnlyContainingTimedGroup() {
        val section = KeyboardSection(
            id = "episode",
            title = "第一集",
            type = SectionType.Episode,
            episodeNumber = 1,
            order = 0,
            groups = listOf(
                PhraseGroup(id = "general", title = "通用", order = 0),
                PhraseGroup(id = "opening", title = "开场", startMs = 0, endMs = 59_999, order = 1),
                PhraseGroup(id = "turn", title = "反转", startMs = 60_000, endMs = 120_000, order = 2),
            ),
        )

        assertEquals("opening", SceneFollower.selectGroup(section, 30_000)?.id)
        assertEquals("turn", SceneFollower.selectGroup(section, 90_000)?.id)
        assertNull(SceneFollower.selectGroup(section, 130_000))
    }
}
