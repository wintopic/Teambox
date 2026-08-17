package com.danmukey.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * Lightweight, allocation-free-on-draw rendering for the only visible runtime action.
 * White means ready; orange means pressed or sending.
 */
class PlusOneButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val backgroundBounds = RectF()
    private val bodyPath = Path()
    private val raisedArmPath = Path()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(112, 12, 13, 16)
        style = Paint.Style.FILL
    }
    private val outlineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlineStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var scale = 1f
    private var active = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "发送 +1 弹幕"
        minimumWidth = dp(BASE_WIDTH_DP)
        minimumHeight = dp(BASE_HEIGHT_DP)
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        isSelected = active
        invalidate()
    }

    fun isActive(): Boolean = active

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(BASE_WIDTH_DP), widthMeasureSpec),
            resolveSize(dp(BASE_HEIGHT_DP), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        scale = min(width / BASE_WIDTH_DP.toFloat(), height / BASE_HEIGHT_DP.toFloat())
        val offsetX = (width - BASE_WIDTH_DP * scale) / 2f
        val offsetY = (height - BASE_HEIGHT_DP * scale) / 2f
        fun x(value: Float): Float = offsetX + value * scale
        fun y(value: Float): Float = offsetY + value * scale

        backgroundBounds.set(x(1f), y(1f), x(BASE_WIDTH_DP - 1f), y(BASE_HEIGHT_DP - 1f))
        bodyPath.reset()
        bodyPath.moveTo(x(13f), y(42f))
        bodyPath.cubicTo(x(13f), y(33f), x(18f), y(27f), x(25f), y(27f))
        bodyPath.cubicTo(x(31f), y(27f), x(35f), y(31f), x(36f), y(37f))
        bodyPath.lineTo(x(36f), y(42f))
        bodyPath.close()

        raisedArmPath.reset()
        raisedArmPath.moveTo(x(32f), y(33f))
        raisedArmPath.cubicTo(x(36f), y(29f), x(39f), y(24f), x(40f), y(19f))
        raisedArmPath.lineTo(x(41f), y(9f))

        outlineFillPaint.strokeWidth = 2.8f * scale
        outlineStrokePaint.strokeWidth = 6.2f * scale
        glyphStrokePaint.strokeWidth = 3.1f * scale
        textOutlinePaint.strokeWidth = 3.2f * scale
        textOutlinePaint.textSize = 18f * scale
        textFillPaint.textSize = 18f * scale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val color = if (active) ACTIVE_ORANGE else READY_WHITE
        glyphFillPaint.color = color
        glyphStrokePaint.color = color
        textFillPaint.color = color

        canvas.drawRoundRect(backgroundBounds, 18f * scale, 18f * scale, backgroundPaint)

        val offsetX = (width - BASE_WIDTH_DP * scale) / 2f
        val offsetY = (height - BASE_HEIGHT_DP * scale) / 2f
        val headX = offsetX + 25f * scale
        val headY = offsetY + 17f * scale
        canvas.drawCircle(headX, headY, 6.4f * scale, outlineFillPaint)
        canvas.drawCircle(headX, headY, 5f * scale, glyphFillPaint)
        canvas.drawPath(bodyPath, outlineFillPaint)
        canvas.drawPath(bodyPath, glyphFillPaint)
        canvas.drawPath(raisedArmPath, outlineStrokePaint)
        canvas.drawPath(raisedArmPath, glyphStrokePaint)
        canvas.drawCircle(
            offsetX + 41.2f * scale,
            offsetY + 7.3f * scale,
            3.8f * scale,
            outlineFillPaint,
        )
        canvas.drawCircle(
            offsetX + 41.2f * scale,
            offsetY + 7.3f * scale,
            2.5f * scale,
            glyphFillPaint,
        )

        val textX = offsetX + 60.5f * scale
        val textCenterY = offsetY + 27f * scale
        val baseline = textCenterY - (textFillPaint.ascent() + textFillPaint.descent()) / 2f
        canvas.drawText(PLUS_ONE_LABEL, textX, baseline, textOutlinePaint)
        canvas.drawText(PLUS_ONE_LABEL, textX, baseline, textFillPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isSelected = active
        info.stateDescription = if (active) "正在发送" else "待命"
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val BASE_WIDTH_DP = 82
        const val BASE_HEIGHT_DP = 52
        const val PLUS_ONE_LABEL = "+1"
        const val READY_WHITE = 0xFFF7F7F7.toInt()
        const val ACTIVE_ORANGE = 0xFFFF6A1A.toInt()
    }
}
