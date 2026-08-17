package com.danmukey.shared.data

import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.TargetCapabilityLevel
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.visual.LocalTemplatePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TargetRuleAction {
    ObserveAccessibility,
    OpenComposer,
    FocusInput,
    SetText,
    ClickSubmit,
    CaptureScreenRegion,
    ReadEpisodeTitle,
    ReadPlaybackTime,
}

@Serializable
enum class TargetRuleSignatureState {
    Verified,
    Unsigned,
}

@Serializable
enum class TargetRuleState {
    Observation,
    ObservationOnly,
    Active,
    Superseded,
    Disabled,
    Expired,
}

@Serializable
enum class TargetRuleSource {
    LocalImport,
    Remote,
}

@Serializable
data class DTargetPayload(
    val ruleId: String,
    val revision: Int,
    val issuedAt: Long,
    val expiresAt: Long? = null,
    val profile: TargetProfile,
    val allowedActions: List<TargetRuleAction>,
    val disabled: Boolean = false,
)

@Serializable
data class DTargetEnvelope(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val signatureAlgorithm: String? = null,
    val keyId: String? = null,
    val signatureHex: String? = null,
    val payload: DTargetPayload,
) {
    companion object {
        const val FORMAT = "danmukey-target"
        const val FORMAT_VERSION = 1
        const val SIGNATURE_ALGORITHM = "ECDSA_P256_SHA256"
    }
}

data class ImportedDTarget(
    val envelope: DTargetEnvelope,
    val signatureState: TargetRuleSignatureState,
    val initialState: TargetRuleState,
)

data class TargetRuleRevision(
    val ruleId: String,
    val revision: Int,
    val source: TargetRuleSource,
    val signatureState: TargetRuleSignatureState,
    val state: TargetRuleState,
    val envelope: DTargetEnvelope,
    val importedAt: Long,
    val activatedAt: Long? = null,
)

fun interface TargetRuleSignatureVerifier {
    fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean
}

fun interface TargetRuleSignatureSigner {
    fun sign(message: ByteArray): ByteArray
}

object RejectAllTargetRuleSignatures : TargetRuleSignatureVerifier {
    override fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean = false
}

object DevelopmentTargetRuleTrust {
    const val KEY_ID = "danmukey-development-2026-08"

    val publicKeys: Map<String, ByteArray> by lazy {
        mapOf(KEY_ID to PUBLIC_KEY_X509_HEX.hexToByteArray())
    }

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private const val PUBLIC_KEY_X509_HEX =
        "3059301306072a8648ce3d020106082a8648ce3d030107034200048362da986759c46f3de4d681da2c9b9d" +
            "a06e376f4da7c809201925e8c073041d093c43b50dff081c5ddec48bac64c314357abdf0da159b046ea223bcab044927"
}

