package com.echoshot.app.mp4detact.models.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.echoshot.app.data.Device
import com.echoshot.app.ml.MoveNetMultiPose
import com.echoshot.app.ml.Type
import com.echoshot.app.ml.TrackerType
import com.echoshot.app.mp4detact.data.Keypoints
import com.echoshot.app.mp4detact.engine.GlLetterboxFbo
import com.echoshot.app.mp4detact.models.PoseModel
import org.opencv.core.Rect
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PoseMoveNetAdapter(
    ctx: Context,
    private val letterboxInputSize: Int = 640,
) : PoseModel {

    companion object { private const val TAG = "PoseMoveNetAdapter" }

    private val impl: MoveNetMultiPose = MoveNetMultiPose.create(
        context = ctx,
        device = Device.CPU,
        type = Type.Dynamic
    ).apply {
        setTracker(TrackerType.OFF)
    }

    override fun open() {
        // [LOG]
        Log.i(TAG, "open() inputSize=$letterboxInputSize")
    }

    override fun close() {
        Log.i(TAG, "close()")
        impl.close()
    }

    override fun estimateHead(
        rgba: ByteBuffer,
        srcW: Int,
        srcH: Int,
        meta: GlLetterboxFbo.LetterboxMeta,
        roi: Rect
    ): Keypoints {
        // [LOG]
        Log.d(TAG, "estimateHead() src=${srcW}x$srcH meta(scale=${meta.scale}, padX=${meta.padX}, padY=${meta.padY}) roi=$roi")

        val t0 = System.nanoTime()

        val local = rgba.duplicate()
        local.clear()

        // ROI 영역만 크롭해서 pose estimation 수행
        val croppedBmp = cropROIFromLetterbox(local, letterboxInputSize, letterboxInputSize, roi, meta)
        if (croppedBmp == null) {
            Log.w(TAG, "estimateHead() failed to crop ROI")
            return Keypoints(null, 0f)
        }

        val persons = impl.estimatePoses(croppedBmp)
        Log.d(TAG, "estimateHead() persons.size=${persons.size}")

        if (persons.isEmpty()) {
            val ms = (System.nanoTime() - t0) / 1e6
            Log.i(TAG, "estimateHead() no person in ${"%.2f".format(ms)} ms")
            croppedBmp.recycle()
            return Keypoints(null, 0f)
        }

        // 크롭된 영역에서는 첫 번째 사람을 선택 (ROI 내에서 가장 큰 사람)
        var bestIdx = 0
        var bestArea = 0f
        for (i in persons.indices) {
            val p = persons[i]
            val r = p.boundingBox ?: continue
            val area = (r.right - r.left) * (r.bottom - r.top)
            if (area > bestArea) {
                bestArea = area
                bestIdx = i
            }
        }

        val picked = persons[bestIdx]
        val head = estimateHeadBoxFromPerson(picked, roi.width, roi.height)
        
        // 크롭된 영역 기준의 얼굴 박스를 원본 프레임 좌표로 변환
        val headInOriginal = if (head != null) {
            // 크롭된 영역의 스케일 팩터 계산
            val scaleX = meta.srcW.toFloat() / letterboxInputSize
            val scaleY = meta.srcH.toFloat() / letterboxInputSize
            
            Rect(
                (head.x * scaleX + roi.x).toInt(),
                (head.y * scaleY + roi.y).toInt(),
                (head.width * scaleX).toInt(),
                (head.height * scaleY).toInt()
            )
        } else null

        val ms = (System.nanoTime() - t0) / 1e6
        Log.i(TAG, "estimateHead() pick#$bestIdx personScore=${"%.3f".format(picked.score)} head=$headInOriginal in ${"%.2f".format(ms)} ms")

        return Keypoints(headInOriginal, picked.score)
    }

    // ---------- utils ----------

    /** ROI 영역을 크롭해서 새로운 비트맵 생성 */
    private fun cropROIFromLetterbox(
        rgba: ByteBuffer,
        letterboxW: Int,
        letterboxH: Int,
        roi: Rect,
        meta: GlLetterboxFbo.LetterboxMeta
    ): Bitmap? {
        try {
            // 전체 레터박스 비트맵 생성
            val fullBmp = rgbaLetterboxToBitmap(rgba, letterboxW, letterboxH)
            
            // ROI 영역을 레터박스 좌표로 변환
            val scaleX = letterboxW.toFloat() / meta.srcW
            val scaleY = letterboxH.toFloat() / meta.srcH
            val roiX = (roi.x * scaleX).toInt().coerceIn(0, letterboxW - 1)
            val roiY = (roi.y * scaleY).toInt().coerceIn(0, letterboxH - 1)
            val roiW = (roi.width * scaleX).toInt().coerceIn(1, letterboxW - roiX)
            val roiH = (roi.height * scaleY).toInt().coerceIn(1, letterboxH - roiY)
            
            // ROI 영역 크롭
            val cropped = Bitmap.createBitmap(fullBmp, roiX, roiY, roiW, roiH)
            fullBmp.recycle()
            
            return cropped
        } catch (e: Exception) {
            Log.e(TAG, "cropROIFromLetterbox failed", e)
            return null
        }
    }

    private fun rgbaLetterboxToBitmap(rgba: ByteBuffer, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val intBuf = IntArray(w * h)
        var k = 0
        while (k < intBuf.size) {
            val r = rgba.get().toInt() and 0xFF
            val g = rgba.get().toInt() and 0xFF
            val b = rgba.get().toInt() and 0xFF
            rgba.get() // A skip
            intBuf[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(intBuf, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun estimateHeadBoxFromPerson(
        p: com.echoshot.app.data.Person,
        lbW: Int,
        lbH: Int
    ): Rect? {
        fun kp(i: Int) = p.keyPoints.getOrNull(i)
        // 1) 얼굴 키포인트 더 완화 (0.1 -> 0.05)
        val FACE_THR = 0.05f

        val faceKps = listOfNotNull(kp(0), kp(1), kp(2), kp(3), kp(4))
        val facePts = faceKps.filter { it.score >= FACE_THR }.map { it.coordinate }

        // 디버그: 얼굴 키포인트 점수 덤프
        try {
            Log.d(TAG, "face scores=" +
                    faceKps.mapIndexed { idx, k -> "${idx}:${"%.2f".format(k.score)}" }
                        .joinToString(", ")
            )
        } catch (_: Throwable) { /* no-op */ }

        val bbox = p.boundingBox
        val bb = if (bbox != null) {
            Rect(
                bbox.left.roundToInt().coerceIn(0, lbW - 1),
                bbox.top.roundToInt().coerceIn(0, lbH - 1),
                (bbox.right - bbox.left).roundToInt().coerceAtLeast(2),
                (bbox.bottom - bbox.top).roundToInt().coerceAtLeast(2)
            )
        } else null

        // --- 케이스 A: 얼굴 키포인트가 조금이라도 있다면 그걸 우선 활용 ---
        if (facePts.isNotEmpty()) {
            val cx = facePts.map { it.x }.average().toFloat()
            val cy = facePts.map { it.y }.average().toFloat()

            // 얼굴 키포인트 간 최대 거리, 어깨 폭
            var maxd = 0f
            for (i in facePts.indices) for (j in i + 1 until facePts.size) {
                val dx = facePts[i].x - facePts[j].x
                val dy = facePts[i].y - facePts[j].y
                maxd = max(maxd, hypot(dx, dy))
            }
            val lsh = kp(5); val rsh = kp(6)
            val shoulder = if (lsh != null && rsh != null && lsh.score >= 0.2f && rsh.score >= 0.2f) {
                hypot(lsh.coordinate.x - rsh.coordinate.x, lsh.coordinate.y - rsh.coordinate.y)
            } else 0f

            val w = max(maxd * 4.0f, shoulder * 1.6f).coerceAtLeast(20f)  // 최소 크기 증가
            val h = (w * 1.2f)

            val x1 = (cx - w / 2f).roundToInt().coerceIn(0, lbW - 2)
            val y1 = (cy - h * 0.55f).roundToInt().coerceIn(0, lbH - 2)
            val x2 = (cx + w / 2f).roundToInt().coerceIn(x1 + 2, lbW - 1)
            val y2 = (cy + h * 0.45f).roundToInt().coerceIn(y1 + 2, lbH - 1)

            val box = Rect(x1, y1, x2 - x1, y2 - y1)
            Log.d(TAG, "head(A:face) cx=${"%.1f".format(cx)}, cy=${"%.1f".format(cy)}, w=${"%.1f".format(w)}, h=${"%.1f".format(h)} → $box")
            return box
        }

        // --- 케이스 B: 얼굴이 전혀 없으면, 어깨 + bbox로 추정 ---
        val lsh = kp(5); val rsh = kp(6)
        if (lsh != null && rsh != null && lsh.score >= 0.2f && rsh.score >= 0.2f) {
            val sx = (lsh.coordinate.x + rsh.coordinate.x) / 2f
            val sy = (lsh.coordinate.y + rsh.coordinate.y) / 2f
            val shoulder = hypot(lsh.coordinate.x - rsh.coordinate.x, lsh.coordinate.y - rsh.coordinate.y).coerceAtLeast(1f)

            // 어깨 폭을 기준으로 머리 크기 추정 (2배 넓게)
            val w = (shoulder * 1.8f).coerceIn(16f, lbW.toFloat())  // 2배 넓게
            val h = (w * 1.15f)

            // 머리 중심은 어깨 중심보다 위쪽으로 약간 올림
            val cx = sx
            val cy = sy - (h * 0.75f)

            val x1 = (cx - w / 2f).roundToInt().coerceIn(0, lbW - 2)
            val y1 = (cy - h / 2f).roundToInt().coerceIn(0, lbH - 2)
            val x2 = (cx + w / 2f).roundToInt().coerceIn(x1 + 2, lbW - 1)
            val y2 = (cy + h / 2f).roundToInt().coerceIn(y1 + 2, lbH - 1)

            val box = Rect(x1, y1, x2 - x1, y2 - y1)
            Log.d(TAG, "head(B:shoulder) shoulder=${"%.1f".format(shoulder)} → $box")
            return box
        }

        // --- 케이스 C: 어깨도 불가 → bbox 최상단 영역을 머리로 가정 ---
        if (bb != null) {
            val headH = (bb.height * 0.40f).coerceAtLeast(16f) // 상단 40%, 최소 크기 증가
            val x1 = bb.x
            val y1 = bb.y
            val x2 = (bb.x + bb.width).coerceAtMost(lbW - 1)
            val y2 = (bb.y + headH.roundToInt()).coerceAtMost(lbH - 1)

            if (x2 > x1 + 1 && y2 > y1 + 1) {
                val box = Rect(x1, y1, x2 - x1, y2 - y1)
                Log.d(TAG, "head(C:bbox-top) bb=$bb → $box")
                return box
            }
        }

        Log.d(TAG, "estimateHeadBoxFromPersonLb: fallback failed (no face/shoulder/bbox)")
        return null
    }

    private fun iou(a: Rect, b: Rect): Float {
        val x1 = max(a.x, b.x); val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.width, b.x + b.width)
        val y2 = min(a.y + a.height, b.y + b.height)
        val inter = max(0, x2 - x1) * max(0, y2 - y1)
        val ua = a.width * a.height + b.width * b.height - inter
        return if (ua <= 0) 0f else inter.toFloat() / ua.toFloat()
    }

    private fun centerDist(a: Rect, b: Rect): Float {
        val ax = a.x + a.width / 2f; val ay = a.y + a.height / 2f
        val bx = b.x + b.width / 2f; val by = b.y + b.height / 2f
        return hypot(ax - bx, ay - by)
    }
}
