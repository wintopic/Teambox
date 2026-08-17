package com.danmukey.shared.data

import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.DiagnosticEvent
import com.danmukey.shared.model.SendRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

object ContentJson {
    val instance: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }
}

@Serializable
data class KeyboardPackageEnvelope(
    val format: String = "danmukey-content",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val pack: KeyboardPack,
)

object ContentCodec {
    private val json = ContentJson.instance

    fun encode(pack: KeyboardPack, exportedAt: Long): String = json.encodeToString(
        KeyboardPackageEnvelope(exportedAt = exportedAt, pack = pack),
    )

    fun decode(text: String): KeyboardPack {
        val trimmed = text.trim()
        val root = json.parseToJsonElement(trimmed)
        return if (root is JsonObject && "format" in root) {
            val envelope = json.decodeFromJsonElement<KeyboardPackageEnvelope>(root)
            require(envelope.format == "danmukey-content") { "不支持的内容包格式" }
            require(envelope.formatVersion == 1) { "不支持的内容包版本 ${envelope.formatVersion}" }
            envelope.pack
        } else {
            json.decodeFromJsonElement<KeyboardPack>(root)
        }
    }
}

@Serializable
data class DiagnosticExportEnvelope(
    val format: String = "danmukey-diagnostics",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val events: List<DiagnosticEvent>,
)

object DiagnosticCodec {
    fun encode(events: List<DiagnosticEvent>, exportedAt: Long): String = ContentJson.instance.encodeToString(
        DiagnosticExportEnvelope(exportedAt = exportedAt, events = events),
    )
}

@Serializable
data class SendRecordExportEnvelope(
    val format: String = "danmukey-send-records",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val records: List<SendRecord>,
)

object SendRecordCodec {
    fun encode(records: List<SendRecord>, exportedAt: Long): String = ContentJson.instance.encodeToString(
        SendRecordExportEnvelope(exportedAt = exportedAt, records = records),
    )
}
