package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack
import kotlinx.serialization.Serializable

@Serializable
data class DKeyManifest(
    val format: String = "danmukey-archive",
    val formatVersion: Int = 1,
    val packId: String,
    val packVersion: Int,
    val name: String,
    val author: String,
    val exportedAt: Long,
    val contentEntry: String = "content.json",
    val contentSha256: String,
)

expect object DKeyArchive {
    fun encode(pack: KeyboardPack, exportedAt: Long): ByteArray
    fun decode(bytes: ByteArray): KeyboardPack
}
