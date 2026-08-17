package com.danmukey.app

import com.danmukey.shared.data.ImportFileLimits
import com.danmukey.shared.data.ImportPayloadKind
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitedImportReaderTest {
    @Test
    fun readerAcceptsExactLimitAndRejectsFirstExtraByte() {
        val kind = ImportPayloadKind.TargetRule
        val maximum = ImportFileLimits.maximumBytes(kind)

        val accepted = ByteArrayInputStream(ByteArray(maximum)).use {
            it.readLimitedImportBytes(kind)
        }
        assertEquals(maximum, accepted.size)

        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream(ByteArray(maximum + 1)).use {
                it.readLimitedImportBytes(kind)
            }
        }
    }
}
