package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual object DKeyArchive {
    actual fun encode(pack: KeyboardPack, exportedAt: Long): ByteArray = encodeJvm(pack, exportedAt)
    actual fun decode(bytes: ByteArray): KeyboardPack = decodeJvm(bytes)
}

private fun encodeJvm(pack: KeyboardPack, exportedAt: Long): ByteArray {
    val content = ContentCodec.encode(pack, exportedAt).encodeToByteArray()
    val manifest = DKeyManifest(
        packId = pack.id,
        packVersion = pack.version,
        name = pack.name,
        author = pack.author,
        exportedAt = exportedAt,
        contentSha256 = content.sha256(),
    )
    return ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(ContentJson.instance.encodeToString(manifest).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.contentEntry))
            zip.write(content)
            zip.closeEntry()
        }
        output.toByteArray()
    }
}

private fun decodeJvm(bytes: ByteArray): KeyboardPack {
    ImportFileLimits.requireWithin(ImportPayloadKind.ContentArchive, bytes.size.toLong())
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory) { "内容包不能包含目录" }
            require(entry.name in ALLOWED_ENTRIES) { "内容包包含未知条目 ${entry.name}" }
            require(entry.name !in entries) { "内容包包含重复条目 ${entry.name}" }
            entries[entry.name] = zip.readLimitedBytes(MAX_ENTRY_BYTES)
            zip.closeEntry()
        }
    }
    require(entries.keys == ALLOWED_ENTRIES) { "内容包条目不完整" }
    val manifestBytes = entries["manifest.json"] ?: error("内容包缺少 manifest.json")
    val manifest = ContentJson.instance.decodeFromString<DKeyManifest>(manifestBytes.decodeToString())
    require(manifest.format == "danmukey-archive") { "不支持的内容包格式" }
    require(manifest.formatVersion == 1) { "不支持的内容包版本 ${manifest.formatVersion}" }
    require(manifest.contentEntry == "content.json") { "不支持的内容条目 ${manifest.contentEntry}" }
    val content = entries[manifest.contentEntry] ?: error("内容包缺少 ${manifest.contentEntry}")
    require(content.sha256() == manifest.contentSha256) { "内容包摘要校验失败" }
    val pack = ContentCodec.decode(content.decodeToString())
    require(pack.id == manifest.packId) { "内容包 ID 不一致" }
    require(pack.version == manifest.packVersion) { "内容包版本信息不一致" }
    require(pack.name == manifest.name) { "内容包名称信息不一致" }
    require(pack.author == manifest.author) { "内容包作者信息不一致" }
    return pack
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun ZipInputStream.readLimitedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count <= 0) break
        total += count
        require(total <= limit) { "内容包条目过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private val ALLOWED_ENTRIES = setOf("manifest.json", "content.json")
private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
