package com.echoshot.app.mp4detact.tracking

import org.opencv.core.Rect
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import com.echoshot.app.mp4detact.data.Detection
import com.echoshot.app.mp4detact.data.iou
import com.echoshot.app.mp4detact.data.centerDist

/** Detector bbox를 현재 TrackBox와 매칭하는 간단 게이팅+스코어링 */
object Association {

    data class MatchResult(
        val index: Int,      // -1이면 미매칭
        val score: Float,    // 매칭 점수(클수록 좋음)
        val iou: Float,
        val dist: Float
    )

    /**
     * 강화된 매칭 로직: IoU + 면적 유사도 + 위치 유사도
     * @param iouThresh 최소 IoU
     * @param centerGateRatio 대각선*ratio(이미지 기준)
     */
    fun matchOne(
        track: Rect,
        dets: List<Detection>,
        imgW: Int, imgH: Int,
        iouThresh: Float,
        centerGateRatio: Float
    ): MatchResult {
        if (dets.isEmpty()) return MatchResult(-1, -1f, 0f, Float.MAX_VALUE)

        val diag = sqrt((imgW * imgW + imgH * imgH).toDouble()).toFloat().coerceAtLeast(1f)
        val distGate = diag * centerGateRatio

        var bestIdx = -1
        var bestScore = -1f
        var bestIoU = 0f
        var bestDist = Float.MAX_VALUE

        for ((i, d) in dets.withIndex()) {
            val r = d.toRect()
            val iouVal = iou(track, r)
            val dist = centerDist(track, r)

            val pass = (iouVal >= iouThresh) || (dist <= distGate)
            if (!pass) continue

            // 강화된 복합 점수: IoU + 면적 유사도 + 위치 유사도
            val areaSimilarity = calculateAreaSimilarity(track, r)
            val positionSimilarity = calculatePositionSimilarity(track, r)
            
            val score = (0.4f * iouVal) + (0.3f * areaSimilarity) + (0.3f * positionSimilarity)
            
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestIoU = iouVal
                bestDist = dist
            }
        }
        return MatchResult(bestIdx, bestScore, bestIoU, bestDist)
    }

    /** 면적 유사도 계산 (0..1) */
    private fun calculateAreaSimilarity(a: Rect, b: Rect): Float {
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val minArea = min(areaA, areaB).toFloat()
        val maxArea = max(areaA, areaB).toFloat()
        return if (maxArea > 0f) minArea / maxArea else 0f
    }

    /** 위치 유사도 계산 (0..1) - 네 모서리 위치 비교 */
    private fun calculatePositionSimilarity(a: Rect, b: Rect): Float {
        // 네 모서리 위치 유사도
        val cornerSimilarity = (
            (1f - kotlin.math.abs(a.x - b.x) / max(a.width, b.width).toFloat()) +
            (1f - kotlin.math.abs(a.y - b.y) / max(a.height, b.height).toFloat()) +
            (1f - kotlin.math.abs((a.x + a.width) - (b.x + b.width)) / max(a.width, b.width).toFloat()) +
            (1f - kotlin.math.abs((a.y + a.height) - (b.y + b.height)) / max(a.height, b.height).toFloat())
        ) / 4f
        
        return cornerSimilarity.coerceIn(0f, 1f)
    }

    // ------------------ 여기서부터 '겹침 판단' 강화 ------------------

    /** 1D 겹침 비율 (0..1) : intersection / min(size1, size2) */
    private fun overlap1D(a1: Int, a2: Int, b1: Int, b2: Int): Float {
        val inter = max(0, min(a2, b2) - max(a1, b1))
        val lenA = max(1, a2 - a1)
        val lenB = max(1, b2 - b1)
        val denom = max(1, min(lenA, lenB))
        return (inter.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
    }

    /** 중심거리 정규화 (0..1 유사도). 박스 자체 크기를 사용하므로 이미지 크기 의존 X */
    private fun centerSim(a: Rect, b: Rect): Float {
        val d = centerDist(a, b)
        // 두 박스 대각선의 평균으로 정규화
        val diagA = sqrt((a.width * a.width + a.height * a.height).toDouble()).toFloat().coerceAtLeast(1f)
        val diagB = sqrt((b.width * b.width + b.height * b.height).toDouble()).toFloat().coerceAtLeast(1f)
        val norm = d / ((diagA + diagB) * 0.5f)
        return (1f - norm).coerceIn(0f, 1f)
    }

    /**
     * IoU + 1D overlap(h/v) + 중심근접성으로 구성된 겹침 스코어(0..1).
     * IoU가 아주 작더라도, 세로/가로로 많이 겹치거나 중심이 매우 가깝다면 스코어가 의미 있게 올라가도록 설계.
     */
    private fun overlapScore(a: Rect, b: Rect): Float {
        val i = iou(a, b)
        val h = overlap1D(a.x, a.x + a.width, b.x, b.x + b.width)
        val v = overlap1D(a.y, a.y + a.height, b.y, b.y + b.height)
        val c = centerSim(a, b)

        // 가중합 (튜닝 포인트): IoU 0.5, 1D overlap의 max 0.3, 중심근접 0.2
        val score = 0.5f * i + 0.3f * max(h, v) + 0.2f * c

        // 중심이 서로의 박스 내부에 가까우면 약간 보너스 (클램프)
        val cx = a.x + a.width * 0.5f
        val cy = a.y + a.height * 0.5f
        val insideAinB = (cx >= b.x && cx <= b.x + b.width && cy >= b.y && cy <= b.y + b.height)
        val bx = b.x + b.width * 0.5f
        val by = b.y + b.height * 0.5f
        val insideBinA = (bx >= a.x && bx <= a.x + a.width && by >= a.y && by <= a.y + a.height)
        val bonus = if (insideAinB || insideBinA) 0.05f else 0f

        return (score + bonus).coerceIn(0f, 1f)
    }

    /** 트랙과 '다른 사람'들의 최고 '겹침 스코어' 반환(겹침 판단용) */
    fun maxIoUWithOthers(track: Rect, dets: List<Detection>, exceptIdx: Int): Float {
        var m = 0f
        for ((i, d) in dets.withIndex()) {
            if (i == exceptIdx) continue
            m = maxOf(m, overlapScore(track, d.toRect()))
        }
        return m
    }

    /** 트랙과 가장 겹침 스코어 큰 두 후보의 (index, score) */
    fun top2ByIoU(track: Rect, dets: List<Detection>): List<Pair<Int, Float>> {
        val arr = dets.mapIndexed { i, d -> i to overlapScore(track, d.toRect()) }
            .sortedByDescending { it.second }
        return arr.take(2)
    }

    // 변환 유틸
    private fun Detection.toRect(): Rect =
        Rect(x1, y1, (x2 - x1).coerceAtLeast(1), (y2 - y1).coerceAtLeast(1))
}
