package com.danmukey.shared.data

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContentCodecTest {
    @Test
    fun legacyPackWithFormatAsPhraseTextIsNotMistakenForEnvelope() {
        val original = SampleContent.createPack(100L)
        val section = original.sections.first()
        val group = section.groups.first()
        val pack = original.copy(
            sections = listOf(
                section.copy(
                    groups = listOf(
                        group.copy(
                            phrases = listOf(group.phrases.first().copy(text = "format")),
                        ),
                    ),
                ),
            ),
        )
        val legacyJson = ContentJson.instance.encodeToString(pack)

        assertEquals(pack, ContentCodec.decode(legacyJson))
    }

    @Test
    fun envelopeFormatAndVersionAreValidatedFromTopLevelFields() {
        val pack = SampleContent.createPack(100L)
        val encoded = ContentCodec.encode(pack, exportedAt = 200L)

        assertEquals(pack, ContentCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            ContentCodec.decode(encoded.replace("danmukey-content", "unsupported-content"))
        }
        assertFailsWith<IllegalArgumentException> {
            ContentCodec.decode(encoded.replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        }
    }
}
