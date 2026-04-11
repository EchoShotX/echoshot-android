package com.echoshot.app.ui

import android.util.Log
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * CustomPreviewFragment (동영상 촬영)용 줌 룰러 어댑터
 * PhotoFragment용 ZoomRulerAdapter와 분리하여 UI 충돌 방지
 */
class VideoZoomRulerAdapter(
    private val minZoom: Float,
    private val midZoom: Float,
    private val maxZoom: Float,
    private val ticksPerLogUnit: Int = 10,   // 동영상용: 더 적은 눈금 (20 → 10)
    val itemWidthDp: Int = 10,               // 동영상용: 더 민감하게
    private val minLeftTicks: Int = 0,
    private val minRightTicks: Int = 0
) : RecyclerView.Adapter<VideoZoomRulerAdapter.VH>() {

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
    val centerIndex = if (safeMinZoom >= 1.0f) 0 else leftTicks

    // 라벨을 찍을 줌 스톱
    private val majorStops = floatArrayOf(0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f)
        .filter { it in safeMinZoom..safeMaxZoom }
        .toFloatArray()

    private val majorPositions: Set<Int> by lazy<Set<Int>> {
        try {
            majorStops.asIterable().mapNotNull { zoom ->
                try {
                    zoomToPosition(zoom)
                } catch (e: Exception) {
                    Log.w("VideoZoomRulerAdapter", "Failed to convert zoom $zoom to position", e)
                    null
                }
            }.toSet()
        } catch (e: Exception) {
            Log.e("VideoZoomRulerAdapter", "Failed to calculate majorPositions", e)
            emptySet<Int>()
        }
    }

    inner class VH(val v: VideoZoomTickView): RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = VideoZoomTickView(parent.context)
        val density = parent.resources.displayMetrics.density
        val w = (density * itemWidthDp).toInt()
        // ✅ 높이를 match_parent로 하여 RecyclerView 전체 높이 사용
        v.layoutParams = ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val isMajorTick = position in majorPositions
        holder.v.bind(isMajorTick, null)
    }

    override fun getItemCount(): Int = total

    /** 정수 포지션 → 줌 (로그 보간) */
    fun positionToZoom(pos: Int): Float = positionToZoom(pos.toFloat())

    /** 실시간 보간용: 실수 포지션 → 줌 */
    fun positionToZoom(posF: Float): Float {
        if (posF.isNaN() || posF.isInfinite()) {
            return safeMidZoom
        }
        
        // minZoom >= 1.0f인 경우: 0번 인덱스가 minZoom부터 시작
        if (safeMinZoom >= 1.0f) {
            if (rightTicks <= 0 || safeMaxZoom <= safeMinZoom || safeMinZoom <= 0f) {
                return safeMinZoom
            }
            val t = (posF / rightTicks).coerceIn(0f, 1f)
            val result = (safeMinZoom * ((safeMaxZoom / safeMinZoom).toDouble().pow(t.toDouble()))).toFloat()
            return if (result.isNaN() || result.isInfinite()) safeMinZoom else result
        }
        
        // 기존 로직: minZoom < 1.0f인 경우
        return if (posF <= centerIndex) {
            if (centerIndex <= 0 || safeMidZoom <= safeMinZoom || safeMinZoom <= 0f) {
                return safeMidZoom
            }
            val t = (posF / centerIndex).coerceIn(0f, 1f)
            val result = (safeMinZoom * ((safeMidZoom / safeMinZoom).toDouble().pow(t.toDouble()))).toFloat()
            if (result.isNaN() || result.isInfinite()) safeMidZoom else result
        } else {
            if (rightTicks <= 0 || safeMaxZoom <= safeMidZoom || safeMidZoom <= 0f) {
                return safeMidZoom
            }
            val t = ((posF - centerIndex) / rightTicks).coerceIn(0f, 1f)
            val result = (safeMidZoom * ((safeMaxZoom / safeMidZoom).toDouble().pow(t.toDouble()))).toFloat()
            if (result.isNaN() || result.isInfinite()) safeMidZoom else result
        }
    }

    /** 줌 → 정수 포지션 */
    fun zoomToPosition(zIn: Float): Int {
        if (zIn.isNaN() || zIn.isInfinite()) {
            Log.w("VideoZoomRulerAdapter", "Invalid zoom value: $zIn, returning centerIndex")
            return centerIndex
        }
        
        val z = zIn.coerceIn(safeMinZoom, safeMaxZoom)
        
        if (safeMaxZoom <= safeMinZoom) {
            Log.w("VideoZoomRulerAdapter", "Invalid zoom range: min=$safeMinZoom, max=$safeMaxZoom, returning centerIndex")
            return centerIndex
        }
        
        // minZoom >= 1.0f인 경우
        if (safeMinZoom >= 1.0f) {
            if (safeMaxZoom <= safeMinZoom || safeMinZoom <= 0f) {
                return 0
            }
            val numerator = ln((z / safeMinZoom).toDouble())
            val denominator = ln((safeMaxZoom / safeMinZoom).toDouble())
            
            if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite() ||
                numerator.isNaN() || numerator.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid log calculation (min>=1.0): num=$numerator, denom=$denominator")
                return 0
            }
            
            val t = (numerator / denominator).toFloat()
            
            if (t.isNaN() || t.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid t value (min>=1.0): $t")
                return 0
            }
            
            val pos = (t * rightTicks).roundToInt()
            return pos.coerceIn(0, total - 1)
        }
        
        // 기존 로직: minZoom < 1.0f인 경우
        return if (z <= safeMidZoom) {
            if (safeMidZoom <= safeMinZoom || safeMinZoom <= 0f) {
                return centerIndex
            }
            
            val numerator = ln((z / safeMinZoom).toDouble())
            val denominator = ln((safeMidZoom / safeMinZoom).toDouble())
            
            if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite() ||
                numerator.isNaN() || numerator.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid log calculation (left): num=$numerator, denom=$denominator")
                return centerIndex
            }
            
            val t = (numerator / denominator).toFloat()
            
            if (t.isNaN() || t.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid t value (left): $t")
                return centerIndex
            }
            
            val pos = (t * centerIndex).roundToInt()
            pos.coerceIn(0, total - 1)
        } else {
            if (safeMaxZoom <= safeMidZoom || safeMidZoom <= 0f) {
                return centerIndex
            }
            
            val numerator = ln((z / safeMidZoom).toDouble())
            val denominator = ln((safeMaxZoom / safeMidZoom).toDouble())
            
            if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite() ||
                numerator.isNaN() || numerator.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid log calculation (right): num=$numerator, denom=$denominator")
                return centerIndex
            }
            
            val t = (numerator / denominator).toFloat()
            
            if (t.isNaN() || t.isInfinite()) {
                Log.w("VideoZoomRulerAdapter", "Invalid t value (right): $t")
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

