package com.danmukey.runtime

import com.danmukey.shared.model.Orientation
import com.danmukey.shared.visual.ArgbFrame

data class TemplateCaptureDraft(
    val frame: ArgbFrame,
    val targetPackage: String,
    val orientation: Orientation,
)

object TemplateCaptureDraftRegistry {
    private val lock = Any()
    private var draft: TemplateCaptureDraft? = null

    fun publish(value: TemplateCaptureDraft) {
        synchronized(lock) { draft = value }
    }

    fun peek(): TemplateCaptureDraft? = synchronized(lock) { draft }

    fun clear() {
        synchronized(lock) { draft = null }
    }
}
