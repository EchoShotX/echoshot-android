// RectOverlayView.kt
package com.echoshot.app.mp4detact

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RectOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val shade = Paint().apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }

    private val r = RectF()
    private var touchMode = 0 // 0:none, 1:drag, 2:resize
    private var lastX = 0f
    private var lastY = 0f
    private val hit = 30f

    // 최초 호출 시 중앙에 정사각형 기본 박스
    fun ensureDefault() {
        if (r.width() > 0f) return
        val side = min(width, height) * 0.4f
        r.set(
            (width - side)/2f,
            (height - side)/2f,
            (width + side)/2f,
            (height + side)/2f
        )
        invalidate()
    }

    fun getRectViewSpace(): RectF = RectF(r)

    /** 크기를 비율로 키우거나 줄임 (정사각 유지) */
    fun nudgeScale(mult: Float) {
        if (r.width() <= 0f) return
        val cx = r.centerX()
        val cy = r.centerY()
        val newSide = (r.width() * mult).coerceIn(20f, min(width, height).toFloat())
        val half = newSide / 2f
        r.set(cx - half, cy - half, cx + half, cy + half)
        clampToBounds()
        invalidate()
    }

    private fun clampToBounds() {
        var dx = 0f; var dy = 0f
        if (r.left < 0f) dx = -r.left
        if (r.top < 0f) dy = -r.top
        if (r.right > width) dx = min(dx, width - r.right)
        if (r.bottom > height) dy = min(dy, height - r.bottom)
        r.offset(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 바깥 영역 음영
        canvas.save()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        val c = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 255)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawRect(r, clear)
        clear.xfermode = null
        canvas.restoreToCount(c)

        // 빨간 테두리
        canvas.drawRect(r, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                touchMode = if (isOnEdge(e.x, e.y)) 2 else if (r.contains(e.x, e.y)) 1 else 0
                return touchMode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                when (touchMode) {
                    1 -> { // drag
                        r.offset(dx, dy)
                        clampToBounds()
                        invalidate()
                    }
                    2 -> { // resize - 정사각 유지: 더 큰 변화량을 기준으로 한쪽만 사용
                        val signX = if (e.x > r.centerX()) 1 else -1
                        val signY = if (e.y > r.centerY()) 1 else -1
                        val delta = max(dx * signX, dy * signY)
                        val newSide = (r.width() + delta * 2f).coerceIn(20f, min(width, height).toFloat())
                        val half = newSide / 2f
                        val cx = r.centerX(); val cy = r.centerY()
                        r.set(cx - half, cy - half, cx + half, cy + half)
                        clampToBounds()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchMode = 0
        }
        return super.onTouchEvent(e)
    }

    private fun isOnEdge(x: Float, y: Float): Boolean {
        val nearLeft   = x in (r.left - hit)..(r.left + hit)   && y in (r.top - hit)..(r.bottom + hit)
        val nearRight  = x in (r.right - hit)..(r.right + hit) && y in (r.top - hit)..(r.bottom + hit)
        val nearTop    = y in (r.top - hit)..(r.top + hit)     && x in (r.left - hit)..(r.right + hit)
        val nearBottom = y in (r.bottom - hit)..(r.bottom + hit)&& x in (r.left - hit)..(r.right + hit)
        return nearLeft || nearRight || nearTop || nearBottom
    }
}
