package com.danmukey.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EpisodeMappingTest {
    @Test
    fun titleNormalizationIgnoresWhitespaceAndPunctuation() {
        assertEquals("庆余年第二季第3集", EpisodeTitleNormalizer.normalize(" 庆余年·第二季 / 第 3 集 "))
        assertEquals("episode03", EpisodeTitleNormalizer.normalize("Episode 03"))
    }

    @Test
    fun equivalentEpisodeNumberStylesShareOneConservativeMatchKey() {
        val expected = "庆余年season2episode3"

        assertEquals(expected, EpisodeTitleNormalizer.matchKey("庆余年 第二季 第三集"))
        assertEquals(expected, EpisodeTitleNormalizer.matchKey("庆余年 S02E03"))
        assertEquals(expected, EpisodeTitleNormalizer.matchKey("庆余年 Season 2 Episode 03"))
        assertEquals("episode3", EpisodeTitleNormalizer.matchKey("Ｅｐｉｓｏｄｅ　０３"))
        assertEquals("episode12", EpisodeTitleNormalizer.matchKey("第十二話"))
        assertEquals("episode10", EpisodeTitleNormalizer.matchKey("⑩"))
    }

    @Test
    fun titleWordsAndDecorationsRemainPartOfMatchKey() {
        assertEquals("三体episode3预告", EpisodeTitleNormalizer.matchKey("三体·第三集·预告"))
        assertNotEquals(
            EpisodeTitleNormalizer.matchKey("三体 第三集"),
            EpisodeTitleNormalizer.matchKey("庆余年 第三集"),
        )
        assertNotEquals(
            EpisodeTitleNormalizer.matchKey("三体 第三集"),
            EpisodeTitleNormalizer.matchKey("三体 第三集 预告"),
        )
    }
}
