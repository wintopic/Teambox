package com.danmukey.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.AppThemeMode
import com.danmukey.shared.model.KeyboardColumnPreset
import com.danmukey.shared.model.KeyboardHeightPreset
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.DiagnosticEvent
import com.danmukey.shared.model.DiagnosticLevel
import com.danmukey.shared.model.TargetProfile
import com.danmukey.shared.model.SendRecord
import com.danmukey.shared.model.SendResult
import com.danmukey.shared.model.SectionType
import com.danmukey.shared.content.PhraseCatalogEntry
import com.danmukey.shared.content.PhraseCatalogQuery
import com.danmukey.shared.content.PhraseEnabledFilter
import com.danmukey.shared.content.PhraseReviewFilter
import com.danmukey.shared.content.PhraseSortOrder
import com.danmukey.shared.content.queryPhrases
import com.danmukey.shared.data.SampleTargets
import com.danmukey.shared.visual.LocalTemplateInfo
import com.danmukey.shared.data.TargetRuleRevision
import com.danmukey.shared.data.TargetRuleSignatureState
import com.danmukey.shared.data.TargetRuleState

enum class AppPage {
    Overview,
    Content,
    Target,
    Diagnostics,
    Settings,
}

data class AndroidCapabilityStatus(
    val keyboardEnabled: Boolean = false,
    val keyboardSelected: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val currentLevel: String = "L0",
    val batteryOptimizationIgnored: Boolean = false,
    val projectionSessionActive: Boolean = false,
    val deviceManufacturer: String = "",
    val systemApi: Int = 0,
)

internal fun shouldShowRestrictedSettingsGuide(
    systemApi: Int,
    accessibilityEnabled: Boolean,
): Boolean = systemApi >= 33 && !accessibilityEnabled

