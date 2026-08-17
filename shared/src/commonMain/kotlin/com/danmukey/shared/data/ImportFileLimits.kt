package com.danmukey.shared.data

enum class ImportPayloadKind {
    ContentText,
    ContentArchive,
    TargetRule,
}

object ImportFileLimits {
    const val MAX_CONTENT_TEXT_BYTES = 8 * 1024 * 1024
    const val MAX_CONTENT_ARCHIVE_BYTES = 20 * 1024 * 1024
    const val MAX_TARGET_RULE_BYTES = 1024 * 1024
    const val MAX_IMPORTED_ROWS = 50_000
    const val MAX_PACK_SECTIONS = 500
    const val MAX_PACK_GROUPS = 5_000
    const val MAX_PACK_PHRASES = 50_000
    const val MAX_PHRASE_CHARACTERS = 10_000

    fun kindForExtension(extension: String): ImportPayloadKind = when (
        extension.trim().removePrefix(".").lowercase()
    ) {
        "dkey" -> ImportPayloadKind.ContentArchive
        "dtarget" -> ImportPayloadKind.TargetRule
        else -> ImportPayloadKind.ContentText
    }

    fun maximumBytes(kind: ImportPayloadKind): Int = when (kind) {
        ImportPayloadKind.ContentText -> MAX_CONTENT_TEXT_BYTES
        ImportPayloadKind.ContentArchive -> MAX_CONTENT_ARCHIVE_BYTES
        ImportPayloadKind.TargetRule -> MAX_TARGET_RULE_BYTES
    }

    fun requireWithin(kind: ImportPayloadKind, sizeBytes: Long) {
        val maximum = maximumBytes(kind)
        require(sizeBytes in 0L..maximum.toLong()) {
            "导入文件超过 ${maximum / 1024 / 1024} MB 限制"
        }
    }
}
