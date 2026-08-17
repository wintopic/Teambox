package com.danmukey.app

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Identifies the endpoint that supplied a successfully validated release. */
enum class AppUpdateSource(val displayName: String) {
    GITHUB("GitHub 官方"),
    CHINA_MIRROR("国内加速"),
}

/** A validated APK asset belonging to wintopic/Teambox on GitHub Releases. */
data class AppUpdateApk(
    val name: String,
    val downloadUrl: String,
    val acceleratedDownloadUrl: String,
    val sizeBytes: Long?,
)

/** A safe, user-displayable failure for one update-check attempt. */
data class AppUpdateError(
    val source: AppUpdateSource?,
    val message: String,
)

sealed interface AppUpdateResult {
    val currentVersion: String

    data class UpdateAvailable(
        override val currentVersion: String,
        val latestVersion: String,
        val releasePageUrl: String,
        val apk: AppUpdateApk,
        val source: AppUpdateSource,
    ) : AppUpdateResult

    data class UpToDate(
        override val currentVersion: String,
        val latestVersion: String,
        val releasePageUrl: String,
        val source: AppUpdateSource,
    ) : AppUpdateResult

    data class Failure(
        override val currentVersion: String,
        val errors: List<AppUpdateError>,
    ) : AppUpdateResult
}

/**
 * Checks GitHub Releases for Teambox updates.
 *
 * This method performs blocking network I/O and must be called from a background thread. It only
 * reads release metadata: it never downloads, installs, or opens an APK. Every URL returned to the
 * caller is validated as an HTTPS URL under github.com/wintopic/Teambox/releases.
 */
object AppUpdateChecker {
    private const val OFFICIAL_RELEASE_API =
        "https://api.github.com/repos/wintopic/Teambox/releases/latest"
    private const val CHINA_MIRROR_RELEASE_API =
        "https://gh-proxy.com/https://api.github.com/repos/wintopic/Teambox/releases/latest"
    private const val CHINA_ACCELERATOR_PREFIX = "https://gh-proxy.com/"
    private const val CONNECT_TIMEOUT_MILLIS = 2_500
    private const val READ_TIMEOUT_MILLIS = 3_000
    private const val MAX_RESPONSE_BYTES = 768 * 1024

    private val endpoints = listOf(
        UpdateEndpoint(AppUpdateSource.GITHUB, OFFICIAL_RELEASE_API),
        UpdateEndpoint(AppUpdateSource.CHINA_MIRROR, CHINA_MIRROR_RELEASE_API),
    )

    fun check(currentVersion: String): AppUpdateResult {
        val current = SemanticVersion.parse(currentVersion)
            ?: return AppUpdateResult.Failure(
                currentVersion = currentVersion,
                errors = listOf(
                    AppUpdateError(
                        source = null,
                        message = "当前版本号不是有效的语义化版本：${currentVersion.safeForDisplay()}",
                    ),
                ),
            )

        val errors = mutableListOf<AppUpdateError>()
        for (endpoint in endpoints) {
            val release = try {
                parseRelease(fetchReleaseJson(endpoint))
            } catch (failure: Exception) {
                errors += AppUpdateError(
                    source = endpoint.source,
                    message = failure.safeMessage(),
                )
                continue
            }

            val latest = SemanticVersion.parse(release.tagName)
            if (latest == null) {
                errors += AppUpdateError(
                    source = endpoint.source,
                    message = "GitHub Release 的版本标签无效。",
                )
                continue
            }

            return if (latest > current) {
                AppUpdateResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = release.tagName,
                    releasePageUrl = release.releasePageUrl,
                    apk = release.apk,
                    source = endpoint.source,
                )
            } else {
                AppUpdateResult.UpToDate(
                    currentVersion = currentVersion,
                    latestVersion = release.tagName,
                    releasePageUrl = release.releasePageUrl,
                    source = endpoint.source,
                )
            }
        }

