package com.echoshot.app.ui

import android.util.Log
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

    // 안전한 초기화: NaN 방어
    private val safeMinZoom = if (minZoom.isNaN() || minZoom <= 0f) 1f else minZoom
    private val safeMidZoom = if (midZoom.isNaN() || midZoom <= 0f) 1f else midZoom
    private val safeMaxZoom = if (maxZoom.isNaN() || maxZoom <= 0f) 1f else maxZoom
    
    private val logSpanLeft = if (safeMidZoom > safeMinZoom && safeMinZoom > 0f) {
        ln((safeMidZoom / safeMinZoom).toDouble())
    } else {
        0.0
    }
    
    private val logSpanRight = if (safeMaxZoom > safeMidZoom && safeMidZoom > 0f) {
        ln((safeMaxZoom / safeMidZoom).toDouble())
    } else {
        0.0
    }

    // ★ 하한 강제 제거(또는 파라미터로 제어)
    val leftTicks = if (logSpanLeft.isNaN() || logSpanLeft.isInfinite()) {
        minLeftTicks
    } else {
        (ticksPerLogUnit * logSpanLeft).roundToInt().coerceAtLeast(minLeftTicks)
    }
    
    val rightTicks = if (logSpanRight.isNaN() || logSpanRight.isInfinite()) {
        minRightTicks
    } else {
        (ticksPerLogUnit * logSpanRight).roundToInt().coerceAtLeast(minRightTicks)
    }

    val total = leftTicks + 1 + rightTicks
    val centerIndex = leftTicks


    // 라벨을 찍을 줌 스톱 (중복 방지: 정확히 해당 포지션에서만 라벨)
    private val majorStops = floatArrayOf(0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f)
        .filter { it in safeMinZoom..safeMaxZoom }
        .toFloatArray()

    // 계산된 "라벨 포지션 집합" (정수 포지션에 1:1 매칭)
    private val majorPositions: Set<Int> by lazy<Set<Int>> {
        try {
            majorStops.asIterable().mapNotNull { zoom ->
                try {
                    zoomToPosition(zoom)
                } catch (e: Exception) {
                    Log.w("ZoomRulerAdapter", "Failed to convert zoom $zoom to position", e)
                    null
                }
            }.toSet()
        } catch (e: Exception) {
            Log.e("ZoomRulerAdapter", "Failed to calculate majorPositions", e)
            emptySet<Int>()
        }
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
        if (posF.isNaN() || posF.isInfinite()) {
            return safeMidZoom
        }
        
        return if (posF <= centerIndex) {
            if (centerIndex <= 0 || safeMidZoom <= safeMinZoom || safeMinZoom <= 0f) {
                return safeMidZoom
            }
            val t = (posF / centerIndex).coerceIn(0f, 1f) // [0..1] => min→mid
            val result = (safeMinZoom * ((safeMidZoom / safeMinZoom).toDouble().pow(t.toDouble()))).toFloat()
            if (result.isNaN() || result.isInfinite()) safeMidZoom else result
        } else {
            if (rightTicks <= 0 || safeMaxZoom <= safeMidZoom || safeMidZoom <= 0f) {
                return safeMidZoom
            }
            val t = ((posF - centerIndex) / rightTicks).coerceIn(0f, 1f) // mid→max
            val result = (safeMidZoom * ((safeMaxZoom / safeMidZoom).toDouble().pow(t.toDouble()))).toFloat()
            if (result.isNaN() || result.isInfinite()) safeMidZoom else result
        }
    }

    /** 줌 → 정수 포지션 (라벨 포지션 계산에 사용) */
    fun zoomToPosition(zIn: Float): Int {
        // 1) null/NaN 체크
        if (zIn.isNaN() || zIn.isInfinite()) {
            Log.w("ZoomRulerAdapter", "Invalid zoom value: $zIn, returning centerIndex")
            return centerIndex
        }
        
        // 2) 안전한 범위 클램프
        val z = zIn.coerceIn(safeMinZoom, safeMaxZoom)
        
        // 3) 범위가 유효하지 않은 경우 (min == max 등)
        if (safeMaxZoom <= safeMinZoom) {
            Log.w("ZoomRulerAdapter", "Invalid zoom range: min=$safeMinZoom, max=$safeMaxZoom, returning centerIndex")
            return centerIndex
        }
        
        return if (z <= safeMidZoom) {
            // 왼쪽 구간: minZoom → midZoom
            if (safeMidZoom <= safeMinZoom || safeMinZoom <= 0f) {
                // 분모가 0이거나 유효하지 않은 경우
                return centerIndex
            }
            
            val numerator = ln((z / safeMinZoom).toDouble())
            val denominator = ln((safeMidZoom / safeMinZoom).toDouble())
            
            if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite() ||
                numerator.isNaN() || numerator.isInfinite()) {
                Log.w("ZoomRulerAdapter", "Invalid log calculation (left): num=$numerator, denom=$denominator")
                return centerIndex
            }
            
            val t = (numerator / denominator).toFloat()
            
            if (t.isNaN() || t.isInfinite()) {
                Log.w("ZoomRulerAdapter", "Invalid t value (left): $t")
                return centerIndex
            }
            
            val pos = (t * centerIndex).roundToInt()
            pos.coerceIn(0, total - 1)
        } else {
            // 오른쪽 구간: midZoom → maxZoom
            if (safeMaxZoom <= safeMidZoom || safeMidZoom <= 0f) {
                // 분모가 0이거나 유효하지 않은 경우
                return centerIndex
            }
            
            val numerator = ln((z / safeMidZoom).toDouble())
            val denominator = ln((safeMaxZoom / safeMidZoom).toDouble())
            
            if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite() ||
                numerator.isNaN() || numerator.isInfinite()) {
                Log.w("ZoomRulerAdapter", "Invalid log calculation (right): num=$numerator, denom=$denominator")
                return centerIndex
            }
            
            val t = (numerator / denominator).toFloat()
            
            if (t.isNaN() || t.isInfinite()) {
                Log.w("ZoomRulerAdapter", "Invalid t value (right): $t")
                return centerIndex
            }
            
            val pos = (centerIndex + t * rightTicks).roundToInt()
            pos.coerceIn(0, total - 1)
        }
    }

    private fun prettyLabel(z: Float): String {
        return if (z < 1f) String.format("%.1fx", z)
        else String.format("%.0fx", z)
    }
}
