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
            mime      = dec.mime

            Log.i(TAG, "Decoder opened: ${srcWidth}x${srcHeight} mime=$mime")

            repeat(warmupDecodeFrames) { dec.decodeNext(15_000) }

            while (!dec.isEos) {
                val f = dec.decodeNext(decodeTimeoutUs)
                if (f == null) {
                    yield()
                    continue
                }

                lastPts = f.ptsMs
                onProgress(frameIdx, f.ptsMs)

                val meta = fbo.render(f.oesTexId, f.stMatrix, srcWidth, srcHeight)
                val rgba = fbo.readRgbaU8()

                val nowMs = SystemClock.elapsedRealtime()
                val skip = (frameIdx % frameStride != 0) || (nowMs < nextInferAllowedAt)
                if (skip) { frameIdx++; yield(); continue }
                nextInferAllowedAt = max(nowMs + minInferIntervalMs, nowMs)

                val dets = pose.infer(
                    rgba = rgba,
                    scale = meta.scale,
                    padX = meta.padX,
                    padY = meta.padY,
                    srcW = meta.srcW,
                    srcH = meta.srcH,
                )

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

