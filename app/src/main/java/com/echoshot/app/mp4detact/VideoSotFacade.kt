// VideoSotFacade.kt
package com.echoshot.app.mp4detact

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.yield
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * MP4 → (레터박스 640×640) → SiamRPN 추적 호출까지를 캡슐화한 파사드.
 * - 디코딩, 레터박스, RGBA→GRAY 변환까지 담당
 * - 트래커는 SiamRPN 백엔드(SiamRpnTs)를 사용
 */
class VideoSotFacade(
    private val ctx: Context,
    private val gl: GlCtx,
    private val modelAssetName: String = "siamrpnpp_mobile.ptl",
    private val inputSize: Int = 640,
    private val decodeTimeoutUs: Long = 50_000,
    private val warmupDecodeFrames: Int = 1,
    private val frameStride: Int = 1,
    private val minInferIntervalMs: Long = 0L,  // 필요 시 rate limit
    private val exemplar: Int = 127,
    private val instance: Int = 255
) {
    companion object { private const val TAG = "VideoSotFacade" }

    // 외부에서 조회
    var srcWidth:  Int = 0; private set
    var srcHeight: Int = 0; private set
    var mime: String = "";  private set

    /**
     * @param uri 비디오 URI
     * @param initBboxSrc 원본 좌표계 초기 박스
     * @param onProgress 진행 콜백
     * @param onTracked (frameIdx, ptsMs, trackedBoxSrc, score) – src 좌표계로 전달
     * @param onInitLogged 초기 프레임에서 init 상자 기록 필요 시 콜백
     */
    suspend fun run(
        uri: Uri,
        initBboxSrc: org.opencv.core.Rect,
        onProgress: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> },
        onTracked: (frameIdx: Int, ptsMs: Long, rectSrc: org.opencv.core.Rect?, score: Float) -> Unit = { _, _, _, _ -> },
        onInitLogged: (frameIdx: Int, ptsMs: Long) -> Unit = { _, _ -> }
    ) {
        val decoder = MediaCodecOesDecoder(ctx, gl)
        val fbo = GlLetterboxFbo(gl, inputSize, inputSize)

        // SiamRPN 백엔드 (TorchScript)
        val tracker = SiamRpnTs(
            ctx = ctx,
            modelAssetName = modelAssetName,
            EXEMPLAR = exemplar,
            INSTANCE = instance
        )

        // GRAY 변환 스크래치
        val gray = Mat(inputSize, inputSize, CvType.CV_8UC1)
        val rgbaScratch = ByteBuffer
            .allocateDirect(inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())

        var frameIdx = -1
        var nextInferAllowedAt = 0L

        try {
            gl.makeCurrent()
            decoder.open(uri)

            // 원본 메타
            srcWidth  = decoder.outWidth
            srcHeight = decoder.outHeight
            mime      = decoder.mime
            Log.i(TAG, "Decoder opened: ${srcWidth}x${srcHeight} mime=$mime")

            // 트래커 준비
            tracker.open()

            repeat(warmupDecodeFrames) { decoder.decodeNext(15_000) }

            var templateSet = false

            while (!decoder.isEos) {
                val fr = decoder.decodeNext(decodeTimeoutUs)
                if (fr == null) {
                    yield()
                    continue
                }
                frameIdx += 1
                onProgress(frameIdx, fr.ptsMs)

                // 1) 레터박스 렌더 (→ 640×640 RGBA)
                val meta = fbo.render(fr.oesTexId, fr.stMatrix, srcWidth, srcHeight)
                val rgba = fbo.readRgbaU8() // position=0 보장

                // DEBUG: 레터박스 스케일/패딩과 원본 크기
                Log.d("SOT/LB", "scale=${meta.scale} pad=(${meta.padX},${meta.padY}) src=${meta.srcW}x${meta.srcH}")

                // 2) init 템플릿 세팅(첫 유효 프레임에 한 번만)
                if (!templateSet) {
                    val initLb = mapSrcToLb(initBboxSrc, meta)
                    val backToSrc = mapLbToSrc(initLb, meta)
                    Log.d("SOT/MAP", "init src=$initBboxSrc -> lb=$initLb -> src'=$backToSrc")
                    // RGBA → GRAY(640)
                    rgbaToGray(rgba, gray)
                    tracker.setTemplate(initLb, gray)
                    onInitLogged(frameIdx, fr.ptsMs)
                    templateSet = true
                    continue
                }

                // 3) 프레임 스킵 / 인터벌 제어
                val now = android.os.SystemClock.elapsedRealtime()
                val skip = (frameIdx % frameStride != 0) || (now < nextInferAllowedAt)
                if (skip) { yield(); continue }
                nextInferAllowedAt = max(now + minInferIntervalMs, now)

                // 4) RGBA → GRAY → track
                rgbaToGray(rgba, gray)
                val (rectLb, score) = tracker.track(gray)

                // 5) 레터박스 → 원본 좌표 복원
                val rectSrc = rectLb?.let { clampRectToSrc(mapLbToSrc(it, meta), srcWidth, srcHeight) }

                onTracked(frameIdx, fr.ptsMs, rectSrc, score)
                yield()
            }
        } finally {
            try { tracker.close() } catch (_: Throwable) {}
            try { decoder.close() } catch (_: Throwable) {}
            try { fbo.release() } catch (_: Throwable) {}
            try { gray.release() } catch (_: Throwable) {}
        }
    }

    // ---------- 보조 ----------

    private fun rgbaToGray(rgba: ByteBuffer, grayOut: Mat) {
        // ByteBuffer → Mat(CV_8UC4)
        val src = Mat(grayOut.rows(), grayOut.cols(), CvType.CV_8UC4)
        val tmpBytes = ByteArray(grayOut.rows() * grayOut.cols() * 4)
        rgba.position(0); rgba.get(tmpBytes); rgba.rewind()
        src.put(0, 0, tmpBytes)
        Imgproc.cvtColor(src, grayOut, Imgproc.COLOR_RGBA2GRAY)
        src.release()
    }

    private fun mapSrcToLb(rSrc: org.opencv.core.Rect, m: GlLetterboxFbo.LetterboxMeta): org.opencv.core.Rect {
        val x = (rSrc.x * m.scale + m.padX).toInt()
        val y = (rSrc.y * m.scale + m.padY).toInt()
        val w = (rSrc.width  * m.scale).toInt()
        val h = (rSrc.height * m.scale).toInt()
        return org.opencv.core.Rect(x, y, max(1, w), max(1, h))
    }

    private fun mapLbToSrc(rLb: org.opencv.core.Rect, m: GlLetterboxFbo.LetterboxMeta): org.opencv.core.Rect {
        val x = ((rLb.x - m.padX) / m.scale).toInt()
        val y = ((rLb.y - m.padY) / m.scale).toInt()
        val w = (rLb.width  / m.scale).toInt()
        val h = (rLb.height / m.scale).toInt()
        return org.opencv.core.Rect(x, y, max(1, w), max(1, h))
    }

    private fun clampRectToSrc(r: org.opencv.core.Rect, srcW: Int, srcH: Int): org.opencv.core.Rect {
        val x1 = clamp(r.x, 0, srcW)
        val y1 = clamp(r.y, 0, srcH)
        val x2 = clamp(r.x + r.width, 0, srcW)
        val y2 = clamp(r.y + r.height, 0, srcH)
        val w = max(1, x2 - x1)
        val h = max(1, y2 - y1)
        return org.opencv.core.Rect(x1, y1, w, h)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int =
        if (v < lo) lo else if (v > hi) hi else v
}
