package com.danmukey.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    Android,
    Windows,
    MacOS,
    IOS,
    Linux,
}

@Serializable
enum class Orientation {
    Portrait,
    Landscape,
}

@Serializable
enum class SectionType {
    General,
    Episode,
    LiveSession,
    Custom,
}

@Serializable
enum class ReviewState {
    Pending,
    Approved,
    Rejected,
}

@Serializable
enum class SendMode {
    InsertOnly,
    TapToSend,
    Continuous,
}

@Serializable
enum class SendResult(val storageValue: String) {
    @SerialName("submitted")
    Submitted("submitted"),

    @SerialName("unconfirmed")
    Unconfirmed("unconfirmed"),

    @SerialName("failed")
    Failed("failed"),

    @SerialName("cancelled")
    Cancelled("cancelled"),

    @SerialName("blocked")
    Blocked("blocked"),
    ;

    companion object {
        fun fromStorage(value: String): SendResult = when (value.lowercase()) {
            "submitted", "success" -> Submitted
            "unconfirmed" -> Unconfirmed
            "cancelled", "canceled" -> Cancelled
            "blocked" -> Blocked
            else -> Failed
        }
    }
}

@Serializable
enum class AutomationTaskStatus {
    Running,
    Completed,
    Failed,
    Cancelled,
    Blocked,
}

@Serializable
enum class SelectionPolicy {
    Manual,
    Sequential,
    Random,
    LeastRecentlyUsed,
}

@Serializable
enum class TargetCapabilityLevel {
    L0,
    L1,
    L2,
    L3,
    L4,
}

@Serializable
enum class DiagnosticLevel {
    Info,
    Warning,
    Error,
}

@Serializable
data class KeyboardPack(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    val description: String = "",
    val cover: String? = null,
    val sections: List<KeyboardSection> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class KeyboardSection(
    val id: String,
    val title: String,
    val type: SectionType,
    val episodeNumber: Int? = null,
    val groups: List<PhraseGroup> = emptyList(),
    val order: Int,
)

@Serializable
data class PhraseGroup(
    val id: String,
    val title: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val phrases: List<PhraseItem> = emptyList(),
    val order: Int,
)

@Serializable
data class PhraseItem(
    val id: String,
    val text: String,
    val tags: Set<String> = emptySet(),
    val source: String = "local",
    val reviewState: ReviewState = ReviewState.Approved,
    val enabled: Boolean = true,
    val order: Int,
)

@Serializable
data class SendPreset(
    val mode: SendMode = SendMode.InsertOnly,
    val selectionPolicy: SelectionPolicy = SelectionPolicy.Manual,
    val recentExclusionCount: Int = 10,
    val intervalMs: Long = 5_000,
    val maxItems: Int = 20,
    val clearBeforeInsert: Boolean = false,
    val stopOnVerificationFailure: Boolean = true,
)

@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f)
        require(top in 0f..1f)
        require(right in 0f..1f)
        require(bottom in 0f..1f)
        require(left <= right)
        require(top <= bottom)
    }
}

@Serializable
sealed interface LocatorSpec {
    @Serializable
    @SerialName("accessibility")
    data class Accessibility(
        val role: String? = null,
        val textContains: String? = null,
        val resourceId: String? = null,
        val clickable: Boolean? = null,
        val editable: Boolean? = null,
    ) : LocatorSpec

    @Serializable
    @SerialName("ocr_text")
    data class OcrText(
        val texts: List<String>,
        val region: NormalizedRect? = null,
    ) : LocatorSpec

    @Serializable
    @SerialName("local_template")
    data class LocalTemplate(
        val templateId: String,
        val region: NormalizedRect? = null,
        val threshold: Float,
    ) : LocatorSpec

    @Serializable
    @SerialName("calibration_point")
    data class CalibrationPoint(
        val x: Float,
        val y: Float,
    ) : LocatorSpec {
        init {
            require(x in 0f..1f)
            require(y in 0f..1f)
        }
    }
}

@Serializable
data class TargetProfile(
    val id: String,
    val displayName: String = id,
    val platform: Platform,
    val appIdentifiers: Set<String>,
    val orientations: Set<Orientation>,
    val capabilityLevel: TargetCapabilityLevel = TargetCapabilityLevel.L0,
    val minAppVersionCode: Long? = null,
    val maxAppVersionCode: Long? = null,
    val minSystemApi: Int? = null,
    val maxSystemApi: Int? = null,
    val composerEntryLocators: List<LocatorSpec> = emptyList(),
    val inputLocators: List<LocatorSpec>,
    val submitLocators: List<LocatorSpec>,
    val episodeTitleLocators: List<LocatorSpec> = emptyList(),
    val playbackTimeLocators: List<LocatorSpec> = emptyList(),
    val maxTextLength: Int? = null,
    val ignoredVisualRegions: List<NormalizedRect> = emptyList(),
    val profileVersion: Int,
)

@Serializable
data class PhraseUsage(
    val phraseId: String,
    val targetId: String,
    val useCount: Long = 0,
    val lastUsedAt: Long? = null,
)

@Serializable
data class EpisodeMapping(
    val id: String,
    val targetId: String,
    val normalizedTitle: String,
    val sectionId: String,
    val confidence: Double,
    val updatedAt: Long,
)

@Serializable
data class ContentFollowState(
    val targetId: String,
    val appIdentifier: String,
    val packId: String,
    val sectionId: String,
    val groupId: String? = null,
    val playbackPositionMs: Long? = null,
    val confidence: Double,
    val observedAt: Long,
)

@Serializable
data class DiagnosticEvent(
    val id: String,
    val level: DiagnosticLevel,
    val eventCode: String,
    val targetId: String? = null,
    val taskId: String? = null,
    val details: Map<String, String> = emptyMap(),
    val createdAt: Long,
)

@Serializable
data class SendRecord(
    val id: String,
    val taskId: String? = null,
    val phraseId: String,
    val packId: String,
    val targetId: String,
    val finalText: String,
    val mode: SendMode,
    val locatorSource: String? = null,
    val confidence: Double? = null,
    val result: SendResult,
    val errorCode: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
data class AutomationTaskRecord(
    val id: String,
    val packId: String,
    val mode: SendMode,
    val expectedPackage: String,
    val targetId: String,
    val status: AutomationTaskStatus,
    val plannedCount: Int,
    val completedCount: Int,
    val composerReopenCount: Int,
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long? = null,
)
