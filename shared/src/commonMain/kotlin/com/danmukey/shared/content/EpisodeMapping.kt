package com.danmukey.shared.content

object EpisodeTitleNormalizer {
    fun normalize(rawTitle: String): String = rawTitle
        .trim()
        .lowercase()
        .filter(Char::isLetterOrDigit)

    fun matchKey(rawTitle: String): String {
        val containsCircledNumber = rawTitle.any { it.code in CIRCLED_NUMBER_RANGE }
        var value = compatibilityFold(rawTitle)
            .lowercase()
            .filter(Char::isLetterOrDigit)
        value = replaceNumberMarker(value, CHINESE_SEASON, "season")
        value = replaceNumberMarker(value, CHINESE_EPISODE, "episode")
        value = SEASON_EPISODE_ABBREVIATION.replace(value) { match ->
            val season = normalizeArabicNumber(match.groupValues[1]) ?: return@replace match.value
            val episode = normalizeArabicNumber(match.groupValues[2]) ?: return@replace match.value
            "season${season}episode${episode}"
        }
        value = replaceArabicMarker(value, ENGLISH_SEASON, "season")
        value = replaceArabicMarker(value, ENGLISH_EPISODE, "episode")
        value = replacePrefixedArabicMarker(value, SHORT_SEASON, "season")
        value = replacePrefixedArabicMarker(value, SHORT_EPISODE, "episode")
        value = replacePrefixedArabicMarker(value, SINGLE_LETTER_EPISODE, "episode")
        if (containsCircledNumber && value.isNotEmpty() && value.all(Char::isDigit)) {
            normalizeArabicNumber(value)?.let { return "episode$it" }
        }
        return value
    }

    private fun compatibilityFold(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when {
                character == IDEOGRAPHIC_SPACE -> append(' ')
                character.code in FULL_WIDTH_ASCII_RANGE -> append(
                    (character.code - FULL_WIDTH_ASCII_OFFSET).toChar(),
                )
                character.code in CIRCLED_NUMBER_RANGE -> append(character.code - CIRCLED_NUMBER_OFFSET)
                else -> append(character)
            }
        }
    }

    private fun replaceNumberMarker(value: String, pattern: Regex, marker: String): String =
        pattern.replace(value) { match ->
            val number = parseEpisodeNumber(match.groupValues[1]) ?: return@replace match.value
            "$marker$number"
        }

    private fun replaceArabicMarker(value: String, pattern: Regex, marker: String): String =
        pattern.replace(value) { match ->
            val number = normalizeArabicNumber(match.groupValues[1]) ?: return@replace match.value
            "$marker$number"
        }

    private fun replacePrefixedArabicMarker(value: String, pattern: Regex, marker: String): String =
        pattern.replace(value) { match ->
            val number = normalizeArabicNumber(match.groupValues[2]) ?: return@replace match.value
            "${match.groupValues[1]}$marker$number"
        }

    private fun parseEpisodeNumber(value: String): Long? {
        normalizeArabicNumber(value)?.let { return it }
        val hasUnit = value.any { it == '十' || it == '百' || it == '千' }
        return if (hasUnit) parseChineseUnitNumber(value) else parseChineseDigitSequence(value)
    }

    private fun normalizeArabicNumber(value: String): Long? = value.toLongOrNull()

    private fun parseChineseDigitSequence(value: String): Long? {
        var result = 0L
        value.forEach { character ->
            val digit = chineseDigit(character) ?: return null
            if (result > (Long.MAX_VALUE - digit) / 10L) return null
            result = result * 10L + digit
        }
        return result
    }

    private fun parseChineseUnitNumber(value: String): Long? {
        var total = 0L
        var digit = 0L
        value.forEach { character ->
            val parsedDigit = chineseDigit(character)
            if (parsedDigit != null) {
                digit = parsedDigit.toLong()
                return@forEach
            }
            val unit = when (character) {
                '十' -> 10L
                '百' -> 100L
                '千' -> 1_000L
                else -> return null
            }
            total += (if (digit == 0L) 1L else digit) * unit
            digit = 0L
        }
        return total + digit
    }

    private fun chineseDigit(character: Char): Int? = when (character) {
        '〇', '零' -> 0
        '一' -> 1
        '二', '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
    }

    private val CHINESE_SEASON = Regex("第([0-9〇零一二三四五六七八九十百千两]+)季")
    private val CHINESE_EPISODE = Regex("第([0-9〇零一二三四五六七八九十百千两]+)[集话話期章]")
    private val SEASON_EPISODE_ABBREVIATION = Regex("s0*([0-9]+)e0*([0-9]+)")
    private val ENGLISH_SEASON = Regex("season0*([0-9]+)")
    private val ENGLISH_EPISODE = Regex("episode0*([0-9]+)")
    private val SHORT_SEASON = Regex("(^|[^a-z])s0*([0-9]+)")
    private val SHORT_EPISODE = Regex("(^|[^a-z])ep0*([0-9]+)")
    private val SINGLE_LETTER_EPISODE = Regex("(^|[^a-z])e0*([0-9]+)")
    private const val IDEOGRAPHIC_SPACE = '\u3000'
    private val FULL_WIDTH_ASCII_RANGE = 0xFF01..0xFF5E
    private const val FULL_WIDTH_ASCII_OFFSET = 0xFEE0
    private val CIRCLED_NUMBER_RANGE = 0x2460..0x2473
    private const val CIRCLED_NUMBER_OFFSET = 0x245F
}