        return AppUpdateResult.Failure(
            currentVersion = currentVersion,
            errors = errors.ifEmpty {
                listOf(AppUpdateError(source = null, message = "更新检查失败。"))
            },
        )
    }

    private fun fetchReleaseJson(endpoint: UpdateEndpoint): String {
        val connection = URL(endpoint.url).openConnection()
        require(connection is HttpsURLConnection) { "更新检查仅允许 HTTPS 连接。" }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Teambox-Android-UpdateChecker")
            connection.setRequestProperty("Connection", "close")

            val status = connection.responseCode
            if (status !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
                throw UpdateCheckException("HTTP $status")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw UpdateCheckException("更新信息响应过大。")
            }
            connection.inputStream.use(::readLimitedUtf8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw UpdateCheckException("更新信息响应过大。")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun parseRelease(json: String): ValidatedRelease {
        val root = try {
            JSONObject(json)
        } catch (failure: JSONException) {
            throw UpdateCheckException("更新信息不是有效的 JSON。", failure)
        }
        if (root.optBoolean("draft", true)) {
            throw UpdateCheckException("更新信息指向草稿 Release。")
        }
        if (root.optBoolean("prerelease", true)) {
            throw UpdateCheckException("更新信息指向预发布 Release。")
        }

        val tagName = root.optString("tag_name").trim()
        if (SemanticVersion.parse(tagName) == null) {
            throw UpdateCheckException("GitHub Release 的版本标签无效。")
        }
        val releasePageUrl = root.optString("html_url").trim()
        if (!isTrustedReleasePageUrl(releasePageUrl, tagName)) {
            throw UpdateCheckException("GitHub Release 页地址未通过安全校验。")
        }

        val assets = root.optJSONArray("assets")
            ?: throw UpdateCheckException("GitHub Release 未包含附件列表。")
        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                if (!name.endsWith(".apk", ignoreCase = true) || name.isUnsafeApkName()) continue
                if (asset.optString("state", "uploaded") != "uploaded") continue
                val downloadUrl = asset.optString("browser_download_url").trim()
                if (!isTrustedApkDownloadUrl(downloadUrl, tagName, name)) continue
                val sizeBytes = if (asset.has("size") && !asset.isNull("size")) {
                    asset.optLong("size").takeIf { it >= 0L }
                } else {
                    null
                }
                add(
                    ApkCandidate(
                        apk = AppUpdateApk(
                            name = name,
                            downloadUrl = downloadUrl,
                            acceleratedDownloadUrl = acceleratedDownloadUrl(downloadUrl),
                            sizeBytes = sizeBytes,
                        ),
                        score = apkPreferenceScore(name, asset.optString("content_type")),
                    ),
                )
            }
        }
        val selectedApk = candidates
            .sortedWith(compareByDescending<ApkCandidate> { it.score }.thenBy { it.apk.name.lowercase() })
            .firstOrNull()
            ?.apk
            ?: throw UpdateCheckException("GitHub Release 中没有可信的 APK 附件。")

        return ValidatedRelease(
            tagName = tagName,
            releasePageUrl = releasePageUrl,
            apk = selectedApk,
        )
    }

    private fun String.isUnsafeApkName(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return listOf("debug", "unsigned", "unaligned", "androidtest", "testonly")
            .any(lower::contains)
    }

    private fun apkPreferenceScore(name: String, contentType: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        var score = 0
        if ("teambox" in lower) score += 20
        if ("universal" in lower || "-all" in lower || "_all" in lower) score += 10
        if (contentType.equals("application/vnd.android.package-archive", ignoreCase = true)) score += 2
        return score
    }

    internal fun compareSemanticVersions(left: String, right: String): Int? {
        val leftVersion = SemanticVersion.parse(left) ?: return null
        val rightVersion = SemanticVersion.parse(right) ?: return null
        return leftVersion.compareTo(rightVersion)
    }

    internal fun isTrustedReleasePageUrl(value: String, tagName: String): Boolean {
        val uri = value.toTrustedGithubUri() ?: return false
        return uri.path == "/wintopic/Teambox/releases/tag/$tagName"
    }

    internal fun isTrustedApkDownloadUrl(value: String, tagName: String, assetName: String): Boolean {
        val uri = value.toTrustedGithubUri() ?: return false
        val expectedPrefix = "/wintopic/Teambox/releases/download/$tagName/"
        if (!uri.path.startsWith(expectedPrefix)) return false
        val fileName = uri.path.removePrefix(expectedPrefix)
        return fileName.isNotEmpty() &&
            '/' !in fileName &&
            fileName.equals(assetName, ignoreCase = false) &&
            fileName.endsWith(".apk", ignoreCase = true)
    }

    internal fun acceleratedDownloadUrl(trustedDownloadUrl: String): String {
        require(
            isTrustedApkDownloadUrl(
                value = trustedDownloadUrl,
                tagName = trustedDownloadUrl.releaseTagFromApkUrl().orEmpty(),
                assetName = trustedDownloadUrl.assetNameFromApkUrl().orEmpty(),
            ),
        ) { "APK 下载地址未通过安全校验。" }
        return CHINA_ACCELERATOR_PREFIX + trustedDownloadUrl
    }

    private fun String.releaseTagFromApkUrl(): String? {
        val path = toTrustedGithubUri()?.path ?: return null
        val prefix = "/wintopic/Teambox/releases/download/"
        if (!path.startsWith(prefix)) return null
        return path.removePrefix(prefix).substringBefore('/').takeIf(String::isNotEmpty)
    }

    private fun String.assetNameFromApkUrl(): String? =
        toTrustedGithubUri()?.path?.substringAfterLast('/')?.takeIf(String::isNotEmpty)

    private fun String.toTrustedGithubUri(): URI? {
        val uri = runCatching { URI(this) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals("github.com", ignoreCase = true)) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (uri.rawPath.isNullOrEmpty() || '\\' in uri.rawPath) return null
        if (uri.normalize().rawPath != uri.rawPath) return null
        if (uri.path.split('/').any { it == "." || it == ".." }) return null
        return uri
    }

    private fun Exception.safeMessage(): String {
        val raw = when (this) {
            is UpdateCheckException -> message
            else -> message?.takeIf(String::isNotBlank)?.let { "网络请求失败：$it" }
        } ?: "更新检查失败：${javaClass.simpleName}"
        return raw.safeForDisplay()
    }

    private fun String.safeForDisplay(): String =
        replace(Regex("\\s+"), " ").trim().take(180)

    private data class UpdateEndpoint(
        val source: AppUpdateSource,
        val url: String,
    )

    private data class ValidatedRelease(
        val tagName: String,
        val releasePageUrl: String,
        val apk: AppUpdateApk,
    )

    private data class ApkCandidate(
        val apk: AppUpdateApk,
        val score: Int,
    )

    private class UpdateCheckException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private data class SemanticVersion(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
        val preRelease: List<PreReleaseIdentifier>,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
            if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
            if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
            for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
                preRelease[index].compareTo(other.preRelease[index])
                    .takeIf { it != 0 }
                    ?.let { return it }
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        companion object {
            private val pattern = Regex(
                "^[vV]?" +
                    "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
            )

            fun parse(value: String): SemanticVersion? {
                val match = pattern.matchEntire(value.trim()) ?: return null
                val preRelease = match.groupValues[4]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                    .orEmpty()
                if (preRelease.any { it.length > 1 && it[0] == '0' && it.all(Char::isDigit) }) {
                    return null
                }
                return SemanticVersion(
                    major = match.groupValues[1].toBigInteger(),
                    minor = match.groupValues[2].toBigInteger(),
                    patch = match.groupValues[3].toBigInteger(),
                    preRelease = preRelease.map(::PreReleaseIdentifier),
                )
            }
        }
    }

    private data class PreReleaseIdentifier(val value: String) : Comparable<PreReleaseIdentifier> {
        private val number = value.takeIf { it.all(Char::isDigit) }?.toBigInteger()

        override fun compareTo(other: PreReleaseIdentifier): Int = when {
            number != null && other.number != null -> number.compareTo(other.number)
            number != null -> -1
            other.number != null -> 1
            else -> value.compareTo(other.value)
        }
    }
}
