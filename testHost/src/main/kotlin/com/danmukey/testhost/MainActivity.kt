package com.danmukey.testhost

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var episodeTitle: TextView
    private lateinit var composerContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var lastSent: TextView
    private lateinit var modeText: TextView
    private lateinit var playbackTime: TextView
    private var testMode: TestMode = TestMode.Standard
    private var playbackStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        applyIntentOverrides(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentOverrides(intent)
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = "怪团建测试宿主"
                textSize = 24f
                setTextColor(Color.BLACK)
            },
        )
        episodeTitle = TextView(this).apply {
            id = R.id.episode_title
            text = "第一集"
            contentDescription = "当前剧集标题"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(episodeTitle)
        playbackTime = TextView(this).apply {
            id = R.id.playback_time
            text = PLAYBACK_STEPS.first()
            contentDescription = "当前播放时间"
            textSize = 16f
            setTextColor(Color.DKGRAY)
        }
        root.addView(playbackTime)
        root.addView(
            Button(this).apply {
                id = R.id.advance_playback
                text = "推进播放进度"
                contentDescription = "推进测试播放进度"
                isAllCaps = false
                setOnClickListener {
                    playbackStep = (playbackStep + 1) % PLAYBACK_STEPS.size
                    playbackTime.text = PLAYBACK_STEPS[playbackStep]
                }
            },
        )
        root.addView(
            TextView(this).apply {
                text = "发送后输入区域会收起，用于验证 ReopeningComposer。"
                textSize = 15f
                setPadding(0, dp(8), 0, dp(16))
            },
        )

        modeText = TextView(this).apply {
            id = R.id.test_mode_text
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(modeText)
        root.addView(
            Button(this).apply {
                id = R.id.cycle_test_mode
                text = "切换故障模式"
                contentDescription = "切换测试故障模式"
                isAllCaps = false
                setOnClickListener {
                    testMode = testMode.next()
                    applyTestMode()
                }
            },
        )

        root.addView(
            Button(this).apply {
                id = R.id.open_composer
                text = "发弹幕"
                contentDescription = "打开弹幕输入入口"
                isAllCaps = false
                setOnClickListener { openComposer() }
            },
        )

        composerContainer = LinearLayout(this).apply {
            id = R.id.composer_container
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        input = EditText(this).apply {
            id = R.id.danmu_input
            hint = "输入弹幕"
            maxLines = 2
            imeOptions = EditorInfo.IME_ACTION_SEND
            contentDescription = "弹幕输入框"
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitAndCollapse("键盘发送动作")
                    true
                } else {
                    false
                }
            }
        }
        composerContainer.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        sendButton = Button(this).apply {
            id = R.id.send_danmu
            text = "发送"
            contentDescription = "发送弹幕"
            isAllCaps = false
            setOnClickListener { submitAndCollapse("标准按钮") }
        }
        composerContainer.addView(sendButton)
        root.addView(composerContainer)

        root.addView(
            CustomSendView(this).apply {
                id = R.id.custom_send_surface
                contentDescription = "自绘发送控件"
                isClickable = true
                isFocusable = true
                setOnClickListener { submitAndCollapse("自绘控件") }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64),
            ).apply {
                topMargin = dp(16)
            },
        )

        lastSent = TextView(this).apply {
            id = R.id.last_sent_text
            text = "尚未发送"
            textSize = 16f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(lastSent)

        applyTestMode()

        return root
    }

    private fun applyIntentOverrides(intent: Intent?) {
        if (!::episodeTitle.isInitialized) return
        intent?.getStringExtra(EXTRA_EPISODE_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { episodeTitle.text = it }
    }

    private fun openComposer() {
        composerContainer.visibility = View.VISIBLE
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitAndCollapse(source: String) {
        val text = input.text?.toString().orEmpty()
        lastSent.text = if (text.isBlank()) {
            "$source 未提交空内容"
        } else {
            "$source 已提交 ${text.take(30)}"
        }
        if (testMode == TestMode.KeepInputForVerificationFailure) {
            lastSent.text = "模拟验证失败，保留输入内容 ${text.take(30)}"
            return
        }
        input.text?.clear()
        composerContainer.visibility = View.GONE
        getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun applyTestMode() {
        modeText.text = testMode.description
        if (::sendButton.isInitialized) {
            sendButton.visibility = if (testMode == TestMode.HideSubmitButton) View.GONE else View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_EPISODE_TITLE = "episode_title"

        private val PLAYBACK_STEPS = listOf(
            "00:30 / 10:00",
            "01:30 / 10:00",
            "03:30 / 10:00",
        )
    }
}

private enum class TestMode(val description: String) {
    Standard("测试模式：标准提交并收起输入区"),
    KeepInputForVerificationFailure("测试模式：点击后保留输入内容，模拟未确认"),
    HideSubmitButton("测试模式：隐藏发送按钮，模拟定位失败"),
    ;

    fun next(): TestMode = entries[(ordinal + 1) % entries.size]
}

private class CustomSendView(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(63, 81, 181)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            18f,
            18f,
            backgroundPaint,
        )
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("自绘控件发送", width / 2f, baseline, textPaint)
    }
}
