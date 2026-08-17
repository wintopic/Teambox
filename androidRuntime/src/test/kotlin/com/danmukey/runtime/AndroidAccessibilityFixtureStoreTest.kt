package com.danmukey.runtime

import com.danmukey.shared.accessibility.AccessibilityBounds
import com.danmukey.shared.accessibility.AccessibilityNodeFixture
import com.danmukey.shared.accessibility.AccessibilityTreeFixture
import com.danmukey.shared.data.LocalDataRetentionPolicy
import com.danmukey.shared.model.Orientation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccessibilityFixtureStoreTest {
    @Test
    fun saveUsesARestrictedNameAndClearRemovesOnlyTheFixtureDirectory() {
        val cacheDirectory = Files.createTempDirectory("danmukey-fixtures-").toFile()
        try {
            val unrelated = cacheDirectory.resolve("keep-me.txt").apply { writeText("safe") }
            val store = AndroidAccessibilityFixtureStore(cacheDirectory) { 10_000L }

            val saved = store.save(fixture(packageName = "target/app", capturedAt = 9_000L))

            assertEquals("target_app-portrait-9000.json", saved.name)
            assertTrue(saved.readText().contains("danmukey-accessibility-fixture"))
            assertEquals(1, store.clear())
            assertFalse(saved.exists())
            assertTrue(unrelated.exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun pruneRemovesExpiredAndExcessFixturesButKeepsTheNewestTwenty() {
        val cacheDirectory = Files.createTempDirectory("danmukey-fixtures-").toFile()
        val day = 24L * 60L * 60L * 1_000L
        val now = 50L * day
        try {
            val store = AndroidAccessibilityFixtureStore(cacheDirectory) { now }
            val expired = store.save(
                fixture(
                    packageName = "expired",
                    capturedAt = now - LocalDataRetentionPolicy.DIAGNOSTIC_RETENTION_MS - 1L,
                ),
            )
            assertFalse(expired.exists())

            repeat(25) { index ->
                store.save(fixture(packageName = "target$index", capturedAt = now - index))
            }

            val retained = cacheDirectory.resolve("accessibility-fixtures").listFiles().orEmpty()
            assertEquals(20, retained.size)
            assertTrue(retained.none { it.name.startsWith("target24-") })
            assertTrue(retained.any { it.name.startsWith("target0-") })
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun fixture(packageName: String, capturedAt: Long) = AccessibilityTreeFixture(
        capturedAt = capturedAt,
        packageName = packageName,
        appVersionCode = 1L,
        systemApi = 29,
        orientation = Orientation.Portrait,
        screenWidth = 1080,
        screenHeight = 2131,
        nodes = listOf(
            AccessibilityNodeFixture(
                depth = 0,
                className = "android.widget.Button",
                resourceId = "target:id/action",
                clickable = true,
                editable = false,
                focusable = true,
                bounds = AccessibilityBounds(0, 0, 100, 50),
            ),
        ),
    )
}