object DTargetCodec {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
        classDiscriminator = "kind"
    }

    fun encodeUnsigned(payload: DTargetPayload): String {
        val normalized = DTargetPolicy.normalizeAndValidate(payload)
        return json.encodeToString(DTargetEnvelope(payload = normalized))
    }

    fun encodeSigned(
        payload: DTargetPayload,
        keyId: String,
        signer: TargetRuleSignatureSigner,
    ): String {
        require(KEY_ID.matches(keyId)) { "签名 keyId 格式无效" }
        val normalized = DTargetPolicy.normalizeAndValidate(payload)
        val signature = signer.sign(signatureMessage(normalized))
        require(signature.isNotEmpty()) { "签名结果不能为空" }
        return json.encodeToString(
            DTargetEnvelope(
                signatureAlgorithm = DTargetEnvelope.SIGNATURE_ALGORITHM,
                keyId = keyId,
                signatureHex = signature.toHex(),
                payload = normalized,
            ),
        )
    }

    fun decodeAndVerify(
        text: String,
        verifier: TargetRuleSignatureVerifier,
        now: Long,
    ): ImportedDTarget {
        val envelope = decodeStored(text)
        val payload = envelope.payload
        require(payload.issuedAt <= now + MAX_CLOCK_SKEW_MS) { "目标规则签发时间晚于当前设备时间" }
        require(payload.expiresAt == null || payload.expiresAt > now) { "目标规则已经过期" }

        val signatureFields = listOf(
            envelope.signatureAlgorithm,
            envelope.keyId,
            envelope.signatureHex,
        )
        val signedFieldCount = signatureFields.count { it != null }
        if (signedFieldCount == 0) {
            require(!payload.disabled) { "未签名规则不能停用现有目标" }
            return ImportedDTarget(
                envelope = envelope,
                signatureState = TargetRuleSignatureState.Unsigned,
                initialState = TargetRuleState.ObservationOnly,
            )
        }
        require(signedFieldCount == signatureFields.size) { "目标规则签名字段不完整" }
        require(envelope.signatureAlgorithm == DTargetEnvelope.SIGNATURE_ALGORITHM) {
            "不支持的目标规则签名算法"
        }
        val keyId = requireNotNull(envelope.keyId)
        require(KEY_ID.matches(keyId)) { "签名 keyId 格式无效" }
        val signature = requireNotNull(envelope.signatureHex).decodeHex()
        require(verifier.verify(keyId, signatureMessage(payload), signature)) { "目标规则签名无效" }
        return ImportedDTarget(
            envelope = envelope,
            signatureState = TargetRuleSignatureState.Verified,
            initialState = if (payload.disabled) TargetRuleState.Disabled else TargetRuleState.Observation,
        )
    }

    fun decodeStored(text: String): DTargetEnvelope {
        val decoded = json.decodeFromString<DTargetEnvelope>(text.trim())
        require(decoded.format == DTargetEnvelope.FORMAT) { "不支持的目标规则格式" }
        require(decoded.formatVersion == DTargetEnvelope.FORMAT_VERSION) {
            "不支持的目标规则版本 ${decoded.formatVersion}"
        }
        val normalized = DTargetPolicy.normalizeAndValidate(decoded.payload)
        return decoded.copy(payload = normalized)
    }

    fun encodeStored(envelope: DTargetEnvelope): String = json.encodeToString(
        envelope.copy(payload = DTargetPolicy.normalizeAndValidate(envelope.payload)),
    )

    fun signatureMessage(payload: DTargetPayload): ByteArray = json
        .encodeToString(DTargetPolicy.normalizeAndValidate(payload))
        .encodeToByteArray()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

    private fun String.decodeHex(): ByteArray {
        require(length in 2..MAX_SIGNATURE_HEX_LENGTH && length % 2 == 0) { "签名编码长度无效" }
        require(all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "签名必须使用十六进制编码" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private val KEY_ID = Regex("[A-Za-z0-9._-]{1,80}")
    private const val MAX_SIGNATURE_HEX_LENGTH = 1_024
    private const val MAX_CLOCK_SKEW_MS = 24L * 60L * 60L * 1_000L
}

object DTargetPolicy {
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,120}")
    private val APP_IDENTIFIER = Regex("[A-Za-z0-9._-]{3,200}")
    private val RESOURCE_ID = Regex("[A-Za-z0-9._-]+:id/[A-Za-z0-9_]+")
    private val SUPPORTED_CAPABILITIES = setOf(
        TargetCapabilityLevel.L0,
        TargetCapabilityLevel.L1,
        TargetCapabilityLevel.L2,
        TargetCapabilityLevel.L3,
    )

    fun normalizeAndValidate(payload: DTargetPayload): DTargetPayload {
        require(SAFE_ID.matches(payload.ruleId)) { "目标规则 ID 格式无效" }
        require(payload.revision > 0) { "目标规则版本必须大于 0" }
        require(payload.issuedAt > 0) { "目标规则签发时间无效" }
        require(payload.expiresAt == null || payload.expiresAt > payload.issuedAt) {
            "目标规则过期时间必须晚于签发时间"
        }
        require(payload.allowedActions.isNotEmpty()) { "目标规则必须声明允许动作" }
        require(payload.allowedActions.size == payload.allowedActions.toSet().size) { "目标规则动作不能重复" }
        require(TargetRuleAction.ObserveAccessibility in payload.allowedActions) {
            "目标规则必须允许只观察验证"
        }

        val profile = payload.profile
        require(profile.id == payload.ruleId) { "规则 ID 与目标配置 ID 不一致" }
        require(profile.profileVersion == payload.revision) { "规则版本与目标配置版本不一致" }
        require(profile.displayName.isNotBlank() && profile.displayName.length <= 80) { "目标名称无效" }
        require(profile.capabilityLevel in SUPPORTED_CAPABILITIES) { "目标能力等级超出 v1 范围" }
        require(profile.appIdentifiers.isNotEmpty() && profile.appIdentifiers.size <= 8) { "目标应用标识数量无效" }
        require(profile.appIdentifiers.all(APP_IDENTIFIER::matches)) { "目标应用标识格式无效" }
        require(profile.orientations.isNotEmpty()) { "目标规则必须声明屏幕方向" }
        require(profile.minAppVersionCode == null || profile.minAppVersionCode >= 0) { "最低应用版本无效" }
        require(profile.maxAppVersionCode == null || profile.maxAppVersionCode >= 0) { "最高应用版本无效" }
        require(
            profile.minAppVersionCode == null ||
                profile.maxAppVersionCode == null ||
                profile.minAppVersionCode <= profile.maxAppVersionCode,
        ) { "应用版本范围无效" }
        require(profile.minSystemApi == null || profile.minSystemApi >= 21) { "最低系统 API 无效" }
        require(profile.maxSystemApi == null || profile.maxSystemApi >= 21) { "最高系统 API 无效" }
        require(
            profile.minSystemApi == null ||
                profile.maxSystemApi == null ||
                profile.minSystemApi <= profile.maxSystemApi,
        ) { "系统 API 范围无效" }
        require(profile.maxTextLength == null || profile.maxTextLength in 1..500) { "目标字数限制无效" }
        require(profile.ignoredVisualRegions.size <= MAX_IGNORED_REGIONS) { "视觉遮盖区域过多" }

        val locatorGroups = listOf(
            profile.composerEntryLocators,
            profile.inputLocators,
            profile.submitLocators,
            profile.episodeTitleLocators,
            profile.playbackTimeLocators,
        )
        require(locatorGroups.all { it.size <= MAX_LOCATORS_PER_ROLE }) { "单个控件的定位器过多" }
        locatorGroups.flatten().forEach(::validateLocator)

        val requiredActions = buildSet {
            add(TargetRuleAction.ObserveAccessibility)
            if (profile.composerEntryLocators.isNotEmpty()) add(TargetRuleAction.OpenComposer)
            if (profile.inputLocators.isNotEmpty()) {
                add(TargetRuleAction.FocusInput)
                add(TargetRuleAction.SetText)
            }
            if (profile.submitLocators.isNotEmpty()) add(TargetRuleAction.ClickSubmit)
            if (profile.episodeTitleLocators.isNotEmpty()) add(TargetRuleAction.ReadEpisodeTitle)
            if (profile.playbackTimeLocators.isNotEmpty()) add(TargetRuleAction.ReadPlaybackTime)
            if (locatorGroups.flatten().any { it is LocatorSpec.OcrText || it is LocatorSpec.LocalTemplate }) {
                add(TargetRuleAction.CaptureScreenRegion)
            }
        }
        require(payload.allowedActions.toSet().containsAll(requiredActions)) {
            "目标规则缺少定位器所需的固定动作声明"
        }

        val normalizedProfile = profile.copy(
            appIdentifiers = profile.appIdentifiers.sorted().toCollection(linkedSetOf()),
            orientations = profile.orientations.sortedBy { it.name }.toCollection(linkedSetOf()),
        )
        return payload.copy(
            profile = normalizedProfile,
            allowedActions = payload.allowedActions.sortedBy { it.name },
        )
    }

    private fun validateLocator(locator: LocatorSpec) {
        when (locator) {
            is LocatorSpec.Accessibility -> {
                require(
                    locator.role != null ||
                        locator.textContains != null ||
                        locator.resourceId != null ||
                        locator.clickable != null ||
                        locator.editable != null,
                ) { "无障碍定位器不能匹配任意节点" }
                require(locator.role == null || locator.role.isNotBlank() && locator.role.length <= 80) {
                    "控件角色格式无效"
                }
                require(locator.textContains == null || locator.textContains.isNotBlank() && locator.textContains.length <= 48) {
                    "控件文案条件无效"
                }
                require(locator.resourceId == null || RESOURCE_ID.matches(locator.resourceId)) {
                    "控件资源 ID 格式无效"
                }
            }

            is LocatorSpec.OcrText -> {
                require(locator.texts.isNotEmpty() && locator.texts.size <= 8) { "OCR 文案数量无效" }
                require(locator.texts.all { it.isNotBlank() && it.length <= 48 }) { "OCR 文案长度无效" }
            }

            is LocatorSpec.LocalTemplate -> {
                require(LocalTemplatePolicy.isValidId(locator.templateId)) { "模板 ID 格式无效" }
                require(locator.threshold in 0.8f..1f) { "模板匹配阈值必须在 0.8 至 1.0 之间" }
            }

            is LocatorSpec.CalibrationPoint -> error(".dtarget v1 不允许携带固定点击坐标")
        }
    }

    private const val MAX_LOCATORS_PER_ROLE = 12
    private const val MAX_IGNORED_REGIONS = 8
}
