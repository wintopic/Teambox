package com.danmukey.runtime

import android.view.accessibility.AccessibilityNodeInfo
import com.danmukey.shared.accessibility.AccessibilityLocatorMatcher
import com.danmukey.shared.accessibility.AccessibilityNodeFacts
import com.danmukey.shared.model.LocatorSpec

internal data class LocatedAccessibilityNode(
    val node: AccessibilityNodeInfo,
    val confidence: Float,
    val source: String,
)

internal object AndroidAccessibilityLocator {
    fun locate(
        root: AccessibilityNodeInfo,
        specs: List<LocatorSpec>,
    ): LocatedAccessibilityNode? {
        specs.forEachIndexed { index, rawSpec ->
            val spec = rawSpec as? LocatorSpec.Accessibility ?: return@forEachIndexed
            val direct = spec.resourceId?.let { resourceId ->
                runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }
                    .getOrDefault(emptyList())
                    .firstMatching(spec)
            }
            if (direct != null) {
                return LocatedAccessibilityNode(
                    node = direct,
                    confidence = (0.99f - index * 0.01f).coerceAtLeast(0.8f),
                    source = "resource_id",
                )
            }
            val recursive = findRecursively(root, spec)
            if (recursive != null) {
                return LocatedAccessibilityNode(
                    node = recursive,
                    confidence = (0.9f - index * 0.02f).coerceAtLeast(0.7f),
                    source = "accessibility_tree",
                )
            }
        }
        return null
    }

    private fun List<AccessibilityNodeInfo>.firstMatching(
        spec: LocatorSpec.Accessibility,
    ): AccessibilityNodeInfo? {
        var selected: AccessibilityNodeInfo? = null
        forEach { node ->
            if (selected == null && matches(node, spec)) {
                selected = node
            } else {
                node.recycle()
            }
        }
        return selected
    }

    private fun findRecursively(
        root: AccessibilityNodeInfo,
        spec: LocatorSpec.Accessibility,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let(queue::addLast)
        }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (matches(node, spec)) {
                queue.forEach(AccessibilityNodeInfo::recycle)
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
            node.recycle()
        }
        queue.forEach(AccessibilityNodeInfo::recycle)
        return null
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        spec: LocatorSpec.Accessibility,
    ): Boolean = AccessibilityLocatorMatcher.matches(
        facts = AccessibilityNodeFacts(
            className = node.className?.toString(),
            resourceId = node.viewIdResourceName,
            clickable = node.isClickable,
            editable = node.isEditable,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
        ),
        spec = spec,
    )

    private const val MAX_NODES = 500
}