@Composable
fun DanmuKeyApp(
    controller: DanmuKeyAppController,
    capabilityStatus: AndroidCapabilityStatus = AndroidCapabilityStatus(),
    onRefreshCapabilities: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onShowKeyboardPicker: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetails: () -> Unit = {},
    onOpenAutostartSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onTestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onShowTaskControl: () -> Unit,
    onOpenTestHost: () -> Unit,
    onImportFile: () -> Unit,
    onExportFile: (fileName: String, content: ByteArray, mimeType: String) -> Unit,
    localTemplates: List<LocalTemplateInfo> = emptyList(),
    onRefreshLocalTemplates: () -> Unit = {},
    onDeleteLocalTemplate: (String) -> Unit = {},
    automationDisclosureAccepted: Boolean,
    onAcceptAutomationDisclosure: () -> Unit,
    onRevokeAutomationDisclosure: () -> Unit,
    appVersionLabel: String = "0.1.0",
    themeMode: AppThemeMode = AppThemeMode.System,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    keyboardAppearanceAvailable: Boolean = false,
    keyboardHeightPreset: KeyboardHeightPreset = KeyboardHeightPreset.Standard,
    onKeyboardHeightPresetChange: (KeyboardHeightPreset) -> Unit = {},
    keyboardColumnPreset: KeyboardColumnPreset = KeyboardColumnPreset.Double,
    onKeyboardColumnPresetChange: (KeyboardColumnPreset) -> Unit = {},
) {
    var page by remember { mutableStateOf(AppPage.Overview) }
    var deferredAutomationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showSystemPermissionGuide by remember { mutableStateOf(false) }
    fun guarded(action: () -> Unit) {
        if (automationDisclosureAccepted) action() else deferredAutomationAction = action
    }
    val useDarkTheme = themeMode.usesDarkTheme(isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()) {
    if (showPrivacyPolicy) {
        InformationDialog(
            title = "隐私说明",
            introduction = "怪团建当前 Android 版本以本机处理为默认，不要求注册账号，也不上传屏幕或无障碍内容。",
            sections = PRIVACY_INFORMATION_SECTIONS,
            onDismiss = { showPrivacyPolicy = false },
        )
    }
    if (showSystemPermissionGuide) {
        InformationDialog(
            title = "系统权限与国产系统设置",
            introduction = "权限按功能逐级申请。只使用手动插入时，不需要无障碍或录屏权限。",
            sections = SYSTEM_PERMISSION_SECTIONS,
            onDismiss = { showSystemPermissionGuide = false },
        )
    }
    if (deferredAutomationAction != null) {
        AlertDialog(
            onDismissRequest = { deferredAutomationAction = null },
            title = { Text("启用任务服务前请确认") },
            text = {
                Column(
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("使用时机", fontWeight = FontWeight.SemiBold)
                    Text("只有你在前台主动开启任务、逐条提交、标定或诊断后，怪团建才开始读取和操作。不会后台自行启动。")
                    Text("读取内容", fontWeight = FontWeight.SemiBold)
                    Text("任务服务会读取当前目标应用包名，以及控件的角色、资源 ID、边界和可操作状态。主动标定或诊断时可能读取一帧屏幕；画面只在本机内存处理。")
                    Text("执行操作", fontWeight = FontWeight.SemiBold)
                    Text("根据你选择的固定内容和目标规则，可能执行聚焦输入框、写入文字和点击发送。真实发送仍必须由你明确触发相应模式。")
                    Text("保存与传输", fontWeight = FontWeight.SemiBold)
                    Text("不上传屏幕、无障碍节点或其他输入框文字。诊断只保存脱敏状态和错误码；只有你主动裁剪保存的局部模板会写入应用私有目录。")
                    Text("停止与撤回", fontWeight = FontWeight.SemiBold)
                    Text("切换目标、锁屏、撤回同意、规则变化或定位不可靠时立即停止。你可随时使用控制层的立即停止或在概览页撤回同意。")
                    Text("请仅用于自有或已获授权内容，并自行遵守目标平台规则。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = deferredAutomationAction
                        deferredAutomationAction = null
                        onAcceptAutomationDisclosure()
                        action?.invoke()
                    },
                ) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { deferredAutomationAction = null }) { Text("取消") }
            },
        )
    }
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = page == AppPage.Overview,
                            onClick = { page = AppPage.Overview },
                            icon = { Text("⌂") },
                            label = { Text("概览") },
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Content,
                            onClick = { page = AppPage.Content },
                            icon = { Text("≡") },
                            label = { Text("内容") },
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Target,
                            onClick = { page = AppPage.Target },
                            icon = { Text("◎") },
                            label = { Text("目标") },
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Diagnostics,
                            onClick = {
                                controller.refreshDiagnostics()
                                page = AppPage.Diagnostics
                            },
                            icon = { Text("!") },
                            label = { Text("诊断") },
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Settings,
                            onClick = { page = AppPage.Settings },
                            icon = { Text("⚙") },
                            label = { Text("设置") },
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    when (page) {
                        AppPage.Overview -> OverviewPage(
                            capabilityStatus = capabilityStatus,
                            packCount = controller.packs.size,
                            statusMessage = controller.statusMessage,
                            automationDisclosureAccepted = automationDisclosureAccepted,
                            appVersionLabel = appVersionLabel,
                            onRefreshCapabilities = onRefreshCapabilities,
                            onOpenKeyboardSettings = onOpenKeyboardSettings,
                            onShowKeyboardPicker = onShowKeyboardPicker,
                            onOpenAccessibilitySettings = { guarded(onOpenAccessibilitySettings) },
                            onOpenAppDetails = onOpenAppDetails,
                            onOpenAutostartSettings = onOpenAutostartSettings,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onRequestScreenCapture = onRequestScreenCapture,
                            onTestScreenCapture = onTestScreenCapture,
                            onStopScreenCapture = onStopScreenCapture,
                            onShowTaskControl = { guarded(onShowTaskControl) },
                            onOpenTestHost = onOpenTestHost,
                            onDismissMessage = controller::consumeStatusMessage,
                            onRevokeAutomationDisclosure = onRevokeAutomationDisclosure,
                            onShowPrivacyPolicy = { showPrivacyPolicy = true },
                            onShowSystemPermissionGuide = { showSystemPermissionGuide = true },
                        )

                        AppPage.Content -> ContentPage(
                            controller = controller,
                            onImportFile = onImportFile,
                            onExportFile = onExportFile,
                        )

                        AppPage.Target -> TargetPage(
                            profiles = controller.targetProfiles,
                            ruleRevisions = controller.targetRuleRevisions,
                            capabilityStatus = capabilityStatus,
                            onRefreshCapabilities = onRefreshCapabilities,
                            onRequestScreenCapture = onRequestScreenCapture,
                            onTestScreenCapture = onTestScreenCapture,
                            onStopScreenCapture = onStopScreenCapture,
                            onShowTaskControl = { guarded(onShowTaskControl) },
                            onOpenTestHost = onOpenTestHost,
                            onImportTargetFile = onImportFile,
                            onDeleteProfile = controller::deleteTargetProfile,
                            onActivateRule = controller::activateTargetRule,
                            onRollbackRule = controller::rollbackTargetRule,
                            localTemplates = localTemplates,
                            onRefreshLocalTemplates = onRefreshLocalTemplates,
                            onDeleteLocalTemplate = onDeleteLocalTemplate,
                        )

                        AppPage.Diagnostics -> DiagnosticsPage(
                            events = controller.diagnostics,
                            sendRecords = controller.sendRecords,
                            onRefresh = controller::refreshDiagnostics,
                            onExportDiagnostics = {
                                val (name, content) = controller.exportDiagnosticsJson()
                                onExportFile(name, content.encodeToByteArray(), "application/json")
                            },
                            onExportSendRecords = {
                                val (name, content) = controller.exportSendRecordsJson()
                                onExportFile(name, content.encodeToByteArray(), "application/json")
                            },
                            onClearDiagnostics = controller::clearDiagnostics,
                            onClearSendRecords = controller::clearSendRecords,
                        )

                        AppPage.Settings -> SettingsPage(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            keyboardAppearanceAvailable = keyboardAppearanceAvailable,
                            keyboardHeightPreset = keyboardHeightPreset,
                            onKeyboardHeightPresetChange = onKeyboardHeightPresetChange,
                            keyboardColumnPreset = keyboardColumnPreset,
                            onKeyboardColumnPresetChange = onKeyboardColumnPresetChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    keyboardAppearanceAvailable: Boolean,
    keyboardHeightPreset: KeyboardHeightPreset,
    onKeyboardHeightPresetChange: (KeyboardHeightPreset) -> Unit,
    keyboardColumnPreset: KeyboardColumnPreset,
    onKeyboardColumnPresetChange: (KeyboardColumnPreset) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("外观设置由 Android 管理端、工具键盘和桌面管理端共用。")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == themeMode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(mode.displayName) },
                            )
                        }
                    }
                    Text(
                        when (themeMode) {
                            AppThemeMode.System -> "跟随系统浅色或深色外观。"
                            AppThemeMode.Light -> "始终使用浅色外观。"
                            AppThemeMode.Dark -> "始终使用深色外观。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            StatusCard(
                title = "显示范围",
                body = "Android 工具键盘会在下次显示或系统主题变化时同步主题。模板选区固定使用高对比暗色，确保覆盖在视频画面上仍可辨认。",
            )
        }
        if (keyboardAppearanceAvailable) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "工具键盘外观",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("候选区高度", style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyboardHeightPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = preset == keyboardHeightPreset,
                                    onClick = { onKeyboardHeightPresetChange(preset) },
                                    label = { Text(preset.displayName) },
                                )
                            }
                        }
                        Text("候选列数", style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyboardColumnPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = preset == keyboardColumnPreset,
                                    onClick = { onKeyboardColumnPresetChange(preset) },
                                    label = { Text(preset.displayName) },
                                )
                            }
                        }
                        Text(
                            "设置会立即通知正在运行的 Android 工具键盘；也可以在键盘内快速循环切换。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private val AppThemeMode.displayName: String
    get() = when (this) {
        AppThemeMode.System -> "跟随系统"
        AppThemeMode.Light -> "浅色"
        AppThemeMode.Dark -> "深色"
    }

@Composable
private fun DiagnosticsPage(
    events: List<DiagnosticEvent>,
    sendRecords: List<SendRecord>,
    onRefresh: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportSendRecords: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onClearSendRecords: () -> Unit,
) {
    var pendingClear by remember { mutableStateOf<DiagnosticClearTarget?>(null) }
    pendingClear?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = {
                Text(if (target == DiagnosticClearTarget.Diagnostics) "清除运行诊断" else "清除发送记录")
            },
            text = {
                Text(
                    if (target == DiagnosticClearTarget.Diagnostics) {
                        "将永久删除当前保存的脱敏运行诊断。"
                    } else {
                        "将移除可查看和可导出的发送正文与定位详情。为防止通过清理记录绕过发送限额，目标、结果和时间会作为最小安全计数最多保留 24 小时，界面不再显示。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (target == DiagnosticClearTarget.Diagnostics) {
                            onClearDiagnostics()
                        } else {
                            onClearSendRecords()
                        }
                        pendingClear = null
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("取消") }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 900.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("本机诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "仅保存任务状态、错误码和定位来源，不保存输入框原文、OCR 全文或屏幕截图。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRefresh) { Text("刷新") }
                OutlinedButton(onClick = onExportDiagnostics, enabled = events.isNotEmpty()) {
                    Text("导出诊断")
                }
                OutlinedButton(onClick = onExportSendRecords, enabled = sendRecords.isNotEmpty()) {
                    Text("导出发送记录")
                }
                OutlinedButton(
                    onClick = { pendingClear = DiagnosticClearTarget.Diagnostics },
                    enabled = events.isNotEmpty(),
                ) { Text("清除诊断") }
                OutlinedButton(
                    onClick = { pendingClear = DiagnosticClearTarget.SendRecords },
                    enabled = sendRecords.isNotEmpty(),
                ) { Text("清除发送记录") }
            }
        }
        item {
            Text("发送记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("这里只显示怪团建主动执行的内容，不读取用户原先输入。", style = MaterialTheme.typography.bodySmall)
        }
        if (sendRecords.isEmpty()) {
            item { StatusCard("暂无发送记录", "提交、未确认、失败、取消或被安全限额阻止的操作会显示在这里。") }
        } else {
            items(sendRecords, key = { "send-${it.id}" }) { record ->
                SendRecordCard(record)
            }
        }
        item {
            Text("运行诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (events.isEmpty()) {
            item { StatusCard("暂无记录", "任务服务运行后，脱敏状态和错误会显示在这里。") }
        } else {
            items(events, key = { it.id }) { event ->
                DiagnosticEventCard(event)
            }
        }
    }
}

private enum class DiagnosticClearTarget {
    Diagnostics,
    SendRecords,
}

@Composable
private fun SendRecordCard(record: SendRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(record.finalText, fontWeight = FontWeight.SemiBold)
            Text(
                "${record.result.displayName} · ${record.mode} · 目标 ${record.targetId}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "定位 ${record.locatorSource ?: "—"} · 置信度 ${record.confidence ?: "—"} · ${record.finishedAt ?: record.startedAt}",
                style = MaterialTheme.typography.bodySmall,
            )
            record.errorCode?.let { errorCode ->
                Text("错误码 $errorCode", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val SendResult.displayName: String
    get() = when (this) {
        SendResult.Submitted -> "已提交"
        SendResult.Unconfirmed -> "未确认"
        SendResult.Failed -> "失败"
        SendResult.Cancelled -> "已取消"
        SendResult.Blocked -> "已阻止"
    }

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val levelText = when (event.level) {
                DiagnosticLevel.Info -> "信息"
                DiagnosticLevel.Warning -> "警告"
                DiagnosticLevel.Error -> "错误"
            }
            Text("$levelText · ${event.eventCode}", fontWeight = FontWeight.SemiBold)
            Text("时间 ${event.createdAt} · 目标 ${event.targetId ?: "—"} · 任务 ${event.taskId ?: "—"}")
            if (event.details.isNotEmpty()) {
                Text(
                    event.details.entries.sortedBy { it.key }
                        .joinToString(" · ") { (key, value) -> "$key=$value" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OverviewPage(
    capabilityStatus: AndroidCapabilityStatus,
    packCount: Int,
    statusMessage: String?,
    automationDisclosureAccepted: Boolean,
    appVersionLabel: String,
    onRefreshCapabilities: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onShowKeyboardPicker: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onTestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onShowTaskControl: () -> Unit,
    onOpenTestHost: () -> Unit,
    onDismissMessage: () -> Unit,
    onRevokeAutomationDisclosure: () -> Unit,
    onShowPrivacyPolicy: () -> Unit,
    onShowSystemPermissionGuide: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("怪团建", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Android $appVersionLabel", style = MaterialTheme.typography.titleMedium)
        }
        if (statusMessage != null) {
            item {
                StatusCard("操作结果", statusMessage) {
                    TextButton(onClick = onDismissMessage) { Text("知道了") }
                }
            }
        }
        item {
            StatusCard(
                title = "当前能力 ${capabilityStatus.currentLevel}",
                body = when {
                    capabilityStatus.keyboardSelected && capabilityStatus.accessibilityEnabled ->
                        "键盘和任务服务均已启用，可验证逐条提交与节点定位。"
                    capabilityStatus.keyboardEnabled ->
                        "键盘已启用，可使用 L1 手动插入；启用任务服务后验证 L2。"
                    else -> "先启用怪团建即可使用 L1 手动插入。"
                },
            ) {
                TextButton(onClick = onRefreshCapabilities) { Text("刷新状态") }
            }
        }
        item {
            StatusCard(
                title = "自动操作用途确认",
                body = if (automationDisclosureAccepted) {
                    "已确认仅在用户主动启动后读取目标控件并执行操作；可随时撤回。"
                } else {
                    "尚未确认。L1 内容浏览和手动插入不受影响；启用任务服务前会显示完整说明。"
                },
            ) {
                if (automationDisclosureAccepted) {
                    TextButton(onClick = onRevokeAutomationDisclosure) { Text("撤回同意并停止任务") }
                }
            }
        }
        item {
            StatusCard(
                title = "隐私与系统说明",
                body = "查看本机数据处理、无障碍用途、录屏会话，以及 Android 13 受限设置和国产系统后台运行说明。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onShowPrivacyPolicy) { Text("隐私说明") }
                    TextButton(onClick = onShowSystemPermissionGuide) { Text("权限与系统设置") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onOpenKeyboardSettings) {
                    Text(if (capabilityStatus.keyboardEnabled) "管理键盘" else "启用键盘")
                }
                Button(modifier = Modifier.weight(1f), onClick = onShowKeyboardPicker) {
                    Text("选择怪团建")
                }
            }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenAccessibilitySettings) {
                Text(if (capabilityStatus.accessibilityEnabled) "管理任务服务" else "启用任务服务")
            }
        }
        if (
            shouldShowRestrictedSettingsGuide(
                systemApi = capabilityStatus.systemApi,
                accessibilityEnabled = capabilityStatus.accessibilityEnabled,
            )
        ) {
            item {
                StatusCard(
                    title = "Android 13 受限设置",
                    body = "如果无障碍页面显示“受限设置”或开关不可用，请先进入怪团建应用信息，打开右上角菜单并选择“允许受限设置”，再返回启用任务服务。",
                ) {
                    TextButton(onClick = onOpenAppDetails) { Text("打开怪团建应用信息") }
                }
            }
        }
        item {
            StatusCard(
                title = "后台运行设置",
                body = "设备 ${capabilityStatus.deviceManufacturer.ifBlank { "Android" }}，" +
                    if (capabilityStatus.batteryOptimizationIgnored) {
                        "系统电池优化已放行。国产系统仍建议同时允许自启动并设为后台无限制。"
                    } else {
                        "建议允许自启动，并把怪团建的电池策略设为无限制，避免任务服务被系统回收。"
                    },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAutostartSettings) { Text("自启动设置") }
                    TextButton(onClick = onOpenBatterySettings) { Text("电池无限制") }
                }
            }
        }
        item {
            StatusCard(
                title = "标定截图会话",
                body = if (capabilityStatus.projectionSessionActive) {
                    "本次会话已授权。只有主动标定或诊断时才读取一帧，识别结果仅在本机内存中处理。"
                } else {
                    "未授权。Android 10 标定需要本次会话录屏许可；不影响手动插入和无障碍节点定位。"
                },
            ) {
                if (capabilityStatus.projectionSessionActive) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onTestScreenCapture) { Text("验证读取一帧") }
                            TextButton(onClick = onStopScreenCapture) { Text("结束截图会话") }
                        }
                        TextButton(onClick = onShowTaskControl) { Text("显示模板采样控制") }
                    }
                } else {
                    TextButton(onClick = onRequestScreenCapture) { Text("授权本次会话") }
                }
            }
        }
        item {
            StatusCard("内容库", "本机已有 $packCount 个键盘包。内容由主应用和专用键盘共享读取。")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onShowTaskControl) {
                    Text("显示控制层")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenTestHost) {
                    Text("打开测试宿主")
                }
            }
        }
        item {
            Text(
                "默认只提供手动插入。逐条提交必须由用户点击触发；目标变化或置信度不足时停止。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ContentPage(
    controller: DanmuKeyAppController,
    onImportFile: () -> Unit,
    onExportFile: (fileName: String, content: ByteArray, mimeType: String) -> Unit,
) {
    var newPackName by remember { mutableStateOf("") }
    var newSectionName by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var newPhrase by remember { mutableStateOf("") }
    var newTags by remember { mutableStateOf("") }
    var confirmDeletePack by remember { mutableStateOf(false) }
    var confirmDeleteSection by remember { mutableStateOf(false) }
    var confirmDeleteGroup by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var wholePackScope by remember { mutableStateOf(false) }
    var reviewFilter by remember { mutableStateOf(PhraseReviewFilter.All) }
    var enabledFilter by remember { mutableStateOf(PhraseEnabledFilter.All) }
    var sortOrder by remember { mutableStateOf(PhraseSortOrder.PackOrder) }
    var selectedPhraseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var episodeTargetId by remember { mutableStateOf("") }
    var episodeObservedTitle by remember { mutableStateOf("") }
    var episodeConfidence by remember { mutableStateOf("1.0") }

    val visibleEntries = controller.selectedPack?.queryPhrases(
        PhraseCatalogQuery(
            text = searchQuery,
            reviewFilter = reviewFilter,
            enabledFilter = enabledFilter,
            sortOrder = sortOrder,
            groupId = if (wholePackScope) null else controller.selectedGroupId,
        ),
    ).orEmpty()
    val visiblePhraseIds = visibleEntries.mapTo(linkedSetOf()) { it.phrase.id }

    fun clearSelectionForQueryChange() {
        selectedPhraseIds = emptySet()
    }

    if (confirmDeletePack) {
        AlertDialog(
            onDismissRequest = { confirmDeletePack = false },
            title = { Text("删除键盘包") },
            text = { Text("将删除当前键盘包及其中全部本地内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteSelectedPack()
                        confirmDeletePack = false
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePack = false }) { Text("取消") }
            },
        )
    }
    if (confirmDeleteSection) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSection = false },
            title = { Text("删除标签") },
            text = { Text("将删除当前标签、其中全部场景和内容，以及关联的本地剧集映射。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteSelectedSection()
                        confirmDeleteSection = false
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSection = false }) { Text("取消") }
            },
        )
    }
    if (confirmDeleteGroup) {
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = false },
            title = { Text("删除场景") },
            text = { Text("将删除当前场景及其中全部内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteSelectedGroup()
                        confirmDeleteGroup = false
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteGroup = false }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 900.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("内容管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        controller.statusMessage?.let { message ->
            item { StatusCard("提示", message) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = newPackName,
                    onValueChange = { newPackName = it },
                    label = { Text("新键盘包名称") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    onClick = {
                        controller.createPack(newPackName)
                        newPackName = ""
                    },
                ) { Text("新建") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(controller.packs, key = { it.id }) { pack ->
                    FilterChip(
                        selected = pack.id == controller.selectedPackId,
                        onClick = {
                            controller.selectPack(pack.id)
                            clearSelectionForQueryChange()
                        },
                        label = { Text(pack.name, maxLines = 1) },
                    )
                }
            }
        }
        controller.selectedPack?.let { pack ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    var editPackName by remember(pack.id, pack.version) { mutableStateOf(pack.name) }
                    var editPackAuthor by remember(pack.id, pack.version) { mutableStateOf(pack.author) }
                    var editPackDescription by remember(pack.id, pack.version) { mutableStateOf(pack.description) }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pack.name, style = MaterialTheme.typography.titleLarge)
                        Text("${pack.author} · 版本 ${pack.version} · ${pack.sections.size} 个标签")
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editPackName,
                            onValueChange = { editPackName = it },
                            label = { Text("键盘包名称") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editPackAuthor,
                            onValueChange = { editPackAuthor = it },
                            label = { Text("作者") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editPackDescription,
                            onValueChange = { editPackDescription = it },
                            label = { Text("说明") },
                            maxLines = 3,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                controller.updateSelectedPack(
                                    name = editPackName,
                                    author = editPackAuthor,
                                    description = editPackDescription,
                                )
                            },
                        ) { Text("保存键盘包信息") }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                OutlinedButton(onClick = onImportFile) { Text("导入文件") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        controller.exportDKey()?.let { (name, content) ->
                                            onExportFile(name, content, "application/zip")
                                        }
                                    },
                                ) { Text("导出 .dkey") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        controller.exportJson()?.let { (name, content) ->
                                            onExportFile(name, content.encodeToByteArray(), "application/json")
                                        }
                                    },
                                ) { Text("导出 JSON") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        controller.exportCsv()?.let { (name, content) ->
                                            onExportFile(name, content.encodeToByteArray(), "text/csv")
                                        }
                                    },
                                ) { Text("导出 CSV") }
                            }
                        }
                        TextButton(onClick = { confirmDeletePack = true }) { Text("删除当前键盘包") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newSectionName,
                    onValueChange = { newSectionName = it },
                    label = { Text("新增标签，例如 第二集") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val episode = Regex("第\\s*(\\d+)\\s*集")
                                    .find(newSectionName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                controller.addSection(newSectionName, episode)
                                newSectionName = ""
                            },
                        ) { Text("添加") }
                    },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pack.sections.sortedBy { it.order }, key = { it.id }) { section ->
                        FilterChip(
                            selected = section.id == controller.selectedSectionId,
                            onClick = {
                                controller.selectSection(section.id)
                                clearSelectionForQueryChange()
                            },
                            label = { Text(section.title) },
                        )
                    }
                }
            }
        }
        controller.selectedSection?.let { section ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    var editSectionTitle by remember(section.id, section.title) {
                        mutableStateOf(section.title)
                    }
                    var editEpisodeNumber by remember(section.id, section.episodeNumber) {
                        mutableStateOf(section.episodeNumber?.toString().orEmpty())
                    }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("编辑标签", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editSectionTitle,
                            onValueChange = { editSectionTitle = it },
                            label = { Text("标签名称") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editEpisodeNumber,
                            onValueChange = { editEpisodeNumber = it },
                            label = { Text("集数，非剧集标签可留空") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val episode = editEpisodeNumber.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                                    if (editEpisodeNumber.isNotBlank() && episode == null) {
                                        controller.reportStatus("集数必须是正整数")
                                    } else {
                                        controller.updateSelectedSection(editSectionTitle, episode)
                                    }
                                },
                            ) { Text("保存标签") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { confirmDeleteSection = true },
                            ) { Text("删除标签") }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("新增场景分组") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                controller.addGroup(newGroupName)
                                newGroupName = ""
                            },
                        ) { Text("添加") }
                    },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(section.groups.sortedBy { it.order }, key = { it.id }) { group ->
                        FilterChip(
                            selected = group.id == controller.selectedGroupId,
                            onClick = {
                                controller.selectGroup(group.id)
                                clearSelectionForQueryChange()
                            },
                            label = { Text("${group.title} · ${group.phrases.size}") },
                        )
                    }
                }
            }
            if (section.type == SectionType.Episode) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("剧集标题映射", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "把目标应用识别到的标题映射到 ${section.title}。映射只切换候选标签，不会自动发送。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (controller.targetProfiles.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(controller.targetProfiles, key = { it.id }) { profile ->
                                        FilterChip(
                                            selected = episodeTargetId == profile.id,
                                            onClick = { episodeTargetId = profile.id },
                                            label = { Text(profile.displayName, maxLines = 1) },
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = episodeTargetId,
                                onValueChange = { episodeTargetId = it },
                                label = { Text("目标配置 ID") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = episodeObservedTitle,
                                onValueChange = { episodeObservedTitle = it },
                                label = { Text("识别到的剧集标题，例如 第 2 集") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = episodeConfidence,
                                onValueChange = { episodeConfidence = it },
                                label = { Text("置信度，0 到 1") },
                                singleLine = true,
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val confidence = episodeConfidence.toDoubleOrNull()
                                    if (confidence == null) {
                                        controller.reportStatus("置信度必须是 0 到 1 之间的数字")
                                    } else {
                                        controller.saveEpisodeMapping(
                                            targetId = episodeTargetId,
                                            observedTitle = episodeObservedTitle,
                                            confidence = confidence,
                                        )
                                        episodeObservedTitle = ""
                                    }
                                },
                            ) { Text("保存到 ${section.title}") }
                        }
                    }
                }
            }
        }
        if (controller.episodeMappings.isNotEmpty()) {
            item {
                Text("本地剧集映射", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(controller.episodeMappings, key = { it.id }) { mapping ->
                val sectionTitle = controller.selectedPack?.sections
                    ?.firstOrNull { it.id == mapping.sectionId }
                    ?.title
                    ?: mapping.sectionId
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${mapping.normalizedTitle} → $sectionTitle", fontWeight = FontWeight.SemiBold)
                        Text(
                            "目标 ${mapping.targetId} · 置信度 ${mapping.confidence}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { controller.deleteEpisodeMapping(mapping.id) }) {
                            Text("删除映射")
                        }
                    }
                }
            }
        }
        controller.selectedGroup?.let { group ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    var editGroupTitle by remember(group.id, group.title) { mutableStateOf(group.title) }
                    var editStartTime by remember(group.id, group.startMs) {
                        mutableStateOf(formatSceneTime(group.startMs))
                    }
                    var editEndTime by remember(group.id, group.endMs) {
                        mutableStateOf(formatSceneTime(group.endMs))
                    }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("编辑场景", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = editGroupTitle,
                            onValueChange = { editGroupTitle = it },
                            label = { Text("场景名称") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = editStartTime,
                                onValueChange = { editStartTime = it },
                                label = { Text("开始 mm:ss") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = editEndTime,
                                onValueChange = { editEndTime = it },
                                label = { Text("结束 mm:ss") },
                                singleLine = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val startMs = parseSceneTime(editStartTime)
                                    val endMs = parseSceneTime(editEndTime)
                                    when {
                                        editStartTime.isNotBlank() && startMs == null ->
                                            controller.reportStatus("开始时间格式应为 mm:ss 或 hh:mm:ss")
                                        editEndTime.isNotBlank() && endMs == null ->
                                            controller.reportStatus("结束时间格式应为 mm:ss 或 hh:mm:ss")
                                        else -> controller.updateSelectedGroup(editGroupTitle, startMs, endMs)
                                    }
                                },
                            ) { Text("保存场景") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { confirmDeleteGroup = true },
                            ) { Text("删除场景") }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = newPhrase,
                            onValueChange = { newPhrase = it },
                            label = { Text("弹幕内容") },
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = newTags,
                            onValueChange = { newTags = it },
                            label = { Text("标签，用 | 分隔") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                controller.addPhrase(
                                    newPhrase,
                                    newTags.split('|').map(String::trim).filter(String::isNotEmpty).toSet(),
                                )
                                newPhrase = ""
                                newTags = ""
                            },
                        ) { Text("添加到当前分组") }
                    }
                }
            }
        }
        controller.selectedPack?.let {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("查找与批量管理", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                clearSelectionForQueryChange()
                            },
                            label = { Text("搜索正文、标签、剧集或场景") },
                            singleLine = true,
                        )
                        Text("范围", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = !wholePackScope,
                                    onClick = {
                                        wholePackScope = false
                                        clearSelectionForQueryChange()
                                    },
                                    label = { Text("当前分组") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = wholePackScope,
                                    onClick = {
                                        wholePackScope = true
                                        clearSelectionForQueryChange()
                                    },
                                    label = { Text("整个键盘包") },
                                )
                            }
                        }
                        Text("审核状态", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PhraseReviewFilter.values().toList()) { filter ->
                                FilterChip(
                                    selected = reviewFilter == filter,
                                    onClick = {
                                        reviewFilter = filter
                                        clearSelectionForQueryChange()
                                    },
                                    label = { Text(filter.displayName) },
                                )
                            }
                        }
                        Text("启用状态", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PhraseEnabledFilter.values().toList()) { filter ->
                                FilterChip(
                                    selected = enabledFilter == filter,
                                    onClick = {
                                        enabledFilter = filter
                                        clearSelectionForQueryChange()
                                    },
                                    label = { Text(filter.displayName) },
                                )
                            }
                        }
                        Text("排序", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PhraseSortOrder.values().toList()) { order ->
                                FilterChip(
                                    selected = sortOrder == order,
                                    onClick = {
                                        sortOrder = order
                                        clearSelectionForQueryChange()
                                    },
                                    label = { Text(order.displayName) },
                                )
                            }
                        }
                        Text(
                            "找到 ${visibleEntries.size} 条 · 已选择 ${selectedPhraseIds.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                OutlinedButton(
                                    enabled = visiblePhraseIds.isNotEmpty(),
                                    onClick = { selectedPhraseIds = visiblePhraseIds },
                                ) { Text("全选结果") }
                            }
                            item {
                                OutlinedButton(
                                    enabled = selectedPhraseIds.isNotEmpty(),
                                    onClick = { selectedPhraseIds = emptySet() },
                                ) { Text("清空选择") }
                            }
                            item {
                                Button(
                                    enabled = selectedPhraseIds.isNotEmpty(),
                                    onClick = { controller.setPhrasesEnabled(selectedPhraseIds, true) },
                                ) { Text("批量启用") }
                            }
                            item {
                                Button(
                                    enabled = selectedPhraseIds.isNotEmpty(),
                                    onClick = { controller.setPhrasesEnabled(selectedPhraseIds, false) },
                                ) { Text("批量禁用") }
                            }
                            item {
                                OutlinedButton(
                                    enabled = selectedPhraseIds.isNotEmpty(),
                                    onClick = { controller.reviewPhrases(selectedPhraseIds, true) },
                                ) { Text("批量通过") }
                            }
                            item {
                                OutlinedButton(
                                    enabled = selectedPhraseIds.isNotEmpty(),
                                    onClick = { controller.reviewPhrases(selectedPhraseIds, false) },
                                ) { Text("批量拒绝") }
                            }
                        }
                    }
                }
            }
            if (visibleEntries.isEmpty()) {
                item {
                    Text(
                        if (searchQuery.isBlank() && !wholePackScope) {
                            "当前分组还没有符合筛选条件的内容。"
                        } else {
                            "没有找到符合条件的内容。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(visibleEntries, key = { it.phrase.id }) { entry ->
                    PhraseRow(
                        entry = entry,
                        selected = entry.phrase.id in selectedPhraseIds,
                        showContext = wholePackScope,
                        onSelectedChange = { selected ->
                            selectedPhraseIds = if (selected) {
                                selectedPhraseIds + entry.phrase.id
                            } else {
                                selectedPhraseIds - entry.phrase.id
                            }
                        },
                        onEdit = { controller.updatePhrase(entry.phrase.id, text = it) },
                        onToggle = { controller.updatePhrase(entry.phrase.id, enabled = it) },
                        onApprove = { controller.reviewPhrase(entry.phrase.id, approved = true) },
                        onReject = { controller.reviewPhrase(entry.phrase.id, approved = false) },
                        onDelete = {
                            controller.deletePhrase(entry.phrase.id)
                            selectedPhraseIds -= entry.phrase.id
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PhraseRow(
    entry: PhraseCatalogEntry,
    selected: Boolean,
    showContext: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit,
) {
    val phrase = entry.phrase
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember(phrase.id, phrase.text) { mutableStateOf(phrase.text) }
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑弹幕") },
            text = {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("弹幕正文") },
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEdit(editText)
                        showEditDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Text(
                    modifier = Modifier.weight(1f),
                    text = phrase.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = phrase.enabled, onCheckedChange = onToggle)
            }
            if (showContext) {
                Text(
                    "${entry.sectionTitle} / ${entry.groupTitle}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (phrase.tags.isNotEmpty()) {
                Text(phrase.tags.sorted().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                when (phrase.reviewState) {
                    ReviewState.Pending -> "待审核"
                    ReviewState.Approved -> "已通过"
                    ReviewState.Rejected -> "已拒绝"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { showEditDialog = true }) { Text("编辑") }
                TextButton(onClick = onApprove) { Text("通过") }
                TextButton(onClick = onReject) { Text("拒绝") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun parseSceneTime(value: String): Long? {
    val cleaned = value.trim()
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split(':')
    if (parts.size !in 1..3 || parts.any { it.toLongOrNull() == null }) return null
    val numbers = parts.map(String::toLong)
    val totalSeconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> numbers[0] * 60L + numbers[1]
        else -> numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
    }
    if (totalSeconds < 0L) return null
    if (numbers.size >= 2 && numbers.last() !in 0L..59L) return null
    if (numbers.size == 3 && numbers[1] !in 0L..59L) return null
    return totalSeconds * 1_000L
}

private fun formatSceneTime(value: Long?): String {
    val totalSeconds = value?.div(1_000L) ?: return ""
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds / 60L % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private val PhraseReviewFilter.displayName: String
    get() = when (this) {
        PhraseReviewFilter.All -> "全部"
        PhraseReviewFilter.Pending -> "待审核"
        PhraseReviewFilter.Approved -> "已通过"
        PhraseReviewFilter.Rejected -> "已拒绝"
    }

private val PhraseEnabledFilter.displayName: String
    get() = when (this) {
        PhraseEnabledFilter.All -> "全部"
        PhraseEnabledFilter.Enabled -> "已启用"
        PhraseEnabledFilter.Disabled -> "已禁用"
    }

private val PhraseSortOrder.displayName: String
    get() = when (this) {
        PhraseSortOrder.PackOrder -> "内容顺序"
        PhraseSortOrder.Text -> "文字排序"
        PhraseSortOrder.ReviewState -> "审核优先"
    }

@Composable
private fun TargetPage(
    profiles: List<TargetProfile>,
    ruleRevisions: List<TargetRuleRevision>,
    capabilityStatus: AndroidCapabilityStatus,
    onRefreshCapabilities: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onTestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onShowTaskControl: () -> Unit,
    onOpenTestHost: () -> Unit,
    onImportTargetFile: () -> Unit,
    onDeleteProfile: (String) -> Unit,
    onActivateRule: (String, Int) -> Unit,
    onRollbackRule: (String) -> Unit,
    localTemplates: List<LocalTemplateInfo>,
    onRefreshLocalTemplates: () -> Unit,
    onDeleteLocalTemplate: (String) -> Unit,
) {
    var pendingTemplateDeletion by remember { mutableStateOf<String?>(null) }
    pendingTemplateDeletion?.let { templateId ->
        AlertDialog(
            onDismissRequest = { pendingTemplateDeletion = null },
            title = { Text("删除本地模板") },
            text = {
                Text("将永久删除模板 $templateId。引用它的目标规则会自动降级并在无法可靠定位时停止。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLocalTemplate(templateId)
                        pendingTemplateDeletion = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplateDeletion = null }) { Text("取消") }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("目标适配", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            StatusCard(
                "当前能力 ${capabilityStatus.currentLevel}",
                "键盘 ${if (capabilityStatus.keyboardEnabled) "已启用" else "未启用"}，" +
                    "当前输入法 ${if (capabilityStatus.keyboardSelected) "是怪团建" else "不是怪团建"}，" +
                    "任务服务 ${if (capabilityStatus.accessibilityEnabled) "已启用" else "未启用"}，" +
                    "标定截图 ${if (capabilityStatus.projectionSessionActive) "本次会话已授权" else "未授权"}。",
            ) {
                TextButton(onClick = onRefreshCapabilities) { Text("重新检测") }
            }
        }
        item {
            StatusCard(
                "M0 测试顺序",
                "先在测试宿主验证输入入口、输入框、发送按钮与收起行为，再进入腾讯视频竖屏和横屏页面采集能力矩阵。",
            )
        }
        item {
            StatusCard(
                "用户标定兜底",
                "打开目标应用并让弹幕入口可见，再显示控制层，点击“标定当前目标”。依次点入口、输入框和发送按钮；最后一步只记录坐标，不会执行发送。",
            ) {
                if (capabilityStatus.projectionSessionActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTestScreenCapture) { Text("验证一帧") }
                        TextButton(onClick = onStopScreenCapture) { Text("结束截图会话") }
                    }
                } else {
                    TextButton(onClick = onRequestScreenCapture) { Text("授权标定截图") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onShowTaskControl) { Text("显示控制层") }
                Button(modifier = Modifier.weight(1f), onClick = onOpenTestHost) { Text("测试宿主") }
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onImportTargetFile) {
                Text("导入 .dtarget 规则")
            }
        }
        item {
            Text(
                "真实应用默认只观察节点，不会自动发布外部内容。一次测试发送必须由用户明确触发。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "本地视觉模板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefreshLocalTemplates) { Text("刷新") }
            }
        }
        if (localTemplates.isEmpty()) {
            item {
                Text(
                    "尚未保存模板。请先授权标定截图，再从控制层主动采样。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            items(localTemplates, key = { it.templateId }) { template ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(template.templateId, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${template.width}×${template.height} · ${template.sizeBytes.coerceAtLeast(0L) / 1024L} KB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { pendingTemplateDeletion = template.templateId }) {
                            Text("删除模板")
                        }
                    }
                }
            }
        }
        item {
            Text("本机目标配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (profiles.isEmpty()) {
            item { Text("暂无目标配置。") }
        } else {
            items(profiles, key = { it.id }) { profile ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${profile.displayName} · ${profile.capabilityLevel}", fontWeight = FontWeight.SemiBold)
                        Text(profile.appIdentifiers.sorted().joinToString(), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "方向 ${profile.orientations.joinToString()} · 配置版本 ${profile.profileVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!SampleTargets.isBuiltIn(profile.id)) {
                            TextButton(onClick = { onDeleteProfile(profile.id) }) { Text("删除此标定") }
                        } else {
                            Text(
                                "内置配置由版本或已验签停用规则管理，不能作为本地标定删除。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("规则包版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (ruleRevisions.isEmpty()) {
            item { Text("尚未导入目标规则包。本地三点标定不属于签名规则包。") }
        } else {
            items(ruleRevisions, key = { "${it.ruleId}-${it.revision}" }) { revision ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${revision.envelope.payload.profile.displayName} · v${revision.revision}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${revision.state.displayName} · ${revision.signatureState.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "动作 ${revision.envelope.payload.allowedActions.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        when {
                            revision.state == TargetRuleState.Observation &&
                                revision.signatureState == TargetRuleSignatureState.Verified -> {
                                TextButton(
                                    onClick = { onActivateRule(revision.ruleId, revision.revision) },
                                ) { Text("观察完成并启用") }
                            }
                            revision.state == TargetRuleState.Active -> {
                                TextButton(onClick = { onRollbackRule(revision.ruleId) }) {
                                    Text("回滚上一版")
                                }
                            }
                            revision.state == TargetRuleState.ObservationOnly -> {
                                Text(
                                    "未签名规则只能用于定位观察，不会进入任务执行配置。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TargetRuleState.displayName: String
    get() = when (this) {
        TargetRuleState.Observation -> "待观察确认"
        TargetRuleState.ObservationOnly -> "仅观察"
        TargetRuleState.Active -> "已启用"
        TargetRuleState.Superseded -> "历史版本"
        TargetRuleState.Disabled -> "已停用"
        TargetRuleState.Expired -> "已过期"
    }

private val TargetRuleSignatureState.displayName: String
    get() = when (this) {
        TargetRuleSignatureState.Verified -> "签名已验证"
        TargetRuleSignatureState.Unsigned -> "未签名"
    }

@Composable
private fun StatusCard(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

private data class InformationSection(
    val title: String,
    val body: String,
)

private val PRIVACY_INFORMATION_SECTIONS = listOf(
    InformationSection(
        "本机保存的数据",
        "键盘包、剧集映射、轮换状态、目标配置、用户主动保存的局部模板、任务状态、发送记录和脱敏诊断保存在应用私有目录。任务状态只含任务 ID、模式、目标、计数和时间；终态任务与运行诊断默认保留 30 天，也可以在诊断页立即清除。发送记录清除后，正文和定位详情会消失；目标、结果和时间作为最小安全计数最多保留 24 小时。",
    ),
    InformationSection(
        "无障碍服务",
        "只有用户主动使用 L2/L3 功能时才需要。服务读取当前目标应用的控件结构与状态，并执行用户选择的固定聚焦、写入或点击动作；不把用户在其他输入框中的原文写入诊断。服务被关闭或回收后，遗留任务只会标记失败，不会自动恢复发送。",
    ),
    InformationSection(
        "屏幕识别",
        "Android 10 的标定与诊断使用每次会话单独授权的 MediaProjection；Android 11 以上优先使用无障碍截图。整屏帧默认只在内存处理，不保存、不上传。",
    ),
    InformationSection(
        "网络与第三方组件",
        "当前 Android 构建不申请互联网权限，不包含广告、账号、行为分析或崩溃上传 SDK。中文 OCR 使用随应用提供的 ML Kit 本地识别组件。",
    ),
    InformationSection(
        "用户控制",
        "导入、导出和模板保存都必须由用户主动触发。可撤回自动操作同意、结束截图会话、清除诊断和发送详情、删除内容或模板；清除应用数据或卸载会删除应用私有数据。",
    ),
)

private val SYSTEM_PERMISSION_SECTIONS = listOf(
    InformationSection(
        "L1 手动插入",
        "只需启用并选择怪团建，不要求无障碍服务或屏幕捕获。",
    ),
    InformationSection(
        "无障碍任务服务",
        "仅用于用户主动选择的逐条提交、目标控件定位、标定、固定连续测试和键盘外立即停止。怪团建不是面向残障人士的无障碍工具。",
    ),
    InformationSection(
        "Android 13 受限设置",
        "从浏览器或文件管理器安装 APK 后，系统可能禁止开启无障碍。请进入系统的应用信息页，打开右上角菜单并选择“允许受限设置”，再返回无障碍设置。不同厂商文案可能略有差异。",
    ),
    InformationSection(
        "小米及其他国产系统",
        "如果任务服务被系统回收，请允许自启动，并把电池或后台策略设为“无限制”。只在确有需要时调整，不要求关闭整机安全功能。MIUI 上无障碍悬浮层的标定和拖拽应使用真人触摸。",
    ),
    InformationSection(
        "录屏授权",
        "MediaProjection 是本次会话授权，覆盖安装、重启或主动结束后会失效。怪团建不会尝试绕过目标应用的安全窗口或截图限制。",
    ),
)

@Composable
private fun InformationDialog(
    title: String,
    introduction: String,
    sections: List<InformationSection>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(introduction)
                sections.forEach { section ->
                    Text(section.title, fontWeight = FontWeight.SemiBold)
                    Text(section.body)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
