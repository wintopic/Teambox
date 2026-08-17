package com.danmukey.runtime

import android.content.Context
import com.danmukey.shared.accessibility.AccessibilityFixtureCodec
import com.danmukey.shared.accessibility.AccessibilityTreeFixture
import com.danmukey.shared.data.LocalDataRetentionPolicy
import java.io.File

/** App-private, bounded storage for user-requested redacted accessibility fixtures. */
class AndroidAccessibilityFixtureStore(
    private val cacheDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.cacheDir)

    fun save(fixture: AccessibilityTreeFixture): File {
        val directory = fixtureDirectory().apply {
            check(exists() || mkdirs()) { "无法创建脱敏控件样本目录" }
        }
        val target = File(directory, fixture.fileName())
        val temporary = File.createTempFile(".fixture-", ".tmp", directory)
        try {
            temporary.writeText(AccessibilityFixtureCodec.encode(fixture), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = false)
                check(temporary.delete()) { "无法删除脱敏控件样本临时文件" }
            }
        } finally {
            temporary.delete()
        }
        target.setLastModified(fixture.capturedAt)
        prune()
        return target
    }

    fun prune(): Int {
        val directory = fixtureDirectory()
        if (!directory.isDirectory) return 0
        var deletedCount = 0
        val cutoff = LocalDataRetentionPolicy.diagnosticCutoff(now())
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .filter { it.name.startsWith(".fixture-") || it.lastModified() < cutoff }
            .forEach { file ->
                if (file.delete()) deletedCount += 1
            }

        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(MAX_RETAINED_FIXTURES)
            .forEach { file ->
                if (file.delete()) deletedCount += 1
            }
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
        return deletedCount
    }

    fun clear(): Int {
        val directory = fixtureDirectory()
        if (!directory.exists()) return 0
        val fileCount = directory.walkTopDown().count(File::isFile)
        check(directory.deleteRecursively() && !directory.exists()) {
            "无法彻底删除脱敏控件样本"
        }
        return fileCount
    }

    private fun fixtureDirectory(): File = File(cacheDirectory, FIXTURE_DIRECTORY)

    private fun AccessibilityTreeFixture.fileName(): String {
        val safePackage = packageName.map { character ->
            if (character.isLetterOrDigit() || character in ".-_") character else '_'
        }.joinToString("").ifBlank { "unknown" }
        return "$safePackage-${orientation.name.lowercase()}-$capturedAt.json"
    }

    companion object {
        private const val FIXTURE_DIRECTORY = "accessibility-fixtures"
        private const val MAX_RETAINED_FIXTURES = 20
    }
}
