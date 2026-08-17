package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack

object ContentPackImport {
    fun prepare(
        decoded: KeyboardPack,
        existing: List<KeyboardPack>,
        timestamp: Long,
    ): KeyboardPack {
        decoded.requireWithinImportLimits()
        val imported = if (decoded.hasIdCollisionWith(existing)) {
            decoded.withFreshIds(timestamp)
        } else {
            decoded
        }
        return imported.copy(updatedAt = timestamp)
    }

    private fun KeyboardPack.requireWithinImportLimits() {
        require(version > 0) { "内容包版本必须大于 0" }
        require(name.isNotBlank()) { "内容包名称不能为空" }
        require(createdAt >= 0L && updatedAt >= 0L) { "内容包时间不能为负数" }
        require(createdAt <= updatedAt) { "内容包创建时间不能晚于更新时间" }
        require(sections.size <= ImportFileLimits.MAX_PACK_SECTIONS) {
            "内容包分区超过 ${ImportFileLimits.MAX_PACK_SECTIONS} 个"
        }
        val ids = mutableSetOf<String>()
        requireUniqueId(id, "内容包", ids)
        var groupCount = 0
        var phraseCount = 0
        sections.forEach { section ->
            requireUniqueId(section.id, "分区", ids)
            require(section.title.isNotBlank()) { "内容包分区标题不能为空" }
            groupCount += section.groups.size
            require(groupCount <= ImportFileLimits.MAX_PACK_GROUPS) {
                "内容包分组超过 ${ImportFileLimits.MAX_PACK_GROUPS} 个"
            }
            section.groups.forEach { group ->
                requireUniqueId(group.id, "分组", ids)
                require(group.title.isNotBlank()) { "内容包分组标题不能为空" }
                require(group.startMs == null || group.startMs >= 0L) { "内容包分组开始时间不能为负数" }
                require(group.endMs == null || group.endMs >= 0L) { "内容包分组结束时间不能为负数" }
                require(group.startMs == null || group.endMs == null || group.startMs <= group.endMs) {
                    "内容包分组开始时间不能晚于结束时间"
                }
                phraseCount += group.phrases.size
                require(phraseCount <= ImportFileLimits.MAX_PACK_PHRASES) {
                    "内容包正文超过 ${ImportFileLimits.MAX_PACK_PHRASES} 条"
                }
                group.phrases.forEach { phrase ->
                    requireUniqueId(phrase.id, "正文", ids)
                    require(phrase.text.isNotBlank()) { "内容包正文不能为空" }
                    require(phrase.text.length <= ImportFileLimits.MAX_PHRASE_CHARACTERS) {
                        "内容包包含超过 ${ImportFileLimits.MAX_PHRASE_CHARACTERS} 字符的单条正文"
                    }
                }
            }
        }
    }

    private fun requireUniqueId(id: String, label: String, ids: MutableSet<String>) {
        require(id.isNotBlank()) { "$label ID 不能为空" }
        require(ids.add(id)) { "内容包包含重复 ID" }
    }

    private fun KeyboardPack.hasIdCollisionWith(existing: List<KeyboardPack>): Boolean {
        val usedIds = buildSet {
            existing.forEach { pack ->
                add(pack.id)
                pack.sections.forEach { section ->
                    add(section.id)
                    section.groups.forEach { group ->
                        add(group.id)
                        group.phrases.forEach { phrase -> add(phrase.id) }
                    }
                }
            }
        }
        if (id in usedIds) return true
        return sections.any { section ->
            section.id in usedIds || section.groups.any { group ->
                group.id in usedIds || group.phrases.any { phrase -> phrase.id in usedIds }
            }
        }
    }

    private fun KeyboardPack.withFreshIds(timestamp: Long): KeyboardPack {
        var offset = 0L
        fun next(prefix: String): String = ContentIds.next(prefix, timestamp + offset++)
        return copy(
            id = next("pack"),
            sections = sections.map { section ->
                section.copy(
                    id = next("section"),
                    groups = section.groups.map { group ->
                        group.copy(
                            id = next("group"),
                            phrases = group.phrases.map { phrase ->
                                phrase.copy(id = next("phrase"))
                            },
                        )
                    },
                )
            },
        )
    }
}
