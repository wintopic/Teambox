package com.danmukey.runtime

import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.danmukey.shared.automation.TargetProfileSelector
import com.danmukey.shared.automation.TargetRuntimeContext
import com.danmukey.shared.data.DanmuKeyRepository
import com.danmukey.shared.model.AndroidDatabaseDriverFactory
import com.danmukey.shared.model.AppThemeMode
import com.danmukey.shared.model.AppThemePreference
import com.danmukey.shared.model.KeyboardAppearancePreference
import com.danmukey.shared.model.KeyboardColumnPreset
import com.danmukey.shared.model.KeyboardHeightPreset
import com.danmukey.shared.model.KeyboardPack
import com.danmukey.shared.model.KeyboardSection
import com.danmukey.shared.model.Orientation
import com.danmukey.shared.model.PhraseGroup
import com.danmukey.shared.model.PhraseItem
import com.danmukey.shared.model.ReviewState
import com.danmukey.shared.model.SelectionPolicy
import com.danmukey.shared.model.createDatabase
import com.danmukey.shared.selection.PhraseCandidate
import com.danmukey.shared.selection.PhraseSelector
import java.util.ArrayDeque

class DanmuKeyboardService : InputMethodService() {
    private lateinit var repository: DanmuKeyRepository
    private lateinit var root: LinearLayout
    private lateinit var packSpinner: Spinner
    private lateinit var sectionScrollView: HorizontalScrollView
    private lateinit var sectionRow: LinearLayout
    private lateinit var groupScrollView: HorizontalScrollView
    private lateinit var groupRow: LinearLayout
    private lateinit var phraseScrollView: ScrollView
    private lateinit var phraseColumn: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var modeButton: Button
    private lateinit var selectionButton: Button
    private lateinit var followButton: Button
    private lateinit var heightButton: Button
    private lateinit var columnButton: Button
    private lateinit var clearBeforeInsertButton: Button
    private lateinit var manualSendButton: Button

