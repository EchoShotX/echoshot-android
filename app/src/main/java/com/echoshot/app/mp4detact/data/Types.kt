package com.echoshot.app.mp4detact.data

import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min

/** Detector 1개 결과 */
data class Detection(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val score: Float,
    val cls: Int = 0,
    val label: String = "person"
) {
    fun toRect(): Rect = Rect(x1, y1, x2 - x1, y2 - y1)
}

/**
 * YOLO11n-pose 1개 결과 (바운딩 박스 + 17개 관절)
 * 
 * COCO Keypoints 순서:
 * 0: 코, 1: 왼눈, 2: 오른눈, 3: 왼귀, 4: 오른귀
 * 5: 왼어깨, 6: 오른어깨, 7: 왼팔꿈치, 8: 오른팔꿈치
 * 9: 왼손목, 10: 오른손목, 11: 왼엉덩이, 12: 오른엉덩이
 * 13: 왼무릎, 14: 오른무릎, 15: 왼발목, 16: 오른발목
 */
data class PoseDetection(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val score: Float,
    val keypoints: List<Keypoint>  // 17개 관절
) {
    companion object {
        const val NUM_KEYPOINTS = 17
        
        // 상체 인덱스 (머리 + 어깨 + 엉덩이)
        val TORSO_INDICES = listOf(0, 1, 2, 3, 4, 5, 6, 11, 12)
        
        // 머리 인덱스
        val HEAD_INDICES = listOf(0, 1, 2, 3, 4)
        
        // 어깨 인덱스
        val SHOULDER_INDICES = listOf(5, 6)
        
        // 엉덩이 인덱스
        val HIP_INDICES = listOf(11, 12)
        
        // COCO keypoint sigmas (표준편차) - OKS 계산용
        val DEFAULT_SIGMAS = floatArrayOf(
            0.026f, 0.025f, 0.025f, 0.035f, 0.035f,  // 코, 눈, 귀
            0.079f, 0.079f, 0.072f, 0.072f,          // 어깨, 팔꿈치
            0.062f, 0.062f,                          // 손목
            0.107f, 0.107f,                          // 엉덩이
            0.087f, 0.087f,                          // 무릎
            0.089f, 0.089f                           // 발목
        )
    }
    
    /** 전체 바운딩 박스 → Rect */
    fun toRect(): Rect = Rect(x1.toInt(), y1.toInt(), (x2 - x1).toInt(), (y2 - y1).toInt())
    
    /** 상체만으로 바운딩 박스 계산 (팔다리 제외) */
    fun getTorsoBox(minConf: Float = 0.3f): Rect? {
        val validPoints = TORSO_INDICES
            .mapNotNull { idx -> keypoints.getOrNull(idx)?.takeIf { it.conf >= minConf } }
        
        if (validPoints.size < 3) return null  // 최소 3점 필요
        
        val minX = validPoints.minOf { it.x }
        val maxX = validPoints.maxOf { it.x }
        val minY = validPoints.minOf { it.y }
        val maxY = validPoints.maxOf { it.y }
        
        return Rect(minX.toInt(), minY.toInt(), (maxX - minX).toInt(), (maxY - minY).toInt())
    }
    
    /** 상체 중심점 계산 (어깨-엉덩이 중간) */
    fun getTorsoCenter(minConf: Float = 0.3f): Pair<Float, Float>? {
        val shoulders = SHOULDER_INDICES
            .mapNotNull { idx -> keypoints.getOrNull(idx)?.takeIf { it.conf >= minConf } }
        val hips = HIP_INDICES
            .mapNotNull { idx -> keypoints.getOrNull(idx)?.takeIf { it.conf >= minConf } }
        
        if (shoulders.isEmpty() && hips.isEmpty()) return null
        
        val allPoints = shoulders + hips
        val cx = allPoints.map { it.x }.average().toFloat()
        val cy = allPoints.map { it.y }.average().toFloat()
        
        return Pair(cx, cy)
    }
    
    /** 머리 박스 계산 */
    fun getHeadBox(minConf: Float = 0.3f): Rect? {
        val headPoints = HEAD_INDICES
            .mapNotNull { idx -> keypoints.getOrNull(idx)?.takeIf { it.conf >= minConf } }
        
        if (headPoints.isEmpty()) return null
        
        val minX = headPoints.minOf { it.x }
        val maxX = headPoints.maxOf { it.x }
        val minY = headPoints.minOf { it.y }
        val maxY = headPoints.maxOf { it.y }
        
        // 머리 박스 패딩 (20%)
        val w = maxX - minX
        val h = maxY - minY
        val padW = w * 0.2f
        val padH = h * 0.2f
        
        return Rect(
            (minX - padW).toInt(),
            (minY - padH).toInt(),
            (w + padW * 2).toInt(),
            (h + padH * 2).toInt()
        )
    }
    
    /** 평균 keypoint confidence */
    fun avgKeypointConf(): Float {
        if (keypoints.isEmpty()) return 0f
        return keypoints.map { it.conf }.average().toFloat()
    }
    
    /** OKS (Object Keypoint Similarity) 계산 - 동일 인물 매칭용 */
    fun oksWithOther(other: PoseDetection, sigmas: FloatArray = DEFAULT_SIGMAS): Float {
        if (keypoints.size != other.keypoints.size) return 0f
        
        // 바운딩 박스 면적 (스케일 팩터)
        val area = max(1f, (x2 - x1) * (y2 - y1))
        
        var sumExp = 0f
        var count = 0
        
        for (i in keypoints.indices) {
            val kp1 = keypoints[i]
            val kp2 = other.keypoints[i]
            
            // 둘 다 visible 해야 비교
            if (kp1.conf < 0.3f || kp2.conf < 0.3f) continue
            
            val dx = kp1.x - kp2.x
            val dy = kp1.y - kp2.y
            val d2 = dx * dx + dy * dy
            
            val sigma = sigmas.getOrElse(i) { 0.05f }
            val k2 = 2 * sigma * sigma
            
            // exp(-d² / (2 * σ² * s²))
            sumExp += kotlin.math.exp(-d2 / (k2 * area))
            count++
        }
        
        return if (count > 0) sumExp / count else 0f
    }
}

