package com.echoshot.app.mp4detact

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.echoshot.app.mp4detact.data.PoseDetection
import com.echoshot.app.mp4detact.models.pose.Yolo11PoseTflite
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * YOLO11n-pose 기반 비디오 포즈 감지 파사드
 * 
 * VideoDetectFacade와 동일한 구조이나, PoseDetection (keypoints 포함)을 출력
 */
class VideoPoseDetectFacade(
    private val ctx: Context,
    private val gl: GlCtx,
    private val modelPath: String = "yolo11n-pose_float16.tflite",
    private val useGpu: Boolean = true,
    private val inputSize: Int = 640,
    private val decodeTimeoutUs: Long = 50_000,
    private val warmupDecodeFrames: Int = 2,
    private val warmupYoloRuns: Int = 1,
    private val frameStride: Int = 1,
    private val minInferIntervalMs: Long = 25
) {
    companion object { private const val TAG = "VideoPoseDetect" }

    // 밖에서 읽을 수 있게 공개
    var srcWidth:  Int = 0; private set
    var srcHeight: Int = 0; private set
    var rotation:  Int = 0; private set
    var mime: String = "";  private set

    suspend fun run(
        uri: Uri,
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> },
        onDetections: (frameIdx: Int, ptsMs: Long, list: List<PoseDetection>) -> Unit = { _, _, _ -> }
    ) {
        val dec = MediaCodecOesDecoder(ctx, gl)
        val fbo = GlLetterboxFbo(gl, inputSize, inputSize)
        val pose = Yolo11PoseTflite(ctx, modelPath, inputSize = inputSize)

        val warmupRgba = ByteBuffer.allocateDirect(inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())

        var frameIdx = 0
        var lastPts = -1L
        var nextInferAllowedAt = 0L

        try {
            gl.makeCurrent()
            pose.open(useGpu)

            repeat(warmupYoloRuns) {
                warmupRgba.position(0)
                pose.infer(warmupRgba, 1f, 0, 0, inputSize, inputSize)
            }

            dec.open(uri)

            srcWidth  = dec.outWidth
            srcHeight = dec.outHeight
            rotation  = dec.rotation
            mime      = dec.mime

            Log.i(TAG, "Decoder opened: ${srcWidth}x${srcHeight} rot=$rotation mime=$mime")

            repeat(warmupDecodeFrames) { dec.decodeNext(15_000) }

            while (!dec.isEos) {
                val f = dec.decodeNext(decodeTimeoutUs)
                if (f == null) {
                    yield()
                    continue
                }

                lastPts = f.ptsMs
                onProgress(frameIdx, f.ptsMs)

                // Use the class property rotation instead of local dec.rotation
                val isPortrait = rotation == 90 || rotation == 270
                val effSrcW = if (isPortrait) srcHeight else srcWidth
                val effSrcH = if (isPortrait) srcWidth else srcHeight

                val finalStMatrix = FloatArray(16)
                if (rotation != 0) {
                    val uvMatrix = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(uvMatrix, 0)
                    android.opengl.Matrix.translateM(uvMatrix, 0, 0.5f, 0.5f, 0f)
                    android.opengl.Matrix.rotateM(uvMatrix, 0, -rotation.toFloat(), 0f, 0f, 1f)
                    android.opengl.Matrix.translateM(uvMatrix, 0, -0.5f, -0.5f, 0f)
                    android.opengl.Matrix.multiplyMM(finalStMatrix, 0, f.stMatrix, 0, uvMatrix, 0)
                } else {
                    System.arraycopy(f.stMatrix, 0, finalStMatrix, 0, 16)
                }

                val meta = fbo.render(f.oesTexId, finalStMatrix, effSrcW, effSrcH)
                val rgba = fbo.readRgbaU8()

                val nowMs = SystemClock.elapsedRealtime()
                val skip = (frameIdx % frameStride != 0) || (nowMs < nextInferAllowedAt)
                if (skip) { frameIdx++; yield(); continue }
                nextInferAllowedAt = max(nowMs + minInferIntervalMs, nowMs)

                val detsRaw = pose.infer(
                    rgba = rgba,
                    scale = meta.scale,
                    padX = meta.padX,
                    padY = meta.padY,
                    srcW = meta.srcW,
                    srcH = meta.srcH,
                )

                val dets = if (rotation == 0) detsRaw else {
                    detsRaw.map { det ->
                        fun invPt(px: Float, py: Float): Pair<Float, Float> {
                            return when (rotation) {
                                90 -> Pair(py, srcHeight.toFloat() - px)
                                180 -> Pair(srcWidth.toFloat() - px, srcHeight.toFloat() - py)
                                270 -> Pair(srcWidth.toFloat() - py, px)
                                else -> Pair(px, py)
                            }
                        }

                        val p1 = invPt(det.x1, det.y1)
                        val p2 = invPt(det.x2, det.y2)
                        val p3 = invPt(det.x1, det.y2)
                        val p4 = invPt(det.x2, det.y1)

                        val nx1 = minOf(p1.first, p2.first, p3.first, p4.first)
                        val nx2 = maxOf(p1.first, p2.first, p3.first, p4.first)
                        val ny1 = minOf(p1.second, p2.second, p3.second, p4.second)
                        val ny2 = maxOf(p1.second, p2.second, p3.second, p4.second)

                        val nKps = det.keypoints.map { kp ->
                            val pt = invPt(kp.x, kp.y)
                            com.echoshot.app.mp4detact.data.Keypoint(pt.first, pt.second, kp.conf)
                        }

                        det.copy(x1 = nx1, y1 = ny1, x2 = nx2, y2 = ny2, keypoints = nKps)
                    }
                }

                onDetections(frameIdx, f.ptsMs, dets)
                frameIdx++
                yield()
            }

            Log.i(TAG, "Done. frames=$frameIdx lastPts=$lastPts ms")
        } finally {
            try { dec.close() } catch (_: Throwable) {}
            try { pose.close() } catch (_: Throwable) {}
            try { fbo.release() } catch (_: Throwable) {}
        }
    }
}
