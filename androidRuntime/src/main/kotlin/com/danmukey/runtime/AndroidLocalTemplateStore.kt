package com.danmukey.runtime

import android.content.Context
import android.graphics.BitmapFactory
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.LocalTemplateStore
import com.danmukey.shared.visual.LocalTemplatePolicy
import com.danmukey.shared.visual.LocalTemplateInfo
import com.danmukey.shared.visual.ScreenCaptureSource
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLocalTemplateStore(
    context: Context,
    private val directory: File = File(context.filesDir, "target-templates"),
) : LocalTemplateStore {
    override suspend fun load(templateId: String): ArgbFrame? = withContext(Dispatchers.IO) {
        if (!LocalTemplatePolicy.isValidId(templateId)) return@withContext null
        val file = File(directory, "$templateId.png")
        if (!file.isFile) return@withContext null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbFrame(
                width = bitmap.width,
                height = bitmap.height,
                pixels = pixels,
                capturedAt = file.lastModified(),
                source = ScreenCaptureSource.LocalAsset,
            )
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun save(templateId: String, frame: ArgbFrame): LocalTemplateSaveResult = withContext(Dispatchers.IO) {
        if (!LocalTemplatePolicy.isValidId(templateId)) {
            return@withContext LocalTemplateSaveResult.Rejected("模板 ID 只能使用字母、数字、点、下划线和短横线")
        }
        LocalTemplatePolicy.validateDimensions(frame.width, frame.height)?.let { reason ->
            return@withContext LocalTemplateSaveResult.Rejected(reason)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext LocalTemplateSaveResult.Failed("无法创建模板目录")
        }
        val destination = File(directory, "$templateId.png")
        if (destination.exists()) return@withContext LocalTemplateSaveResult.AlreadyExists

        val temporary = File(directory, ".$templateId-${System.nanoTime()}.tmp")
        val bitmap = android.graphics.Bitmap.createBitmap(
            frame.width,
            frame.height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val compressed = FileOutputStream(temporary).use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            if (!compressed || !temporary.renameTo(destination)) {
                temporary.delete()
                return@withContext LocalTemplateSaveResult.Failed("模板文件写入失败")
            }
            LocalTemplateSaveResult.Saved(templateId, frame.width, frame.height)
        } catch (_: Throwable) {
            temporary.delete()
            LocalTemplateSaveResult.Failed("模板文件写入失败")
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun list(): List<LocalTemplateInfo> = withContext(Dispatchers.IO) {
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            .mapNotNull { file ->
                val templateId = file.nameWithoutExtension
                if (!LocalTemplatePolicy.isValidId(templateId)) return@mapNotNull null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@mapNotNull null
                LocalTemplateInfo(
                    templateId = templateId,
                    width = options.outWidth,
                    height = options.outHeight,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                )
            }
            .sortedWith(compareByDescending<LocalTemplateInfo> { it.updatedAt }.thenBy { it.templateId })
            .toList()
    }

    suspend fun delete(templateId: String): Boolean = withContext(Dispatchers.IO) {
        if (!LocalTemplatePolicy.isValidId(templateId)) return@withContext false
        val file = File(directory, "$templateId.png")
        file.isFile && file.delete()
    }

}

sealed interface LocalTemplateSaveResult {
    data class Saved(val templateId: String, val width: Int, val height: Int) : LocalTemplateSaveResult
    data object AlreadyExists : LocalTemplateSaveResult
    data class Rejected(val reason: String) : LocalTemplateSaveResult
    data class Failed(val reason: String) : LocalTemplateSaveResult
}
