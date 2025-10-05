package com.echoshot.app.mp4detact.tracking

import org.opencv.core.Rect
import kotlin.math.roundToInt
import com.echoshot.app.mp4detact.data.clampRect
import com.echoshot.app.mp4detact.data.centerX
import com.echoshot.app.mp4detact.data.centerY

/** 현재 타깃의 박스/속도/상태 보관 */
data class TrackBox(
    var rect: Rect,
    var vx: Float = 0f,                 // px/frame
    var vy: Float = 0f,                 // px/frame
    var state: TrackState = TrackState.TRACKING,
    var lostCount: Int = 0,             // 매칭 실패 누적
    var lastUpdateFrame: Int = 0
) {
    /** 새 박스로 갱신하며 속도 추정 */
    fun updateWith(newRect: Rect, frameIdx: Int) {
        val cx0 = centerX(rect); val cy0 = centerY(rect)
        val cx1 = centerX(newRect); val cy1 = centerY(newRect)
        vx = (cx1 - cx0)
        vy = (cy1 - cy0)
        rect = newRect
        lostCount = 0
        lastUpdateFrame = frameIdx
    }

    /** 단순 관성 예측(매칭 실패 시) */
    fun predict(W: Int, H: Int): Rect {
        val cx = centerX(rect) + vx
        val cy = centerY(rect) + vy
        val out = Rect(
            (cx - rect.width / 2f).roundToInt(),
            (cy - rect.height/ 2f).roundToInt(),
            rect.width, rect.height
        )
        return clampRect(out, W, H)
    }
}
