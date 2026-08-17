package com.danmukey.shared.accessibility

import com.danmukey.shared.data.ContentJson
import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.model.Orientation
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class AccessibilityBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right)
        require(top <= bottom)
    }
}

data class AccessibilityNodeFacts(
    val className: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val text: String? = null,
    val contentDescription: String? = null,
)

@Serializable
data class AccessibilityNodeFixture(
    val depth: Int,
    val className: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focusable: Boolean = false,
    val bounds: AccessibilityBounds,
    val safeText: String? = null,
    val safeContentDescription: String? = null,
) {
    init {
        require(depth >= 0)
        require(!editable || (safeText == null && safeContentDescription == null)) {
            "可编辑节点不能保存文字或内容描述"
        }
    }

    fun facts(): AccessibilityNodeFacts = AccessibilityNodeFacts(
        className = className,
        resourceId = resourceId,
        clickable = clickable,
        editable = editable,
        text = safeText,
        contentDescription = safeContentDescription,
    )
}

@Serializable
data class AccessibilityTreeFixture(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val capturedAt: Long,
    val packageName: String,
    val appVersionCode: Long? = null,
    val systemApi: Int,
    val orientation: Orientation,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<AccessibilityNodeFixture>,
) {
    init {
        require(packageName.isNotBlank())
        require(screenWidth > 0)
        require(screenHeight > 0)
    }

    companion object {
        const val FORMAT = "danmukey-accessibility-fixture"
        const val FORMAT_VERSION = 1
    }
}

object AccessibilityFixtureCodec {
    fun encode(fixture: AccessibilityTreeFixture): String = ContentJson.instance.encodeToString(fixture)

    fun decode(text: String): AccessibilityTreeFixture {
        val fixture = ContentJson.instance.decodeFromString<AccessibilityTreeFixture>(text)
        require(fixture.format == AccessibilityTreeFixture.FORMAT) {
            "不支持的无障碍 fixture 格式"
        }
        require(fixture.formatVersion == AccessibilityTreeFixture.FORMAT_VERSION) {
            "不支持的无障碍 fixture 版本 ${fixture.formatVersion}"
        }
        return fixture
    }
}

object AccessibilityFixtureRedactor {
    fun safeLabel(
        rawValue: CharSequence?,
        editable: Boolean,
        allowedLabels: Set<String>,
        maxLength: Int = DEFAULT_MAX_LENGTH,
    ): String? {
        if (editable || maxLength <= 0) return null
        val normalized = rawValue
            ?.toString()
            ?.replace(WHITESPACE, " ")
            ?.trim()
            .orEmpty()
        if (normalized.isBlank() || normalized !in allowedLabels) return null
        return normalized.take(maxLength)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val DEFAULT_MAX_LENGTH = 48
}

object AccessibilityLocatorMatcher {
    fun matches(
        facts: AccessibilityNodeFacts,
        spec: LocatorSpec.Accessibility,
    ): Boolean {
        val resourceId = spec.resourceId
        val role = spec.role
        val textContains = spec.textContains
        if (resourceId != null && facts.resourceId != resourceId) return false
        if (role != null && facts.className?.contains(role, ignoreCase = true) != true) return false
        if (spec.clickable != null && facts.clickable != spec.clickable) return false
        if (spec.editable != null && facts.editable != spec.editable) return false
        if (textContains != null) {
            val candidate = listOfNotNull(facts.text, facts.contentDescription).joinToString(" ")
            if (!candidate.contains(textContains, ignoreCase = true)) return false
        }
        return true
    }
}

data class LocatedFixtureNode(
    val node: AccessibilityNodeFixture,
    val confidence: Float,
    val source: String,
)

object AccessibilityFixtureLocator {
    fun locate(
        fixture: AccessibilityTreeFixture,
        specs: List<LocatorSpec>,
    ): LocatedFixtureNode? {
        specs.forEachIndexed { index, rawSpec ->
            val spec = rawSpec as? LocatorSpec.Accessibility ?: return@forEachIndexed
            val node = fixture.nodes.firstOrNull { candidate ->
                AccessibilityLocatorMatcher.matches(candidate.facts(), spec)
            } ?: return@forEachIndexed
            return LocatedFixtureNode(
                node = node,
                confidence = if (spec.resourceId != null) {
                    (0.99f - index * 0.01f).coerceAtLeast(0.8f)
                } else {
                    (0.9f - index * 0.02f).coerceAtLeast(0.7f)
                },
                source = if (spec.resourceId != null) "resource_id" else "accessibility_tree",
            )
        }
        return null
    }
}
