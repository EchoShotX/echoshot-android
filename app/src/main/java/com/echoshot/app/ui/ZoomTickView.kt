package com.echoshot.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

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
        val top = if (isMajor) h * 0.15f else h * 0.30f
        val bottom = h * 0.75f
        val p = if (isMajor) tickPaint else minorTickPaint

        c.drawLine(cx, top, cx, bottom, p)

        label?.let {
            val y = bottom + (h * 0.18f)
            c.drawText(it, cx, y, textPaint)
        }
    }

    fun bind(major: Boolean, lbl: String?) {
        isMajor = major
        label = lbl
        invalidate()
    }
}
