package com.echoshot.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * CustomPreviewFragment (동영상 촬영)용 줌 눈금 뷰
 * PhotoFragment용 ZoomTickView와 분리하여 UI 충돌 방지
 */
class VideoZoomTickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var isMajor: Boolean = false
    var label: String? = null

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
        textSize = resources.displayMetrics.scaledDensity * 12f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        
        // ✅ 아래 50% 위치에 눈금 그리기
        val centerY = h * 0.50f
        // ✅ 눈금 길이: major = 전체 30%, minor = major의 60% (위아래 20%씩 잘림)
        val majorHalfLen = h * 0.15f
        val minorHalfLen = h * 0.15f * 0.6f  // major의 60%
        
        val halfLen = if (isMajor) majorHalfLen else minorHalfLen
        val top = centerY - halfLen
        val bottom = centerY + halfLen
        val p = if (isMajor) tickPaint else minorTickPaint

        c.drawLine(cx, top, cx, bottom, p)

        label?.let {
            val y = bottom + (h * 0.08f)
            c.drawText(it, cx, y, textPaint)
        }
    }

    fun bind(major: Boolean, lbl: String?) {
        isMajor = major
        label = lbl
        invalidate()
    }
}

