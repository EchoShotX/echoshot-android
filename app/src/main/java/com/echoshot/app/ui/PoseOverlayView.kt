// 파일: PoseOverlayView.kt
package com.echoshot.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.echoshot.app.data.BodyPart
import com.echoshot.app.data.Person

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** 외부에서 할당되는 사람 리스트 */
    var people: List<Person> = emptyList()

    /** 키포인트(점) 그리기용 페인트 */
    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }

    /** 스켈레톤 선 그리기용 페인트 */
    private val linePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    /** 바운딩박스 그리기용 페인트 */
    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    /** 연결할 키포인트 쌍 (스켈레톤) */
    private val skeletonConnections = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (person in people) {
            // 1) 바운딩 박스
            person.boundingBox?.let { box ->
                canvas.drawRect(box.left, box.top, box.right, box.bottom, boxPaint)
            }

            // 2) 스켈레톤 라인
            skeletonConnections.forEach { (partA, partB) ->
                val kpA = person.keyPoints.firstOrNull { it.bodyPart == partA }
                val kpB = person.keyPoints.firstOrNull { it.bodyPart == partB }
                if (kpA != null && kpB != null &&
                    kpA.score > 0.2f && kpB.score > 0.2f) {
                    canvas.drawLine(
                        kpA.coordinate.x, kpA.coordinate.y,
                        kpB.coordinate.x, kpB.coordinate.y,
                        linePaint
                    )
                }
            }

            // 3) 키포인트(점)
            person.keyPoints.forEach { kp ->
                if (kp.score > 0.2f) {
                    canvas.drawCircle(
                        kp.coordinate.x,
                        kp.coordinate.y,
                        8f,
                        pointPaint
                    )
                }
            }
        }
    }
}
