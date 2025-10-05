package com.echoshot.app.mp4detact.pipeline

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.echoshot.app.mp4detact.data.*
import com.echoshot.app.mp4detact.engine.*
import com.echoshot.app.mp4detact.models.yolo.YoloTflite
import com.echoshot.app.mp4detact.models.pose.PoseMoveNetAdapter
import com.echoshot.app.mp4detact.models.face.FaceEmbedder
import com.echoshot.app.mp4detact.io.JsonLogger
import com.echoshot.app.mp4detact.tracking.StateMachine
import com.echoshot.app.mp4detact.engine.GlLetterboxFbo.LetterboxMeta
import kotlinx.coroutines.yield
import org.opencv.core.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 단일 패스 디코드 루프에서
 *  - N프레임 간격으로 Detector 실행
 *  - 상태머신(StateMachine)으로 추적/REID 이벤트 처리
 *  - Pose/Face는 StateMachine의 콜백으로 “필요할 때만” 실행
 *  - 매 프레임 JSONL 로깅
 */
class HybridProcessor(
    private val ctx: Context,
    private val gl: GlCtx,
    private val yolo: YoloTflite,
    private val poseModel: PoseMoveNetAdapter,
    private val faceModel: FaceEmbedder,
    private val cfg: HybridConfig = HybridConfig()
) {
    companion object { private const val TAG = "HybridProcessor" }

    // ---------- small utils ----------
    private fun ns() = System.nanoTime()
    private fun Long.ms() = this / 1_000_000.0
    private fun Rect.str(): String = "(${x},${y},${x+max(0,width)},${y+max(0,height)})"
    private fun Rect.area(): Int = max(0, width) * max(0, height)
    private fun detSample(ds: List<Detection>): String =
        if (ds.isEmpty()) "[]" else "[(${ds[0].x1},${ds[0].y1})-(${ds[0].x2},${ds[0].y2}) s=${"%.2f".format(ds[0].score)}]"

    /** 최신 프레임 RGBA와 letterbox 메타를 콜백에서 참조하기 위한 홀더 */
    private class CurFrame {
        var rgba: ByteBuffer? = null
        var meta: LetterboxMeta? = null
        var srcW: Int = 0
        var srcH: Int = 0
        fun reset() { rgba = null; meta = null }
    }

    /**
     * 한 번(One-pass) 처리
     */
    suspend fun runOnePass(
        uri: Uri,
        userInitBox: Rect,
        logger: JsonLogger
    ) {
        val dec = MediaCodecOesDecoder(ctx, gl)
        val fbo = GlLetterboxFbo(gl, cfg.inputSize, cfg.inputSize)

        // 최신 프레임 컨텍스트 보관 (StateMachine 콜백이 참조)
        val cur = CurFrame()

        // Pose/Face 콜백 구현 (StateMachine에 전달)
        val poseHeadCb: (Rect) -> Keypoints = cb@ { roiOriginal ->
            val rgba = cur.rgba ?: run {
                Log.d(TAG, "[POSE] skip: rgba=null")
                return@cb Keypoints(null, 0f)
            }
            val meta = cur.meta ?: run {
                Log.d(TAG, "[POSE] skip: meta=null")
                return@cb Keypoints(null, 0f)
            }
            val t0 = ns()
            val kp = poseModel.estimateHead(rgba, cur.srcW, cur.srcH, meta, roiOriginal)
            val dt = ns() - t0
            if (kp.head != null) {
                Log.d(
                    TAG,
                    "[POSE] roi=${roiOriginal.str()} -> head=${kp.head!!.str()} area=${kp.head!!.area()} conf=${"%.2f".format(kp.conf)} in ${dt.ms()} ms"
                )
            } else {
                Log.d(TAG, "[POSE] roi=${roiOriginal.str()} -> NO HEAD in ${dt.ms()} ms (conf=${"%.2f".format(kp.conf)})")
            }
            kp
        }

        val faceEmbedCb: (Rect) -> FaceVec? = fb@ { headBoxOriginal ->
            val rgba = cur.rgba ?: run {
                Log.d(TAG, "[FACE] skip: rgba=null")
                return@fb null
            }
            val meta = cur.meta ?: run {
                Log.d(TAG, "[FACE] skip: meta=null")
                return@fb null
            }
            val bufW = cfg.inputSize
            val bufH = cfg.inputSize

            val t0 = ns()
            val fv = faceModel.embedFromLetterbox(
                rgba = rgba,
                bufferW = bufW,
                bufferH = bufH,
                meta = meta,
                headBoxOriginal = headBoxOriginal
            )
            val dt = ns() - t0
            if (fv != null) {
                Log.d(
                    TAG,
                    "[FACE] head=${headBoxOriginal.str()} -> emb[${fv.v.size}] ok in ${dt.ms()} ms"
                )
            } else {
                Log.d(TAG, "[FACE] head=${headBoxOriginal.str()} -> EMBEDDING FAIL in ${dt.ms()} ms")
            }
            fv
        }

        val sm = StateMachine(cfg, /*imgW*/0, /*imgH*/0, poseHeadCb, faceEmbedCb)

        // 준비
        val warmupRgba = ByteBuffer.allocateDirect(cfg.inputSize * cfg.inputSize * 4)
            .order(ByteOrder.nativeOrder())

        var frameIdx = 0
        var lastPts = 0L
        var nextInferAllowedAt = 0L

        try {
            gl.makeCurrent()
            yolo.open(cfg.useGpu)
            poseModel.open()
            faceModel.open()

            // YOLO 워밍업(선택)
            repeat(cfg.warmupYoloRuns) {
                yolo.infer(warmupRgba, 1f, 0, 0, cfg.inputSize, cfg.inputSize)
            }

            // 디코더 시작
            dec.open(uri)
            val imgW = dec.outWidth
            val imgH = dec.outHeight
            Log.i(TAG, "Video opened ${imgW}x${imgH}, inputSize=${cfg.inputSize}")

            // StateMachine 재생성(실제 소스 크기 반영)
            val sm2 = StateMachine(cfg, imgW, imgH, poseHeadCb, faceEmbedCb)

            // 초기화: 사용자 박스로 직접 시작 (폴백 로직)
            Log.i(TAG, "Initializing with user box: ${userInitBox.x},${userInitBox.y},${userInitBox.width},${userInitBox.height}")
            sm2.initWith(userInitBox, frameIdx)
            Log.i(TAG, "StateMachine initialized with user box")

            // 디코드 루프
            repeat(cfg.warmupDecodeFrames) { dec.decodeNext(15_000) }

            while (!dec.isEos) {
                val f = dec.decodeNext(cfg.decodeTimeoutUs)
                if (f == null) {
                    yield()
                    continue
                }
                lastPts = f.ptsMs

                // GL letterbox 렌더 → RGBA 픽셀
                val tR0 = ns()
                val meta = fbo.render(f.oesTexId, f.stMatrix, imgW, imgH)
                val rgba = fbo.readRgbaU8()
                val tR = ns() - tR0
                Log.d(TAG, "[RENDER] frame=$frameIdx pts=${f.ptsMs}ms meta(scale=${"%.4f".format(meta.scale)}, pad=${meta.padX},${meta.padY}) in ${tR.ms()} ms")

                // 최신 프레임 컨텍스트 갱신
                cur.rgba = rgba
                cur.meta = meta
                cur.srcW = imgW
                cur.srcH = imgH

                // Detector 주기 제어
                val nowMs = SystemClock.elapsedRealtime()
                val doDetect = (frameIdx % cfg.detStride == 0) && (nowMs >= nextInferAllowedAt)
                Log.d(TAG, "[DETECT] frame=$frameIdx doDetect=$doDetect (stride=${cfg.detStride}, nowMs=$nowMs, nextAllowed=$nextInferAllowedAt)")

                val dets: List<Detection> =
                    if (doDetect) {
                        val t0 = ns()
                        nextInferAllowedAt = max(nowMs + cfg.minInferIntervalMs, nowMs)
                        Log.d(TAG, "[YOLO] Starting inference for frame $frameIdx")
                        val out = yolo.infer(
                            rgba = rgba,
                            scale = meta.scale,
                            padX = meta.padX,
                            padY = meta.padY,
                            srcW = meta.srcW,
                            srcH = meta.srcH
                        )
                        val dt = ns() - t0
                        Log.i(TAG, "[YOLO] Completed inference: ${out.size} detections in ${dt.ms()} ms")
                        Log.d(
                            TAG,
                            "[DETECT] frame=$frameIdx persons=${out.size} sample=${detSample(out)} in ${dt.ms()} ms"
                        )
                        
                        // ReID 로직: 여러 디텍션이 있고 겹치는 경우 얼굴 비교
                        val finalDets = if (out.size > 1) {
                            val overlapping = findOverlappingDetections(out)
                            if (overlapping.size >= 2) {
                                Log.d(TAG, "[REID] ${overlapping.size} overlapping detections found")
                                val reidResult = sm2.performReid(overlapping)
                                if (reidResult != null) {
                                    Log.d(TAG, "[REID] Selected detection: ${reidResult.x1},${reidResult.y1},${reidResult.x2},${reidResult.y2}")
                                    listOf(reidResult)
                                } else {
                                    Log.d(TAG, "[REID] No suitable match found, using original detections")
                                    out
                                }
                            } else {
                                out
                            }
                        } else {
                            out
                        }
                        
                        finalDets
                    } else {
                        Log.d(TAG, "[DETECT] frame=$frameIdx skip (stride=${cfg.detStride})")
                        emptyList()
                    }

                // 상태 업데이트 + 로깅
                val tS0 = ns()
                Log.i(TAG, "[STATE] Processing frame $frameIdx with ${dets.size} detections")
                val entry = sm2.step(dets, frameIdx, f.ptsMs)
                val tS = ns() - tS0
                Log.i(TAG, "[STATE] Frame $frameIdx: state=${entry.state}, track=${entry.track?.let { "${it.x},${it.y},${it.width},${it.height}" } ?: "null"}, dets=${entry.dets?.size ?: 0}")
                Log.d(
                    TAG,
                    "[STATE] frame=$frameIdx state=${entry.state} track=${entry.track?.let { Rect(it.x,it.y,it.width,it.height).str() } ?: "null"} dets=${entry.dets?.size ?: 0} in ${tS.ms()} ms"
                )
                logger.append(entry)

                frameIdx++
                yield()
            }

            Log.i(TAG, "Done one-pass frames=$frameIdx lastPts=$lastPts ms")
        } finally {
            try { dec.close() } catch (_:Throwable) {}
            try { fbo.release() } catch (_:Throwable) {}
            try { yolo.close() } catch (_:Throwable) {}
            try { poseModel.close() } catch (_:Throwable) {}
            try { faceModel.close() } catch (_:Throwable) {}
        }
    }

    /** 겹치는 디텍션들을 찾는 헬퍼 함수 */
    private fun findOverlappingDetections(detections: List<Detection>): List<Detection> {
        val overlapping = mutableListOf<Detection>()
        
        for (i in detections.indices) {
            for (j in i + 1 until detections.size) {
                val det1 = detections[i]
                val det2 = detections[j]
                val rect1 = det1.toRect()
                val rect2 = det2.toRect()
                
                // IoU 계산
                val iou = com.echoshot.app.mp4detact.data.iou(rect1, rect2)
                if (iou > cfg.overlapAmbiguous) {
                    if (!overlapping.contains(det1)) overlapping.add(det1)
                    if (!overlapping.contains(det2)) overlapping.add(det2)
                }
            }
        }
        
        return overlapping
    }
    
    /** 사용자 박스와 가장 잘 매칭되는 디텍션 찾기 */
    private fun findBestMatchingDetection(userBox: Rect, detections: List<Detection>): Detection? {
        if (detections.isEmpty()) return null
        
        var bestDetection: Detection? = null
        var bestIoU = 0f
        
        for (det in detections) {
            val detRect = det.toRect()
            val iou = com.echoshot.app.mp4detact.data.iou(userBox, detRect)
            
            if (iou > bestIoU) {
                bestIoU = iou
                bestDetection = det
            }
        }
        
        Log.i(TAG, "Best matching detection: IoU=${"%.3f".format(bestIoU)} for ${bestDetection?.let { "${it.x1},${it.y1},${it.x2},${it.y2}" }}")
        return if (bestIoU > 0.1f) bestDetection else null  // 최소 IoU 0.1 이상
    }
}
