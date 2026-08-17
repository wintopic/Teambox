package com.danmukey.shared.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DKeyArchiveTest {
    @Test
    fun archiveRoundTripPreservesPack() {
        val pack = SampleContent.createPack(100L)
        val encoded = DKeyArchive.encode(pack, exportedAt = 200L)
        assertEquals(pack, DKeyArchive.decode(encoded))
    }

    @Test
    fun damagedArchiveIsRejected() {
        val encoded = DKeyArchive.encode(SampleContent.createPack(100L), exportedAt = 200L)
        val damaged = encoded.copyOf().also { bytes ->
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x40).toByte()
        }
        assertFails { DKeyArchive.decode(damaged) }
    }

    @Test
    fun archiveWithUnexpectedEntryIsRejected() {
        val encoded = DKeyArchive.encode(SampleContent.createPack(100L), exportedAt = 200L)
        val withExtraEntry = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                ZipInputStream(ByteArrayInputStream(encoded)).use { source ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        zip.putNextEntry(ZipEntry(entry.name))
                        source.copyTo(zip)
                        zip.closeEntry()
                        source.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("unexpected.txt"))
                zip.write("hidden payload".encodeToByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        assertFails { DKeyArchive.decode(withExtraEntry) }
    }
}
