package com.danmukey.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.danmukey.shared.data.ContentTextFormat
import com.danmukey.shared.data.DanmuKeyRepository
import com.danmukey.shared.data.EcdsaP256TargetRuleVerifier
import com.danmukey.shared.data.DevelopmentTargetRuleTrust
import com.danmukey.shared.data.ImportFileLimits
import com.danmukey.shared.db.DesktopDatabaseDriverFactory
import com.danmukey.shared.model.createDatabase
import com.danmukey.shared.model.AppThemeMode
import com.danmukey.shared.model.AppThemePreference
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import java.util.prefs.Preferences

fun main() {
    val dataDirectory = File(System.getProperty("user.home"), ".danmukey")
    val database = createDatabase(
        DesktopDatabaseDriverFactory(File(dataDirectory, "danmukey.db")),
    )
    val controller = DanmuKeyAppController(
        repository = DanmuKeyRepository(database),
        now = System::currentTimeMillis,
        targetRuleVerifier = EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys),
    )
    val themePreferences = Preferences.userRoot().node("com/danmukey/${AppThemePreference.STORAGE_NAME}")

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "怪团建",
        ) {
            var themeMode by remember {
                mutableStateOf(
                    AppThemeMode.fromStorage(
                        themePreferences.get(AppThemePreference.STORAGE_KEY, null),
                    ),
                )
            }
            DanmuKeyApp(
                controller = controller,
                onRefreshCapabilities = {},
                onOpenKeyboardSettings = {},
                onShowKeyboardPicker = {},
                onOpenAccessibilitySettings = {},
                onOpenAutostartSettings = {},
                onOpenBatterySettings = {},
                onRequestScreenCapture = {},
                onTestScreenCapture = {},
                onStopScreenCapture = {},
                onShowTaskControl = {},
                onOpenTestHost = {},
                onImportFile = {
                    chooseFile(FileDialog.LOAD, "导入内容")?.let { file ->
                        runCatching {
                            val extension = file.extension.lowercase()
                            val kind = ImportFileLimits.kindForExtension(extension)
                            ImportFileLimits.requireWithin(kind, file.length())
                            val bytes = file.inputStream().use { it.readLimitedImportBytes(kind) }
                            when (extension) {
                                "dkey" -> controller.importDKey(bytes)
                                "dtarget" -> controller.importDTarget(bytes.decodeToString())
                                "json" -> controller.importJson(bytes.decodeToString())
                                "csv" -> controller.importText(ContentTextFormat.Csv, bytes.decodeToString(), file.name)
                                "srt" -> controller.importText(ContentTextFormat.Srt, bytes.decodeToString(), file.name)
                                "ass", "ssa" -> controller.importText(ContentTextFormat.Ass, bytes.decodeToString(), file.name)
                                else -> controller.importText(ContentTextFormat.Txt, bytes.decodeToString(), file.name)
                            }
                        }.onFailure { controller.reportError("导入失败", it) }
                    }
                },
                onExportFile = { fileName, content, _ ->
                    chooseFile(FileDialog.SAVE, "导出内容", fileName)?.let { file ->
                        runCatching { file.writeBytes(content) }
                            .onSuccess { controller.reportStatus("已导出 ${file.name}") }
                            .onFailure { controller.reportError("导出失败", it) }
                    }
                },
                automationDisclosureAccepted = true,
                onAcceptAutomationDisclosure = {},
                onRevokeAutomationDisclosure = {},
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    themePreferences.put(AppThemePreference.STORAGE_KEY, mode.name)
                },
            )
        }
    }
}

private fun chooseFile(mode: Int, title: String, suggestedName: String? = null): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (suggestedName != null) dialog.file = suggestedName
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val fileName = dialog.file ?: return null
    return File(directory, fileName)
}
