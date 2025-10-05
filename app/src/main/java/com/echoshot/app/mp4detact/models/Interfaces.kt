package com.echoshot.app.mp4detact.models

import com.echoshot.app.mp4detact.data.Detection
import com.echoshot.app.mp4detact.data.FaceVec
import com.echoshot.app.mp4detact.data.Keypoints
import com.echoshot.app.mp4detact.engine.GlLetterboxFbo
import org.opencv.core.Rect
import java.nio.ByteBuffer

/** YOLO 같은 전신 Detector */
interface Detector : AutoCloseable {
    /** 필요 시 delegate/스레드 준비 */
    fun open() {}
    /**
     * @param rgba  정사각 레터박스 RGBA(ByteBuffer, position=0 가정)
     * @param scale/padX/padY  레터박스 파라미터(원본으로 역보정용)
     * @param srcW/srcH        원본 프레임 크기
     * @return 원본 좌표계의 bbox 리스트
     */
    fun infer(
        rgba: ByteBuffer,
        scale: Float,
        padX: Int,
        padY: Int,
        srcW: Int,
        srcH: Int
    ): List<Detection>

    override fun close() {}
}

/** MoveNet MultiPose 등을 감싼 ‘머리 박스 추정부’ */
interface PoseModel : AutoCloseable {
    fun open() {}
    /**
     * @param rgba 정사각 레터박스 RGBA
     * @param srcW/srcH 원본 크기
     * @param meta  레터박스 메타(역보정 필요 시)
     * @param roi   현재 타깃 후보(원본 좌표계)
     * @return  머리 Rect + 신뢰도 (없으면 head=null, conf=0)
     */
    fun estimateHead(
        rgba: ByteBuffer,
        srcW: Int,
        srcH: Int,
        meta: GlLetterboxFbo.LetterboxMeta,
        roi: Rect
    ): Keypoints

    override fun close() {}
}

/** MobileFaceNet 등 임베딩 추출기 */
interface FaceEmbedder : AutoCloseable {
    fun open() {}
    /**
     * @param rgba 정사각 레터박스 RGBA
     * @param srcW/srcH 원본 크기
     * @param meta  레터박스 메타
     * @param headRect 원본 좌표계의 얼굴(머리) 박스
     * @return 임베딩 벡터(없으면 null)
     */
    fun embed(
        rgba: ByteBuffer,
        srcW: Int,
        srcH: Int,
        meta: GlLetterboxFbo.LetterboxMeta,
        headRect: Rect
    ): FaceVec?

    override fun close() {}
}
