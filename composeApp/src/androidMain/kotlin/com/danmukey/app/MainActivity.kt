package com.danmukey.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.danmukey.runtime.DanmuAccessibilityService
import com.danmukey.runtime.PlusOneButtonView
import com.danmukey.runtime.ProjectionCaptureService
import com.danmukey.runtime.ProjectionCaptureSessionRegistry
import java.util.concurrent.Executors

/** Native, single-purpose Android entry point for raised-hand auto click. */
class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var previewButton: PlusOneButtonView
    private lateinit var updateStatusText: TextView
    private lateinit var updateCheckButton: Button
    private lateinit var acceleratedDownloadButton: Button
    private lateinit var officialReleaseButton: Button
    private var removeCaptureStateListener: (() -> Unit)? = null
    private val updateExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var updateCheckInFlight = false
    private var availableUpdate: AppUpdateResult.UpdateAvailable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow(window)
        setContentView(buildContent())
        removeCaptureStateListener = ProjectionCaptureSessionRegistry.addStateListener {
            runOnUiThread(::refreshUi)
        }
        checkForUpdates(userInitiated = false)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        removeCaptureStateListener?.invoke()
        removeCaptureStateListener = null
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(32))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(
            TextView(this).apply {
                text = "怪团建"
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "自动识别屏幕上的举手 +1 标志并点击"
                textSize = 16f
                setTextColor(SECONDARY_TEXT)
            },
            verticalMargins(top = 6, bottom = 26),
        )

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(24))
            background = roundedBackground(CARD, 24)
        }
        previewButton = PlusOneButtonView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewCard.addView(
            previewButton,
            LinearLayout.LayoutParams(dp(164), dp(104)),
        )
        previewCard.addView(
            TextView(this).apply {
                text = "白色已停止 · 橘色检测中"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(SECONDARY_TEXT)
            },
            verticalMargins(top = 12),
        )
        root.addView(
            previewCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(20) },
        )

        statusText = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(CARD, 18)
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) },
        )

        actionButton = Button(this).apply {
            isAllCaps = false
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(ORANGE, 18)
            setOnClickListener { handlePrimaryAction() }
        }
        root.addView(
            actionButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply { bottomMargin = dp(22) },
        )

        root.addView(
            TextView(this).apply {
                text = "返回腾讯视频后，应用只扫描画面上部的低分辨率图像，识别白色举手小人和固定“+1”；后面的计数数字不参与识别。同一个向左移动的标志只点击一次；停止后不再截图或点击。"
                textSize = 14f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(SECONDARY_TEXT)
            },
        )

        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(CARD, 18)
        }
        updateCard.addView(
            TextView(this).apply {
                text = "版本更新"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            },
        )
        updateStatusText = TextView(this).apply {
            text = "当前版本 ${BuildConfig.VERSION_NAME} · 正在后台检查"
            textSize = 14f
            setTextColor(SECONDARY_TEXT)
        }
        updateCard.addView(updateStatusText, verticalMargins(top = 6, bottom = 12))

        updateCheckButton = secondaryButton("检查更新") {
            checkForUpdates(userInitiated = true)
        }
        updateCard.addView(
            updateCheckButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ),
        )

        acceleratedDownloadButton = secondaryButton("国内加速下载") {
            availableUpdate?.let { openWebPage(it.apk.acceleratedDownloadUrl) }
        }.apply { visibility = View.GONE }
        updateCard.addView(
            acceleratedDownloadButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(10) },
        )

        officialReleaseButton = secondaryButton("GitHub 官方页") {
            availableUpdate?.let { openWebPage(it.releasePageUrl) }
        }.apply { visibility = View.GONE }
        updateCard.addView(
            officialReleaseButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(10) },
        )
        root.addView(
            updateCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22) },
        )

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun handlePrimaryAction() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val consentAccepted = preferences.getBoolean(KEY_AUTOMATION_DISCLOSURE_ACCEPTED, false)
        val serviceEnabled = isAccessibilityServiceEnabled()
        val autoClickEnabled = preferences.getBoolean(
            DanmuAccessibilityService.KEY_RAISED_HAND_AUTO_CLICK_ENABLED,
            false,
        )

        if (!consentAccepted) {
            preferences.edit()
                .putBoolean(KEY_AUTOMATION_DISCLOSURE_ACCEPTED, true)
                .putBoolean(DanmuAccessibilityService.KEY_RAISED_HAND_AUTO_CLICK_ENABLED, true)
                .apply()
            if (serviceEnabled) {
                startAutoClickWithCapturePermissionIfNeeded()
            } else {
                openAccessibilitySettings()
            }
            refreshUi()
            return
        }

        if (!serviceEnabled) {
            preferences.edit()
                .putBoolean(DanmuAccessibilityService.KEY_RAISED_HAND_AUTO_CLICK_ENABLED, true)
                .apply()
            openAccessibilitySettings()
            refreshUi()
            return
        }

        if (autoClickEnabled && !isCaptureReady()) {
            startAutoClickWithCapturePermissionIfNeeded()
            refreshUi()
            return
        }

        val nextEnabled = !autoClickEnabled
        preferences.edit()
            .putBoolean(DanmuAccessibilityService.KEY_RAISED_HAND_AUTO_CLICK_ENABLED, nextEnabled)
            .apply()
        if (nextEnabled) {
            startAutoClickWithCapturePermissionIfNeeded()
        } else {
            sendServiceAction(DanmuAccessibilityService.ACTION_STOP_RAISED_HAND_AUTO_CLICK)
            ProjectionCaptureService.stop(this)
        }
        refreshUi()
    }

    private fun startAutoClickWithCapturePermissionIfNeeded() {
        if (ProjectionCaptureSessionRegistry.isActive) {
            sendServiceAction(DanmuAccessibilityService.ACTION_START_RAISED_HAND_AUTO_CLICK)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            ProjectionCaptureService.start(this, resultCode, data)
            sendServiceAction(DanmuAccessibilityService.ACTION_START_RAISED_HAND_AUTO_CLICK)
        }
        refreshUi()
    }

    private fun refreshUi() {
        if (!::statusText.isInitialized) return
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val consentAccepted = preferences.getBoolean(KEY_AUTOMATION_DISCLOSURE_ACCEPTED, false)
        val serviceEnabled = isAccessibilityServiceEnabled()
        val autoClickEnabled = preferences.getBoolean(
            DanmuAccessibilityService.KEY_RAISED_HAND_AUTO_CLICK_ENABLED,
            false,
        )
        val captureReady = isCaptureReady()
        val ready = consentAccepted && serviceEnabled && autoClickEnabled && captureReady

        previewButton.setActive(ready)
        when {
            !consentAccepted -> {
                statusText.text = "待开启 · 需要屏幕识别和无障碍点击"
                actionButton.text = "同意并开启"
            }
            !serviceEnabled -> {
                statusText.text = "还差一步 · 请在系统设置中开启“怪团建服务”"
                actionButton.text = "开启系统服务"
            }
            autoClickEnabled && !captureReady -> {
                statusText.text = "还差一步 · 请授权本次屏幕识别会话"
                actionButton.text = "授权并继续"
            }
            autoClickEnabled -> {
                statusText.text = "检测中 · 返回视频即可自动点击"
                actionButton.text = "停止自动点击"
            }
            else -> {
                statusText.text = "已停止 · 不截图、不点击"
                actionButton.text = "开始自动点击"
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':').any { component ->
            component.startsWith("$packageName/") && component.contains("DanmuAccessibilityService")
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isCaptureReady(): Boolean = ProjectionCaptureSessionRegistry.isActive

    private fun sendServiceAction(action: String) {
        sendBroadcast(
            Intent(action).setPackage(packageName),
            DanmuAccessibilityService.CONTROL_PERMISSION,
        )
    }

    private fun checkForUpdates(userInitiated: Boolean) {
        if (updateCheckInFlight || updateExecutor.isShutdown) return
        if (!userInitiated) {
            val lastCheckAt = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, 0L)
            if (System.currentTimeMillis() - lastCheckAt < AUTO_UPDATE_CHECK_INTERVAL_MILLIS) {
                updateStatusText.text = "当前版本 ${BuildConfig.VERSION_NAME} · 可手动检查更新"
                return
            }
        }
        updateCheckInFlight = true
        if (::updateCheckButton.isInitialized) {
            updateCheckButton.isEnabled = false
            updateCheckButton.text = "检查中…"
            if (userInitiated) {
                updateStatusText.text = "正在连接 GitHub；访问失败时会尝试国内加速"
            }
        }
        updateExecutor.execute {
            val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
            if (!userInitiated) {
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, System.currentTimeMillis())
                    .apply()
            }
            runOnUiThread {
                updateCheckInFlight = false
                if (isFinishing || isDestroyed || !::updateCheckButton.isInitialized) return@runOnUiThread
                renderUpdateResult(result, userInitiated)
            }
        }
    }

    private fun renderUpdateResult(result: AppUpdateResult, userInitiated: Boolean) {
        updateCheckButton.isEnabled = true
        updateCheckButton.text = "重新检查"
        acceleratedDownloadButton.visibility = View.GONE
        officialReleaseButton.visibility = View.GONE
        when (result) {
            is AppUpdateResult.UpdateAvailable -> {
                availableUpdate = result
                val size = result.apk.sizeBytes?.let(::formatFileSize)?.let { " · $it" }.orEmpty()
                updateStatusText.text =
                    "发现 ${result.latestVersion}$size · ${result.source.displayName}"
                acceleratedDownloadButton.visibility = View.VISIBLE
                officialReleaseButton.visibility = View.VISIBLE
            }
            is AppUpdateResult.UpToDate -> {
                availableUpdate = null
                updateStatusText.text = "已是最新版 ${result.latestVersion} · ${result.source.displayName}"
            }
            is AppUpdateResult.Failure -> {
                availableUpdate = null
                updateStatusText.text = if (userInitiated) {
                    result.errors.joinToString(separator = "\n") { error ->
                        "${error.source?.displayName ?: "本地"}：${error.message}"
                    }
                } else {
                    "当前版本 ${BuildConfig.VERSION_NAME} · 自动检查暂不可用"
                }
            }
        }
    }

    private fun openWebPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开网页的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun configureWindow(window: Window) {
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        isAllCaps = false
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        background = roundedBackground(SECONDARY_BUTTON, 14)
        setOnClickListener { onClick() }
    }

    private fun verticalMargins(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PREFERENCES_NAME = "danmukey_preferences"
        const val KEY_AUTOMATION_DISCLOSURE_ACCEPTED = "automation_disclosure_accepted"
        const val KEY_LAST_AUTO_UPDATE_CHECK_AT = "last_auto_update_check_at"
        const val AUTO_UPDATE_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
        const val BACKGROUND = 0xFF101114.toInt()
        const val CARD = 0xFF1C1D22.toInt()
        const val SECONDARY_TEXT = 0xFFB7B8C0.toInt()
        const val SECONDARY_BUTTON = 0xFF32343B.toInt()
        const val ORANGE = 0xFFFF6A1A.toInt()
        const val REQUEST_SCREEN_CAPTURE = 4102
    }
}