    private var packs: List<KeyboardPack> = emptyList()
    private var selectedPackId: String? = null
    private var selectedSectionId: String? = null
    private var selectedGroupId: String? = null
    private var tapToSendEnabled = false
    /** Conservative default: a live accessibility runtime handshake is required for L2. */
    private var automationRuntimeConnected = false
    private var clearBeforeInsertEnabled = false
    private var keyboardHeightPreset = KeyboardHeightPreset.Standard
    private var keyboardColumnPreset = KeyboardColumnPreset.Double
    private var selectionPolicy = SelectionPolicy.Manual
    private var contentFollowEnabled = false
    private var themePalette = KeyboardThemePalette.Light
    private var currentGroup: PhraseGroup? = null
    private val groupSelectionIndexes = mutableMapOf<String, Int>()
    private val recentPhraseIds = ArrayDeque<String>()
    private val accessibilitySettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            enforceTapToSendAvailability()
        }
    }
    private val keyboardStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DanmuAccessibilityService.ACTION_CONTENT_CONTEXT_CHANGED -> {
                    if (::root.isInitialized) refreshContent()
                }

                DanmuAccessibilityService.ACTION_REVOKE_AUTOMATION_CONSENT -> {
                    disableTapToSend("自动操作同意已撤回，已切换手动模式")
                }

                DanmuAccessibilityService.ACTION_TASK_STATUS_CHANGED -> {
                    val message = intent.getStringExtra(DanmuAccessibilityService.EXTRA_TASK_STATUS)
                        ?.trim()
                        .orEmpty()
                    if (message.isNotBlank() && ::statusText.isInitialized) {
                        statusText.text = message
                        statusText.setTextColor(themePalette.secondaryText)
                    }
                }

                DanmuAccessibilityService.ACTION_AUTOMATION_RUNTIME_STATE_CHANGED -> {
                    val connected = intent.getBooleanExtra(
                        DanmuAccessibilityService.EXTRA_AUTOMATION_RUNTIME_CONNECTED,
                        false,
                    )
                    automationRuntimeConnected = connected
                    if (!connected) {
                        disableTapToSend("任务服务已断开，已切换手动模式")
                    } else {
                        enforceTapToSendAvailability()
                    }
                }

                ACTION_APPEARANCE_CHANGED -> {
                    themePalette = currentThemePalette()
                    loadKeyboardAppearance()
                    if (::root.isInitialized) setInputView(onCreateInputView())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DanmuKeyRepository(
            createDatabase(AndroidDatabaseDriverFactory(applicationContext)),
        )
        repository.ensureSeedData(System.currentTimeMillis())
        themePalette = currentThemePalette()
        tapToSendEnabled = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_TAP_TO_SEND, false)
        clearBeforeInsertEnabled = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_CLEAR_BEFORE_INSERT, false)
        loadKeyboardAppearance()
        selectionPolicy = runCatching {
            SelectionPolicy.valueOf(
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString(KEY_SELECTION_POLICY, SelectionPolicy.Manual.name)
                    .orEmpty(),
            )
        }.getOrDefault(SelectionPolicy.Manual)
        contentFollowEnabled = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_CONTENT_FOLLOW_ENABLED, false)
        ContextCompat.registerReceiver(
            this,
            keyboardStateReceiver,
            IntentFilter().apply {
                addAction(DanmuAccessibilityService.ACTION_CONTENT_CONTEXT_CHANGED)
                addAction(DanmuAccessibilityService.ACTION_REVOKE_AUTOMATION_CONSENT)
                addAction(DanmuAccessibilityService.ACTION_TASK_STATUS_CHANGED)
                addAction(DanmuAccessibilityService.ACTION_AUTOMATION_RUNTIME_STATE_CHANGED)
                addAction(ACTION_APPEARANCE_CHANGED)
            },
            DanmuAccessibilityService.CONTROL_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        requestAutomationRuntimeState()
        enforceTapToSendAvailability()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessibilitySettingsObserver,
        )
    }

    override fun onCreateInputView(): View {
        themePalette = currentThemePalette()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(themePalette.background, 0f)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "怪团建"
                textSize = 16f
                setTextColor(themePalette.primaryText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        statusText = TextView(this).apply {
            text = "L1 手动发送"
            textSize = 12f
            setTextColor(themePalette.success)
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(statusText)
        modeButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isAllCaps = false
            applyControlTheme()
            setOnClickListener {
                if (!tapToSendEnabled) {
                    when (tapToSendUnavailableReason(candidateEnabled = true)) {
                        TapToSendUnavailableReason.AutomationConsentMissing -> {
                            showModeError("请先在主应用确认自动操作说明")
                            return@setOnClickListener
                        }

                        TapToSendUnavailableReason.AccessibilityServiceDisabled -> {
                            showModeError("请先在系统设置启用怪团建服务")
                            return@setOnClickListener
                        }

                        TapToSendUnavailableReason.AutomationRuntimeDisconnected -> {
                            showModeError("请等待怪团建服务实时连接")
                            return@setOnClickListener
                        }

                        null -> Unit
                    }
                }
                tapToSendEnabled = !tapToSendEnabled
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_TAP_TO_SEND, tapToSendEnabled)
                    .apply()
                updateModeLabel()
            }
        }
        header.addView(modeButton)
        header.addView(
            Button(this).apply {
                text = "切换"
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isAllCaps = false
                applyControlTheme()
                setOnClickListener { switchKeyboard() }
            },
        )
        root.addView(header)
        updateModeLabel()

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selectionButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
            applyControlTheme()
            setOnClickListener {
                val policies = SelectionPolicy.values()
                selectionPolicy = policies[(selectionPolicy.ordinal + 1) % policies.size]
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SELECTION_POLICY, selectionPolicy.name)
                    .apply()
                updateSelectionLabel()
                bindPhrases(currentGroup)
            }
        }
        selectionRow.addView(selectionButton)
        followButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
            applyControlTheme()
            setOnClickListener {
                contentFollowEnabled = !contentFollowEnabled
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CONTENT_FOLLOW_ENABLED, contentFollowEnabled)
                    .apply()
                updateFollowLabel()
                sendBroadcast(
                    Intent(DanmuAccessibilityService.ACTION_CONTENT_FOLLOW_SETTING_CHANGED)
                        .setPackage(packageName),
                )
                refreshContent()
            }
        }
        selectionRow.addView(followButton)
        heightButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
            applyControlTheme()
            setOnClickListener {
                keyboardHeightPreset = keyboardHeightPreset.next()
                getSharedPreferences(KeyboardAppearancePreference.STORAGE_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KeyboardAppearancePreference.HEIGHT_KEY, keyboardHeightPreset.name)
                    .apply()
                updateHeightLabel()
                applyKeyboardHeight()
            }
        }
        selectionRow.addView(heightButton)
        columnButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setPadding(dp(8), dp(5), dp(8), dp(5))
            applyControlTheme()
            setOnClickListener {
                keyboardColumnPreset = keyboardColumnPreset.next()
                getSharedPreferences(KeyboardAppearancePreference.STORAGE_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KeyboardAppearancePreference.COLUMN_KEY, keyboardColumnPreset.name)
                    .apply()
                updateColumnLabel()
                bindPhrases(currentGroup)
            }
        }
        selectionRow.addView(columnButton)
        root.addView(selectionRow)
        updateSelectionLabel()
        updateFollowLabel()
        updateHeightLabel()
        updateColumnLabel()

        packSpinner = Spinner(this).apply {
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(
            packSpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        sectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sectionScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sectionRow)
        }
        root.addView(sectionScrollView)

        groupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        groupScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(groupRow)
        }
        root.addView(groupScrollView)

        phraseColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        phraseScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(phraseColumn)
        }
        root.addView(
            phraseScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(keyboardHeightPreset.phraseAreaDp),
            ),
        )

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        clearBeforeInsertButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setPadding(dp(10), dp(7), dp(10), dp(7))
            applyControlTheme()
            setOnClickListener {
                clearBeforeInsertEnabled = !clearBeforeInsertEnabled
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CLEAR_BEFORE_INSERT, clearBeforeInsertEnabled)
                    .apply()
                updateClearBeforeInsertLabel()
            }
        }
        actionRow.addView(
            clearBeforeInsertButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            },
        )
        manualSendButton = Button(this).apply {
            text = "发送"
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
            isAllCaps = false
            setTextColor(themePalette.onAccent)
            setPadding(dp(20), dp(7), dp(20), dp(7))
            background = roundedBackground(themePalette.accent, dp(8).toFloat())
            setOnClickListener { submitFromKeyboard() }
        }
        actionRow.addView(manualSendButton)
        root.addView(actionRow)
        updateClearBeforeInsertLabel()
        updateModeLabel()

        refreshContent()
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::root.isInitialized) refreshContent()
        requestAutomationRuntimeState()
        enforceTapToSendAvailability()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        themePalette = currentThemePalette()
        if (::root.isInitialized) setInputView(onCreateInputView())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(keyboardStateReceiver) }
        runCatching { contentResolver.unregisterContentObserver(accessibilitySettingsObserver) }
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun refreshContent() {
        packs = repository.loadAllPacks()
        val targetPackage = currentInputEditorInfo?.packageName.orEmpty()
        val followState = if (contentFollowEnabled && targetPackage.isNotBlank()) {
            repository.loadLatestContentFollowState(
                appIdentifier = targetPackage,
                observedSince = System.currentTimeMillis() - FOLLOW_STATE_MAX_AGE_MS,
            )
        } else {
            null
        }
        val followedPack = followState?.let { state -> packs.firstOrNull { it.id == state.packId } }
        if (followedPack != null) {
            selectedPackId = followedPack.id
            selectedSectionId = followState.sectionId
            selectedGroupId = followState.groupId
        }
        val pack = followedPack ?: packs.firstOrNull { it.id == selectedPackId } ?: packs.firstOrNull()
        selectedPackId = pack?.id
        bindPacks(pack)
        bindSections(pack)
        if (followState != null && followedPack != null) {
            val sectionTitle = followedPack.sections.firstOrNull { it.id == followState.sectionId }?.title
            val groupTitle = followedPack.sections.flatMap { it.groups }
                .firstOrNull { it.id == followState.groupId }
                ?.title
            statusText.text = listOfNotNull("跟随", sectionTitle, groupTitle).joinToString(" · ")
            statusText.setTextColor(themePalette.success)
        }
    }

    private fun bindPacks(selected: KeyboardPack?) {
        packSpinner.onItemSelectedListener = null
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            packs.map(KeyboardPack::name),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also(::applySpinnerItemTheme)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also(::applySpinnerItemTheme)
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        packSpinner.adapter = adapter
        val selectedIndex = packs.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        if (packs.isNotEmpty()) packSpinner.setSelection(selectedIndex, false)
        packSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val pack = packs.getOrNull(position) ?: return
                if (pack.id == selectedPackId) return
                selectedPackId = pack.id
                selectedSectionId = null
                selectedGroupId = null
                bindSections(pack)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun bindSections(pack: KeyboardPack?) {
        sectionRow.removeAllViews()
        val sections = pack?.sections?.sortedBy(KeyboardSection::order).orEmpty()
        val section = sections.firstOrNull { it.id == selectedSectionId } ?: sections.firstOrNull()
        selectedSectionId = section?.id
        var selectedButton: Button? = null
        sections.forEach { item ->
            val isSelected = item.id == section?.id
            val button = selectorButton(
                text = item.title,
                selected = isSelected,
            ) {
                selectedSectionId = item.id
                selectedGroupId = null
                bindSections(pack)
            }
            sectionRow.addView(button)
            if (isSelected) selectedButton = button
        }
        selectedButton?.let { revealSelection(sectionScrollView, it) }
        bindGroups(section)
    }

    private fun bindGroups(section: KeyboardSection?) {
        groupRow.removeAllViews()
        val groups = section?.groups?.sortedBy(PhraseGroup::order).orEmpty()
        val group = groups.firstOrNull { it.id == selectedGroupId } ?: groups.firstOrNull()
        selectedGroupId = group?.id
        currentGroup = group
        var selectedButton: Button? = null
        groups.forEach { item ->
            val isSelected = item.id == group?.id
            val button = selectorButton(
                text = "${item.title} ${item.phrases.count { it.enabled && it.reviewState == ReviewState.Approved }}",
                selected = isSelected,
            ) {
                selectedGroupId = item.id
                bindGroups(section)
            }
            groupRow.addView(button)
            if (isSelected) selectedButton = button
        }
        selectedButton?.let { revealSelection(groupScrollView, it) }
        bindPhrases(group)
    }

    private fun bindPhrases(group: PhraseGroup?) {
        phraseColumn.removeAllViews()
        val phrases = group?.phrases
            ?.filter { it.enabled && it.reviewState == ReviewState.Approved }
            ?.sortedBy(PhraseItem::order)
            .orEmpty()
        if (phrases.isEmpty()) {
            phraseColumn.addView(
                TextView(this).apply {
                    text = "当前分组没有已通过且启用的内容"
                    textSize = 14f
                    setTextColor(themePalette.secondaryText)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(24), dp(8), dp(24))
                },
            )
            return
        }
        val activeGroup = group ?: return
        if (selectionPolicy != SelectionPolicy.Manual) {
            val suggested = selectSuggestedPhrase(activeGroup, phrases)
            phraseColumn.addView(
                Button(this).apply {
                    text = suggested?.let { "建议下一条：${it.text}" } ?: "暂无可用建议"
                    textSize = 13f
                    isAllCaps = false
                    isEnabled = suggested != null
                    maxLines = 2
                    applyControlTheme()
                    setOnClickListener { suggested?.let(::commitPhrase) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(3), dp(3), dp(3), dp(5)) },
            )
        }
        phrases.chunked(keyboardColumnPreset.columnCount).forEach { rowPhrases ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowPhrases.forEach { phrase ->
                row.addView(
                    Button(this).apply {
                        text = phrase.text
                        textSize = 13f
                        isAllCaps = false
                        maxLines = 2
                        applyControlTheme()
                        setOnClickListener { commitPhrase(phrase) }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    },
                )
            }
            repeat(keyboardColumnPreset.columnCount - rowPhrases.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            phraseColumn.addView(row)
        }
    }

    private fun selectorButton(text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(
                if (selected) themePalette.accent else themePalette.controlBackground,
                dp(16).toFloat(),
            )
            setTextColor(if (selected) themePalette.onAccent else themePalette.primaryText)
            setOnClickListener { onClick() }
        }.also { button ->
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }

    private fun commitPhrase(phrase: PhraseItem) {
        val unavailableReason = tapToSendUnavailableReason()
        if (unavailableReason != null) {
            disableTapToSend(
                when (unavailableReason) {
                    TapToSendUnavailableReason.AutomationConsentMissing ->
                        "自动操作同意已撤回，本次只插入"

                    TapToSendUnavailableReason.AccessibilityServiceDisabled ->
                        "任务服务未启用，本次只插入"

                    TapToSendUnavailableReason.AutomationRuntimeDisconnected ->
                        "任务服务未实时连接，本次只插入"
                },
            )
        }
        val target = currentInputEditorInfo?.packageName ?: "unknown"
        val maximumLength = targetProfileForPackage(target)?.maxTextLength
        if (KeyboardInputActions.exceedsMaximumLength(phrase.text, maximumLength)) {
            statusText.text = "内容长度 ${phrase.text.length} 超过目标允许的 $maximumLength 字，未插入"
            statusText.setTextColor(themePalette.error)
            return
        }
        val connection = currentInputConnection ?: return
        val inserted = KeyboardInputActions.insertText(
            editor = AndroidKeyboardEditor(connection),
            text = phrase.text,
            clearBeforeInsert = clearBeforeInsertEnabled,
        )
        if (inserted) {
            repository.recordPhraseUse(phrase.id, target, System.currentTimeMillis())
            rememberPhraseUse(phrase)
            if (tapToSendEnabled) {
                statusText.text = "已插入，等待提交"
                sendBroadcast(
                    Intent(DanmuAccessibilityService.ACTION_SUBMIT_CURRENT_TEXT)
                        .setPackage(packageName)
                        .putExtra(DanmuAccessibilityService.EXTRA_PHRASE, phrase.text)
                        .putExtra(DanmuAccessibilityService.EXTRA_PHRASE_ID, phrase.id)
                        .putExtra(DanmuAccessibilityService.EXTRA_PACK_ID, selectedPackId.orEmpty())
                        .putExtra(DanmuAccessibilityService.EXTRA_TARGET_PACKAGE, target),
                    DanmuAccessibilityService.CONTROL_PERMISSION,
                )
            } else {
                statusText.text = if (unavailableReason != null) {
                    when (unavailableReason) {
                        TapToSendUnavailableReason.AutomationConsentMissing ->
                            "同意已撤回，已改为手动；文字已插入"

                        TapToSendUnavailableReason.AccessibilityServiceDisabled ->
                            "任务服务未启用，已改为手动；文字已插入"

                        TapToSendUnavailableReason.AutomationRuntimeDisconnected ->
                            "任务服务未实时连接，已改为手动；文字已插入"
                    }
                } else {
                    "已插入，点“发送”或应用发送按钮"
                }
            }
            statusText.setTextColor(themePalette.success)
            bindPhrases(currentGroup)
        } else {
            statusText.text = if (clearBeforeInsertEnabled) {
                "无法安全清空，未插入"
            } else {
                "插入失败"
            }
            statusText.setTextColor(themePalette.error)
        }
    }

    private fun submitFromKeyboard() {
        val connection = currentInputConnection
        if (connection == null) {
            statusText.text = "当前没有可用输入框"
            statusText.setTextColor(themePalette.error)
            return
        }
        when (
            KeyboardInputActions.submitFromKeyboard(
                editor = AndroidKeyboardEditor(connection),
                imeOptions = currentInputEditorInfo?.imeOptions ?: 0,
            )
        ) {
            ManualSubmitResult.Submitted -> {
                statusText.text = "已执行输入框发送动作"
                statusText.setTextColor(themePalette.success)
            }

            ManualSubmitResult.Unsupported -> {
                statusText.text = "此输入框不支持键盘发送，请点应用发送按钮"
                statusText.setTextColor(themePalette.warning)
            }

            ManualSubmitResult.Rejected -> {
                statusText.text = "应用拒绝键盘发送，请点应用发送按钮"
                statusText.setTextColor(themePalette.error)
            }
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && shouldOfferSwitchingToNextInputMethod()) {
            switchToNextInputMethod(false)
        } else {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    private fun updateModeLabel() {
        if (!::modeButton.isInitialized) return
        modeButton.text = if (tapToSendEnabled) "模式：点击即发" else "模式：手动"
        if (::statusText.isInitialized) {
            statusText.text = if (tapToSendEnabled) "点击内容即提交 · 需要 L2" else "L1 手动发送"
            statusText.setTextColor(themePalette.success)
        }
        if (::manualSendButton.isInitialized) {
            manualSendButton.isEnabled = !tapToSendEnabled
            manualSendButton.alpha = if (tapToSendEnabled) 0.45f else 1f
        }
    }

    private fun disableTapToSend(message: String) {
        if (tapToSendEnabled) {
            tapToSendEnabled = false
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TAP_TO_SEND, false)
                .apply()
        }
        if (::modeButton.isInitialized) updateModeLabel()
        if (::statusText.isInitialized) {
            statusText.text = message
            statusText.setTextColor(themePalette.warning)
        }
    }

    private fun enforceTapToSendAvailability() {
        when (tapToSendUnavailableReason()) {
            TapToSendUnavailableReason.AutomationConsentMissing ->
                disableTapToSend("自动操作同意已撤回，已切换手动模式")

            TapToSendUnavailableReason.AccessibilityServiceDisabled ->
                disableTapToSend("任务服务未启用，已切换手动模式")

            TapToSendUnavailableReason.AutomationRuntimeDisconnected ->
                disableTapToSend("任务服务未实时连接，已切换手动模式")

            null -> Unit
        }
    }

    private fun tapToSendUnavailableReason(
        candidateEnabled: Boolean = tapToSendEnabled,
    ): TapToSendUnavailableReason? = KeyboardInputActions.tapToSendUnavailableReason(
        tapToSendEnabled = candidateEnabled,
        automationConsentAccepted = hasAutomationConsent(),
        accessibilityServiceEnabled = isAccessibilityServiceEnabled(),
        automationRuntimeConnected = automationRuntimeConnected,
    )

    private fun requestAutomationRuntimeState() {
        sendBroadcast(
            Intent(DanmuAccessibilityService.ACTION_QUERY_AUTOMATION_RUNTIME_STATE)
                .setPackage(packageName),
            DanmuAccessibilityService.CONTROL_PERMISSION,
        )
    }

    private fun showModeError(message: String) {
        if (!::statusText.isInitialized) return
        statusText.text = message
        statusText.setTextColor(themePalette.error)
    }

    private fun updateClearBeforeInsertLabel() {
        if (!::clearBeforeInsertButton.isInitialized) return
        clearBeforeInsertButton.text = if (clearBeforeInsertEnabled) {
            "输入前：清空"
        } else {
            "输入前：保留"
        }
    }

    private fun updateSelectionLabel() {
        if (!::selectionButton.isInitialized) return
        selectionButton.text = "选择：${selectionPolicy.displayName}"
    }

    private fun updateFollowLabel() {
        if (!::followButton.isInitialized) return
        followButton.text = if (contentFollowEnabled) "跟随：开" else "跟随：关"
    }

    private fun updateHeightLabel() {
        if (!::heightButton.isInitialized) return
        heightButton.text = "高度：${keyboardHeightPreset.displayName}"
    }

    private fun updateColumnLabel() {
        if (!::columnButton.isInitialized) return
        columnButton.text = "布局：${keyboardColumnPreset.displayName}"
    }

    private fun applyKeyboardHeight() {
        if (!::phraseScrollView.isInitialized) return
        val params = phraseScrollView.layoutParams
        params.height = dp(keyboardHeightPreset.phraseAreaDp)
        phraseScrollView.layoutParams = params
        phraseScrollView.requestLayout()
    }

    private fun revealSelection(scrollView: HorizontalScrollView, button: View) {
        scrollView.post {
            scrollView.smoothScrollTo((button.left - dp(12)).coerceAtLeast(0), 0)
        }
    }

    private fun selectSuggestedPhrase(group: PhraseGroup, phrases: List<PhraseItem>): PhraseItem? {
        val target = currentInputEditorInfo?.packageName ?: "unknown"
        val usage = repository.usageForTarget(target)
        val selected = PhraseSelector.select(
            candidates = phrases.map { phrase ->
                val phraseUsage = usage[phrase.id]
                PhraseCandidate(
                    id = phrase.id,
                    order = phrase.order,
                    enabled = phrase.enabled && phrase.reviewState == ReviewState.Approved,
                    useCount = phraseUsage?.useCount ?: 0L,
                    lastUsedAt = phraseUsage?.lastUsedAt,
                )
            },
            policy = selectionPolicy,
            recentPhraseIds = recentPhraseIds.toSet(),
            currentIndex = groupSelectionIndexes[group.id] ?: 0,
            randomSeed = System.currentTimeMillis(),
        ) ?: return null
        return phrases.firstOrNull { it.id == selected.id }
    }

    private fun rememberPhraseUse(phrase: PhraseItem) {
        recentPhraseIds.remove(phrase.id)
        recentPhraseIds.addLast(phrase.id)
        while (recentPhraseIds.size > RECENT_EXCLUSION_COUNT) recentPhraseIds.removeFirst()
        val groupId = currentGroup?.id ?: return
        if (selectionPolicy == SelectionPolicy.Sequential) {
            groupSelectionIndexes[groupId] = phrase.order + 1
        }
    }

    private val SelectionPolicy.displayName: String
        get() = when (this) {
            SelectionPolicy.Manual -> "手动"
            SelectionPolicy.Sequential -> "顺序"
            SelectionPolicy.Random -> "随机"
            SelectionPolicy.LeastRecentlyUsed -> "最少使用"
        }

    private fun hasAutomationConsent(): Boolean = getSharedPreferences(
        AUTOMATION_PREFERENCES,
        MODE_PRIVATE,
    ).getBoolean(KEY_AUTOMATION_DISCLOSURE_ACCEPTED, false)

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val expectedComponent = ComponentName(this, DanmuAccessibilityService::class.java)
        val enabledInSettings = enabledServices
            .split(':')
            .asSequence()
            .mapNotNull(ComponentName::unflattenFromString)
            .any { component -> component == expectedComponent }
        return enabledInSettings && DanmuAccessibilityService.isAutomationRuntimeConnected()
    }

    private fun targetProfileForPackage(targetPackage: String) = TargetProfileSelector.select(
        profiles = repository.loadTargetProfiles(),
        context = TargetRuntimeContext(
            appIdentifier = targetPackage,
            orientation = currentOrientation(),
            systemApi = Build.VERSION.SDK_INT,
            appVersionCode = packageVersionCode(targetPackage),
        ),
    )

    private fun currentOrientation(): Orientation = when (resources.configuration.orientation) {
        android.content.res.Configuration.ORIENTATION_LANDSCAPE -> Orientation.Landscape
        else -> Orientation.Portrait
    }

    private fun packageVersionCode(targetPackage: String): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(targetPackage, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(targetPackage, 0).versionCode.toLong()
        }
    }.getOrNull()

    private fun currentThemePalette(): KeyboardThemePalette {
        val mode = AppThemeMode.fromStorage(
            getSharedPreferences(AppThemePreference.STORAGE_NAME, MODE_PRIVATE)
                .getString(AppThemePreference.STORAGE_KEY, null),
        )
        val systemDarkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return KeyboardThemePalette.resolve(mode, systemDarkTheme)
    }

    private fun loadKeyboardAppearance() {
        val preferences = getSharedPreferences(KeyboardAppearancePreference.STORAGE_NAME, MODE_PRIVATE)
        keyboardHeightPreset = KeyboardHeightPreset.fromStorage(
            preferences.getString(KeyboardAppearancePreference.HEIGHT_KEY, null),
        )
        keyboardColumnPreset = KeyboardColumnPreset.fromStorage(
            preferences.getString(KeyboardAppearancePreference.COLUMN_KEY, null),
        )
    }

    private fun Button.applyControlTheme() {
        backgroundTintList = ColorStateList.valueOf(themePalette.controlBackground)
        setTextColor(themePalette.primaryText)
    }

    private fun applySpinnerItemTheme(view: View) {
        (view as? TextView)?.apply {
            setTextColor(themePalette.primaryText)
            setBackgroundColor(themePalette.surface)
        }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_APPEARANCE_CHANGED = "com.danmukey.action.APPEARANCE_CHANGED"
        private const val PREFERENCES = "keyboard_mode"
        private const val KEY_TAP_TO_SEND = "tap_to_send"
        private const val KEY_CLEAR_BEFORE_INSERT = "clear_before_insert"
        private const val KEY_SELECTION_POLICY = "selection_policy"
        private const val KEY_CONTENT_FOLLOW_ENABLED = "content_follow_enabled"
        private const val RECENT_EXCLUSION_COUNT = 10
        private const val FOLLOW_STATE_MAX_AGE_MS = 30_000L
        private const val AUTOMATION_PREFERENCES = "danmukey_preferences"
        private const val KEY_AUTOMATION_DISCLOSURE_ACCEPTED = "automation_disclosure_accepted"
    }
}
