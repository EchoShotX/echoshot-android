package com.echoshot.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * PhotoFragment (사진 촬영)용 줌 눈금 뷰
 * 눈금이 중앙에 그려지는 기존 스타일
 */
class ZoomTickView @JvmOverloads constructor(
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
        
        // ✅ PhotoFragment용: 중앙에 눈금 그리기 (기존 스타일)
        val centerY = h / 2f
        val majorHalfLen = h * 0.35f  // PhotoFragment용 눈금 길이
        val minorHalfLen = h * 0.20f
        
        val halfLen = if (isMajor) majorHalfLen else minorHalfLen
        val top = centerY - halfLen
        val bottom = centerY + halfLen
        val p = if (isMajor) tickPaint else minorTickPaint

        c.drawLine(cx, top, cx, bottom, p)

        label?.let {
            val y = bottom + (h * 0.15f)
            c.drawText(it, cx, y, textPaint)
        }
    }

    fun bind(major: Boolean, lbl: String?) {
        isMajor = major
        label = lbl
        invalidate()
    }
}