/** 단일 관절 (x, y, confidence) */
data class Keypoint(
    val x: Float,
    val y: Float,
    val conf: Float
) {
    fun isVisible(threshold: Float = 0.3f): Boolean = conf >= threshold
}

/** Pose 결과의 핵심만 (머리 박스 + 신뢰도) */
data class Keypoints(
    val head: Rect?,          // 없음(가림) 가능
    val conf: Float           // 전체 포즈 신뢰도
)

/** 얼굴(또는 상반신) 임베딩 벡터 */
data class FaceVec(val v: FloatArray)

/** FBO 렌더 후, 원본↔모델 입력 좌표 변환에 필요한 메타 */
data class FrameMeta(
    val scale: Float,   // src -> square 입력 축척
    val padX: Int,      // letterbox x padding
    val padY: Int,      // letterbox y padding
    val srcW: Int,      // 원본 프레임 W
    val srcH: Int       // 원본 프레임 H
)

/** 트래킹 상태 */
enum class TrackState { TRACKING, AMBIGUOUS, REID, LOST }

/** 현재 추적 박스와 동역학(속도) */
data class TrackBox(
    var rect: Rect,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var state: TrackState = TrackState.TRACKING
)

/** 로그 1프레임 레코드 (JSONL로 쓰기 좋게 단순 타입만 사용) */
data class TrackLogEntry(
    val frame: Int,
    val ptsMs: Long,
    val state: String,
    val track: Rect?,                   // null이면 미검출/LOST
    val vx: Float? = null,
    val vy: Float? = null,
    val dets: List<Detection> = emptyList(),
    val notes: Map<String, Any?> = emptyMap()
)
