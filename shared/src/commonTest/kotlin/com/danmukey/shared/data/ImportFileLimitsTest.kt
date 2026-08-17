package com.danmukey.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImportFileLimitsTest {
    @Test
    fun extensionsSelectExpectedPayloadLimits() {
        assertEquals(ImportPayloadKind.ContentArchive, ImportFileLimits.kindForExtension("dkey"))
        assertEquals(ImportPayloadKind.TargetRule, ImportFileLimits.kindForExtension(".DTARGET"))
        assertEquals(ImportPayloadKind.ContentText, ImportFileLimits.kindForExtension("csv"))
        assertEquals(ImportPayloadKind.ContentText, ImportFileLimits.kindForExtension("unknown"))
    }

    @Test
    fun exactSizeIsAcceptedAndFirstExtraByteIsRejected() {
        ImportPayloadKind.entries.forEach { kind ->
            val maximum = ImportFileLimits.maximumBytes(kind)
            ImportFileLimits.requireWithin(kind, maximum.toLong())
            assertFailsWith<IllegalArgumentException> {
                ImportFileLimits.requireWithin(kind, maximum.toLong() + 1L)
            }
        }
    }
}
