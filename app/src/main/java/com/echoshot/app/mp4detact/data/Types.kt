package com.echoshot.app.mp4detact.data

import org.opencv.core.Rect

/** Detector 1개 결과 */
data class Detection(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val score: Float,
    val cls: Int = 0,
    val label: String = "person"
) {
    fun toRect(): Rect = Rect(x1, y1, x2 - x1, y2 - y1)
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
