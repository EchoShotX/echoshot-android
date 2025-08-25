package com.echoshot.app.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class ZoomRulerAdapter(
    private val minZoom: Float,
    private val midZoom: Float,
    private val maxZoom: Float,
    private val ticksPerLogUnit: Int = 80,   // ← val로 보관
    private val itemWidthDp: Int = 12,
    private val minLeftTicks: Int = 0,       // ← 하한을 파라미터로
    private val minRightTicks: Int = 0
) : RecyclerView.Adapter<ZoomRulerAdapter.VH>() {

    private val logSpanLeft  = ln((midZoom / minZoom).toDouble())
    private val logSpanRight = ln((maxZoom / midZoom).toDouble())

    // ★ 하한 강제 제거(또는 파라미터로 제어)
    val leftTicks  = (ticksPerLogUnit * logSpanLeft ).roundToInt().coerceAtLeast(minLeftTicks)
    val rightTicks = (ticksPerLogUnit * logSpanRight).roundToInt().coerceAtLeast(minRightTicks)

    val total = leftTicks + 1 + rightTicks
    val centerIndex = leftTicks


    // 라벨을 찍을 줌 스톱 (중복 방지: 정확히 해당 포지션에서만 라벨)
    private val majorStops = floatArrayOf(0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f)
        .filter { it in minZoom..maxZoom }
        .toFloatArray()

    // 계산된 “라벨 포지션 집합” (정수 포지션에 1:1 매칭)
    private val majorPositions: Set<Int> by lazy {
        majorStops.map { zoomToPosition(it) }.toSet()
    }

    inner class VH(val v: ZoomTickView): RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ZoomTickView(parent.context)
        val density = parent.resources.displayMetrics.density
        val w = (density * itemWidthDp).toInt()
        val h = (density * 56).toInt()
        v.layoutParams = ViewGroup.LayoutParams(w, h)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val isMajorTick = position in majorPositions   // 굵기만 다르게
        holder.v.bind(isMajorTick, /*label*/ null)     // ← 라벨 전달을 항상 null
    }

    override fun getItemCount(): Int = total

    /** 정수 포지션 → 줌 (로그 보간) */
    fun positionToZoom(pos: Int): Float = positionToZoom(pos.toFloat())

    /** 실시간 보간용: 실수 포지션 → 줌 */
    fun positionToZoom(posF: Float): Float {
        return if (posF <= centerIndex) {
            val t = (posF / centerIndex).coerceIn(0f, 1f) // [0..1] => min→mid
            (minZoom * ((midZoom / minZoom).toDouble().pow(t.toDouble()))).toFloat()
        } else {
            val t = ((posF - centerIndex) / rightTicks).coerceIn(0f, 1f) // mid→max
            (midZoom * ((maxZoom / midZoom).toDouble().pow(t.toDouble()))).toFloat()
        }
    }

    /** 줌 → 정수 포지션 (라벨 포지션 계산에 사용) */
    fun zoomToPosition(zIn: Float): Int {
        val z = zIn.coerceIn(minZoom, maxZoom)
        return if (z <= midZoom) {
            val t = (ln((z / minZoom).toDouble()) / ln((midZoom / minZoom).toDouble())).toFloat()
            (t * centerIndex).roundToInt()
        } else {
            val t = (ln((z / midZoom).toDouble()) / ln((maxZoom / midZoom).toDouble())).toFloat()
            (centerIndex + t * rightTicks).roundToInt()
        }
    }

    private fun prettyLabel(z: Float): String {
        return if (z < 1f) String.format("%.1fx", z)
        else String.format("%.0fx", z)
    }
}
