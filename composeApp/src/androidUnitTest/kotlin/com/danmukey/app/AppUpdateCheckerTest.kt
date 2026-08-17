package com.danmukey.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateCheckerTest {
    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(requireNotNull(AppUpdateChecker.compareSemanticVersions("1.10.0", "1.9.9")) > 0)
        assertTrue(requireNotNull(AppUpdateChecker.compareSemanticVersions("v2.0.0", "1.99.99")) > 0)
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("1.2.3+build.8", "1.2.3+build.9"))
    }

    @Test
    fun followsSemanticVersionPreReleaseOrdering() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        )
        ordered.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                requireNotNull(AppUpdateChecker.compareSemanticVersions(lower, higher)) < 0,
                "$lower should be lower than $higher",
            )
        }
    }

    @Test
    fun rejectsNonSemanticVersionsBeforeNetworkAccess() {
        assertNull(AppUpdateChecker.compareSemanticVersions("1.0", "1.0.0"))
        assertNull(AppUpdateChecker.compareSemanticVersions("01.0.0", "1.0.0"))
        assertNull(AppUpdateChecker.compareSemanticVersions("1.0.0-01", "1.0.0"))

        val result = AppUpdateChecker.check("not-a-version")
        val failure = assertIs<AppUpdateResult.Failure>(result)
        assertEquals(null, failure.errors.single().source)
    }

    @Test
    fun acceptsOnlyTheFixedGitHubReleasePage() {
        assertTrue(
            AppUpdateChecker.isTrustedReleasePageUrl(
                "https://github.com/wintopic/Teambox/releases/tag/v1.2.3",
                "v1.2.3",
            ),
        )

        val rejected = listOf(
            "http://github.com/wintopic/Teambox/releases/tag/v1.2.3",
            "https://github.com.evil.example/wintopic/Teambox/releases/tag/v1.2.3",
            "https://github.com/other/Teambox/releases/tag/v1.2.3",
            "https://github.com/wintopic/Teambox/releases/tag/v9.9.9",
            "https://github.com/wintopic/Teambox/releases/tag/v1.2.3?download=1",
            "https://github.com/wintopic/Teambox/releases/../issues",
        )
        rejected.forEach { url ->
            assertTrue(!AppUpdateChecker.isTrustedReleasePageUrl(url, "v1.2.3"), url)
        }
    }

    @Test
    fun acceptsOnlyMatchingApkAssetsFromTheFixedRepository() {
        val trusted =
            "https://github.com/wintopic/Teambox/releases/download/v1.2.3/Teambox-v1.2.3.apk"
        assertTrue(
            AppUpdateChecker.isTrustedApkDownloadUrl(
                trusted,
                tagName = "v1.2.3",
                assetName = "Teambox-v1.2.3.apk",
            ),
        )

        val rejected = listOf(
            "https://objects.githubusercontent.com/Teambox-v1.2.3.apk",
            "https://github.com/wintopic/Other/releases/download/v1.2.3/Teambox-v1.2.3.apk",
            "https://github.com/wintopic/Teambox/releases/download/v9.9.9/Teambox-v1.2.3.apk",
            "https://github.com/wintopic/Teambox/releases/download/v1.2.3/other.apk",
            "https://github.com/wintopic/Teambox/releases/download/v1.2.3/Teambox-v1.2.3.apk?raw=1",
        )
        rejected.forEach { url ->
            assertTrue(
                !AppUpdateChecker.isTrustedApkDownloadUrl(
                    url,
                    tagName = "v1.2.3",
                    assetName = "Teambox-v1.2.3.apk",
                ),
                url,
            )
        }
    }

    @Test
    fun constructsAcceleratedUrlFromTrustedAssetOnly() {
        val trusted =
            "https://github.com/wintopic/Teambox/releases/download/v1.2.3/Teambox-v1.2.3.apk"
        assertEquals(
            "https://gh-proxy.com/$trusted",
            AppUpdateChecker.acceleratedDownloadUrl(trusted),
        )
        assertFailsWith<IllegalArgumentException> {
            AppUpdateChecker.acceleratedDownloadUrl("https://evil.example/app.apk")
        }
    }
}
