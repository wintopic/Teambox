package com.danmukey.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.danmukey.runtime.AndroidLocalTemplateStore
import com.danmukey.runtime.DanmuAccessibilityService
import com.danmukey.runtime.LocalTemplateSaveResult
import com.danmukey.runtime.TemplateCaptureDraft
import com.danmukey.runtime.TemplateCaptureDraftRegistry
import com.danmukey.shared.model.Orientation
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.LocalTemplatePolicy
import com.danmukey.shared.visual.PixelRect
import com.danmukey.shared.visual.crop
import com.danmukey.shared.visual.mapAspectFitSelectionToFrame
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class TemplateCaptureActivity : ComponentActivity() {
    private val templateStore by lazy { AndroidLocalTemplateStore(this) }
    private lateinit var draft: TemplateCaptureDraft
    private lateinit var selectionView: TemplateSelectionView
    private lateinit var templateIdInput: EditText
    private lateinit var statusText: TextView
    private var returningToTarget = false
    private var cancellationReceiverRegistered = false
    private val cancellationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DanmuAccessibilityService.ACTION_CANCEL_TEMPLATE_CAPTURE) {
                completeAndReturn(showControlsOnly = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        ContextCompat.registerReceiver(
            this,
            cancellationReceiver,
            IntentFilter(DanmuAccessibilityService.ACTION_CANCEL_TEMPLATE_CAPTURE),
            DanmuAccessibilityService.CONTROL_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cancellationReceiverRegistered = true

        val pendingDraft = TemplateCaptureDraftRegistry.peek()
        if (pendingDraft == null) {
            Toast.makeText(this, R.string.template_capture_expired, Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }
        draft = pendingDraft
        setContentView(buildContentView())
        sendBroadcast(
            Intent(DanmuAccessibilityService.ACTION_TEMPLATE_CAPTURE_ACTIVITY_OPENED)
                .setPackage(packageName),
        )
        onBackPressedDispatcher.addCallback(this) { cancelAndReturn() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::draft.isInitialized || returningToTarget) return
        val matchesDraft = when (draft.orientation) {
            Orientation.Portrait -> newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            Orientation.Landscape -> newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        if (!matchesDraft) {
            Toast.makeText(this, R.string.template_capture_rotated, Toast.LENGTH_LONG).show()
            cancelAndReturn()
        }
    }

    override fun onDestroy() {
        if (cancellationReceiverRegistered) {
            runCatching { unregisterReceiver(cancellationReceiver) }
            cancellationReceiverRegistered = false
        }
        if (::selectionView.isInitialized) selectionView.release()
        if (isFinishing) TemplateCaptureDraftRegistry.clear()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(18, 19, 22))
        }
        root.addView(
            TextView(this).apply {
                setText(R.string.template_capture_title)
                setTextColor(Color.WHITE)
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(
                    R.string.template_capture_instructions,
                    draft.targetPackage,
                    draft.orientation.displayName,
                )
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(0, dp(6), 0, dp(10))
            },
        )

        templateIdInput = EditText(this).apply {
            setText(defaultTemplateId(draft.targetPackage))
            setHint(R.string.template_id_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(104, 168, 255))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(templateIdInput)

        statusText = TextView(this).apply {
            setText(R.string.template_selection_none)
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(statusText)

        root.addView(
            Button(this).apply {
                setText(R.string.template_cancel)
                isAllCaps = false
                setOnClickListener { cancelAndReturn() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ),
        )
        root.addView(
            Button(this).apply {
                setText(R.string.template_save_selection)
                isAllCaps = false
                setOnClickListener { saveSelection(this) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply {
                topMargin = dp(4)
            },
        )

        selectionView = TemplateSelectionView(this).apply {
            setFrame(draft.frame)
            onSelectionChanged = { bounds -> updateSelectionStatus(bounds) }
        }
        root.addView(
            selectionView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun updateSelectionStatus(bounds: PixelRect?) {
        val dimensionViolation = bounds?.let {
            LocalTemplatePolicy.validateDimensions(it.width, it.height)
        }
        statusText.text = when {
            bounds == null -> getString(R.string.template_selection_invalid)
            dimensionViolation != null -> dimensionViolation
            else -> getString(R.string.template_selection_size, bounds.width, bounds.height)
        }
    }

    private fun saveSelection(saveButton: Button) {
        val templateId = templateIdInput.text.toString().trim()
        if (!LocalTemplatePolicy.isValidId(templateId)) {
            statusText.setText(R.string.template_id_invalid)
            return
        }
        val bounds = selectionView.selectedFrameBounds()
        if (bounds == null) {
            statusText.setText(R.string.template_selection_required)
            return
        }
        LocalTemplatePolicy.validateDimensions(bounds.width, bounds.height)?.let { reason ->
            statusText.text = reason
            return
        }
        val cropped = draft.frame.crop(bounds)
        saveButton.isEnabled = false
        statusText.setText(R.string.template_saving)
        lifecycleScope.launch {
            when (val result = templateStore.save(templateId, cropped)) {
                is LocalTemplateSaveResult.Saved -> {
                    Toast.makeText(
                        this@TemplateCaptureActivity,
                        getString(
                            R.string.template_saved,
                            result.templateId,
                            result.width,
                            result.height,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    notifyTemplateSaved(result)
                    completeAndReturn(showControlsOnly = false)
                }

                LocalTemplateSaveResult.AlreadyExists -> {
                    statusText.setText(R.string.template_already_exists)
                    saveButton.isEnabled = true
                }

                is LocalTemplateSaveResult.Rejected -> {
                    statusText.text = result.reason
                    saveButton.isEnabled = true
                }

                is LocalTemplateSaveResult.Failed -> {
                    statusText.text = result.reason
                    saveButton.isEnabled = true
                }
            }
        }
    }

    private fun notifyTemplateSaved(result: LocalTemplateSaveResult.Saved) {
        sendBroadcast(
            Intent(DanmuAccessibilityService.ACTION_TEMPLATE_SAVED)
                .setPackage(packageName)
                .putExtra(DanmuAccessibilityService.EXTRA_TEMPLATE_ID, result.templateId)
                .putExtra(DanmuAccessibilityService.EXTRA_TEMPLATE_WIDTH, result.width)
                .putExtra(DanmuAccessibilityService.EXTRA_TEMPLATE_HEIGHT, result.height)
                .putExtra(DanmuAccessibilityService.EXTRA_TARGET_PACKAGE, draft.targetPackage),
            DanmuAccessibilityService.CONTROL_PERMISSION,
        )
    }

    private fun cancelAndReturn() = completeAndReturn(showControlsOnly = true)

    private fun completeAndReturn(showControlsOnly: Boolean) {
        if (returningToTarget) return
        returningToTarget = true
        TemplateCaptureDraftRegistry.clear()
        if (showControlsOnly) {
            sendBroadcast(
                Intent(DanmuAccessibilityService.ACTION_SHOW_TASK_CONTROL).setPackage(packageName),
                DanmuAccessibilityService.CONTROL_PERMISSION,
            )
        }
        finishAndRemoveTask()
    }

    private fun defaultTemplateId(targetPackage: String): String {
        val suffix = targetPackage.substringAfterLast('.').filter { it.isLetterOrDigit() || it in "._-" }
        return "${suffix.ifBlank { "target" }}-template"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val Orientation.displayName: String
        get() = if (this == Orientation.Portrait) "竖屏" else "横屏"
}

private class TemplateSelectionView(context: android.content.Context) : View(context) {
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 64)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
    }
    private var frame: ArgbFrame? = null
    private var bitmap: Bitmap? = null
    private var startX: Float? = null
    private var startY: Float? = null
    private var endX: Float? = null
    private var endY: Float? = null

    var onSelectionChanged: ((PixelRect?) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        contentDescription = context.getString(R.string.template_selection_canvas)
        isFocusable = true
    }

    fun setFrame(value: ArgbFrame) {
        frame = value
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(value.width, value.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(value.pixels, 0, value.width, 0, 0, value.width, value.height)
        }
        invalidate()
    }

    fun selectedFrameBounds(): PixelRect? {
        val currentFrame = frame ?: return null
        val sx = startX ?: return null
        val sy = startY ?: return null
        val ex = endX ?: return null
        val ey = endY ?: return null
        if (width <= 0 || height <= 0) return null
        return mapAspectFitSelectionToFrame(
            viewWidth = width,
            viewHeight = height,
            frameWidth = currentFrame.width,
            frameHeight = currentFrame.height,
            startX = sx,
            startY = sy,
            endX = ex,
            endY = ey,
        )
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        frame = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBitmap = bitmap ?: return
        val destination = imageDestination(currentBitmap.width, currentBitmap.height)
        canvas.drawBitmap(currentBitmap, null, destination, imagePaint)
        val selection = selectionInView(destination) ?: return
        canvas.drawRect(destination.left, destination.top, destination.right, selection.top, shadePaint)
        canvas.drawRect(destination.left, selection.bottom, destination.right, destination.bottom, shadePaint)
        canvas.drawRect(destination.left, selection.top, selection.left, selection.bottom, shadePaint)
        canvas.drawRect(selection.right, selection.top, destination.right, selection.bottom, shadePaint)
        canvas.drawRect(selection, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentFrame = frame ?: return false
        val destination = imageDestination(currentFrame.width, currentFrame.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!destination.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = event.x.coerceIn(destination.left, destination.right)
                startY = event.y.coerceIn(destination.top, destination.bottom)
                endX = startX
                endY = startY
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (startX == null) return false
                endX = event.x.coerceIn(destination.left, destination.right)
                endY = event.y.coerceIn(destination.top, destination.bottom)
                invalidate()
                onSelectionChanged?.invoke(selectedFrameBounds())
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (startX == null) return false
                endX = event.x.coerceIn(destination.left, destination.right)
                endY = event.y.coerceIn(destination.top, destination.bottom)
                invalidate()
                onSelectionChanged?.invoke(selectedFrameBounds())
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun imageDestination(frameWidth: Int, frameHeight: Int): RectF {
        val scale = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val displayedWidth = frameWidth * scale
        val displayedHeight = frameHeight * scale
        val left = (width - displayedWidth) / 2f
        val top = (height - displayedHeight) / 2f
        return RectF(left, top, left + displayedWidth, top + displayedHeight)
    }

    private fun selectionInView(destination: RectF): RectF? {
        val sx = startX ?: return null
        val sy = startY ?: return null
        val ex = endX ?: return null
        val ey = endY ?: return null
        val left = min(sx, ex).coerceIn(destination.left, destination.right)
        val top = min(sy, ey).coerceIn(destination.top, destination.bottom)
        val right = max(sx, ex).coerceIn(destination.left, destination.right)
        val bottom = max(sy, ey).coerceIn(destination.top, destination.bottom)
        if (right <= left || bottom <= top) return null
        return RectF(left, top, right, bottom)
    }
}
