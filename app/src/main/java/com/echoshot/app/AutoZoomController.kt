// 파일: AutoZoomController.kt
package com.echoshot.app.autozoom

import android.graphics.RectF

class AutoZoomController(
    private val calibrationFrames: Int = 5
) {
    var isActive: Boolean = false
        private set

    private var isCalibrated = false
    private var count = 0
    private var sumW = 0f
    private var sumH = 0f
    private var sumA = 0f

    private var targetRatioW = 0f
    private var targetRatioH = 0f
    private var targetRatioA = 0f

    /** 버튼 토글 시 호출 */
    fun toggle() {
        isActive = !isActive
        if (!isActive) reset()
    }

    /** 내부 상태 리셋 */
    private fun reset() {
        isCalibrated = false
        count = 0; sumW = 0f; sumH = 0f; sumA = 0f
    }

    /**
     * 매 프레임 BoundingBox가 들어올 때 호출.
     * @param currentZoom  현재 적용된 줌 레벨 (1.0f 기준)
     * @param box          감지된 사람의 bounding box
     * @param viewW        SurfaceView.width
     * @param viewH        SurfaceView.height
     * @return              새로 적용할 줌 레벨 or null (변경 불필요)
     */
    fun update(
        currentZoom: Float,
        box: RectF,
        viewW: Int,
        viewH: Int,
        keyPointCount: Int
    ): Float? {
        if (!isActive) return null
        // 예시: keyPointCount가 17이 아닐 땐 신뢰도가 낮다고 보고 보정 스킵
        if (keyPointCount  < 15) return null

        val w = box.width()
        val h = box.height()
        val area = w * h

        // 1) 캘리브레이션 단계
        if (!isCalibrated) {
            sumW += w; sumH += h; sumA += area; count++
            if (count >= calibrationFrames) {
                targetRatioW = (sumW / count) / viewW
                targetRatioH = (sumH / count) / viewH
                targetRatioA = (sumA / count) / (viewW.toFloat() * viewH.toFloat())
                isCalibrated = true
            }
            return null
        }

        // 2) 캘리브레이션 완료 후 → 현재 비율과 비교
        // 2) 캘리브레이션 완료 후 → 현재 비율과 비교
        val curRatioW = w / viewW
        val ratioDiffW = targetRatioW / curRatioW

        // 원시 목표 줌 계산
        val rawNewZoom = currentZoom * ratioDiffW

        //  rawNewZoom < 1.0f 면 즉시 1.0f 반환
        if (rawNewZoom < 1.0f) {
            return 1.0f
        }

        // 최소 1.0f, (필요하면 최대 maxZoom) 사이로 클램프
        val clampedZoom = rawNewZoom.coerceAtLeast(1.0f)

        // 허용 범위(너무 급격한 변화만 보간)
        val changeRatio = clampedZoom / currentZoom
        if (changeRatio < 0.80f || changeRatio > 1.2f) {
            // 기존처럼 부드러운 보간
            return (currentZoom + (clampedZoom - currentZoom) * 0.3f)
                .coerceAtLeast(1.0f)
        }

        // 변화가 작으면 업데이트 불필요
        return null
    }
}
