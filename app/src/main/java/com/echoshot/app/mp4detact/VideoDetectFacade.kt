package com.echoshot.app.mp4detact

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class VideoDetectFacade(
    private val ctx: Context,
    private val gl: GlCtx,
    private val modelPath: String,
    private val useGpu: Boolean = true,
    private val inputSize: Int = 640,
    private val decodeTimeoutUs: Long = 50_000,
    private val warmupDecodeFrames: Int = 2,
    private val warmupYoloRuns: Int = 1,
    private val frameStride: Int = 1,
    private val minInferIntervalMs: Long = 25
) {
    companion object { private const val TAG = "VideoDetectFacade" }

    // 👇 밖에서 읽을 수 있게 공개
    var srcWidth:  Int = 0; private set
    var srcHeight: Int = 0; private set
    var mime: String = "";  private set

    suspend fun run(
        uri: Uri,
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> },
        onDetections: (frameIdx: Int, ptsMs: Long, list: List<Detection>) -> Unit = { _, _, _ -> }
    ) {
        val dec = MediaCodecOesDecoder(ctx, gl)
        val fbo = GlLetterboxFbo(gl, inputSize, inputSize)
        val yolo = YoloTflite(ctx, modelPath, inputSize = inputSize)

        val warmupRgba = ByteBuffer.allocateDirect(inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())

        var frameIdx = 0
        var lastPts = -1L
        var nextInferAllowedAt = 0L

        try {
            gl.makeCurrent()
            yolo.open(useGpu)

            repeat(warmupYoloRuns) {
                warmupRgba.position(0)
                yolo.infer(warmupRgba, 1f, 0, 0, inputSize, inputSize)
            }

            dec.open(uri)

            // 👇 디코더에서 읽어와서 저장(당신의 MediaCodecOesDecoder가 videoW/videoH를 갖고 있다면 그걸 사용)
            srcWidth  = dec.outWidth   // dec.outWidth 라면 거기에 맞춰 바꾸세요
            srcHeight = dec.outHeight   // dec.outHeight 라면 거기에 맞춰 바꾸세요
            mime      = dec.mime     // 없으면 ""로 두어도 됨

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

                // 👇 내부에서도 일관되게 srcWidth/Height 사용
                val meta = fbo.render(f.oesTexId, f.stMatrix, srcWidth, srcHeight)
                val rgba = fbo.readRgbaU8()

                val nowMs = android.os.SystemClock.elapsedRealtime()
                val skip = (frameIdx % frameStride != 0) || (nowMs < nextInferAllowedAt)
                if (skip) { frameIdx++; yield(); continue }
                nextInferAllowedAt = max(nowMs + minInferIntervalMs, nowMs)

                val dets = yolo.infer(
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
            try { yolo.close() } catch (_: Throwable) {}
            try { fbo.release() } catch (_: Throwable) {}
        }
    }
}

