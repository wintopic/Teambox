package com.danmukey.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionCapturePrivacyTest {
    @Test
    fun removesTheWholeLegacyProjectionDirectoryWithoutTouchingOtherCacheFiles() {
        val cacheDirectory = Files.createTempDirectory("danmukey-cache-").toFile()
        try {
            val legacyDirectory = cacheDirectory.resolve("projection")
            legacyDirectory.resolve("nested").mkdirs()
            legacyDirectory.resolve("latest.png").writeBytes(byteArrayOf(1, 2, 3))
            legacyDirectory.resolve("nested/older-frame.png").writeBytes(byteArrayOf(4, 5, 6))
            val unrelated = cacheDirectory.resolve("keep-me.txt").apply { writeText("safe") }

            val result = LegacyProjectionArtifactCleaner.clear(cacheDirectory)

            assertTrue(result.artifactsFound)
            assertTrue(result.deleted)
            assertFalse(legacyDirectory.exists())
            assertTrue(unrelated.exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun missingLegacyDirectoryIsAlreadyClean() {
        val cacheDirectory = Files.createTempDirectory("danmukey-cache-").toFile()
        try {
            val result = LegacyProjectionArtifactCleaner.clear(cacheDirectory)

            assertFalse(result.artifactsFound)
            assertTrue(result.deleted)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }
}
